package com.laterbox.app.reminders

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ActivityEventType
import com.laterbox.app.diagnostics.AppHealthChecker
import com.laterbox.app.diagnostics.AppHealthReport
import com.laterbox.app.diagnostics.IssueSeverity
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.movies.domain.repository.MoviesRepository
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
    private val appHealthChecker: AppHealthChecker
) {

    suspend fun notifyExtractionFailure(
        savedLinkId: Long,
        url: String,
        linkTitle: String?,
        errorMessage: String,
        dedupKey: String,
        currentHour: Int = currentHourOfDay()
    ): Boolean {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!prefs.smartNotificationsExtractionFailure) return false
        if (prefs.quietHoursEnabled && NotificationHelper.isWithinQuietHours(currentHour, prefs.quietHoursStartHour, prefs.quietHoursEndHour)) {
            return false
        }

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
        currentHour: Int = currentHourOfDay()
    ): Boolean {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!prefs.smartNotificationsDownloadFailure) return false
        if (prefs.quietHoursEnabled && NotificationHelper.isWithinQuietHours(currentHour, prefs.quietHoursStartHour, prefs.quietHoursEndHour)) {
            return false
        }

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
        if (!prefs.smartNotificationsAppHealth) return report
        if (prefs.quietHoursEnabled && NotificationHelper.isWithinQuietHours(currentHour, prefs.quietHoursStartHour, prefs.quietHoursEndHour)) {
            return report
        }
        if ((nowMs - prefs.lastAppHealthAlertAt) < HEALTH_ALERT_COOLDOWN_MS) {
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
        currentHour: Int = currentHourOfDay()
    ): Boolean {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
        if (prefs.quietHoursEnabled && NotificationHelper.isWithinQuietHours(currentHour, prefs.quietHoursStartHour, prefs.quietHoursEndHour)) {
            return false
        }

        // 1. Contextual library/activity summary (max once every 20h)
        if (prefs.smartNotificationsSummaries && (nowMs - prefs.lastSummaryNotificationAt) >= SUMMARY_COOLDOWN_MS) {
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
                    notificationId = SUMMARY_NOTIFICATION_ID,
                    title = title,
                    body = body,
                    destination = "chat",
                    initialPrompt = "Summarize what needs my attention in Laterbox right now"
                )
                if (posted) {
                    userPreferencesRepository.recordSummaryNotificationPosted(nowMs)
                    activityEventRepository.recordEvent(
                        type = ActivityEventType.NOTIFICATION_POSTED,
                        title = title,
                        detail = body
                    )
                    return true
                }
            }
        }

        // 2. Non-repeating contextual tips (opt-in, max once every 24h)
        if (prefs.smartNotificationsTips && (nowMs - prefs.lastTipNotificationAt) >= TIP_COOLDOWN_MS) {
            val nextTip = selectNextUnshownTip(prefs.shownTipIds) ?: return false
            val posted = NotificationHelper.showContextualSummaryOrTipNotification(
                context = appContext,
                notificationId = TIP_NOTIFICATION_ID_BASE + nextTip.id.hashCode(),
                title = nextTip.title,
                body = nextTip.body,
                destination = nextTip.destination,
                initialPrompt = null
            )
            if (posted) {
                userPreferencesRepository.recordShownTip(nextTip.id, nowMs)
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
        currentHour: Int = currentHourOfDay()
    ): Boolean {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!prefs.smartRemindersEnabled || !prefs.smartNotificationsMoviesTv) return false
        if (prefs.quietHoursEnabled && NotificationHelper.isWithinQuietHours(currentHour, prefs.quietHoursStartHour, prefs.quietHoursEndHour)) {
            return false
        }
        if (prefs.lastMoviesNotificationAt > 0L && (nowMs - prefs.lastMoviesNotificationAt) < MOVIES_NUDGE_COOLDOWN_MS) {
            return false
        }
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false

        val dao = database.moviesDao()
        val mutedKeys = dao.getAllSeriesPreferencesSnapshot()
            .filter { it.remindersMuted }
            .map { it.mediaKey }
            .toSet()

        fun isMuted(canonicalId: String?, compositeId: String): Boolean =
            (canonicalId != null && canonicalId in mutedKeys) || compositeId in mutedKeys

        // 1. Continue Watching / Next Episode Nudge
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
                notificationId = MOVIES_CW_NOTIFICATION_BASE + (cwCandidate.compositeId.hashCode() and 0x0FFF),
                providerId = cwCandidate.providerId,
                titleId = cwCandidate.titleId,
                title = title,
                body = body,
                originTag = "MOVIES_CONTINUE_WATCHING",
                posterUrl = cwCandidate.posterUrl
            )
            if (posted) {
                userPreferencesRepository.recordMoviesNotificationPosted(nowMs)
                activityEventRepository.recordEvent(
                    type = ActivityEventType.NOTIFICATION_POSTED,
                    title = title,
                    detail = body
                )
                return true
            }
        }

        // 2. Verified New Season Alert (TVMaze only)
        // NOTE: "Airing Soon" / next-episode reminders are blocked on upstream episode air dates;
        // implementing them requires TVMaze `/shows/{id}/episodes` or TMDB `next_episode_to_air`.
        if (moviesRepository != null) {
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
                        notificationId = MOVIES_SEASON_NOTIFICATION_BASE + (candidate.titleId.hashCode() and 0x0FFF),
                        providerId = "tvmaze",
                        titleId = candidate.titleId,
                        title = title,
                        body = body,
                        originTag = "MOVIES_NEW_SEASON",
                        posterUrl = candidate.posterUrl
                    )
                    if (posted) {
                        userPreferencesRepository.recordMoviesNotificationPosted(nowMs)
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
                notificationId = MOVIES_WL_NOTIFICATION_BASE + (wlCandidate.compositeId.hashCode() and 0x0FFF),
                providerId = wlCandidate.providerId,
                titleId = wlCandidate.titleId,
                title = title,
                body = body,
                originTag = "MOVIES_WATCHLIST",
                posterUrl = wlCandidate.posterUrl
            )
            if (posted) {
                userPreferencesRepository.recordMoviesNotificationPosted(nowMs)
                activityEventRepository.recordEvent(
                    type = ActivityEventType.NOTIFICATION_POSTED,
                    title = title,
                    detail = body
                )
                return true
            }
        }

        return false
    }

    private fun currentHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    companion object {
        const val HEALTH_ALERT_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val SUMMARY_COOLDOWN_MS = 20L * 60L * 60L * 1000L
        const val TIP_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val MOVIES_NUDGE_COOLDOWN_MS = 18L * 60L * 60L * 1000L
        const val CONTINUE_WATCHING_STALE_MS = 6L * 60L * 60L * 1000L
        const val WATCHLIST_NUDGE_AGE_MS = 24L * 60L * 60L * 1000L
        private const val SUMMARY_NOTIFICATION_ID = 940_100
        private const val TIP_NOTIFICATION_ID_BASE = 941_000
        private const val MOVIES_CW_NOTIFICATION_BASE = 945_000
        private const val MOVIES_SEASON_NOTIFICATION_BASE = 950_000
        private const val MOVIES_WL_NOTIFICATION_BASE = 955_000

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
