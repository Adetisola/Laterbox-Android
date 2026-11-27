package com.laterbox.app.reminders

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.laterbox.app.ai.RemoteCapabilities
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ActivityEventType
import com.laterbox.app.diagnostics.AppHealthChecker
import com.laterbox.app.diagnostics.AppHealthReport
import com.laterbox.app.diagnostics.IssueSeverity
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.flow.first
import java.util.Calendar

data class ContextualTip(
    val id: String,
    val title: String,
    val body: String,
    val destination: String
)

class ContextualNotificationEngine(
    private val appContext: Context,
    private val database: LaterboxDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val activityEventRepository: ActivityEventRepository,
    private val appHealthChecker: AppHealthChecker,
    private val notificationGovernor: NotificationGovernor = NotificationGovernor(userPreferencesRepository),
    private val remoteConfigManager: RemoteConfigManager? = null
) {

    private fun isTier3RemoteGovernorEnabled(): Boolean {
        return remoteConfigManager?.currentConfig?.isFeatureEnabled(
            RemoteCapabilities.KEY_CONTEXTUAL_NOTIFICATION_GOVERNOR,
            defaultValue = true
        ) ?: true
    }

    suspend fun notifyExtractionFailure(
        savedLinkId: Long,
        url: String,
        linkTitle: String?,
        errorMessage: String,
        dedupKey: String,
        currentHour: Int = currentHourOfDay(),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        val decision = notificationGovernor.evaluate(
            category = NotificationCategory.EXTRACTION_FAILURE,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour
        )
        if (decision !is GovernorDecision.Allow) return false

        val cleanTitle = linkTitle?.takeIf { it.isNotBlank() } ?: "Saved link"
        val cleanError = ActivityEventRepository.sanitizeText(errorMessage, 140)
            .ifBlank { "Could not inspect downloadable media formats." }

        val posted = NotificationHelper.showExtractionFailedNotification(
            context = appContext,
            savedLinkId = savedLinkId,
            url = url,
            linkTitle = cleanTitle,
            errorMessage = cleanError,
            dedupKey = dedupKey
        )
        if (posted) {
            activityEventRepository.recordEvent(
                type = ActivityEventType.NOTIFICATION_POSTED,
                title = "Notified: media inspection failed for $cleanTitle",
                detail = cleanError,
                linkId = savedLinkId
            )
        }
        return posted
    }

    suspend fun notifyDownloadFailure(
        taskId: String,
        savedLinkId: Long,
        title: String?,
        errorMessage: String,
        dedupKey: String,
        currentHour: Int = currentHourOfDay(),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        val decision = notificationGovernor.evaluate(
            category = NotificationCategory.DOWNLOAD_FAILURE,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour
        )
        if (decision !is GovernorDecision.Allow) return false

        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "Media download"
        val cleanError = ActivityEventRepository.sanitizeText(errorMessage, 140)
            .ifBlank { "Download could not complete." }

        val posted = NotificationHelper.showDownloadFailedNotification(
            context = appContext,
            taskId = taskId,
            savedLinkId = savedLinkId,
            title = cleanTitle,
            errorMessage = cleanError,
            dedupKey = dedupKey
        )
        if (posted) {
            activityEventRepository.recordEvent(
                type = ActivityEventType.NOTIFICATION_POSTED,
                title = "Notified: download failed for $cleanTitle",
                detail = cleanError,
                linkId = savedLinkId
            )
        }
        return posted
    }

    suspend fun evaluateAndNotifyAppHealthIfNeeded(
        nowMs: Long = System.currentTimeMillis(),
        currentHour: Int = currentHourOfDay()
    ): AppHealthReport {
        val report = appHealthChecker.runCheck()
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        val decision = notificationGovernor.evaluate(
            category = NotificationCategory.APP_HEALTH,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour
        )
        if (decision !is GovernorDecision.Allow) {
            return report
        }

        // Only surface meaningful actionable device/engine health warnings (avoid nagging on transient offline state)
        val actionableIssues = report.issues.filter { issue ->
            issue.id in setOf("ytdlp_failed", "ffmpeg_failed", "quickjs_missing", "storage_critical", "storage_low") ||
                (issue.id == "failed_inspections" && report.failedInspectionsCount >= 3) ||
                (issue.id == "failed_downloads" && report.failedDownloadsCount >= 2)
        }
        val leadIssue = actionableIssues.firstOrNull { it.severity == IssueSeverity.CRITICAL }
            ?: actionableIssues.firstOrNull()
            ?: return report

        val posted = NotificationHelper.showAppHealthNotification(
            context = appContext,
            title = leadIssue.title,
            body = "${leadIssue.summary} Tap to run a full assistant health check."
        )
        if (posted) {
            userPreferencesRepository.recordAppHealthAlertPosted(nowMs)
            activityEventRepository.recordEvent(
                type = ActivityEventType.NOTIFICATION_POSTED,
                title = "App health alert: ${leadIssue.title}",
                detail = leadIssue.summary
            )
        }
        return report
    }

    suspend fun maybeSendContextualSummaryOrTip(
        nowMs: Long = System.currentTimeMillis(),
        currentHour: Int = currentHourOfDay(),
        postedTier3InCurrentWakeup: Boolean = false
    ): Boolean {
        if (postedTier3InCurrentWakeup || !isTier3RemoteGovernorEnabled()) return false
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false

        // 1. Contextual library/activity summary
        val summaryDecision = notificationGovernor.evaluate(
            category = NotificationCategory.CONTEXTUAL_SUMMARY,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour,
            postedTier3InCurrentWakeup = postedTier3InCurrentWakeup
        )
        if (summaryDecision is GovernorDecision.Allow) {
            val inbox = database.savedLinkDao().getInboxLinks().first()
            val unread = inbox.filter { !it.link.isRead }
            val failedInspections = database.mediaInspectionDao().countFailedOrNeedsAuth()
            val failedDownloads = database.downloadTaskDao().countFailedTasks()

            if (unread.size >= 3 || failedDownloads > 0 || failedInspections >= 2) {
                val videoCount = unread.count { it.link.contentType == ContentType.VIDEO }
                val title = when {
                    failedDownloads > 0 -> "Downloads & Inbox check-in"
                    unread.size >= 5 -> "${unread.size} saved links waiting in your Inbox"
                    else -> "Your Laterbox catch-up summary"
                }
                val body = buildString {
                    append("${unread.size} unread ${if (unread.size == 1) "link" else "links"}")
                    if (videoCount > 0) append(" ($videoCount ${if (videoCount == 1) "video" else "videos"})")
                    if (failedDownloads > 0) append(" • $failedDownloads failed ${if (failedDownloads == 1) "download" else "downloads"}")
                    if (failedInspections >= 2) append(" • $failedInspections media inspections need attention")
                    append(". Tap to open your assistant.")
                }
                val posted = NotificationHelper.showContextualSummaryOrTipNotification(
                    context = appContext,
                    notificationId = NotificationIds.CONTEXTUAL_SUMMARY_NOTIFICATION_ID,
                    title = title,
                    body = body,
                    destination = "chat",
                    initialPrompt = "Summarize what needs my attention in Laterbox right now",
                    category = NotificationCategory.CONTEXTUAL_SUMMARY
                )
                if (posted) {
                    userPreferencesRepository.recordSummaryNotificationPosted(nowMs)
                    notificationGovernor.recordTier3Posted(NotificationCategory.CONTEXTUAL_SUMMARY, nowMs)
                    activityEventRepository.recordEvent(
                        type = ActivityEventType.NOTIFICATION_POSTED,
                        title = title,
                        detail = body
                    )
                    return true
                }
            }
        }

        // 2. Non-repeating contextual tips
        val tipDecision = notificationGovernor.evaluate(
            category = NotificationCategory.HELPFUL_TIP,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour,
            postedTier3InCurrentWakeup = postedTier3InCurrentWakeup
        )
        if (tipDecision is GovernorDecision.Allow) {
            val nextTip = selectNextUnshownTip(prefs.shownTipIds) ?: return false
            val posted = NotificationHelper.showContextualSummaryOrTipNotification(
                context = appContext,
                notificationId = NotificationIds.helpfulTipNotificationId(nextTip.id),
                title = nextTip.title,
                body = nextTip.body,
                destination = nextTip.destination,
                initialPrompt = null,
                category = NotificationCategory.HELPFUL_TIP
            )
            if (posted) {
                userPreferencesRepository.recordShownTip(nextTip.id, nowMs)
                notificationGovernor.recordTier3Posted(NotificationCategory.HELPFUL_TIP, nowMs)
                activityEventRepository.recordEvent(
                    type = ActivityEventType.NOTIFICATION_POSTED,
                    title = "Tip: ${nextTip.title}",
                    detail = nextTip.body
                )
                return true
            }
        }

        return false
    }

    suspend fun maybeNotifyMoviesAndTv(
        moviesRepository: MoviesRepository? = null,
        nowMs: Long = System.currentTimeMillis(),
        currentHour: Int = currentHourOfDay(),
        postedTier3InCurrentWakeup: Boolean = false
    ): Boolean {
        if (postedTier3InCurrentWakeup || !isTier3RemoteGovernorEnabled()) return false
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false

        val dao = database.moviesDao()
        val mutedKeys = dao.getAllSeriesPreferencesSnapshot()
            .filter { it.remindersMuted }
            .map { it.mediaKey }
            .toSet()

        fun isMuted(canonicalId: String?, compositeId: String): Boolean =
            (canonicalId != null && canonicalId in mutedKeys) || compositeId in mutedKeys

        // 1. Continue Watching / Next Episode Nudge
        val cwDecision = notificationGovernor.evaluate(
            category = NotificationCategory.MOVIES_CONTINUE_WATCHING,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour,
            postedTier3InCurrentWakeup = postedTier3InCurrentWakeup
        )
        if (cwDecision is GovernorDecision.Allow) {
            val continueWatching = dao.observeContinueWatching(5).first()
            val cwCandidate = continueWatching.firstOrNull { entry ->
                !entry.isTrailer &&
                    !entry.isHiddenTitle &&
                    !isMuted(entry.canonicalId, entry.compositeId) &&
                    (nowMs - entry.lastWatchedAt) >= CONTINUE_WATCHING_STALE_MS
            }
            if (cwCandidate != null) {
                val title = "Continue \"${cwCandidate.title}\""
                val body = if (cwCandidate.kind == "tv_series" &&
                    cwCandidate.seasonNumber != null &&
                    cwCandidate.episodeNumber != null
                ) {
                    "Pick up S${cwCandidate.seasonNumber}E${cwCandidate.episodeNumber} where you left off in Movies & TV."
                } else {
                    "Pick up where you left off in Movies & TV."
                }
                val posted = NotificationHelper.showMoviesSmartNudgeNotification(
                    context = appContext,
                    dedupKey = "movies_cw:${cwCandidate.historyKey}:${cwCandidate.lastWatchedAt}",
                    notificationId = NotificationIds.moviesContinueWatchingNotificationId(cwCandidate.compositeId),
                    providerId = cwCandidate.providerId,
                    titleId = cwCandidate.titleId,
                    title = title,
                    body = body,
                    originTag = "MOVIES_CONTINUE_WATCHING",
                    posterUrl = cwCandidate.posterUrl,
                    category = NotificationCategory.MOVIES_CONTINUE_WATCHING
                )
                if (posted) {
                    userPreferencesRepository.recordMoviesNotificationPosted(nowMs)
                    notificationGovernor.recordTier3Posted(NotificationCategory.MOVIES_CONTINUE_WATCHING, nowMs)
                    activityEventRepository.recordEvent(
                        type = ActivityEventType.NOTIFICATION_POSTED,
                        title = title,
                        detail = body
                    )
                    return true
                }
            }
        }

        // 2. Verified New Season Alert (TVMaze only)
        // NOTE: "Airing Soon" / next-episode reminders are blocked on upstream episode air dates;
        // implementing them requires TVMaze `/shows/{id}/episodes` or TMDB `next_episode_to_air`.
        val seasonDecision = notificationGovernor.evaluate(
            category = NotificationCategory.MOVIES_NEW_SEASON,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour,
            postedTier3InCurrentWakeup = postedTier3InCurrentWakeup
        )
        if (moviesRepository != null && seasonDecision is GovernorDecision.Allow) {
            data class SeriesCandidate(
                val titleId: String,
                val compositeId: String,
                val canonicalId: String?,
                val title: String,
                val posterUrl: String?
            )
            val watchlistSeries = dao.getWatchlistSnapshot()
                .filter { it.providerId == "tvmaze" && it.kind == "tv_series" }
                .map { SeriesCandidate(it.titleId, it.compositeId, it.canonicalId, it.title, it.posterUrl) }
            val historySeries = dao.getWatchHistorySnapshot(30)
                .filter { it.providerId == "tvmaze" && it.kind == "tv_series" && !it.isTrailer && !it.isHiddenTitle }
                .map { SeriesCandidate(it.titleId, it.compositeId, it.canonicalId, it.title, it.posterUrl) }
            val candidates = (historySeries + watchlistSeries)
                .distinctBy { it.titleId }
                .filter { !isMuted(it.canonicalId, it.compositeId) }
                .take(4)

            for (candidate in candidates) {
                val seriesHistory = dao.getHistoryListForTitle(candidate.compositeId, candidate.canonicalId)
                if (seriesHistory.any { it.isHiddenTitle }) continue
                val knownMaxSeason = seriesHistory.mapNotNull { it.seasonNumber }.maxOrNull() ?: 0
                if (knownMaxSeason <= 0) continue

                val verifiedSeasons = moviesRepository.getSeasons("tvmaze", candidate.titleId)
                    .getOrNull()
                    .orEmpty()
                val verifiedMaxSeason = verifiedSeasons.maxOfOrNull { it.seasonNumber } ?: 0
                if (verifiedMaxSeason > knownMaxSeason) {
                    val title = "New season of ${candidate.title}"
                    val body = "Season $verifiedMaxSeason is now listed for ${candidate.title}."
                    val posted = NotificationHelper.showMoviesSmartNudgeNotification(
                        context = appContext,
                        dedupKey = "tvmaze_new_season:${candidate.titleId}:s${verifiedMaxSeason}",
                        notificationId = NotificationIds.moviesNewSeasonNotificationId(candidate.titleId),
                        providerId = "tvmaze",
                        titleId = candidate.titleId,
                        title = title,
                        body = body,
                        originTag = "MOVIES_NEW_SEASON",
                        posterUrl = candidate.posterUrl,
                        category = NotificationCategory.MOVIES_NEW_SEASON
                    )
                    if (posted) {
                        userPreferencesRepository.recordMoviesNotificationPosted(nowMs)
                        notificationGovernor.recordTier3Posted(NotificationCategory.MOVIES_NEW_SEASON, nowMs)
                        activityEventRepository.recordEvent(
                            type = ActivityEventType.NOTIFICATION_POSTED,
                            title = title,
                            detail = body
                        )
                        return true
                    }
                }
            }
        }

        // 3. Unwatched Watchlist Nudge
        val watchlistDecision = notificationGovernor.evaluate(
            category = NotificationCategory.MOVIES_WATCHLIST,
            prefs = prefs,
            nowMs = nowMs,
            currentHour = currentHour,
            postedTier3InCurrentWakeup = postedTier3InCurrentWakeup
        )
        if (watchlistDecision is GovernorDecision.Allow) {
            val historySnapshot = dao.getWatchHistorySnapshot(50).filter { !it.isTrailer }
            val touchedCompositeIds = historySnapshot.map { it.compositeId }.toSet()
            val touchedCanonicalIds = historySnapshot.mapNotNull { it.canonicalId }.toSet()
            val wlCandidate = dao.getWatchlistSnapshot().firstOrNull { item ->
                (nowMs - item.addedAt) >= WATCHLIST_NUDGE_AGE_MS &&
                    !isMuted(item.canonicalId, item.compositeId) &&
                    item.compositeId !in touchedCompositeIds &&
                    (item.canonicalId == null || item.canonicalId !in touchedCanonicalIds)
            }
            if (wlCandidate != null) {
                val title = "From your watchlist: ${wlCandidate.title}"
                val body = "You saved \"${wlCandidate.title}\" to watch later in Movies & TV."
                val posted = NotificationHelper.showMoviesSmartNudgeNotification(
                    context = appContext,
                    dedupKey = "movies_wl:${wlCandidate.compositeId}",
                    notificationId = NotificationIds.moviesWatchlistNotificationId(wlCandidate.compositeId),
                    providerId = wlCandidate.providerId,
                    titleId = wlCandidate.titleId,
                    title = title,
                    body = body,
                    originTag = "MOVIES_WATCHLIST",
                    posterUrl = wlCandidate.posterUrl,
                    category = NotificationCategory.MOVIES_WATCHLIST
                )
                if (posted) {
                    userPreferencesRepository.recordMoviesNotificationPosted(nowMs)
                    notificationGovernor.recordTier3Posted(NotificationCategory.MOVIES_WATCHLIST, nowMs)
                    activityEventRepository.recordEvent(
                        type = ActivityEventType.NOTIFICATION_POSTED,
                        title = title,
                        detail = body
                    )
                    return true
                }
            }
        }

        return false
    }

    private fun currentHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    companion object {
        const val HEALTH_ALERT_COOLDOWN_MS = NotificationGovernor.HEALTH_ALERT_COOLDOWN_MS
        const val SUMMARY_COOLDOWN_MS = NotificationGovernor.SUMMARY_COOLDOWN_MS
        const val TIP_COOLDOWN_MS = NotificationGovernor.TIP_COOLDOWN_MS
        const val MOVIES_NUDGE_COOLDOWN_MS = NotificationGovernor.MOVIES_NUDGE_COOLDOWN_MS
        const val CONTINUE_WATCHING_STALE_MS = 6L * 60L * 60L * 1000L
        const val WATCHLIST_NUDGE_AGE_MS = 24L * 60L * 60L * 1000L

        val TIP_CATALOG: List<ContextualTip> = listOf(
            ContextualTip(
                id = "tip_assistant_search",
                title = "Ask your Laterbox assistant",
                body = "Type in the Inbox bar to search your saved links, check media errors, or summarize what's unread.",
                destination = "chat"
            ),
            ContextualTip(
                id = "tip_capture_selection",
                title = "Save links faster from any app",
                body = "Use Share to Laterbox, or turn on the Accessibility floating Save pill in Capture & Shortcuts.",
                destination = "settings/capture_shortcuts"
            ),
            ContextualTip(
                id = "tip_audio_only_download",
                title = "Save audio tracks from videos",
                body = "Open any video link in Laterbox to choose lower data resolutions or extract audio-only formats.",
                destination = "inbox"
            ),
            ContextualTip(
                id = "tip_wifi_download_policy",
                title = "Control cellular data for large media",
                body = "You can require Wi-Fi for downloads over 20 MB in Settings → Media & Downloads.",
                destination = "settings/media_downloads"
            ),
            ContextualTip(
                id = "tip_quiet_hours",
                title = "Keep nights peaceful with Quiet Hours",
                body = "Adjust when contextual alerts and summaries are muted in Settings → Reminders & AI.",
                destination = "settings/reminders_ai"
            )
        )

        fun selectNextUnshownTip(shownTipIds: Set<String>): ContextualTip? =
            TIP_CATALOG.firstOrNull { !shownTipIds.contains(it.id) }
    }
}
