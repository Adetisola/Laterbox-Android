package com.laterbox.app.ai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.SavedLinkWithCollection
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.diagnostics.AppHealthChecker
import com.laterbox.app.intelligence.UserUnderstandingRepository
import com.laterbox.app.media.data.adapters.ytdlp.QuickJsHelper
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpInitState
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class BuiltAssistantContext(
    val systemInstruction: String,
    val contextBlock: String,
    val capabilitySnapshot: CapabilitySnapshot = CapabilitySnapshot()
)

class LaterboxContextEngine(
    private val database: LaterboxDatabase,
    private val activityEventRepository: ActivityEventRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val appHealthChecker: AppHealthChecker? = null,
    private val ytDlpRuntimeManager: YtDlpRuntimeManager? = null,
    private val appContext: Context? = null,
    private val remoteConfigManager: RemoteConfigManager? = null,
    private val userUnderstandingRepository: UserUnderstandingRepository? = null
) {

    suspend fun buildContextForPrompt(prompt: String): BuiltAssistantContext = withContext(Dispatchers.IO) {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        val keywords = extractKeywords(prompt)
        val lowerPrompt = prompt.lowercase()

        val linkDao = database.savedLinkDao()
        val inboxCountDef = async { try { linkDao.getInboxCount() } catch (_: Throwable) { 0 } }
        val archivedCountDef = async { try { linkDao.getArchivedCount() } catch (_: Throwable) { 0 } }
        val candidateLinksDef = async {
            try {
                val recent = linkDao.getRecentInboxLinksSnapshot(limit = 20)
                if (keywords.isEmpty()) recent
                else {
                    val matched = keywords.take(4).flatMap { kw ->
                        linkDao.searchInboxLinksSnapshot(kw, limit = 10)
                    }
                    (matched + recent).distinctBy { it.link.id }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val inspectionsDef = async {
            try {
                database.mediaInspectionDao().getRecentInspectionsSnapshot(15)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val downloadTasksDef = async {
            try {
                database.downloadTaskDao().getRecentTasksSnapshot(15)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val downloadedAssetCountDef = async {
            try {
                database.downloadedAssetDao().getAssetCount().first()
            } catch (_: Throwable) {
                0
            }
        }
        val storedDocumentCountDef = async {
            try {
                database.downloadedAssetDao().filterAssets(MediaType.DOCUMENT.name, "").first().size
            } catch (_: Throwable) {
                0
            }
        }
        val offlineMovieAssetsDef = async {
            try {
                database.downloadedAssetDao().filterAssetsByCategory("MOVIES", "").first()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val collectionsListDef = async {
            try {
                database.collectionDao().getCollectionsWithCounts().first()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val watchlistItemsDef = async {
            try {
                database.moviesDao().observeWatchlist().first()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val viewingHistoryDef = async {
            try {
                database.moviesDao().observeViewingHistory(30).first()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val recentlyViewedDef = async {
            try {
                database.moviesDao().getRecentlyViewedSnapshot(8)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val activeMovieRemindersDef = async {
            try {
                database.moviesDao().getActiveViewingRemindersSnapshot()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val seriesPrefsDef = async {
            try {
                database.moviesDao().getAllSeriesPreferencesSnapshot()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val collectionMediaDef = async {
            try {
                database.moviesDao().getCollectionMediaSnapshot(50)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val recentNotificationsDef = async {
            try {
                database.notificationHistoryDao().getRecentHistorySnapshot(8)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val recentEventsDef = async {
            try {
                activityEventRepository.getRecentEvents(15)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        val healthReportDef = async {
            try {
                appHealthChecker?.runCheck()
            } catch (_: Throwable) {
                null
            }
        }

        val inboxCount = inboxCountDef.await()
        val archivedCount = archivedCountDef.await()
        val candidateLinks = candidateLinksDef.await()
        val rankedLinks = rankRelevantLinks(candidateLinks, keywords, maxItems = 10)
        val inspections = inspectionsDef.await()
        val downloadTasks = downloadTasksDef.await()
        val downloadedAssetCount = downloadedAssetCountDef.await()
        val storedDocumentCount = storedDocumentCountDef.await()
        val offlineMovieAssets = offlineMovieAssetsDef.await()
        val collectionsList = collectionsListDef.await()
        val watchlistItems = watchlistItemsDef.await()
        val viewingHistory = viewingHistoryDef.await()
        val recentlyViewed = recentlyViewedDef.await()
        val activeMovieReminders = activeMovieRemindersDef.await()
        val seriesPrefs = seriesPrefsDef.await()
        val collectionMedia = collectionMediaDef.await()
        val recentNotifications = recentNotificationsDef.await()
        val recentEvents = recentEventsDef.await()
        val healthReport = healthReportDef.await()

        val ytDlpState = ytDlpRuntimeManager?.ytDlpState?.value
        val ffmpegState = ytDlpRuntimeManager?.ffmpegState?.value
        val ytDlpVersion = when (ytDlpState) {
            is YtDlpInitState.Initialized -> ytDlpState.version ?: prefs.ytDlpVersion.ifBlank { null }
            else -> prefs.ytDlpVersion.ifBlank { null }
        }
        val quickJsInfo = appContext?.let {
            try {
                QuickJsHelper.inspectQuickJsRuntime(it)
            } catch (_: Throwable) {
                null
            }
        }
        val quickJsReady = quickJsInfo?.isAvailable ?: false
        val quickJsVersion = quickJsInfo?.version?.takeIf { it != "unknown" }?.let { ver ->
            if (!quickJsInfo.identity.isNullOrBlank() && quickJsInfo.identity != "unknown") "${quickJsInfo.identity} $ver" else ver
        }
        val exportFolderWritable = if (!prefs.customExportDirectoryUri.isNullOrBlank() && appContext != null) {
            try {
                val tree = DocumentFile.fromTreeUri(appContext, Uri.parse(prefs.customExportDirectoryUri))
                tree != null && tree.exists() && tree.canWrite()
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        val nonTrailerHistory = viewingHistory.filter { !it.isTrailer }
        val inProgressHistory = nonTrailerHistory.filter { h ->
            h.durationMs <= 0L || (h.positionMs.toDouble() / h.durationMs.toDouble()) < 0.96
        }
        val completedHistory = nonTrailerHistory.filter { h ->
            h.durationMs > 0L && (h.positionMs.toDouble() / h.durationMs.toDouble()) >= 0.96
        }
        val quietHoursSummary = if (prefs.quietHoursEnabled) {
            "%02d:00–%02d:00".format(prefs.quietHoursStartHour, prefs.quietHoursEndHour)
        } else {
            "Disabled"
        }
        val rc = remoteConfigManager?.currentConfig
        val onlineServicesPaused = rc?.maintenance?.isOnlineServicesPaused == true
        val remoteMoviesTvEnabled = rc?.isFeatureEnabled(RemoteCapabilities.KEY_MOVIES_TV) ?: true
        val remoteProviderMoviebox = rc?.isFeatureEnabled(RemoteCapabilities.KEY_PROVIDER_MOVIEBOX) ?: true
        val remoteProviderInternetArchive = rc?.isFeatureEnabled(RemoteCapabilities.KEY_PROVIDER_INTERNET_ARCHIVE) ?: true
        val remoteProviderTvmaze = rc?.isFeatureEnabled(RemoteCapabilities.KEY_PROVIDER_TVMAZE) ?: true
        val remoteMoviesRecommendations = rc?.isFeatureEnabled(RemoteCapabilities.KEY_MOVIES_RECOMMENDATIONS) ?: true
        val remotePlayerIntroSkip = rc?.isFeatureEnabled(RemoteCapabilities.KEY_PLAYER_INTRO_SKIP) ?: true
        val remoteAssistantEnabled = rc?.isFeatureEnabled(RemoteCapabilities.KEY_AI_ASSISTANT) ?: true
        val remoteSmartRemindersEnabled = rc?.isFeatureEnabled(RemoteCapabilities.KEY_SMART_REMINDERS) ?: true
        val remoteExperienceIntelligence = rc?.isFeatureEnabled(RemoteCapabilities.KEY_EXPERIENCE_INTELLIGENCE) ?: true

        val activeProviderNames = buildList {
            if (remoteProviderMoviebox) add("Live Streaming Catalogue")
            if (remoteProviderInternetArchive) add("Internet Archive")
            if (remoteProviderTvmaze) add("TV Series Guide")
        }
        val moviesProvidersSummary = when {
            onlineServicesPaused -> "Paused during maintenance (offline library available)"
            !remoteMoviesTvEnabled -> "Disabled"
            activeProviderNames.isEmpty() -> "None (all online catalogue providers disabled)"
            else -> activeProviderNames.joinToString(", ")
        }

        val capabilitySnapshot = CapabilitySnapshot(
            ytDlpState = ytDlpState?.name ?: "NOT_INITIALIZED",
            ytDlpVersion = ytDlpVersion,
            quickJsReady = quickJsReady,
            quickJsVersion = quickJsVersion,
            ffmpegState = ffmpegState?.name ?: "NOT_INITIALIZED",
            youtubeTranscriptsReady = ytDlpState !is YtDlpInitState.Failed,
            storedDocumentCount = storedDocumentCount,
            configuredExportFolderName = prefs.customExportDirectoryName?.let { sanitize(it, 60) },
            configuredExportFolderWritable = exportFolderWritable,
            assistantEnabled = prefs.assistantEnabled && remoteAssistantEnabled,
            smartRemindersEnabled = prefs.smartRemindersEnabled && remoteSmartRemindersEnabled,
            quietHoursSummary = quietHoursSummary,
            moviesProvidersSummary = moviesProvidersSummary,
            moviesWatchlistCount = watchlistItems.size,
            moviesInProgressCount = inProgressHistory.size,
            moviesCompletedCount = completedHistory.size,
            moviesOfflineCount = offlineMovieAssets.size,
            collectionsCount = collectionsList.size,
            moviesActiveRemindersCount = activeMovieReminders.size,
            clientVersionCode = com.laterbox.app.BuildConfig.VERSION_CODE,
            clientVersionName = com.laterbox.app.BuildConfig.VERSION_NAME,
            onlineServicesPaused = onlineServicesPaused,
            moviesTvEnabled = remoteMoviesTvEnabled,
            providerMovieboxEnabled = remoteProviderMoviebox,
            providerInternetArchiveEnabled = remoteProviderInternetArchive,
            providerTvmazeEnabled = remoteProviderTvmaze,
            moviesRecommendationsEnabled = remoteMoviesRecommendations,
            playerIntroSkipEnabled = remotePlayerIntroSkip
        )
        val evaluatedCapabilities = AppCapabilityRegistry.evaluateCapabilities(capabilitySnapshot)
        val asksAboutAllCapabilities = listOf(
            "what can you", "capabilit", "all features", "what does laterbox", "support", "help"
        ).any { lowerPrompt.contains(it) }
        val scopedCapabilities = if (asksAboutAllCapabilities) {
            evaluatedCapabilities
        } else {
            val selected = evaluatedCapabilities.filter { item ->
                item.status != CapabilityAvailability.SUPPORTED ||
                    keywords.any { kw ->
                        item.title.contains(kw, ignoreCase = true) ||
                            item.section.contains(kw, ignoreCase = true) ||
                            item.id.contains(kw, ignoreCase = true) ||
                            item.description.contains(kw, ignoreCase = true)
                    }
            }
            selected.ifEmpty { evaluatedCapabilities.take(3) }
        }
        val optInTasteSummary = if (
            prefs.personalizationEnabled &&
            prefs.assistantPersonalContextEnabled &&
            remoteExperienceIntelligence
        ) {
            runCatching { userUnderstandingRepository?.buildPrivacySafeAssistantSummary().orEmpty() }
                .getOrDefault("")
        } else {
            ""
        }

        val asksForDeepContent = listOf(
            "transcript", "caption", "full article", "summarize the video", "what did they say",
            "read the whole", "word for word", "entire page", "full text"
        ).any { lowerPrompt.contains(it) }

        val rankedWatchlist = rankByKeywords(watchlistItems, keywords, 8) { w ->
            "${w.title} ${w.kind} ${w.genresCsv} ${w.summary.orEmpty()}"
        }
        val rankedHistory = rankByKeywords(nonTrailerHistory, keywords, 8) { h ->
            "${h.title} ${h.kind} ${h.episodeLabel.orEmpty()}"
        }
        val rankedRecentlyViewed = rankByKeywords(recentlyViewed, keywords, 5) { rv ->
            "${rv.title} ${rv.kind} ${rv.genresCsv}"
        }
        val mutedSeriesCount = seriesPrefs.count { it.remindersMuted }

        val contextBlock = buildString {
            val nowMs = System.currentTimeMillis()
            val zoneId = java.time.ZoneId.systemDefault()
            val zonedNow = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zoneId)
            appendLine("=== LATERBOX USER CONTEXT SNAPSHOT ===")
            appendLine("Current local time: $zonedNow (timezone=${zoneId.id}, currentEpochMillis=$nowMs)")
            if (prefs.userName.isNotBlank()) {
                appendLine("User name: ${sanitize(prefs.userName, 40)}")
            }
            if (optInTasteSummary.isNotBlank()) {
                appendLine("On-Device Taste Profile: ${sanitize(optInTasteSummary, 240)}")
            }
            appendLine(
                "Library summary: $inboxCount inbox links, $archivedCount archived links, " +
                    "${collectionsList.size} collections (${collectionMedia.size} Movies & TV items), $downloadedAssetCount downloaded media files " +
                    "($storedDocumentCount saved documents, ${offlineMovieAssets.size} offline movies/episodes), " +
                    "${watchlistItems.size} Movies & TV watchlist items, ${activeMovieReminders.size} Movies & TV reminders" +
                    (if (mutedSeriesCount > 0) ", $mutedSeriesCount muted series." else ".")
            )
            if (onlineServicesPaused) {
                val maintMsg = rc?.maintenance?.message?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Online services are temporarily paused during maintenance."
                appendLine("Maintenance notice: ${sanitize(maintMsg, 200)}")
            }
            appendLine()
            appendLine(AppCapabilityRegistry.formatLiveCapabilitiesBlock(capabilitySnapshot))
            appendLine()
            appendLine(AppCapabilityRegistry.formatEvaluatedCapabilitiesBlock(scopedCapabilities))
            val omittedCount = evaluatedCapabilities.size - scopedCapabilities.size
            if (omittedCount > 0) {
                appendLine("- ($omittedCount additional capabilities are SUPPORTED and available.)")
            }

            if (healthReport != null) {
                appendLine()
                appendLine("App Health Summary:")
                appendLine(
                    "- Status=${healthReport.overallStatus.name} | " +
                        "ytDlp=${sanitize(healthReport.ytDlpStateLabel, 24)} | " +
                        "ffmpeg=${sanitize(healthReport.ffmpegStateLabel, 24)} | " +
                        "quickJs=${if (healthReport.quickJsAvailable) "available" else "missing"} | " +
                        "storageFreeMb=${healthReport.availableStorageMb()} | " +
                        "network=${when { healthReport.isNetworkValidated -> "validated"; healthReport.isNetworkConnected -> "connected"; else -> "offline" }} | " +
                        "notifications=${if (healthReport.notificationsEnabled) "enabled" else "disabled"} | " +
                        "exactAlarms=${if (healthReport.exactAlarmsAllowed) "allowed" else "restricted"} | " +
                        "failedInspections=${healthReport.failedInspectionsCount} | " +
                        "failedDownloads=${healthReport.failedDownloadsCount}"
                )
                for (issue in healthReport.issues.take(5)) {
                    appendLine("- [${issue.severity.name}] ${sanitize(issue.title, 80)}: ${sanitize(issue.summary, 120)} (Fix: ${sanitize(issue.suggestedFix, 100)})")
                }
            }

            val explicitlyRequestsCollections = EXPLICIT_COLLECTION_LIST_REGEX.containsMatchIn(lowerPrompt)
            val matchedCollections = collectionsList.filter { col ->
                collectionMatchesPrompt(col.name, keywords, explicitlyRequestsCollections)
            }
            if (matchedCollections.isNotEmpty()) {
                val colSlice = matchedCollections.take(10)
                appendLine("\nCollections (max ${colSlice.size}):")
                for (col in colSlice) {
                    appendLine("- [collectionId=${col.id}] name=\"${sanitize(col.name, 50)}\" items=${col.itemCount}")
                }
            }

            if (rankedLinks.isNotEmpty()) {
                appendLine("\nRelevant & recent saved links (max ${rankedLinks.size}):")
                for (item in rankedLinks) {
                    val l = item.link
                    val visibleCols = item.collections
                        .filter { col -> collectionMatchesPrompt(col.name, keywords, explicitlyRequestsCollections) }
                        .joinToString(", ") { sanitize(it.name, 50) }
                    val statusFlags = buildList {
                        if (!l.isRead) add("unread") else add("read")
                        if (l.reminderAt != null && !l.nudgeDismissed) add("reminder-set")
                    }.joinToString(", ")
                    append("- [linkId=${l.id}] title=\"${sanitize(l.title, 80)}\" domain=${sanitize(l.domain, 40)} url=${sanitize(l.originalUrl, 120)} type=${l.contentType.name} status=($statusFlags)")
                    if (visibleCols.isNotEmpty()) {
                        append(" collections=[$visibleCols]")
                    }
                    if (!l.personalNote.isNullOrBlank()) {
                        append(" note=\"${sanitize(l.personalNote, 120)}\"")
                    }
                    if (!l.description.isNullOrBlank()) {
                        append(" desc=\"${sanitize(l.description, 120)}\"")
                    }
                    appendLine()
                }
            }

            if (rankedWatchlist.isNotEmpty()) {
                appendLine("\nMovies & TV Watchlist (max ${rankedWatchlist.size}):")
                for (w in rankedWatchlist) {
                    append("- [providerId=${sanitize(w.providerId, 30)} titleId=${sanitize(w.titleId, 60)}] ")
                    append("title=\"${sanitize(w.title, 70)}\" kind=${w.kind}")
                    w.releaseYear?.let { append(" year=$it") }
                    if (w.genresCsv.isNotBlank()) append(" genres=\"${sanitize(w.genresCsv, 60)}\"")
                    appendLine()
                }
            }

            if (rankedHistory.isNotEmpty()) {
                appendLine("\nMovies & TV Watch History (max ${rankedHistory.size}):")
                for (h in rankedHistory) {
                    val pct = if (h.durationMs > 0L) ((h.positionMs * 100L) / h.durationMs).coerceIn(0L, 100L) else 0L
                    append("- [providerId=${sanitize(h.providerId, 30)} titleId=${sanitize(h.titleId, 60)}] ")
                    append("title=\"${sanitize(h.title, 70)}\" kind=${h.kind}")
                    if (h.seasonNumber != null && h.episodeNumber != null) {
                        append(" S${h.seasonNumber}E${h.episodeNumber}")
                    }
                    append(" progress=${pct}%")
                    if (h.rewatchCount > 0) append(" rewatches=${h.rewatchCount}")
                    appendLine()
                }
            }

            if (rankedRecentlyViewed.isNotEmpty()) {
                appendLine("\nMovies & TV Recently Viewed (max ${rankedRecentlyViewed.size}):")
                for (rv in rankedRecentlyViewed) {
                    append("- [providerId=${sanitize(rv.providerId, 30)} titleId=${sanitize(rv.titleId, 60)}] ")
                    append("title=\"${sanitize(rv.title, 70)}\" kind=${rv.kind}")
                    rv.releaseYear?.let { append(" year=$it") }
                    appendLine()
                }
            }

            if (activeMovieReminders.isNotEmpty()) {
                val reminderSlice = activeMovieReminders.take(5)
                val dateFmt = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
                appendLine("\nMovies & TV Viewing Reminders (max ${reminderSlice.size}):")
                for (rem in reminderSlice) {
                    append("- [providerId=${sanitize(rem.providerId, 30)} titleId=${sanitize(rem.titleId, 60)}] ")
                    append("title=\"${sanitize(rem.title, 70)}\" triggerAt=\"${dateFmt.format(java.util.Date(rem.triggerAtMillis))}\"")
                    if (!rem.note.isNullOrBlank()) append(" note=\"${sanitize(rem.note, 80)}\"")
                    appendLine()
                }
            }

            if (offlineMovieAssets.isNotEmpty()) {
                appendLine("\nOffline Downloaded Movies & Episodes (max 6):")
                for (asset in offlineMovieAssets.take(6)) {
                    append("- title=\"${sanitize(asset.title ?: "Downloaded title", 70)}\" category=${asset.contentCategory ?: asset.mediaType}")
                    if (!asset.qualityLabel.isNullOrBlank()) append(" quality=${sanitize(asset.qualityLabel, 20)}")
                    appendLine()
                }
            }

            if (inspections.isNotEmpty()) {
                val relevantInspections = inspections.filter {
                    it.status != "COMPLETED" || keywords.isEmpty() || rankedLinks.any { rl -> rl.link.id == it.savedLinkId }
                }.take(8)
                if (relevantInspections.isNotEmpty()) {
                    appendLine("\nRecent media inspections:")
                    for (insp in relevantInspections) {
                        append("- linkId=${insp.savedLinkId} status=${insp.status}")
                        if (!insp.errorCode.isNullOrBlank()) append(" code=${insp.errorCode}")
                        if (!insp.errorMessage.isNullOrBlank()) append(" detail=\"${sanitize(insp.errorMessage, 100)}\"")
                        appendLine()
                    }
                }
            }

            if (downloadTasks.isNotEmpty()) {
                val tasksSlice = downloadTasks.take(8)
                appendLine("\nRecent download tasks:")
                for (t in tasksSlice) {
                    append("- taskId=${t.id} linkId=${t.savedLinkId} status=${t.status} type=${t.mediaType} progress=${t.progress}%")
                    if (!t.title.isNullOrBlank()) append(" title=\"${sanitize(t.title, 60)}\"")
                    if (!t.errorMessage.isNullOrBlank()) append(" error=\"${sanitize(t.errorMessage, 100)}\"")
                    appendLine()
                }
            }

            if (recentNotifications.isNotEmpty()) {
                appendLine("\nRecent in-app notifications:")
                for (n in recentNotifications.take(5)) {
                    appendLine("- [${n.origin}] ${sanitize(n.title, 70)}: ${sanitize(n.body, 100)}")
                }
            }

            if (recentEvents.isNotEmpty()) {
                appendLine("\nRecent app activity events:")
                for (ev in recentEvents.take(8)) {
                    append("- [${ev.eventType}] ${sanitize(ev.title, 80)}")
                    if (!ev.detail.isNullOrBlank()) append(" (${sanitize(ev.detail, 100)})")
                    appendLine()
                }
            }

            if (asksForDeepContent) {
                appendLine("\nCONTENT GROUNDING NOTE: Laterbox does not store full paywalled article bodies or run local speech-to-text audio transcription. However, Laterbox DOES support verbatim YouTube caption/transcript extraction for single videos and playlists, clipboard copying, and exporting to Markdown (.md), Plain Text (.txt), and PDF (.pdf) into Downloads Library → Documents (and a configured export folder). Never invent or paraphrase unextracted video speech; you may offer OPEN_YOUTUBE_TRANSCRIPTS so the user can extract available captions directly.")
            }
        }.trim()

        val systemInstruction = buildString {
            appendLine("You are the Laterbox Assistant embedded inside the Laterbox Android app.")
            appendLine("Be concise, warm, accurate, and grounded strictly in Laterbox's real features and the provided user context snapshot.")
            appendLine("Never invent saved links, Movies & TV titles, providerId/titleId/linkId/taskId values, URLs, formats, release dates, timestamps, or app settings that do not exist.")
            appendLine("NEVER emit [ACTIONS] for simple greetings, small talk, or casual chat (e.g., \"hello\", \"hi\", \"thanks\"). Only emit [ACTIONS] when the user explicitly requests a task (e.g., \"play X\", \"remind me to Y\", \"save Z\").")
            appendLine("When scheduling a reminder, compute triggerAtMillis and triggerAtIso strictly relative to Current local time and currentEpochMillis above so it is always in the future.")
            appendLine()
            appendLine(LaterboxKnowledgeBase.FEATURE_GUIDE)
            appendLine()
            appendLine(LaterboxKnowledgeBase.ALLOWED_ACTION_SPEC)
            appendLine()
            appendLine(contextBlock)
        }.trim()

        BuiltAssistantContext(
            systemInstruction = systemInstruction,
            contextBlock = contextBlock,
            capabilitySnapshot = capabilitySnapshot.copy(
                clientVersionCode = com.laterbox.app.BuildConfig.VERSION_CODE,
                clientVersionName = com.laterbox.app.BuildConfig.VERSION_NAME
            )
        )
    }

    private fun extractKeywords(prompt: String): Set<String> {
        return prompt.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
    }

    private fun <T> rankByKeywords(
        items: List<T>,
        keywords: Set<String>,
        maxItems: Int,
        haystackSelector: (T) -> String
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        if (keywords.isEmpty()) return items.take(maxItems)
        return items.sortedByDescending { item ->
            val haystack = haystackSelector(item).lowercase()
            var score = 0
            for (kw in keywords) {
                if (haystack.contains(kw)) score += 10
            }
            score
        }.take(maxItems)
    }

    private fun rankRelevantLinks(
        links: List<SavedLinkWithCollection>,
        keywords: Set<String>,
        maxItems: Int
    ): List<SavedLinkWithCollection> {
        if (links.isEmpty()) return emptyList()
        if (keywords.isEmpty()) return links.take(maxItems)

        return links.sortedByDescending { item ->
            val l = item.link
            val haystack = buildString {
                append(l.title.lowercase()).append(' ')
                append(l.domain.lowercase()).append(' ')
                append(l.originalUrl.lowercase()).append(' ')
                append(l.personalNote?.lowercase().orEmpty()).append(' ')
                append(l.description?.lowercase().orEmpty()).append(' ')
                item.collections.forEach { append(it.name.lowercase()).append(' ') }
            }
            var score = 0
            for (kw in keywords) {
                if (haystack.contains(kw)) score += 10
            }
            if (!l.isRead) score += 1
            score
        }.take(maxItems)
    }

    private fun collectionMatchesPrompt(
        name: String,
        keywords: Set<String>,
        explicitlyRequestsCollections: Boolean
    ): Boolean {
        if (explicitlyRequestsCollections) return true
        if (keywords.isEmpty()) return false
        val nameTokens = name.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
        if (nameTokens.isEmpty()) return false
        return nameTokens.any { tok -> tok in keywords }
    }

    private fun sanitize(raw: String, maxLen: Int): String {
        return ActivityEventRepository.sanitizeText(raw, maxLen)
            .replace("\n", " ")
            .replace("\r", " ")
    }

    companion object {
        private val EXPLICIT_COLLECTION_LIST_REGEX = Regex(
            pattern = "\\b(?:" +
                "(?:my|all|existing|saved|custom|available|current)\\s+collections(?!\\s+(?:work|works|feature|features|settings?|sync|backup|privacy|help|guide|tab|screen|do|mean)\\b)" +
                "|(?:list|show|view|see|get|display|name|names\\s+of|browse|check|open|find)\\s+(?:all\\s+)?(?:my\\s+|the\\s+)?collections(?!\\s+(?:settings?|help|guide|feature|features|work|works|tab|screen)\\b)" +
                "|(?:what|which)\\s+(?:collections|are\\s+(?:my|the)\\s+collections)(?!\\s+(?:work|works|do|mean|settings?|feature|features)\\b)" +
                "|collections?\\s+(?:list|names?|inventory)" +
                "|collection\\s+names?" +
                ")\\b",
            option = RegexOption.IGNORE_CASE
        )

        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "what", "when", "where",
            "which", "have", "from", "about", "your", "laterbox", "links", "link",
            "saved", "please", "can", "you", "how", "why", "are", "was", "were",
            "not", "ill", "show", "hello", "hey", "sure", "feel", "today",
            "should", "could", "would", "collection", "collections", "does", "did",
            "has", "had", "but", "yet", "now", "just", "very", "too", "also",
            "into", "out", "off", "all", "any", "some", "more", "most", "other",
            "such", "only", "own", "same", "than", "then", "there", "here",
            "these", "those", "they", "them", "their", "who", "whom", "whose",
            "want", "need", "help", "tell", "find", "give", "make", "know",
            "think", "look", "work", "explain", "recommend", "fix"
        )
    }
}
