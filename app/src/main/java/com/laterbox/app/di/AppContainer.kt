package com.laterbox.app.di

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.laterbox.app.ai.LaterboxContextEngine
import com.laterbox.app.data.backup.BackupManager
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ChatRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.data.repository.NotificationHistoryRepository
import com.laterbox.app.diagnostics.AppHealthChecker
import com.laterbox.app.reminders.ContextualNotificationEngine

interface AppContainer {
    val database: LaterboxDatabase
    val linkRepository: LinkRepository
    val collectionRepository: CollectionRepository
    val userPreferencesRepository: UserPreferencesRepository
    val backupManager: BackupManager
    val reminderScheduler: com.laterbox.app.reminders.ReminderScheduler
    val notificationHistoryRepository: NotificationHistoryRepository
    val mediaRepository: com.laterbox.app.media.domain.repository.MediaRepository
    val mediaInspectionManager: com.laterbox.app.media.data.inspection.MediaInspectionManager
    val platformResolver: com.laterbox.app.media.domain.adapter.PlatformResolver
    val ytDlpRuntimeManager: com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
    val ytDlpUpdater: com.laterbox.app.media.data.adapters.ytdlp.YtDlpUpdater
    val diagnosticsBuffer: com.laterbox.app.media.data.diagnostics.InspectionDiagnosticsBuffer
    val warmYtDlpServer: com.laterbox.app.media.data.adapters.ytdlp.WarmYtDlpServer
    val chatRepository: ChatRepository
    val activityEventRepository: ActivityEventRepository
    val appHealthChecker: AppHealthChecker
    val laterboxContextEngine: LaterboxContextEngine
    val contextualNotificationEngine: ContextualNotificationEngine
    val speechCoordinator: com.laterbox.app.ai.speech.SpeechTranscriptionCoordinator
    val youTubeTranscriptDataSource: com.laterbox.app.transcript.data.YouTubeTranscriptDataSource
    val youTubeTranscriptExtractor: com.laterbox.app.transcript.data.YouTubeTranscriptExtractor
    val transcriptExportManager: com.laterbox.app.transcript.export.TranscriptExportManager
    val moviesRepository: com.laterbox.app.movies.domain.repository.MoviesRepository
    val moviesDownloadBridge: com.laterbox.app.movies.data.download.MoviesDownloadBridge
    val subtitleDownloadManager: com.laterbox.app.movies.data.download.SubtitleDownloadManager
    val appStorageManager: com.laterbox.app.storage.AppStorageManager
    val remoteConfigManager: com.laterbox.app.remoteconfig.RemoteConfigManager
    val versionGate: com.laterbox.app.remoteconfig.LaterboxVersionGate
    val backendAiClient: com.laterbox.app.ai.LaterboxBackendAiClient
    val analyticsTracker: com.laterbox.app.analytics.LaterboxAnalyticsTracker
    val toastController: com.laterbox.app.ui.components.LaterboxToastController
    val connectivityObserver: com.laterbox.app.network.LaterboxConnectivityObserver
    suspend fun resolveBackendBaseUrl(): String
    suspend fun getBackendAppCheckToken(forceRefresh: Boolean = false): String?
    suspend fun getBackendAuthHeader(forceRefresh: Boolean = false): Pair<String, String>?
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    private val containerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override val toastController: com.laterbox.app.ui.components.LaterboxToastController by lazy {
        com.laterbox.app.ui.components.LaterboxToastController(
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
            )
        )
    }

    override val connectivityObserver: com.laterbox.app.network.LaterboxConnectivityObserver by lazy {
        com.laterbox.app.network.LaterboxConnectivityObserver(
            context = context.applicationContext,
            toastController = toastController
        )
    }

    @Volatile
    private var cachedBackendBaseUrl: String = com.laterbox.app.BuildConfig.MOVIEBOX_API_BASE_URL
        .trim()
        .trimEnd('/')

    override val database: LaterboxDatabase by lazy {
        LaterboxDatabase.getInstance(context)
    }

    override val linkRepository: LinkRepository by lazy {
        LinkRepository(database.savedLinkDao(), context.applicationContext)
    }

    override val collectionRepository: CollectionRepository by lazy {
        CollectionRepository(database.collectionDao())
    }

    override val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context)
    }

    override val versionGate: com.laterbox.app.remoteconfig.LaterboxVersionGate by lazy {
        com.laterbox.app.remoteconfig.LaterboxVersionGate(
            currentVersionCode = com.laterbox.app.BuildConfig.VERSION_CODE,
            onForceRefreshRequested = {
                containerScope.launch {
                    runCatching { remoteConfigManager.refreshNow() }
                }
            },
            onBlocked426 = {
                analyticsTracker.trackErrorObserved(
                    errorCategory = "unsupported_version_blocked",
                    httpStatus = 426,
                )
            },
        )
    }

    private val clientHeadersInterceptor: com.laterbox.app.remoteconfig.LaterboxClientHeadersInterceptor by lazy {
        com.laterbox.app.remoteconfig.LaterboxClientHeadersInterceptor(
            clientVersionCode = com.laterbox.app.BuildConfig.VERSION_CODE,
            clientVersionName = com.laterbox.app.BuildConfig.VERSION_NAME,
            backendBaseUrlProvider = { cachedBackendBaseUrl },
            versionGate = versionGate,
        )
    }

    override val backupManager: BackupManager by lazy {
        BackupManager(database)
    }

    override val reminderScheduler: com.laterbox.app.reminders.ReminderScheduler by lazy {
        com.laterbox.app.reminders.ReminderScheduler(context)
    }

    override val notificationHistoryRepository: NotificationHistoryRepository by lazy {
        NotificationHistoryRepository(database.notificationHistoryDao())
    }

    override val mediaInspectionManager: com.laterbox.app.media.data.inspection.MediaInspectionManager by lazy {
        com.laterbox.app.media.data.inspection.MediaInspectionManager(
            appContext = context.applicationContext,
            inspectionDao = database.mediaInspectionDao(),
            platformResolver = platformResolver,
            warmServer = warmYtDlpServer
        )
    }

    override val subtitleDownloadManager: com.laterbox.app.movies.data.download.SubtitleDownloadManager by lazy {
        com.laterbox.app.movies.data.download.SubtitleDownloadManager(
            context = context.applicationContext,
            okHttpClient = moviesOkHttpClient
        )
    }

    override val mediaRepository: com.laterbox.app.media.domain.repository.MediaRepository by lazy {
        com.laterbox.app.media.data.repository.MediaRepositoryImpl(
            context = context.applicationContext,
            assetDao = database.downloadedAssetDao(),
            taskDao = database.downloadTaskDao(),
            inspectionManager = mediaInspectionManager,
            subtitleDownloadManager = subtitleDownloadManager,
            onDownloadStarted = { analyticsTracker.trackFeatureUsed("media_download") },
        )
    }

    override val ytDlpRuntimeManager: com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager by lazy {
        com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager(context.applicationContext)
    }

    override val ytDlpUpdater: com.laterbox.app.media.data.adapters.ytdlp.YtDlpUpdater by lazy {
        com.laterbox.app.media.data.adapters.ytdlp.YtDlpUpdater(
            appContext = context.applicationContext,
            userPreferencesRepository = userPreferencesRepository,
            runtimeManager = ytDlpRuntimeManager,
            onBinaryUpdated = { warmYtDlpServer.shutdown() }
        )
    }

    override val diagnosticsBuffer: com.laterbox.app.media.data.diagnostics.InspectionDiagnosticsBuffer by lazy {
        com.laterbox.app.media.data.diagnostics.InspectionDiagnosticsBuffer()
    }

    override val warmYtDlpServer: com.laterbox.app.media.data.adapters.ytdlp.WarmYtDlpServer by lazy {
        com.laterbox.app.media.data.adapters.ytdlp.WarmYtDlpServer(
            appContext = context.applicationContext,
            userPreferencesRepository = userPreferencesRepository
        )
    }

    override val speechCoordinator: com.laterbox.app.ai.speech.SpeechTranscriptionCoordinator by lazy {
        com.laterbox.app.ai.speech.SpeechTranscriptionCoordinator(
            recorder = com.laterbox.app.ai.speech.AudioRecorderController(context.applicationContext),
            gemini = com.laterbox.app.ai.speech.GeminiTranscribeClient(),
            fallback = com.laterbox.app.ai.speech.AndroidSpeechRecognizerAdapter(context.applicationContext),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
            ),
            apiKeyProvider = {
                userPreferencesRepository.userPreferencesFlow.first().geminiApiKey.ifBlank { null }
            },
            isNetworkAvailable = {
                runCatching {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val net = cm?.activeNetwork ?: return@runCatching false
                    val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }.getOrDefault(true)
            }
        )
    }

    override val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }

    override val activityEventRepository: ActivityEventRepository by lazy {
        ActivityEventRepository(database.activityEventDao())
    }

    override val appHealthChecker: AppHealthChecker by lazy {
        AppHealthChecker(
            appContext = context.applicationContext,
            database = database,
            runtimeManager = ytDlpRuntimeManager,
            userPreferencesRepository = userPreferencesRepository
        )
    }

    override val laterboxContextEngine: LaterboxContextEngine by lazy {
        LaterboxContextEngine(
            database = database,
            activityEventRepository = activityEventRepository,
            userPreferencesRepository = userPreferencesRepository,
            appHealthChecker = appHealthChecker,
            ytDlpRuntimeManager = ytDlpRuntimeManager,
            appContext = context.applicationContext
        )
    }

    override val contextualNotificationEngine: ContextualNotificationEngine by lazy {
        ContextualNotificationEngine(
            appContext = context.applicationContext,
            database = database,
            userPreferencesRepository = userPreferencesRepository,
            activityEventRepository = activityEventRepository,
            appHealthChecker = appHealthChecker
        )
    }

    private val sharedOkHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .dns(com.laterbox.app.network.ResilientDns.INSTANCE)
            .build()
    }

    override val platformResolver: com.laterbox.app.media.domain.adapter.PlatformResolver by lazy {
        val directAdapter = com.laterbox.app.media.data.adapters.direct.DirectUrlAdapter(sharedOkHttpClient)
        val spotifyDiscovery = com.laterbox.app.media.data.adapters.spotify.MusicDiscoveryProviderImpl(
            appContext = context.applicationContext,
            runtimeManager = ytDlpRuntimeManager
        )
        val spotifyAdapter = com.laterbox.app.media.data.adapters.spotify.SpotifyMusicAdapter(
            musicDiscoveryProvider = spotifyDiscovery
        )
        val ytDlpAdapter = com.laterbox.app.media.data.adapters.ytdlp.YtDlpAdapter(
            appContext = context.applicationContext,
            runtimeManager = ytDlpRuntimeManager,
            diagnosticsBuffer = diagnosticsBuffer,
            warmServer = warmYtDlpServer
        )
        val xAdapter = com.laterbox.app.media.data.adapters.x.XMediaAdapter(
            okHttpClient = sharedOkHttpClient,
            fallbackYtDlpAdapter = ytDlpAdapter
        )
        val metadataAdapter = com.laterbox.app.media.data.adapters.metadata.MetadataOnlyAdapter(sharedOkHttpClient)
        val registry = com.laterbox.app.media.domain.adapter.AdapterRegistry(
            listOf(directAdapter, spotifyAdapter, xAdapter, ytDlpAdapter, metadataAdapter)
        )
        com.laterbox.app.media.domain.adapter.PlatformResolver(registry)
    }

    override val youTubeTranscriptDataSource: com.laterbox.app.transcript.data.YouTubeTranscriptDataSource by lazy {
        com.laterbox.app.transcript.data.LaterboxYouTubeTranscriptDataSource(
            appContext = context.applicationContext,
            runtimeManager = ytDlpRuntimeManager,
            warmServer = warmYtDlpServer
        )
    }

    override val youTubeTranscriptExtractor: com.laterbox.app.transcript.data.YouTubeTranscriptExtractor by lazy {
        com.laterbox.app.transcript.data.YouTubeTranscriptExtractor(
            dataSource = youTubeTranscriptDataSource,
            isOnlineServicesPaused = { remoteConfigManager.currentConfig.maintenance.isOnlineServicesPaused },
            onTranscriptFeatureUsed = { analyticsTracker.trackFeatureUsed("youtube_transcripts") },
            onPerformanceMetric = { metric, ms -> analyticsTracker.trackPerformanceMetric(metric, ms) },
            onErrorObserved = { cat -> analyticsTracker.trackErrorObserved(cat) },
        )
    }

    override val transcriptExportManager: com.laterbox.app.transcript.export.TranscriptExportManager by lazy {
        com.laterbox.app.transcript.export.TranscriptExportManager(
            context = context.applicationContext,
            mediaRepository = mediaRepository
        )
    }

    private val moviesOkHttpClient: okhttp3.OkHttpClient by lazy {
        sharedOkHttpClient.newBuilder()
            .addInterceptor(clientHeadersInterceptor)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override val moviesRepository: com.laterbox.app.movies.domain.repository.MoviesRepository by lazy {
        val nativeOkHttpClient = sharedOkHttpClient.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val nativeStreamResolver = com.laterbox.app.movies.data.provider.MovieBoxNativeStreamResolver(
            okHttpClient = nativeOkHttpClient,
        )
        val movieBoxProvider = com.laterbox.app.movies.data.provider.MovieBoxProvider(
            okHttpClient = moviesOkHttpClient,
            baseUrlProvider = {
                userPreferencesRepository.userPreferencesFlow.first().movieBoxApiBaseUrl
                    .ifBlank { com.laterbox.app.BuildConfig.MOVIEBOX_API_BASE_URL }
            },
            appCheckTokenProvider = { forceRefresh ->
                getBackendAppCheckToken(forceRefresh)
            },
            authHeaderProvider = { forceRefresh ->
                getBackendAuthHeader(forceRefresh)
            },
            authDiagnosticProvider = {
                backendAuthProvider.lastResolutionReason
            },
            nativeStreamResolver = nativeStreamResolver,
        )
        val iaProvider = com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider(moviesOkHttpClient)
        val tvMazeProvider = com.laterbox.app.movies.data.provider.TvMazeSeriesProvider(moviesOkHttpClient)
        val tmdbProvider = com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider(
            okHttpClient = moviesOkHttpClient,
            apiKeyProvider = { userPreferencesRepository.userPreferencesFlow.first().tmdbApiKey }
        )
        com.laterbox.app.movies.data.repository.MoviesRepositoryImpl(
            moviesDao = database.moviesDao(),
            internetArchiveProvider = iaProvider,
            tvMazeProvider = tvMazeProvider,
            tmdbProvider = tmdbProvider,
            fixtureProvider = com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider(),
            movieBoxProvider = movieBoxProvider,
            useSampleFixturesOnlyProvider = {
                com.laterbox.app.BuildConfig.DEBUG &&
                    userPreferencesRepository.userPreferencesFlow.first().moviesUseSampleFixturesOnly
            },
            mediaResolver = com.laterbox.app.movies.data.resolver.MediaResolver(database.moviesDao()),
            downloadedAssetDao = database.downloadedAssetDao(),
            localMediaUriResolver = { relPath ->
                val file = com.laterbox.app.media.data.storage.MediaStorageManager(context.applicationContext)
                    .resolveFile(relPath)
                if (file.exists() && file.length() > 0L) android.net.Uri.fromFile(file).toString() else null
            },
            subtitleDownloadManager = subtitleDownloadManager,
            downloadPlaybackProgressDao = database.downloadPlaybackProgressDao(),
            isMovieBoxEnabled = {
                val cfg = remoteConfigManager.currentConfig
                !cfg.maintenance.isOnlineServicesPaused &&
                    cfg.isFeatureEnabled(com.laterbox.app.ai.RemoteCapabilities.KEY_PROVIDER_MOVIEBOX)
            },
            isInternetArchiveEnabled = {
                val cfg = remoteConfigManager.currentConfig
                !cfg.maintenance.isOnlineServicesPaused &&
                    cfg.isFeatureEnabled(com.laterbox.app.ai.RemoteCapabilities.KEY_PROVIDER_INTERNET_ARCHIVE)
            },
            isTvMazeEnabled = {
                val cfg = remoteConfigManager.currentConfig
                !cfg.maintenance.isOnlineServicesPaused &&
                    cfg.isFeatureEnabled(com.laterbox.app.ai.RemoteCapabilities.KEY_PROVIDER_TVMAZE)
            },
            isTmdbEnabled = {
                !remoteConfigManager.currentConfig.maintenance.isOnlineServicesPaused
            },
            onAnalyticsPerformance = { metricName, durationMs, screenId ->
                analyticsTracker.trackPerformanceMetric(
                    metricName = metricName,
                    metricValueMs = durationMs,
                    screenId = screenId,
                )
            },
            onAnalyticsError = { category, screenId ->
                analyticsTracker.trackErrorObserved(
                    errorCategory = category,
                    screenId = screenId,
                )
            },
        )
    }

    override val moviesDownloadBridge: com.laterbox.app.movies.data.download.MoviesDownloadBridge by lazy {
        com.laterbox.app.movies.data.download.MoviesDownloadBridge(
            mediaRepository = mediaRepository
        )
    }

    override val appStorageManager: com.laterbox.app.storage.AppStorageManager by lazy {
        com.laterbox.app.storage.AppStorageManager(
            context = context.applicationContext,
            moviesDao = database.moviesDao(),
            downloadTaskDao = database.downloadTaskDao(),
            mediaRepository = mediaRepository
        )
    }

    override val remoteConfigManager: com.laterbox.app.remoteconfig.RemoteConfigManager by lazy {
        val rcOkHttp = sharedOkHttpClient.newBuilder()
            .addInterceptor(clientHeadersInterceptor)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val versionCode = com.laterbox.app.BuildConfig.VERSION_CODE
        val cache = com.laterbox.app.remoteconfig.RemoteConfigCache(context.applicationContext)
        val client = com.laterbox.app.remoteconfig.RemoteConfigClient(
            okHttpClient = rcOkHttp,
            baseUrlProvider = { resolveBackendBaseUrl() },
            appCheckTokenProvider = { forceRefresh -> getBackendAppCheckToken(forceRefresh) },
            authHeaderProvider = { forceRefresh -> getBackendAuthHeader(forceRefresh) },
            clientVersionCode = versionCode,
            clientVersionName = com.laterbox.app.BuildConfig.VERSION_NAME,
        )
        val repository = com.laterbox.app.remoteconfig.RemoteConfigRepository(
            cache = cache,
            client = client,
            clientVersionCode = versionCode,
            versionGate = versionGate,
        )
        com.laterbox.app.remoteconfig.RemoteConfigManager(repository = repository)
    }

    override val backendAiClient: com.laterbox.app.ai.LaterboxBackendAiClient by lazy {
        val aiOkHttp = sharedOkHttpClient.newBuilder()
            .addInterceptor(clientHeadersInterceptor)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        com.laterbox.app.ai.LaterboxBackendAiClient(
            okHttpClient = aiOkHttp,
            baseUrlProvider = { resolveBackendBaseUrl() },
            appCheckTokenProvider = { forceRefresh -> getBackendAppCheckToken(forceRefresh) },
            authHeaderProvider = { forceRefresh -> getBackendAuthHeader(forceRefresh) }
        )
    }

    override val analyticsTracker: com.laterbox.app.analytics.LaterboxAnalyticsTracker by lazy {
        val analyticsOkHttp = sharedOkHttpClient.newBuilder()
            .addInterceptor(clientHeadersInterceptor)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val remoteAnalyticsEnabledFlow = remoteConfigManager.state
            .map { it.activeConfig.analyticsCollectionEnabled }
            .stateIn(
                scope = containerScope,
                started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
                initialValue = remoteConfigManager.currentConfig.analyticsCollectionEnabled,
            )
        com.laterbox.app.analytics.LaterboxAnalyticsTracker(
            appContext = context.applicationContext,
            okHttpClient = analyticsOkHttp,
            baseUrlProvider = { resolveBackendBaseUrl() },
            appCheckTokenProvider = { forceRefresh -> getBackendAppCheckToken(forceRefresh) },
            authHeaderProvider = { forceRefresh -> getBackendAuthHeader(forceRefresh) },
            remoteCollectionEnabledProvider = { remoteConfigManager.currentConfig.analyticsCollectionEnabled },
            remoteCollectionFlow = remoteAnalyticsEnabledFlow,
        ).also { tracker ->
            remoteConfigManager.attachAnalyticsTracker(tracker)
        }
    }

    override suspend fun resolveBackendBaseUrl(): String {
        val resolved = userPreferencesRepository.userPreferencesFlow.first().movieBoxApiBaseUrl
            .ifBlank { com.laterbox.app.BuildConfig.MOVIEBOX_API_BASE_URL }
            .trim()
            .trimEnd('/')
        cachedBackendBaseUrl = resolved
        return resolved
    }

    private val backendAuthProvider: com.laterbox.app.auth.LaterboxBackendAuthProvider by lazy {
        com.laterbox.app.auth.LaterboxBackendAuthProvider.createFirebaseBacked(context.applicationContext)
    }

    override suspend fun getBackendAppCheckToken(forceRefresh: Boolean): String? {
        return backendAuthProvider.resolveAppCheckOnlyToken(forceRefresh)
    }

    override suspend fun getBackendAuthHeader(forceRefresh: Boolean): Pair<String, String>? {
        return backendAuthProvider.resolveHeaderPair(forceRefresh)
    }
}
