package com.laterbox.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.ui.archive.ArchiveViewModel
import com.laterbox.app.ui.collections.CollectionDetailViewModel
import com.laterbox.app.ui.collections.CollectionsViewModel
import com.laterbox.app.ui.inbox.InboxViewModel
import com.laterbox.app.ui.settings.SettingsViewModel
import com.laterbox.app.ui.sheets.SaveLinkViewModel
import com.laterbox.app.ui.sheets.SavedItemDetailViewModel

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            val app = laterboxApplication()
            InboxViewModel(
                linkRepository = app.container.linkRepository,
                userPreferencesRepository = app.container.userPreferencesRepository,
                notificationHistoryRepository = app.container.notificationHistoryRepository,
                inspectionDao = app.container.database.mediaInspectionDao()
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.ui.notifications.NotificationHistoryViewModel(
                app.container.notificationHistoryRepository
            )
        }
        initializer {
            val app = laterboxApplication()
            CollectionsViewModel(app.container.collectionRepository)
        }
        initializer {
            val app = laterboxApplication()
            CollectionDetailViewModel(
                linkRepository = app.container.linkRepository,
                moviesRepository = app.container.moviesRepository
            )
        }
        initializer {
            val app = laterboxApplication()
            ArchiveViewModel(app.container.linkRepository)
        }
        initializer {
            val app = laterboxApplication()
            SaveLinkViewModel(
                linkRepository = app.container.linkRepository,
                collectionRepository = app.container.collectionRepository,
                inboxResolverClient = app.container.inboxResolverClient,
                mediaRepository = app.container.mediaRepository
            )
        }
        initializer {
            val app = laterboxApplication()
            SavedItemDetailViewModel(
                linkRepository = app.container.linkRepository,
                collectionRepository = app.container.collectionRepository,
                mediaRepository = app.container.mediaRepository,
                platformResolver = app.container.platformResolver
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.media.ui.downloads.DownloadsViewModel(
                mediaRepository = app.container.mediaRepository,
                inspectionDao = app.container.database.mediaInspectionDao(),
                subtitleDownloadManager = app.container.subtitleDownloadManager,
                progressDao = app.container.database.downloadPlaybackProgressDao()
            )
        }
        initializer {
            val app = laterboxApplication()
            SettingsViewModel(
                userPreferencesRepository = app.container.userPreferencesRepository,
                backupManager = app.container.backupManager,
                linkRepository = app.container.linkRepository,
                appContext = app.applicationContext,
                ytDlpUpdater = app.container.ytDlpUpdater,
                ytDlpRuntimeManager = app.container.ytDlpRuntimeManager,
                diagnosticsBuffer = app.container.diagnosticsBuffer,
                database = app.container.database,
                backendBaseUrlProvider = { app.container.resolveBackendBaseUrl() },
                appCheckTokenProvider = { forceRefresh -> app.container.getBackendAppCheckToken(forceRefresh) },
                authHeaderProvider = { forceRefresh -> app.container.getBackendAuthHeader(forceRefresh) },
                moviesRepository = app.container.moviesRepository,
                appStorageManager = app.container.appStorageManager
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.ui.chat.ChatViewModel(
                chatRepository = app.container.chatRepository,
                userPreferencesRepository = app.container.userPreferencesRepository,
                laterboxContextEngine = app.container.laterboxContextEngine,
                linkRepository = app.container.linkRepository,
                mediaRepository = app.container.mediaRepository,
                speech = app.container.speechCoordinator,
                moviesRepository = app.container.moviesRepository,
                collectionRepository = app.container.collectionRepository,
                reminderScheduler = app.container.reminderScheduler,
                backendAiClient = app.container.backendAiClient,
                analyticsTracker = app.container.analyticsTracker,
                applicationContext = app.applicationContext,
                isAssistantRemotePaused = {
                    val rc = app.container.remoteConfigManager.currentConfig
                    rc.maintenance.isOnlineServicesPaused ||
                        !rc.isFeatureEnabled(com.laterbox.app.ai.RemoteCapabilities.KEY_AI_ASSISTANT)
                }
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.transcript.ui.YouTubeTranscriptViewModel(
                extractor = app.container.youTubeTranscriptExtractor,
                exportManager = app.container.transcriptExportManager,
                userPreferencesRepository = app.container.userPreferencesRepository
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.movies.ui.MoviesHomeViewModel(
                moviesRepository = app.container.moviesRepository,
                userPreferencesRepository = app.container.userPreferencesRepository,
                collectionRepository = app.container.collectionRepository,
                computeDispatcher = kotlinx.coroutines.Dispatchers.Default
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.movies.ui.MoviesCatalogueViewModel(
                moviesRepository = app.container.moviesRepository,
                collectionRepository = app.container.collectionRepository
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.movies.ui.MovieDetailViewModel(
                moviesRepository = app.container.moviesRepository,
                moviesDownloadBridge = app.container.moviesDownloadBridge,
                userPreferencesRepository = app.container.userPreferencesRepository,
                collectionRepository = app.container.collectionRepository,
                reminderScheduler = app.container.reminderScheduler,
                mediaRepository = app.container.mediaRepository,
                computeDispatcher = kotlinx.coroutines.Dispatchers.Default
            )
        }
        initializer {
            val app = laterboxApplication()
            com.laterbox.app.movies.ui.MoviePlayerViewModel(
                moviesRepository = app.container.moviesRepository,
                moviesDownloadBridge = app.container.moviesDownloadBridge,
                userPreferencesRepository = app.container.userPreferencesRepository
            )
        }
    }
}

fun CreationExtras.laterboxApplication(): LaterboxApplication =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LaterboxApplication)
