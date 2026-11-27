package com.laterbox.app.reminders

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.ai.AiCopyResult
import com.laterbox.app.ai.PlainReminderCopy
import com.laterbox.app.ai.ReminderOrigin
import com.laterbox.app.ai.SmartDigestEngine
import com.laterbox.app.reminders.smart.SmartReminderCoordinator
import com.laterbox.app.reminders.smart.SmartReminderScheduler
import com.laterbox.app.reminders.smart.SmartScheduleType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val appContainer = (context.applicationContext as? LaterboxApplication)?.container ?: return
        val linkRepository = appContainer.linkRepository
        val userPreferencesRepository = appContainer.userPreferencesRepository
        val governor = appContainer.notificationGovernor

        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(GO_ASYNC_BUDGET_MS) {
                    when (action) {
                        ACTION_TRIGGER_REMINDER -> {
                            val linkId = intent.getLongExtra(EXTRA_LINK_ID, -1L)
                            if (linkId <= 0) return@withTimeoutOrNull

                            val prefs = userPreferencesRepository.userPreferencesFlow.first()
                            // Tier-1 fast-path: explicit commitments are NEVER delayed or suppressed by Quiet Hours/caps
                            if (governor.evaluate(NotificationCategory.MANUAL_LINK_REMINDER, prefs) !is GovernorDecision.Allow) {
                                return@withTimeoutOrNull
                            }

                            var link = linkRepository.getLinkById(linkId).first()
                            if (link != null && !link.isArchived && !link.nudgeDismissed) {
                                if (link.previewImageUrl.isNullOrBlank()) {
                                    withNonBlockingTimeout(METADATA_FETCH_TIMEOUT_MS) {
                                        linkRepository.fetchMetadataNow(linkId)
                                    }
                                    link = linkRepository.getLinkById(linkId).first() ?: link
                                }

                                val userName = prefs.userName.ifBlank { null }
                                val aiResult = withNonBlockingTimeout(AI_COPY_TIMEOUT_MS) {
                                    SmartDigestEngine.generateDigestCopy(
                                        link = link,
                                        userName = userName,
                                        origin = ReminderOrigin.MANUAL,
                                        customApiKey = prefs.geminiApiKey.ifBlank { null }
                                    )
                                }

                                val copy = when (aiResult) {
                                    is AiCopyResult.Success -> aiResult.copy
                                    else -> {
                                        if (aiResult is AiCopyResult.Failure) {
                                            Log.d(TAG, "AI reminder generation failed (${aiResult.reason.userMessage}); using plain copy")
                                        }
                                        PlainReminderCopy.forManual(link)
                                    }
                                }

                                NotificationHelper.showReminderNotification(
                                    context = context,
                                    link = link,
                                    userName = userName,
                                    origin = ReminderOrigin.MANUAL,
                                    copy = copy,
                                    skipMetadataFetch = true
                                )
                                linkRepository.recordNudge(linkId)
                            }
                        }

                        ACTION_TRIGGER_SMART_SCHEDULE -> {
                            val scheduleTypeName = intent.getStringExtra(EXTRA_SCHEDULE_TYPE) ?: return@withTimeoutOrNull
                            val slotKey = intent.getStringExtra(EXTRA_SLOT_KEY) ?: return@withTimeoutOrNull
                            val targetMillis = intent.getLongExtra(EXTRA_TARGET_MILLIS, 0L)
                            val type = try {
                                SmartScheduleType.valueOf(scheduleTypeName)
                            } catch (_: Exception) {
                                return@withTimeoutOrNull
                            }

                            val scheduler = SmartReminderScheduler(context)
                            val prefs = userPreferencesRepository.userPreferencesFlow.first()
                            val settings = prefs.toSmartReminderSettings()

                            if (!settings.enabled) {
                                Log.d(TAG, "Smart reminders master switch disabled; skipping $slotKey")
                                return@withTimeoutOrNull
                            }

                            try {
                                val nowMillis = System.currentTimeMillis()
                                if (targetMillis > 0 && (nowMillis - targetMillis) > STALE_THRESHOLD_MS) {
                                    Log.w(TAG, "Missed schedule slot $slotKey by ${(nowMillis - targetMillis) / 60_000} mins; skipping delivery")
                                    return@withTimeoutOrNull
                                }

                                if (prefs.deliveredSlotKeys.contains(slotKey)) {
                                    Log.d(TAG, "Slot $slotKey already delivered; skipping duplicate execution")
                                    return@withTimeoutOrNull
                                }

                                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                                    Log.w(TAG, "Notifications disabled; skipping slot $slotKey")
                                    return@withTimeoutOrNull
                                }

                                // Enforce at most 1 Tier-3 notification per smart-schedule wakeup
                                var tier3PostedThisWakeup = false

                                val digestDecision = governor.evaluate(
                                    category = NotificationCategory.SMART_DIGEST,
                                    prefs = prefs,
                                    nowMs = nowMillis,
                                    postedTier3InCurrentWakeup = tier3PostedThisWakeup
                                )
                                if (digestDecision is GovernorDecision.Allow) {
                                    val candidate = linkRepository.getSmartDigestCandidate(
                                        maxNudgeCount = settings.persistence.maxNudges
                                    )
                                    if (candidate != null) {
                                        var currentLink = linkRepository.getLinkById(candidate.id).first() ?: candidate
                                        if (currentLink.previewImageUrl.isNullOrBlank()) {
                                            withNonBlockingTimeout(METADATA_FETCH_TIMEOUT_MS) {
                                                linkRepository.fetchMetadataNow(candidate.id)
                                            }
                                            currentLink = linkRepository.getLinkById(candidate.id).first() ?: candidate
                                        }

                                        val result = withNonBlockingTimeout(AI_COPY_TIMEOUT_MS) {
                                            SmartDigestEngine.generateDigestCopy(
                                                link = currentLink,
                                                userName = prefs.userName.ifBlank { null },
                                                origin = ReminderOrigin.SMART_DIGEST,
                                                customApiKey = prefs.geminiApiKey.ifBlank { null }
                                            )
                                        }

                                        val copy = when (result) {
                                            is AiCopyResult.Success -> result.copy
                                            else -> PlainReminderCopy.forSmartDigest(
                                                link = currentLink,
                                                userName = prefs.userName.ifBlank { null }
                                            )
                                        }

                                        val posted = NotificationHelper.showReminderNotification(
                                            context = context,
                                            link = currentLink,
                                            origin = copy.origin,
                                            copy = copy,
                                            skipMetadataFetch = true
                                        )
                                        if (posted) {
                                            linkRepository.recordNudge(currentLink.id)
                                            userPreferencesRepository.recordDeliveredSlot(slotKey)
                                            governor.recordTier3Posted(NotificationCategory.SMART_DIGEST, nowMillis)
                                            tier3PostedThisWakeup = true
                                            Log.d(TAG, "Delivered smart reminder for slot $slotKey (isAi=${copy.isAiGenerated})")
                                        }
                                    }
                                }

                                // Tier-2 operational health alert uses independent cooldown & does not count toward Tier-3 cap
                                try {
                                    appContainer.contextualNotificationEngine.evaluateAndNotifyAppHealthIfNeeded(
                                        nowMs = nowMillis
                                    )
                                } catch (_: Throwable) {}

                                if (!tier3PostedThisWakeup) {
                                    try {
                                        val postedSummaryOrTip = appContainer.contextualNotificationEngine
                                            .maybeSendContextualSummaryOrTip(
                                                nowMs = nowMillis,
                                                postedTier3InCurrentWakeup = tier3PostedThisWakeup
                                            )
                                        if (postedSummaryOrTip) {
                                            tier3PostedThisWakeup = true
                                        }
                                    } catch (_: Throwable) {}
                                }

                                if (!tier3PostedThisWakeup) {
                                    try {
                                        withTimeoutOrNull(MOVIES_CONTEXTUAL_TIMEOUT_MS) {
                                            appContainer.contextualNotificationEngine.maybeNotifyMoviesAndTv(
                                                moviesRepository = appContainer.moviesRepository,
                                                nowMs = nowMillis,
                                                postedTier3InCurrentWakeup = tier3PostedThisWakeup
                                            )
                                        }
                                    } catch (_: Throwable) {}
                                }
                            } finally {
                                withContext(NonCancellable) {
                                    runCatching { scheduler.scheduleNext(type) }
                                }
                            }
                        }

                        ACTION_TRIGGER_SMART_DIGEST -> {
                            // Legacy alarm action migration: cancel old alarm and sync via scheduler
                            ReminderScheduler(context).cancelLegacySmartDigest()
                            val prefs = userPreferencesRepository.userPreferencesFlow.first()
                            SmartReminderCoordinator.sync(context, prefs.toSmartReminderSettings())
                        }

                        ACTION_TRIGGER_MOVIE_REMINDER -> {
                            val mediaKey = intent.getStringExtra(EXTRA_MEDIA_KEY)
                            val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
                            val titleId = intent.getStringExtra(EXTRA_TITLE_ID)
                            val fallbackTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                            val fallbackNote = intent.getStringExtra(EXTRA_NOTE)
                            if (mediaKey.isNullOrBlank() || providerId.isNullOrBlank() || titleId.isNullOrBlank()) {
                                return@withTimeoutOrNull
                            }

                            val prefs = userPreferencesRepository.userPreferencesFlow.first()
                            // Tier-1 fast-path: explicit movie viewing commitments bypass Quiet Hours & caps
                            if (governor.evaluate(NotificationCategory.MOVIE_VIEWING_REMINDER, prefs) !is GovernorDecision.Allow) {
                                return@withTimeoutOrNull
                            }

                            val moviesDao = appContainer.database.moviesDao()
                            val reminder = moviesDao.getViewingReminderByMediaKey(mediaKey)
                            if (reminder == null || reminder.isDismissed) {
                                return@withTimeoutOrNull
                            }
                            val seriesPrefs = moviesDao.getSeriesPreferences(mediaKey)
                            if (seriesPrefs?.remindersMuted == true) {
                                return@withTimeoutOrNull
                            }

                            val posted = NotificationHelper.showMovieViewingReminderNotification(
                                context = context,
                                mediaKey = mediaKey,
                                providerId = reminder.providerId.ifBlank { providerId },
                                titleId = reminder.titleId.ifBlank { titleId },
                                title = reminder.title.ifBlank { fallbackTitle },
                                note = reminder.note ?: fallbackNote,
                                posterUrl = reminder.posterUrl
                            )
                            if (posted) {
                                appContainer.moviesRepository.dismissViewingReminder(
                                    providerId = reminder.providerId.ifBlank { providerId },
                                    titleId = reminder.titleId.ifBlank { titleId }
                                )
                            }
                        }

                        ACTION_DISMISS_MOVIE_REMINDER -> {
                            val mediaKey = intent.getStringExtra(EXTRA_MEDIA_KEY)
                            val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
                            val titleId = intent.getStringExtra(EXTRA_TITLE_ID)
                            if (!providerId.isNullOrBlank() && !titleId.isNullOrBlank()) {
                                appContainer.moviesRepository.dismissViewingReminder(providerId, titleId)
                            }
                            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                            if (notifId != -1) {
                                notificationManager?.cancel(notifId)
                            }
                            if (!mediaKey.isNullOrBlank()) {
                                notificationManager?.cancel(NotificationIds.movieReminderId(mediaKey))
                            }
                        }

                        ACTION_DISMISS_REMINDER -> {
                            val linkId = intent.getLongExtra(EXTRA_LINK_ID, -1L)
                            if (linkId > 0) {
                                linkRepository.dismissReminder(linkId)
                                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                                val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, NotificationIds.linkReminderNotificationId(linkId))
                                notificationManager?.cancel(notifId)
                                notificationManager?.cancel(NotificationIds.linkReminderNotificationId(linkId))
                                notificationManager?.cancel(NotificationIds.smartDigestNotificationId(linkId))
                                // Defensive cleanup of any pre-migration notifications
                                notificationManager?.cancel(linkId.toInt())
                                notificationManager?.cancel((linkId + 500_000L).toInt())
                                Log.d(TAG, "Dismissed reminder for link $linkId (notifId $notifId)")
                            }
                        }

                        ACTION_NOTIFICATION_SWIPED -> {
                            val categoryName = intent.getStringExtra(EXTRA_NOTIFICATION_CATEGORY)
                            val category = categoryName?.let { raw ->
                                runCatching { NotificationCategory.valueOf(raw) }.getOrNull()
                            } ?: return@withTimeoutOrNull
                            governor.onTier3NotificationSwiped(category, System.currentTimeMillis())
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Error handling reminder broadcast for action $action", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private suspend fun <T> withNonBlockingTimeout(timeoutMs: Long, block: suspend () -> T): T? {
        val deferred = ioBudgetScope.async { block() }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            if (deferred.isActive) {
                deferred.cancel()
            }
        }
    }

    companion object {
        private val ioBudgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val TAG = "ReminderReceiver"
        const val ACTION_TRIGGER_REMINDER = "com.laterbox.app.action.TRIGGER_REMINDER"
        const val ACTION_TRIGGER_SMART_SCHEDULE = "com.laterbox.app.action.TRIGGER_SMART_SCHEDULE"
        const val ACTION_TRIGGER_SMART_DIGEST = "com.laterbox.app.action.TRIGGER_SMART_DIGEST"
        const val ACTION_DISMISS_REMINDER = "com.laterbox.app.action.DISMISS_REMINDER"
        const val ACTION_TRIGGER_MOVIE_REMINDER = "com.laterbox.app.action.TRIGGER_MOVIE_REMINDER"
        const val ACTION_DISMISS_MOVIE_REMINDER = "com.laterbox.app.action.DISMISS_MOVIE_REMINDER"
        const val ACTION_NOTIFICATION_SWIPED = "com.laterbox.app.action.NOTIFICATION_SWIPED"
        const val EXTRA_LINK_ID = "extra_link_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_NOTIFICATION_CATEGORY = "extra_notification_category"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SCHEDULE_TYPE = "extra_schedule_type"
        const val EXTRA_SLOT_KEY = "extra_slot_key"
        const val EXTRA_TARGET_MILLIS = "extra_target_millis"
        const val EXTRA_MEDIA_KEY = "extra_media_key"
        const val EXTRA_PROVIDER_ID = "extra_provider_id"
        const val EXTRA_TITLE_ID = "extra_title_id"
        const val EXTRA_NOTE = "extra_note"
        const val STALE_THRESHOLD_MS = 3 * 60 * 60 * 1000L
        const val GO_ASYNC_BUDGET_MS = 8_500L
        const val METADATA_FETCH_TIMEOUT_MS = 1_500L
        const val AI_COPY_TIMEOUT_MS = 3_000L
        const val MOVIES_CONTEXTUAL_TIMEOUT_MS = 2_500L
    }
}
