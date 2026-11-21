package com.laterbox.app.ui.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.ai.ChatAction
import com.laterbox.app.ai.LaterboxContextEngine
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ChatRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.diagnostics.AppHealthChecker
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.DownloadTask
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.ViewingReminderItem
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.reminders.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantActionPermissionsTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var db: LaterboxDatabase
    private lateinit var chatRepo: ChatRepository
    private lateinit var linkRepo: LinkRepository
    private lateinit var mediaRepo: RecordingFakeMediaRepository
    private lateinit var moviesRepo: RecordingFakeMoviesRepository
    private lateinit var collectionRepo: CollectionRepository
    private lateinit var prefs: UserPreferencesRepository
    private lateinit var vm: ChatViewModel

    private suspend fun awaitCondition(
        scope: kotlinx.coroutines.test.TestScope,
        timeoutMs: Long = 3_000L,
        predicate: suspend () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            scope.testScheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(10)
        }
        scope.testScheduler.advanceUntilIdle()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        chatRepo = ChatRepository(db.chatDao())
        linkRepo = LinkRepository(db.savedLinkDao(), ctx)
        mediaRepo = RecordingFakeMediaRepository()
        moviesRepo = RecordingFakeMoviesRepository()
        collectionRepo = CollectionRepository(db.collectionDao())
        prefs = UserPreferencesRepository(ctx)
        kotlinx.coroutines.runBlocking {
            prefs.revokeAllAssistantCategoryPermissions()
            prefs.userPreferencesFlow.first()
        }
        val activityRepo = ActivityEventRepository(db.activityEventDao())
        vm = ChatViewModel(
            chatRepository = chatRepo,
            userPreferencesRepository = prefs,
            laterboxContextEngine = LaterboxContextEngine(db, activityRepo, prefs),
            appHealthChecker = AppHealthChecker(ctx, db, com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager(ctx), prefs),
            linkRepository = linkRepo,
            mediaRepository = mediaRepo,
            moviesRepository = moviesRepo,
            collectionRepository = collectionRepo,
            reminderScheduler = ReminderScheduler(ctx)
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun allowOnce_executesActionWithReceipt_andDoesNotPersistGrant() = runTest(mainDispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val linkId = db.savedLinkDao().insertLink(
            SavedLinkEntity(originalUrl = "https://example.com/v1", normalizedUrl = "https://example.com/v1", title = "Talk", domain = "example.com")
        )
        val convId = chatRepo.createConversation("Check")
        vm.selectConversation(convId)
        awaitCondition(this) { vm.uiState.value.activeConversationId == convId }

        val action = ChatAction.RetryInspection(linkId, "Talk")
        vm.requestMutatingActionConfirmation(action)
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction != null }
        assertEquals(action, vm.uiState.value.pendingMutatingAction)

        vm.confirmMutatingAction()
        awaitCondition(this) { mediaRepo.inspectedLinkIds.size == 1 && chatRepo.getMessagesSnapshot(convId).isNotEmpty() }

        assertNull(vm.uiState.value.pendingMutatingAction)
        assertEquals(listOf(linkId), mediaRepo.inspectedLinkIds)
        assertTrue(chatRepo.getGrantedActionTypes(convId).isEmpty())
        val messages = chatRepo.getMessagesSnapshot(convId)
        assertTrue(messages.last().content.contains("Started a fresh media inspection"))

        // Second request in same conversation must ask again
        vm.requestMutatingActionConfirmation(action)
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction != null }
        assertNotNull(vm.uiState.value.pendingMutatingAction)
    }

    @Test
    fun alwaysAllow_grantsCategorySafely_keepsOtherCategoriesAndDestructivePrompted_andCascadeDeletes() = runTest(mainDispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val linkId = db.savedLinkDao().insertLink(
            SavedLinkEntity(originalUrl = "https://example.com/v2", normalizedUrl = "https://example.com/v2", title = "Demo", domain = "example.com")
        )
        mediaRepo.taskFlow.value = DownloadTask(
            id = "task-1", savedLinkId = linkId, sourceUrl = "https://example.com/v2",
            sourcePlatform = "Direct", candidateId = "c1", formatSelector = null,
            mediaType = MediaType.VIDEO, qualityLabel = "720p", title = "Demo Video",
            assetId = null, status = DownloadState.FAILED, progress = 0,
            downloadedBytes = 0, totalBytes = null, createdAt = 1L, updatedAt = 1L
        )
        moviesRepo.seedTitle(
            MovieTitleSummary(
                id = "42",
                providerId = "tvmaze",
                kind = MediaKind.TV_SERIES,
                title = "Andor",
                releaseYear = 2022,
                sourceBadge = DataSourceBadge.LIVE_TVMAZE
            )
        )
        val convA = chatRepo.createConversation("Conv A")
        val convB = chatRepo.createConversation("Conv B")

        vm.selectConversation(convA)
        awaitCondition(this) { vm.uiState.value.activeConversationId == convA }
        vm.requestMutatingActionConfirmation(ChatAction.RetryInspection(linkId, "Demo"))
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction != null }
        vm.alwaysAllowMutatingActionInConversation()
        awaitCondition(this) {
            mediaRepo.inspectedLinkIds.size == 1 &&
                "LIBRARY_MANAGEMENT" in kotlinx.coroutines.runBlocking { prefs.userPreferencesFlow.first().grantedAssistantActionCategories }
        }

        assertEquals(1, mediaRepo.inspectedLinkIds.size)
        assertEquals(listOf("RETRY_INSPECTION"), chatRepo.getGrantedActionTypes(convA))

        // Repeat Library Management action runs automatically without prompting
        vm.requestMutatingActionConfirmation(ChatAction.RetryDownload("task-1", "Demo Video"))
        awaitCondition(this) { mediaRepo.retriedTaskIds.size == 1 }
        assertNull(vm.uiState.value.pendingMutatingAction)

        // Irreversible destructive action in the same category STILL requires explicit confirmation
        vm.requestMutatingActionConfirmation(ChatAction.DeleteDownload(taskId = "task-1", title = "Demo Video"))
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload }
        assertTrue(vm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload)
        vm.dismissMutatingAction()
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction == null }

        // Different category (Reminder Management) still prompts
        vm.requestMutatingActionConfirmation(
            ChatAction.ScheduleMovieReminder("tvmaze", "42", triggerAtMillis = System.currentTimeMillis() + 600_000L, title = "Andor")
        )
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.ScheduleMovieReminder }
        assertTrue(vm.uiState.value.pendingMutatingAction is ChatAction.ScheduleMovieReminder)
        vm.dismissMutatingAction()
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction == null }

        // Revoking categories restores prompting in Conv B
        kotlinx.coroutines.runBlocking { prefs.revokeAllAssistantCategoryPermissions() }
        vm.selectConversation(convB)
        awaitCondition(this) { vm.uiState.value.activeConversationId == convB }
        vm.requestMutatingActionConfirmation(ChatAction.RetryInspection(linkId, "Demo"))
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.RetryInspection }
        assertTrue(vm.uiState.value.pendingMutatingAction is ChatAction.RetryInspection)

        // Deleting Conv A cascades conversation permission rows
        chatRepo.deleteConversation(convA)
        awaitCondition(this) { db.chatDao().getGrantedActionTypes(convA).isEmpty() }
        assertTrue(db.chatDao().getGrantedActionTypes(convA).isEmpty())
    }

    @Test
    fun invalidTarget_andDuplicateExecution_areSafelyRejected() = runTest(mainDispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val convId = chatRepo.createConversation("Errors")
        vm.selectConversation(convId)
        awaitCondition(this) { vm.uiState.value.activeConversationId == convId }

        // Missing link ID must fail validation without prompting or executing
        vm.requestMutatingActionConfirmation(ChatAction.RetryInspection(9999L, "Ghost"))
        awaitCondition(this) { chatRepo.getMessagesSnapshot(convId).isNotEmpty() }
        assertNull(vm.uiState.value.pendingMutatingAction)
        assertTrue(mediaRepo.inspectedLinkIds.isEmpty())
        assertTrue(chatRepo.getMessagesSnapshot(convId).last().content.contains("no longer exists"))

        // Concurrent duplicate retry download prevention
        val linkId = db.savedLinkDao().insertLink(
            SavedLinkEntity(originalUrl = "https://example.com/v3", normalizedUrl = "https://example.com/v3", title = "V3", domain = "example.com")
        )
        mediaRepo.taskFlow.value = DownloadTask(
            id = "task-dup", savedLinkId = linkId, sourceUrl = "https://example.com/v3",
            sourcePlatform = "Direct", candidateId = "c1", formatSelector = null,
            mediaType = MediaType.VIDEO, qualityLabel = "720p", title = "V3",
            assetId = null, status = DownloadState.FAILED, progress = 0,
            downloadedBytes = 0, totalBytes = null, createdAt = 1L, updatedAt = 1L
        )
        mediaRepo.retryDelayMs = 200L
        vm.requestMutatingActionConfirmation(ChatAction.RetryDownload("task-dup", "V3"))
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.RetryDownload }
        vm.alwaysAllowMutatingActionInConversation()
        // Trigger a second duplicate while first is still suspending
        vm.requestMutatingActionConfirmation(ChatAction.RetryDownload("task-dup", "V3"))
        awaitCondition(this) { mediaRepo.retriedTaskIds.isNotEmpty() }
        assertEquals(1, mediaRepo.retriedTaskIds.size)
    }

    @Test
    fun moviesMutatingActions_validateTargets_requireApproval_andExecute() = runTest(mainDispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val show = MovieTitleSummary(
            id = "66",
            providerId = "tvmaze",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            releaseYear = 2022,
            sourceBadge = DataSourceBadge.LIVE_TVMAZE
        )
        moviesRepo.seedTitle(show)

        val convId = chatRepo.createConversation("Movies Actions")
        vm.selectConversation(convId)
        awaitCondition(this) { vm.uiState.value.activeConversationId == convId }

        // 1. Unknown title is rejected before prompting
        vm.requestMutatingActionConfirmation(
            ChatAction.ToggleMovieWatchlist("tvmaze", "999999", add = true, title = "Unknown")
        )
        awaitCondition(this) { chatRepo.getMessagesSnapshot(convId).isNotEmpty() }
        assertNull(vm.uiState.value.pendingMutatingAction)
        assertTrue(chatRepo.getMessagesSnapshot(convId).last().content.contains("could not be found"))

        // 2. Past reminder timestamp is rejected
        vm.requestMutatingActionConfirmation(
            ChatAction.ScheduleMovieReminder("tvmaze", "66", triggerAtMillis = 1000L, title = "Severance")
        )
        awaitCondition(this) { chatRepo.getMessagesSnapshot(convId).size >= 2 }
        assertNull(vm.uiState.value.pendingMutatingAction)
        assertTrue(chatRepo.getMessagesSnapshot(convId).last().content.contains("already in the past"))

        // 3. ToggleMovieWatchlist requires confirmation then adds to watchlist
        vm.requestMutatingActionConfirmation(
            ChatAction.ToggleMovieWatchlist("tvmaze", "66", add = true, title = "Severance")
        )
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist }
        assertTrue(vm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist)
        vm.confirmMutatingAction()
        awaitCondition(this) { moviesRepo.observeIsInWatchlist("tvmaze:66").first() && chatRepo.getMessagesSnapshot(convId).size >= 3 }
        assertTrue(moviesRepo.observeIsInWatchlist("tvmaze:66").first())
        assertTrue(chatRepo.getMessagesSnapshot(convId).last().content.contains("Added \"Severance\" to your Movies & TV watchlist"))

        // 4. AddMovieToCollection creates named collection and adds media
        vm.requestMutatingActionConfirmation(
            ChatAction.AddMovieToCollection("tvmaze", "66", collectionName = "Weekend Binges", title = "Severance")
        )
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.AddMovieToCollection }
        vm.confirmMutatingAction()
        awaitCondition(this) { moviesRepo.addedToCollection.isNotEmpty() }
        val createdCol = collectionRepo.collectionsWithCounts.first()
            .map { it.collection }
            .firstOrNull { it.name == "Weekend Binges" }
        assertNotNull(createdCol)
        assertTrue(moviesRepo.addedToCollection.any { it.first == "tvmaze:66" && it.second == createdCol?.id })

        // 5. MarkMovieWatched records watched episode
        vm.requestMutatingActionConfirmation(
            ChatAction.MarkMovieWatched("tvmaze", "66", seasonNumber = 1, episodeNumber = 3, watched = true, title = "Severance")
        )
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.MarkMovieWatched }
        vm.confirmMutatingAction()
        awaitCondition(this) { moviesRepo.markedWatched.isNotEmpty() }
        assertEquals(listOf("tvmaze:66:S1E3"), moviesRepo.markedWatched)

        // 6. ScheduleMovieReminder schedules future reminder
        val futureMs = System.currentTimeMillis() + 3_600_000L
        vm.requestMutatingActionConfirmation(
            ChatAction.ScheduleMovieReminder("tvmaze", "66", triggerAtMillis = futureMs, note = "Watch S1E4", title = "Severance")
        )
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.ScheduleMovieReminder }
        vm.confirmMutatingAction()
        awaitCondition(this) { moviesRepo.scheduledReminders.isNotEmpty() }
        assertEquals(1, moviesRepo.scheduledReminders.size)
        assertEquals(futureMs, moviesRepo.scheduledReminders.first().triggerAtMillis)

        // 7. Always allow scoped to Reminder Management executes repeats automatically
        vm.requestMutatingActionConfirmation(
            ChatAction.MuteSeriesReminders("tvmaze", "66", muted = true, title = "Severance")
        )
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.MuteSeriesReminders }
        vm.alwaysAllowMutatingActionInConversation()
        awaitCondition(this) { moviesRepo.mutedSeries["tvmaze:66"] == true }
        assertTrue(moviesRepo.mutedSeries["tvmaze:66"] == true)

        vm.requestMutatingActionConfirmation(
            ChatAction.MuteSeriesReminders("tvmaze", "66", muted = false, title = "Severance")
        )
        awaitCondition(this) { moviesRepo.mutedSeries["tvmaze:66"] == false }
        assertNull(vm.uiState.value.pendingMutatingAction)
        assertFalse(moviesRepo.mutedSeries["tvmaze:66"] ?: true)
    }

    private class RecordingFakeMediaRepository : MediaRepository {
        val inspectedLinkIds = mutableListOf<Long>()
        val retriedTaskIds = mutableListOf<String>()
        val taskFlow = MutableStateFlow<DownloadTask?>(null)
        var retryDelayMs: Long = 0L

        override fun getAssetsForLink(linkId: Long): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAllAssets(): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun filterAssets(mediaType: MediaType?, query: String): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAssetById(id: String): Flow<MediaAsset?> = flowOf(null)
        override fun getTasksForLink(linkId: Long): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getQueueTasks(): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getTaskById(taskId: String): Flow<DownloadTask?> = taskFlow
        override fun getStorageBreakdown(): Flow<Map<MediaType, Long>> = flowOf(emptyMap())
        override fun getAssetCount(): Flow<Int> = flowOf(0)
        override suspend fun enqueueDownload(
            linkId: Long, candidate: MediaCandidate, sourcePlatform: String, title: String?,
            artist: String?, album: String?, artworkUrl: String?, spotifyTrackId: String?, versionTag: String?
        ) = "id"
        override suspend fun overrideCellularRequirement(taskId: String) {}
        override suspend fun pauseDownload(taskId: String) {}
        override suspend fun resumeDownload(taskId: String) {}
        override suspend fun cancelDownload(taskId: String) {}
        override suspend fun dismissTask(taskId: String) {}
        override suspend fun clearTerminalTasks() {}
        override suspend fun retryDownload(taskId: String) {
            if (retryDelayMs > 0) delay(retryDelayMs)
            retriedTaskIds += taskId
            taskFlow.value = taskFlow.value?.copy(status = DownloadState.QUEUED)
        }
        override suspend fun deleteAsset(assetId: String) {}
        override suspend fun exportAsset(assetId: String) = Result.success("")
        override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
        override suspend fun runOrphanSweep() {}
        override suspend fun recoverInterruptedTasks() {}
        override fun getInspectionForLink(linkId: Long): Flow<InspectionResult?> = flowOf(null)
        override fun isInspectingLink(linkId: Long): Flow<Boolean> = flowOf(false)
        override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {
            inspectedLinkIds += linkId
        }
        override suspend fun cancelInspection(linkId: Long) {}
    }

    private class RecordingFakeMoviesRepository : MoviesRepository {
        private val titlesByComposite = mutableMapOf<String, MovieTitleSummary>()
        private val watchlistFlow = MutableStateFlow<List<MovieTitleSummary>>(emptyList())
        val addedToCollection = mutableListOf<Pair<String, Long>>()
        val markedWatched = mutableListOf<String>()
        val scheduledReminders = mutableListOf<ViewingReminderItem>()
        val mutedSeries = mutableMapOf<String, Boolean>()

        fun seedTitle(summary: MovieTitleSummary) {
            titlesByComposite[summary.compositeId] = summary
        }

        override suspend fun getHomeFeed(forceRefresh: Boolean) = Result.success(MoviesHomeFeed())
        override suspend fun getCatalogue(
            kind: MediaKind?, page: Int, pageSize: Int, genre: String?, sort: CatalogueSort, country: String?, year: String?
        ) = Result.success(CataloguePage())
        override suspend fun getGenres() = Result.success(emptyList<String>())
        override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int) =
            Result.success(CataloguePage())
        override suspend fun getSuggestions(query: String, limit: Int) = Result.success(emptyList<SearchSuggestion>())
        override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> {
            val found = titlesByComposite["$providerId:$titleId"]
            return if (found != null) Result.success(MediaDetails(summary = found))
            else Result.failure(NoSuchElementException("Not found"))
        }
        override suspend fun getSeasons(providerId: String, seriesId: String) = Result.success(emptyList<SeasonSummary>())
        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int) =
            Result.success(emptyList<EpisodeItem>())
        override suspend fun resolvePlayback(
            providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?
        ): Result<PlaybackResolution> = Result.failure(UnsupportedOperationException())
        override suspend fun getSubtitles(
            providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?
        ) = Result.success(emptyList<SubtitleTrack>())

        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = watchlistFlow
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> =
            watchlistFlow.map { list -> list.any { it.compositeId == compositeId } }
        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean {
            val current = watchlistFlow.value
            return if (current.any { it.compositeId == item.compositeId }) {
                watchlistFlow.value = current.filterNot { it.compositeId == item.compositeId }
                false
            } else {
                watchlistFlow.value = current + item
                true
            }
        }

        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override suspend fun saveWatchProgress(
            item: MovieTitleSummary, positionMs: Long, durationMs: Long,
            seasonNumber: Int?, episodeNumber: Int?, episodeLabel: String?
        ) = Unit
        override suspend fun getSavedPositionMs(
            providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?
        ): Long = 0L

        override suspend fun markWatched(
            item: MovieTitleSummary, durationMs: Long, seasonNumber: Int?, episodeNumber: Int?,
            episodeLabel: String?, isRewatch: Boolean
        ) {
            val ep = if (seasonNumber != null && episodeNumber != null) ":S${seasonNumber}E$episodeNumber" else ""
            markedWatched += "${item.compositeId}$ep"
        }

        override suspend fun addMediaToCollection(item: MovieTitleSummary, collectionId: Long) {
            addedToCollection += item.compositeId to collectionId
        }

        override suspend fun setSeriesRemindersMuted(providerId: String, titleId: String, muted: Boolean) {
            mutedSeries["$providerId:$titleId"] = muted
        }

        override suspend fun scheduleViewingReminder(
            item: MovieTitleSummary, triggerAtMillis: Long, note: String?, seasonNumber: Int?, episodeNumber: Int?
        ) {
            scheduledReminders += ViewingReminderItem(
                mediaKey = item.stableMediaKey,
                compositeId = item.compositeId,
                providerId = item.providerId,
                titleId = item.id,
                kind = item.kind,
                title = item.title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                note = note,
                triggerAtMillis = triggerAtMillis
            )
        }

        override fun observeRecentSearches(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit
    }
}
