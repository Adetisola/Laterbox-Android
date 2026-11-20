package com.laterbox.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.ai.AiCopyResult
import com.laterbox.app.ai.ReminderOrigin
import com.laterbox.app.ai.SmartDigestEngine
import com.laterbox.app.data.backup.BackupManager
import com.laterbox.app.data.backup.BackupRestoreResult
import com.laterbox.app.data.preferences.CardTapAction
import com.laterbox.app.data.preferences.FontSizeScale
import com.laterbox.app.data.preferences.InboxViewMode
import com.laterbox.app.data.preferences.SmartReminderPersistence
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.data.preferences.ThemeMode
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.reminders.NotificationHelper
import com.laterbox.app.reminders.smart.SmartReminderCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

data class ViewingHistoryUiState(
    val isLoading: Boolean = true,
    val historyItems: List<WatchHistoryItem> = emptyList(),
    val recentlyViewed: List<MovieTitleSummary> = emptyList(),
    val hiddenTitles: List<WatchHistoryItem> = emptyList(),
    val actionFeedbackMessage: String? = null,
    val actionErrorMessage: String? = null
)

class SettingsViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val backupManager: BackupManager,
    private val linkRepository: LinkRepository? = null,
    private val appContext: Context? = null,
    private val ytDlpUpdater: com.laterbox.app.media.data.adapters.ytdlp.YtDlpUpdater? = null,
    private val ytDlpRuntimeManager: com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager? = null,
    private val diagnosticsBuffer: com.laterbox.app.media.data.diagnostics.InspectionDiagnosticsBuffer? = null,
    private val database: com.laterbox.app.data.local.LaterboxDatabase? = null,
    private val backendBaseUrlProvider: (suspend () -> String)? = null,
    private val appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
    private val moviesRepository: MoviesRepository? = null
) : ViewModel() {

    private val runtimeManager: com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager? =
        ytDlpRuntimeManager ?: appContext?.let { ctx ->
            (ctx.applicationContext as? com.laterbox.app.LaterboxApplication)?.container?.ytDlpRuntimeManager
                ?: com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager(ctx)
        }

    val ytDlpState: StateFlow<com.laterbox.app.media.data.adapters.ytdlp.YtDlpInitState> =
        runtimeManager?.ytDlpState ?: MutableStateFlow(com.laterbox.app.media.data.adapters.ytdlp.YtDlpInitState.NotInitialized).asStateFlow()

    val ffmpegState: StateFlow<com.laterbox.app.media.data.adapters.ytdlp.FfmpegInitState> =
        runtimeManager?.ffmpegState ?: MutableStateFlow(com.laterbox.app.media.data.adapters.ytdlp.FfmpegInitState.NotInitialized).asStateFlow()

    val preferences: StateFlow<UserPreferences> = userPreferencesRepository.userPreferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserPreferences()
    )

    private val _aiConnectionStatus = MutableStateFlow<Pair<Boolean, String>?>(null)
    val aiConnectionStatus: StateFlow<Pair<Boolean, String>?> = _aiConnectionStatus.asStateFlow()

    private val _isUpdatingYtDlp = MutableStateFlow(false)
    val isUpdatingYtDlp: StateFlow<Boolean> = _isUpdatingYtDlp.asStateFlow()

    private val _localMetrics = MutableStateFlow<com.laterbox.app.diagnostics.LocalLibraryMetrics?>(null)
    val localMetrics: StateFlow<com.laterbox.app.diagnostics.LocalLibraryMetrics?> = _localMetrics.asStateFlow()

    fun refreshLocalMetrics() {
        viewModelScope.launch {
            val prefs = userPreferencesRepository.userPreferencesFlow.first()
            val db = database ?: (appContext?.applicationContext as? com.laterbox.app.LaterboxApplication)?.container?.database
            _localMetrics.value = if (db != null) {
                com.laterbox.app.diagnostics.ProductMetricsTracker.collectLocalMetrics(db, prefs)
            } else {
                com.laterbox.app.diagnostics.ProductMetricsTracker.computeMetrics(
                    inboxCount = 0,
                    inboxUnreadCount = 0,
                    archivedCount = 0,
                    totalReadCount = 0,
                    activeScheduledReminders = 0,
                    mediaAssetsCount = 0,
                    estimatedStorageBytes = 0L,
                    prefs = prefs
                )
            }
        }
    }

    fun resetActivityCounters() {
        viewModelScope.launch {
            userPreferencesRepository.resetActivityCounters()
            refreshLocalMetrics()
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            userPreferencesRepository.setThemeMode(themeMode)
        }
    }

    fun setFontSizeScale(scale: FontSizeScale) {
        viewModelScope.launch {
            userPreferencesRepository.setFontSizeScale(scale)
        }
    }

    fun setAutoMarkAsRead(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAutoMarkAsRead(enabled)
        }
    }

    fun setDefaultCardTapAction(action: CardTapAction) {
        viewModelScope.launch {
            userPreferencesRepository.setDefaultCardTapAction(action)
        }
    }

    fun setInboxViewMode(mode: InboxViewMode) {
        viewModelScope.launch {
            userPreferencesRepository.setInboxViewMode(mode)
        }
    }

    fun setAllowSystemBackup(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAllowSystemBackup(enabled)
        }
    }

    fun setUserName(name: String) {
        viewModelScope.launch {
            userPreferencesRepository.setUserName(name)
        }
    }

    fun setAccessibilityCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAccessibilityCaptureEnabled(enabled)
        }
    }

    fun setClipboardSuggestionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setClipboardSuggestionEnabled(enabled)
        }
    }

    fun toggleExcludedCapturePackage(packageName: String) {
        viewModelScope.launch {
            userPreferencesRepository.toggleExcludedCapturePackage(packageName)
        }
    }

    private suspend fun syncReminders() {
        appContext?.let { ctx ->
            val p = userPreferencesRepository.userPreferencesFlow.first()
            SmartReminderCoordinator.sync(ctx, p.toSmartReminderSettings())
        }
    }

    fun setSmartRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartRemindersEnabled(enabled)
            syncReminders()
        }
    }

    fun setMorningEveningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMorningEveningEnabled(enabled)
            syncReminders()
        }
    }

    fun setMorningTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setMorningTime(hour, minute)
            syncReminders()
        }
    }

    fun setEveningTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setEveningTime(hour, minute)
            syncReminders()
        }
    }

    fun setWeekendRoundupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setWeekendRoundupEnabled(enabled)
            syncReminders()
        }
    }

    fun setWeekendRoundupTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setWeekendRoundupTime(hour, minute)
            syncReminders()
        }
    }

    fun setDailyDigestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDailyDigestEnabled(enabled)
            syncReminders()
        }
    }

    fun setDailyDigestTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setDailyDigestTime(hour, minute)
            syncReminders()
        }
    }

    fun setDailyDigestWeekdaysOnly(weekdaysOnly: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDailyDigestWeekdaysOnly(weekdaysOnly)
            syncReminders()
        }
    }

    fun setCustomScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setCustomScheduleEnabled(enabled)
            syncReminders()
        }
    }

    fun setCustomScheduleDays(days: Set<Int>) {
        viewModelScope.launch {
            userPreferencesRepository.setCustomScheduleDays(days)
            syncReminders()
        }
    }

    fun setCustomScheduleTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setCustomScheduleTime(hour, minute)
            syncReminders()
        }
    }

    fun setSmartReminderPersistence(persistence: SmartReminderPersistence) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartReminderPersistence(persistence)
        }
    }

    fun setGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            userPreferencesRepository.setGeminiApiKey(apiKey)
            _aiConnectionStatus.value = null
        }
    }

    fun testAiConnection() {
        viewModelScope.launch {
            _aiConnectionStatus.value = Pair(false, "Testing connection...")
            val currentKey = preferences.value.geminiApiKey
            val result = SmartDigestEngine.testGeminiApi(currentKey)
            _aiConnectionStatus.value = result
        }
    }

    suspend fun sendTestSmartDigest(): Boolean {
        val ctx = appContext ?: return false
        val currentPrefs = preferences.value
        val targetLink = linkRepository?.getSmartDigestCandidate()
            ?: SavedLink(
                id = 999_999L,
                originalUrl = "https://instagram.com/p/sample",
                normalizedUrl = "https://instagram.com/p/sample",
                title = "Design systems and typography in practice",
                domain = "instagram.com",
                siteName = "Instagram",
                contentType = ContentType.VIDEO,
                createdAt = System.currentTimeMillis() - (3 * 24 * 60 * 60 * 1000L)
            )

        val result = SmartDigestEngine.generateDigestCopy(
            link = targetLink,
            userName = currentPrefs.userName.ifBlank { null },
            origin = ReminderOrigin.SMART_DIGEST,
            customApiKey = currentPrefs.geminiApiKey.ifBlank { null }
        )

        return when (result) {
            is AiCopyResult.Success -> {
                NotificationHelper.showReminderNotification(
                    context = ctx,
                    link = targetLink,
                    userName = currentPrefs.userName.ifBlank { null },
                    origin = ReminderOrigin.SMART_DIGEST,
                    copy = result.copy
                )
                true
            }
            is AiCopyResult.Failure -> {
                _aiConnectionStatus.value = Pair(false, "Test digest failed: ${result.reason.userMessage}")
                false
            }
        }
    }

    suspend fun exportBackup(outputStream: OutputStream): Result<Int> {
        return backupManager.exportToJson(outputStream)
    }

    suspend fun importBackup(inputStream: InputStream): Result<BackupRestoreResult> {
        return backupManager.importFromJson(inputStream)
    }

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateCheckResult = MutableStateFlow<com.laterbox.app.update.AppUpdateInfo?>(null)
    val updateCheckResult: StateFlow<com.laterbox.app.update.AppUpdateInfo?> = _updateCheckResult.asStateFlow()

    fun checkForUpdates(
        currentVersion: String = com.laterbox.app.BuildConfig.VERSION_NAME,
        onResult: (String) -> Unit = {}
    ) {
        if (_isCheckingUpdate.value) return
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            try {
                appContext?.let { ctx ->
                    try {
                        com.laterbox.app.update.ApkInstallerHelper.cleanupOldUpdateApks(ctx, currentVersion)
                    } catch (_: Exception) {}
                }
                val baseUrl = backendBaseUrlProvider?.invoke()
                    ?: (appContext?.applicationContext as? com.laterbox.app.LaterboxApplication)
                        ?.container?.resolveBackendBaseUrl()
                    ?: userPreferencesRepository.userPreferencesFlow.first().movieBoxApiBaseUrl
                        .ifBlank { com.laterbox.app.BuildConfig.MOVIEBOX_API_BASE_URL }
                val resolvedAppCheckProvider: (suspend (Boolean) -> String?)? = appCheckTokenProvider
                    ?: (appContext?.applicationContext as? com.laterbox.app.LaterboxApplication)
                        ?.container?.let { container ->
                            { forceRefresh -> container.getBackendAppCheckToken(forceRefresh) }
                        }
                val releasesUrl = com.laterbox.app.update.GitHubUpdateChecker.buildBackendUpdateUrl(
                    baseUrl = baseUrl,
                    forceRefresh = true
                )
                val result = com.laterbox.app.update.GitHubUpdateChecker.checkForUpdate(
                    currentVersionName = currentVersion,
                    releasesUrl = releasesUrl,
                    context = appContext,
                    appCheckTokenProvider = resolvedAppCheckProvider,
                    forceRefresh = true
                )
                when (result) {
                    is com.laterbox.app.update.UpdateCheckResult.UpdateAvailable -> {
                        userPreferencesRepository.recordUpdateCheck()
                        _updateCheckResult.value = result.updateInfo
                    }
                    is com.laterbox.app.update.UpdateCheckResult.UpToDate -> {
                        userPreferencesRepository.recordUpdateCheck()
                        onResult("Laterbox is up to date (v$currentVersion)")
                    }
                    is com.laterbox.app.update.UpdateCheckResult.NetworkError -> {
                        onResult("Network error: ${result.message}")
                    }
                    is com.laterbox.app.update.UpdateCheckResult.AuthError -> {
                        onResult("Update authentication failed (${result.statusCode}): ${result.message}")
                    }
                    is com.laterbox.app.update.UpdateCheckResult.RateLimited -> {
                        val waitText = result.retryAfterSeconds?.let { " Try again in ${it}s." } ?: ""
                        onResult("Update server rate limit reached.$waitText ${result.message}".trim())
                    }
                    is com.laterbox.app.update.UpdateCheckResult.ApiError -> {
                        onResult("Update check failed (${result.statusCode}): ${result.message}")
                    }
                    is com.laterbox.app.update.UpdateCheckResult.InvalidRelease -> {
                        onResult("Could not parse release info: ${result.message}")
                    }
                    is com.laterbox.app.update.UpdateCheckResult.NoCompatibleApk -> {
                        userPreferencesRepository.recordUpdateCheck()
                        onResult("Version ${result.releaseVersion} is available, but no APK matching your device (${result.deviceAbis.joinToString()}) was found.")
                    }
                }
            } catch (e: Exception) {
                onResult("Could not check for updates: ${e.message}")
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateCheckResult.value = null
    }

    fun postponeUpdate(version: String) {
        _updateCheckResult.value = null
        viewModelScope.launch {
            userPreferencesRepository.dismissUpdate(version)
        }
    }

    fun setMediaDownloadNetworkPolicy(policy: com.laterbox.app.data.preferences.MediaDownloadNetworkPolicy) {
        viewModelScope.launch {
            userPreferencesRepository.setMediaDownloadNetworkPolicy(policy)
        }
    }

    fun setAutoInspectMediaOnSave(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAutoInspectMediaOnSave(enabled)
        }
    }

    fun updateYtDlp(onResult: (String) -> Unit) {
        if (_isUpdatingYtDlp.value) return
        _isUpdatingYtDlp.value = true
        viewModelScope.launch {
            try {
                val updater = ytDlpUpdater ?: appContext?.let { ctx ->
                    com.laterbox.app.media.data.adapters.ytdlp.YtDlpUpdater(ctx, userPreferencesRepository)
                }
                if (updater == null) {
                    onResult("Updater not available")
                    return@launch
                }
                val result = updater.forceUpdate()
                if (result.isSuccess) {
                    val version = result.getOrThrow()
                    onResult("yt-dlp updated ($version)")
                } else {
                    onResult("Failed to update yt-dlp: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                onResult("Error updating yt-dlp: ${e.message}")
            } finally {
                _isUpdatingYtDlp.value = false
            }
        }
    }

    fun setFastEngineEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFastEngineEnabled(enabled)
        }
    }

    fun getDiagnosticReport(): com.laterbox.app.media.data.adapters.ytdlp.YtDlpDiagnosticReport? {
        return runtimeManager?.captureDiagnosticReport()
    }

    fun getDiagnosticsReportText(): String {
        val runtimeText = runtimeManager?.captureDiagnosticReport()?.toFormattedString() ?: "No runtime diagnostic report available."
        val inspectionsText = diagnosticsBuffer?.exportAsText() ?: "No inspection timings recorded yet."
        return """
            $runtimeText

            ========================================
            INSPECTION TIMING DIAGNOSTICS (LAST 50)
            ========================================

            $inspectionsText
        """.trimIndent()
    }

    fun retryInitializeEngine(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val mgr = runtimeManager
            if (mgr == null) {
                onResult("Runtime manager not available")
                return@launch
            }
            val ytResult = mgr.ensureYtDlpInitialized()
            if (ytResult.isSuccess) {
                val ffmpegResult = mgr.ensureFfmpegInitialized()
                if (ffmpegResult.isSuccess) {
                    onResult("Media engine (yt-dlp + FFmpeg) initialized successfully!")
                } else {
                    onResult("yt-dlp initialized, but FFmpeg failed: ${ffmpegResult.exceptionOrNull()?.message}")
                }
            } else {
                onResult("Initialization failed: ${ytResult.exceptionOrNull()?.message}")
            }
        }
    }

    fun setCustomExportDirectory(uri: String, name: String) {
        viewModelScope.launch {
            userPreferencesRepository.setCustomExportDirectory(uri, name)
        }
    }

    fun clearCustomExportDirectory() {
        viewModelScope.launch {
            userPreferencesRepository.clearCustomExportDirectory()
        }
    }

    fun setAssistantEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAssistantEnabled(enabled)
        }
    }

    fun setAssistantAiDisclosureAccepted(accepted: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAssistantAiDisclosureAccepted(accepted)
            if (!accepted) userPreferencesRepository.setAudioTranscriptionConsentAccepted(false)
        }
    }

    fun setSmartNotificationsExtractionFailure(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartNotificationsExtractionFailure(enabled)
        }
    }

    fun setSmartNotificationsDownloadFailure(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartNotificationsDownloadFailure(enabled)
        }
    }

    fun setSmartNotificationsAppHealth(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartNotificationsAppHealth(enabled)
        }
    }

    fun setSmartNotificationsSummaries(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartNotificationsSummaries(enabled)
        }
    }

    fun setSmartNotificationsTips(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartNotificationsTips(enabled)
        }
    }

    fun setSmartNotificationsMoviesTv(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setSmartNotificationsMoviesTv(enabled)
        }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setQuietHoursEnabled(enabled)
        }
    }

    fun setQuietHoursWindow(startHour: Int, endHour: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setQuietHoursWindow(startHour, endHour)
        }
    }

    fun setMoviesDoubleTapSeekSeconds(seconds: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesDoubleTapSeekSeconds(seconds)
        }
    }

    fun setMoviesSwipeToSeekEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesSwipeToSeekEnabled(enabled)
        }
    }

    fun setMoviesSwipeSeekSensitivity(sensitivity: Float) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesSwipeSeekSensitivity(sensitivity)
        }
    }

    fun setMoviesBrightnessGestureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesBrightnessGestureEnabled(enabled)
        }
    }

    fun setMoviesVolumeGestureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesVolumeGestureEnabled(enabled)
        }
    }

    fun setMoviesAutoplayNextEpisode(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesAutoplayNextEpisode(enabled)
        }
    }

    fun setMoviesAutoplayCountdownSeconds(seconds: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesAutoplayCountdownSeconds(seconds)
        }
    }

    fun setMoviesDefaultQualityHeight(height: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesDefaultQualityHeight(height)
        }
    }

    fun setSubtitleStyleConfig(config: SubtitleStyleConfig) {
        viewModelScope.launch {
            userPreferencesRepository.setSubtitleStyleConfig(config)
        }
    }

    private data class OptimisticHistoryOverrides(
        val removedHistoryKeys: Set<String> = emptySet(),
        val clearAllHistoryPending: Boolean = false,
        val clearTrailersPending: Boolean = false,
        val removedRecentKeys: Set<String> = emptySet(),
        val clearRecentPending: Boolean = false,
        val unhiddenKeys: Set<String> = emptySet(),
        val actionFeedbackMessage: String? = null,
        val actionErrorMessage: String? = null
    )

    private val _viewingHistoryOverrides = MutableStateFlow(OptimisticHistoryOverrides())
    private val inFlightViewingHistoryOps = mutableSetOf<String>()

    val viewingHistoryUiState: StateFlow<ViewingHistoryUiState> = if (moviesRepository == null) {
        MutableStateFlow(ViewingHistoryUiState(isLoading = false)).asStateFlow()
    } else {
        combine(
            moviesRepository.observeViewingHistory(100),
            moviesRepository.observeRecentlyViewed(50),
            moviesRepository.observeHiddenHistory(100),
            _viewingHistoryOverrides
        ) { history, recent, hidden, overrides ->
            val visibleHistory = when {
                overrides.clearAllHistoryPending -> emptyList()
                overrides.clearTrailersPending -> history.filterNot { it.isTrailer }
                overrides.removedHistoryKeys.isEmpty() -> history
                else -> history.filterNot {
                    val effectiveKey = it.historyKey.ifBlank { it.compositeId }
                    effectiveKey in overrides.removedHistoryKeys || it.compositeId in overrides.removedHistoryKeys
                }
            }
            val visibleRecent = if (overrides.clearRecentPending) {
                emptyList()
            } else if (overrides.removedRecentKeys.isEmpty()) {
                recent
            } else {
                recent.filterNot {
                    it.compositeId in overrides.removedRecentKeys || it.stableMediaKey in overrides.removedRecentKeys
                }
            }
            val visibleHidden = if (overrides.unhiddenKeys.isEmpty()) {
                hidden
            } else {
                hidden.filterNot {
                    it.compositeId in overrides.unhiddenKeys || it.stableMediaKey in overrides.unhiddenKeys
                }
            }
            ViewingHistoryUiState(
                isLoading = false,
                historyItems = visibleHistory,
                recentlyViewed = visibleRecent,
                hiddenTitles = visibleHidden,
                actionFeedbackMessage = overrides.actionFeedbackMessage,
                actionErrorMessage = overrides.actionErrorMessage
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ViewingHistoryUiState(isLoading = true)
        )
    }

    fun clearViewingHistoryFeedback() {
        _viewingHistoryOverrides.update { it.copy(actionFeedbackMessage = null, actionErrorMessage = null) }
    }

    fun observeViewingHistory(limit: Int = 100): Flow<List<WatchHistoryItem>> =
        moviesRepository?.observeViewingHistory(limit) ?: emptyFlow()

    fun observeMovieWatchHistory(limit: Int = 100): Flow<List<WatchHistoryItem>> =
        moviesRepository?.observeMovieWatchHistory(limit) ?: emptyFlow()

    fun observeEpisodeWatchHistory(limit: Int = 100): Flow<List<WatchHistoryItem>> =
        moviesRepository?.observeEpisodeWatchHistory(limit) ?: emptyFlow()

    fun observeTrailerHistory(limit: Int = 100): Flow<List<WatchHistoryItem>> =
        moviesRepository?.observeTrailerHistory(limit) ?: emptyFlow()

    fun observeHiddenHistory(limit: Int = 100): Flow<List<WatchHistoryItem>> =
        moviesRepository?.observeHiddenHistory(limit) ?: emptyFlow()

    fun observeRecentlyViewed(limit: Int = 50): Flow<List<MovieTitleSummary>> =
        moviesRepository?.observeRecentlyViewed(limit) ?: emptyFlow()

    fun unhideMovieTitle(providerId: String, titleId: String) {
        val repo = moviesRepository ?: return
        val compositeId = "$providerId:$titleId"
        val opKey = "unhide:$compositeId"
        if (!inFlightViewingHistoryOps.add(opKey)) return
        _viewingHistoryOverrides.update {
            it.copy(
                unhiddenKeys = it.unhiddenKeys + compositeId,
                actionFeedbackMessage = "Title restored",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val res = runCatching { repo.setTitleHidden(providerId, titleId, false) }
                if (res.isFailure) {
                    _viewingHistoryOverrides.update {
                        it.copy(
                            unhiddenKeys = it.unhiddenKeys - compositeId,
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't unhide title. Please try again."
                        )
                    }
                }
            } finally {
                inFlightViewingHistoryOps.remove(opKey)
            }
        }
    }

    fun removeRecentlyViewed(providerId: String, titleId: String) {
        val repo = moviesRepository ?: return
        val compositeId = "$providerId:$titleId"
        val opKey = "rv:$compositeId"
        if (!inFlightViewingHistoryOps.add(opKey)) return
        _viewingHistoryOverrides.update {
            it.copy(
                removedRecentKeys = it.removedRecentKeys + compositeId,
                actionFeedbackMessage = "Removed from Recently Viewed",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val res = runCatching { repo.removeRecentlyViewed(providerId, titleId) }
                if (res.isFailure) {
                    _viewingHistoryOverrides.update {
                        it.copy(
                            removedRecentKeys = it.removedRecentKeys - compositeId,
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't remove title from Recently Viewed."
                        )
                    }
                }
            } finally {
                inFlightViewingHistoryOps.remove(opKey)
            }
        }
    }

    fun clearRecentlyViewed() {
        val repo = moviesRepository ?: return
        val opKey = "clear_rv"
        if (!inFlightViewingHistoryOps.add(opKey)) return
        _viewingHistoryOverrides.update {
            it.copy(
                clearRecentPending = true,
                actionFeedbackMessage = "Cleared Recently Viewed",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val res = runCatching { repo.clearRecentlyViewed() }
                if (res.isFailure) {
                    _viewingHistoryOverrides.update {
                        it.copy(
                            clearRecentPending = false,
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't clear Recently Viewed."
                        )
                    }
                } else {
                    _viewingHistoryOverrides.update { it.copy(clearRecentPending = false, removedRecentKeys = emptySet()) }
                }
            } finally {
                inFlightViewingHistoryOps.remove(opKey)
            }
        }
    }

    fun deleteViewingHistoryEntry(historyKey: String) {
        val repo = moviesRepository ?: return
        val opKey = "del:$historyKey"
        if (!inFlightViewingHistoryOps.add(opKey)) return
        _viewingHistoryOverrides.update {
            it.copy(
                removedHistoryKeys = it.removedHistoryKeys + historyKey,
                actionFeedbackMessage = "Removed from Viewing History",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val res = runCatching { repo.deleteViewingHistoryEntry(historyKey) }
                if (res.isFailure) {
                    _viewingHistoryOverrides.update {
                        it.copy(
                            removedHistoryKeys = it.removedHistoryKeys - historyKey,
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't delete history entry. Please try again."
                        )
                    }
                }
            } finally {
                inFlightViewingHistoryOps.remove(opKey)
            }
        }
    }

    fun clearViewingHistory(trailersOnly: Boolean? = null) {
        val repo = moviesRepository ?: return
        val opKey = "clear_hist_${trailersOnly}"
        if (!inFlightViewingHistoryOps.add(opKey)) return
        _viewingHistoryOverrides.update {
            it.copy(
                clearAllHistoryPending = trailersOnly != true,
                clearTrailersPending = trailersOnly == true,
                actionFeedbackMessage = if (trailersOnly == true) "Cleared trailer history" else "Cleared viewing history",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val res = runCatching { repo.clearViewingHistory(trailersOnly) }
                if (res.isFailure) {
                    _viewingHistoryOverrides.update {
                        it.copy(
                            clearAllHistoryPending = false,
                            clearTrailersPending = false,
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't clear viewing history. Please try again."
                        )
                    }
                } else {
                    _viewingHistoryOverrides.update {
                        it.copy(
                            clearAllHistoryPending = false,
                            clearTrailersPending = false,
                            removedHistoryKeys = emptySet()
                        )
                    }
                }
            } finally {
                inFlightViewingHistoryOps.remove(opKey)
            }
        }
    }
}


