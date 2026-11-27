package com.laterbox.app.reminders

import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

enum class NotificationTier {
    COMMITMENT,
    OPERATIONAL,
    SUGGESTION
}

enum class NotificationCategory(val tier: NotificationTier) {
    // Tier 1 — Explicit user-scheduled commitments (NEVER suppressed or delayed by Quiet Hours or caps)
    MANUAL_LINK_REMINDER(NotificationTier.COMMITMENT),
    MOVIE_VIEWING_REMINDER(NotificationTier.COMMITMENT),

    // Tier 2 — Operational & recovery alerts (independent toggles, cooldowns, Quiet Hours except progress)
    EXTRACTION_FAILURE(NotificationTier.OPERATIONAL),
    DOWNLOAD_FAILURE(NotificationTier.OPERATIONAL),
    APP_HEALTH(NotificationTier.OPERATIONAL),
    FOREGROUND_PROGRESS(NotificationTier.OPERATIONAL),

    // Tier 3 — Proactive intelligent suggestions (Quiet Hours + max 1/wakeup + shared daily cap + spacing + swipe backoff)
    SMART_DIGEST(NotificationTier.SUGGESTION),
    CONTEXTUAL_SUMMARY(NotificationTier.SUGGESTION),
    HELPFUL_TIP(NotificationTier.SUGGESTION),
    MOVIES_CONTINUE_WATCHING(NotificationTier.SUGGESTION),
    MOVIES_NEW_SEASON(NotificationTier.SUGGESTION),
    MOVIES_WATCHLIST(NotificationTier.SUGGESTION)
}

sealed class GovernorDecision {
    data object Allow : GovernorDecision()
    data class Suppress(val reason: String) : GovernorDecision()
}

/**
 * Deterministic notification governor enforcing strict tier isolation:
 * - Tier 1 ([NotificationTier.COMMITMENT]): ALWAYS allowed, ALWAYS bypasses Quiet Hours,
 *   never consumes Tier-3 daily budget.
 * - Tier 2 ([NotificationTier.OPERATIONAL]): respects category toggles, Quiet Hours (except
 *   [NotificationCategory.FOREGROUND_PROGRESS]), and 24h App Health cooldown without touching Tier-3 quota.
 * - Tier 3 ([NotificationTier.SUGGESTION]): enforces Quiet Hours strictly, max 1 notification per
 *   smart-schedule wakeup, shared daily cap ([MAX_TIER3_PER_DAY] = 2), minimum spacing
 *   ([MIN_TIER3_SPACING_MS] = 6h), per-category cooldowns, and isolated swipe backoff protected
 *   by a 1500 ms Clear-All batch-dismissal guard.
 */
class NotificationGovernor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val deferredFlushScope: CoroutineScope? = null
) {

    private data class PendingSwipe(
        val category: NotificationCategory,
        val timestampMs: Long
    )

    private val swipeMutex = Mutex()
    private var pendingSwipe: PendingSwipe? = null
    private var clearAllSuppressedUntilMs: Long = 0L
    private var pendingFlushJob: Job? = null

    suspend fun evaluate(
        category: NotificationCategory,
        prefs: UserPreferences,
        nowMs: Long = System.currentTimeMillis(),
        currentHour: Int = Instant.ofEpochMilli(nowMs).atZone(zoneIdProvider()).hour,
        postedTier3InCurrentWakeup: Boolean = false,
        isForegroundProgress: Boolean = false
    ): GovernorDecision {
        // Tier 1 commitments are invariant: ALWAYS allowed, ALWAYS bypass Quiet Hours & caps.
        if (category.tier == NotificationTier.COMMITMENT) {
            return GovernorDecision.Allow
        }

        val flushedSwipe = flushPendingSwipeIfMature(nowMs)
        val effectivePrefs = if (flushedSwipe) {
            userPreferencesRepository.userPreferencesFlow.first()
        } else {
            prefs
        }

        if (category.tier == NotificationTier.OPERATIONAL) {
            if (isForegroundProgress || category == NotificationCategory.FOREGROUND_PROGRESS) {
                return GovernorDecision.Allow
            }
            val toggleEnabled = when (category) {
                NotificationCategory.EXTRACTION_FAILURE -> effectivePrefs.smartNotificationsExtractionFailure
                NotificationCategory.DOWNLOAD_FAILURE -> effectivePrefs.smartNotificationsDownloadFailure
                NotificationCategory.APP_HEALTH -> effectivePrefs.smartNotificationsAppHealth
                NotificationCategory.FOREGROUND_PROGRESS -> true
                else -> true
            }
            if (!toggleEnabled) {
                return GovernorDecision.Suppress("toggle_disabled")
            }
            if (effectivePrefs.quietHoursEnabled &&
                NotificationHelper.isWithinQuietHours(
                    currentHour,
                    effectivePrefs.quietHoursStartHour,
                    effectivePrefs.quietHoursEndHour
                )
            ) {
                return GovernorDecision.Suppress("quiet_hours")
            }
            if (category == NotificationCategory.APP_HEALTH &&
                effectivePrefs.lastAppHealthAlertAt > 0L &&
                nowMs >= effectivePrefs.lastAppHealthAlertAt &&
                (nowMs - effectivePrefs.lastAppHealthAlertAt) < HEALTH_ALERT_COOLDOWN_MS
            ) {
                return GovernorDecision.Suppress("health_cooldown")
            }
            return GovernorDecision.Allow
        }

        // Tier 3 (SUGGESTION)
        val tier3ToggleEnabled = when (category) {
            NotificationCategory.SMART_DIGEST -> effectivePrefs.smartRemindersEnabled
            NotificationCategory.CONTEXTUAL_SUMMARY -> effectivePrefs.smartNotificationsSummaries
            NotificationCategory.HELPFUL_TIP -> effectivePrefs.smartNotificationsTips
            NotificationCategory.MOVIES_CONTINUE_WATCHING,
            NotificationCategory.MOVIES_NEW_SEASON,
            NotificationCategory.MOVIES_WATCHLIST ->
                effectivePrefs.smartRemindersEnabled && effectivePrefs.smartNotificationsMoviesTv
            else -> false
        }
        if (!tier3ToggleEnabled) {
            return GovernorDecision.Suppress("toggle_disabled")
        }

        if (effectivePrefs.quietHoursEnabled &&
            NotificationHelper.isWithinQuietHours(
                currentHour,
                effectivePrefs.quietHoursStartHour,
                effectivePrefs.quietHoursEndHour
            )
        ) {
            return GovernorDecision.Suppress("quiet_hours")
        }

        if (postedTier3InCurrentWakeup) {
            return GovernorDecision.Suppress("wakeup_burst_cap")
        }

        val todayEpoch = epochDayFor(nowMs)
        if (effectivePrefs.tier3BudgetDayEpoch == todayEpoch &&
            effectivePrefs.tier3DailyCount >= MAX_TIER3_PER_DAY
        ) {
            return GovernorDecision.Suppress("daily_cap")
        }

        if (effectivePrefs.lastTier3NotificationAt > 0L &&
            nowMs >= effectivePrefs.lastTier3NotificationAt &&
            (nowMs - effectivePrefs.lastTier3NotificationAt) < MIN_TIER3_SPACING_MS
        ) {
            return GovernorDecision.Suppress("spacing_cooldown")
        }

        val categoryCooldownSuppress = when (category) {
            NotificationCategory.CONTEXTUAL_SUMMARY ->
                effectivePrefs.lastSummaryNotificationAt > 0L &&
                    nowMs >= effectivePrefs.lastSummaryNotificationAt &&
                    (nowMs - effectivePrefs.lastSummaryNotificationAt) < SUMMARY_COOLDOWN_MS
            NotificationCategory.HELPFUL_TIP ->
                effectivePrefs.lastTipNotificationAt > 0L &&
                    nowMs >= effectivePrefs.lastTipNotificationAt &&
                    (nowMs - effectivePrefs.lastTipNotificationAt) < TIP_COOLDOWN_MS
            NotificationCategory.MOVIES_CONTINUE_WATCHING,
            NotificationCategory.MOVIES_NEW_SEASON,
            NotificationCategory.MOVIES_WATCHLIST ->
                effectivePrefs.lastMoviesNotificationAt > 0L &&
                    nowMs >= effectivePrefs.lastMoviesNotificationAt &&
                    (nowMs - effectivePrefs.lastMoviesNotificationAt) < MOVIES_NUDGE_COOLDOWN_MS
            else -> false
        }
        if (categoryCooldownSuppress) {
            return GovernorDecision.Suppress("category_cooldown")
        }

        val backoffUntil = effectivePrefs.tier3BackoffUntilMap()[category.name] ?: 0L
        if (backoffUntil > nowMs) {
            return GovernorDecision.Suppress("swipe_backoff")
        }

        return GovernorDecision.Allow
    }

    suspend fun recordTier3Posted(
        category: NotificationCategory,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (category.tier != NotificationTier.SUGGESTION) return
        val dayEpoch = epochDayFor(nowMs)
        userPreferencesRepository.recordTier3NotificationPosted(dayEpoch, nowMs)
    }

    suspend fun recordNotificationOpened(category: NotificationCategory) {
        if (category.tier == NotificationTier.SUGGESTION) {
            userPreferencesRepository.clearTier3SwipeBackoff(category.name)
        }
    }

    /**
     * Records a Tier-3 notification swipe dismissal with a 1500 ms Clear-All batch guard.
     *
     * If 2 or more swipe broadcasts arrive within [CLEAR_ALL_BATCH_WINDOW_MS], they are treated
     * as Android "Clear All" noise and zero category backoff is applied. An isolated swipe whose
     * batch window expires (> 1500 ms without another swipe) applies doubled cooldown backoff
     * (24h -> 48h -> 72h max).
     */
    suspend fun onTier3NotificationSwiped(
        category: NotificationCategory,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (category.tier != NotificationTier.SUGGESTION) return
        var matureToCommit: PendingSwipe? = null

        swipeMutex.withLock {
            pendingFlushJob?.cancel()
            pendingFlushJob = null

            if (nowMs <= clearAllSuppressedUntilMs &&
                nowMs >= clearAllSuppressedUntilMs - CLEAR_ALL_BATCH_WINDOW_MS
            ) {
                clearAllSuppressedUntilMs = nowMs + CLEAR_ALL_BATCH_WINDOW_MS
                pendingSwipe = null
                return
            }

            val existing = pendingSwipe
            if (existing != null) {
                val delta = nowMs - existing.timestampMs
                if (delta in 0L..CLEAR_ALL_BATCH_WINDOW_MS) {
                    // Clear-All batch detected: discard both swipes without penalty.
                    pendingSwipe = null
                    clearAllSuppressedUntilMs = nowMs + CLEAR_ALL_BATCH_WINDOW_MS
                    return
                } else if (delta > CLEAR_ALL_BATCH_WINDOW_MS) {
                    matureToCommit = existing
                    pendingSwipe = PendingSwipe(category, nowMs)
                } else {
                    pendingSwipe = PendingSwipe(category, nowMs)
                }
            } else {
                pendingSwipe = PendingSwipe(category, nowMs)
            }
        }

        if (matureToCommit != null) {
            commitSwipeBackoff(matureToCommit!!.category, matureToCommit!!.timestampMs)
        }

        if (deferredFlushScope != null) {
            swipeMutex.withLock {
                if (pendingSwipe != null) {
                    val scheduledAt = pendingSwipe!!.timestampMs
                    pendingFlushJob = deferredFlushScope.launch {
                        delay(CLEAR_ALL_BATCH_WINDOW_MS + 100L)
                        flushPendingSwipeIfMature(scheduledAt + CLEAR_ALL_BATCH_WINDOW_MS + 1L)
                    }
                }
            }
        }
    }

    suspend fun flushPendingSwipeIfMature(nowMs: Long = System.currentTimeMillis()): Boolean {
        val mature = swipeMutex.withLock {
            val current = pendingSwipe ?: return@withLock null
            if (nowMs - current.timestampMs > CLEAR_ALL_BATCH_WINDOW_MS) {
                pendingSwipe = null
                pendingFlushJob?.cancel()
                pendingFlushJob = null
                current
            } else {
                null
            }
        } ?: return false

        commitSwipeBackoff(mature.category, mature.timestampMs)
        return true
    }

    internal suspend fun resetInMemorySwipeStateForTest() {
        swipeMutex.withLock {
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            pendingSwipe = null
            clearAllSuppressedUntilMs = 0L
        }
    }

    private suspend fun commitSwipeBackoff(category: NotificationCategory, swipeAtMs: Long) {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        val prevStreak = prefs.tier3SwipeStreakMap()[category.name] ?: 0
        val newStreak = (prevStreak + 1).coerceAtMost(3)
        val multiplier = 1L shl (newStreak - 1)
        val backoffMs = (BASE_SWIPE_BACKOFF_MS * multiplier).coerceAtMost(MAX_SWIPE_BACKOFF_MS)
        userPreferencesRepository.applyTier3SwipeBackoff(
            categoryName = category.name,
            backoffUntilMs = swipeAtMs + backoffMs,
            newStreak = newStreak
        )
    }

    private fun epochDayFor(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(zoneIdProvider()).toLocalDate().toEpochDay()

    companion object {
        const val MAX_TIER3_PER_DAY = 2
        const val MIN_TIER3_SPACING_MS = 6L * 60L * 60L * 1000L
        const val HEALTH_ALERT_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val SUMMARY_COOLDOWN_MS = 20L * 60L * 60L * 1000L
        const val TIP_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val MOVIES_NUDGE_COOLDOWN_MS = 18L * 60L * 60L * 1000L
        const val CLEAR_ALL_BATCH_WINDOW_MS = 1_500L
        const val BASE_SWIPE_BACKOFF_MS = 24L * 60L * 60L * 1000L
        const val MAX_SWIPE_BACKOFF_MS = 72L * 60L * 60L * 1000L
    }
}
