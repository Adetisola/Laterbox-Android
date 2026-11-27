package com.laterbox.app.ui.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.ai.AssistantActionHandler
import com.laterbox.app.ai.ChatAction
import com.laterbox.app.ai.ChatActionStatus
import com.laterbox.app.ai.LaterboxBackendAiClient
import com.laterbox.app.ai.LaterboxContextEngine
import com.laterbox.app.auth.LaterboxBackendAuthProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ChatRepository
import com.laterbox.app.data.repository.ChatRole
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.DownloadTask
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.movies.domain.model.BrowseSection
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ChatViewModelActionQueueTest {

    private lateinit var context: Context
    private lateinit var db: LaterboxDatabase
    private lateinit var chatRepo: ChatRepository
    private lateinit var prefs: UserPreferencesRepository
    private lateinit var linkRepo: LinkRepository
    private lateinit var mediaRepo: NoopMediaRepository
    private lateinit var moviesRepo: FakeMoviesRepository
    private lateinit var collectionRepo: CollectionRepository
    private val mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        chatRepo = ChatRepository(db.chatDao())
        prefs = UserPreferencesRepository(context)
        kotlinx.coroutines.runBlocking {
            prefs.revokeAllAssistantCategoryPermissions()
            prefs.userPreferencesFlow.first()
        }
        linkRepo = LinkRepository(db.savedLinkDao(), context)
        mediaRepo = NoopMediaRepository()
        moviesRepo = FakeMoviesRepository().apply {
            seedTitle(
                MovieTitleSummary(
                    id = "66",
                    providerId = "tvmaze",
                    kind = MediaKind.TV_SERIES,
                    title = "Severance",
                    releaseYear = 2022,
                    sourceBadge = DataSourceBadge.LIVE_TVMAZE
                )
            )
        }
        collectionRepo = CollectionRepository(db.collectionDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun awaitCondition(scope: TestScope, timeoutMs: Long = 5_000L, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            scope.testScheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(10)
        }
        scope.testScheduler.advanceUntilIdle()
        assertTrue("Condition timed out", predicate())
    }

    private fun buildViewModel(backendClient: LaterboxBackendAiClient): ChatViewModel {
        val activityRepo = ActivityEventRepository(db.activityEventDao())
        return ChatViewModel(
            chatRepository = chatRepo,
            userPreferencesRepository = prefs,
            laterboxContextEngine = LaterboxContextEngine(db, activityRepo, prefs),
            linkRepository = linkRepo,
            mediaRepository = mediaRepo,
            moviesRepository = moviesRepo,
            collectionRepository = collectionRepo,
            reminderScheduler = ReminderScheduler(context),
            backendAiClient = backendClient
        )
    }

    @Test
    fun multiMutatingBatch_denyingFirstActionAbortsSecondActionInRoom() = runTest(mainDispatcher) {
        prefs.setAssistantAiDisclosureAccepted(true)
        val sseReply = """
            event: response_start
            data: {"manifestRevision":"2026.12.01-v3","manifestSynced":true}

            event: approval_required
            data: {"action":{"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"tvmaze","titleId":"66","add":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT"}

            event: approval_required
            data: {"action":{"type":"MARK_MOVIE_WATCHED","providerId":"tvmaze","titleId":"66","seasonNumber":1,"episodeNumber":1,"watched":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT"}

            event: response_complete
            data: {"fullText":"I proposed watchlist and watched updates.","displayText":"I proposed watchlist and watched updates.","actions":[]}

        """.trimIndent()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("ai/v1/chat/stream") == true -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(sseReply)
                    request.path?.contains("ai/v1/titles/generate") == true -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"title":"Severance Updates","source":"gemini"}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"accepted":true,"status":"ok"}""")
                }
            }
        }
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val vm = buildViewModel(
                LaterboxBackendAiClient(baseUrlProvider = { baseUrl }, appCheckTokenProvider = { "tok" })
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.sendMessage("Update Severance watchlist and mark S1E1 watched")
            awaitCondition(this) {
                !vm.uiState.value.isStreaming &&
                    vm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist
            }

            // Deny Action #1 -> must abort Action #2 cleanly with zero dangling AWAITING_APPROVAL rows
            vm.dismissMutatingAction()
            val convId = vm.uiState.value.activeConversationId!!
            awaitCondition(this) {
                val msg = chatRepo.getMessagesSnapshot(convId)
                    .firstOrNull { it.role == ChatRole.ASSISTANT.name && it.actionsJson != null }
                val tracked = AssistantActionHandler.fromTrackedJsonList(msg?.actionsJson)
                tracked.size == 2 && tracked.all { it.status == ChatActionStatus.DENIED }
            }

            assertNull(vm.uiState.value.pendingMutatingAction)
            val msg = chatRepo.getMessagesSnapshot(convId)
                .first { it.role == ChatRole.ASSISTANT.name && it.actionsJson != null }
            val tracked = AssistantActionHandler.fromTrackedJsonList(msg.actionsJson)
            assertFalse(tracked.any { it.status == ChatActionStatus.AWAITING_APPROVAL })
            assertTrue(tracked[1].statusDetail?.contains("Skipped because previous action") == true)
            assertFalse(moviesRepo.observeIsInWatchlist("tvmaze:66").first())
            assertTrue(moviesRepo.markedWatched.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun switchingConversationsMidApproval_clearsPendingDialogAndTransitionsAwaitingRowsToDenied() = runTest(mainDispatcher) {
        prefs.setAssistantAiDisclosureAccepted(true)
        val sseReply = """
            event: response_start
            data: {"manifestRevision":"2026.12.01-v3","manifestSynced":true}

            event: approval_required
            data: {"action":{"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"tvmaze","titleId":"66","add":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT"}

            event: approval_required
            data: {"action":{"type":"DELETE_DOWNLOAD","taskId":"t-1","title":"Old Clip"},"category":"LIBRARY_MANAGEMENT"}

            event: response_complete
            data: {"fullText":"Ready when you confirm.","displayText":"Ready when you confirm.","actions":[]}

        """.trimIndent()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("ai/v1/chat/stream") == true -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(sseReply)
                    else -> MockResponse().setResponseCode(200).setBody("""{"title":"Severance Actions","source":"gemini"}""")
                }
            }
        }
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val vm = buildViewModel(
                LaterboxBackendAiClient(baseUrlProvider = { baseUrl }, appCheckTokenProvider = { "tok" })
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.sendMessage("Add Severance and delete Old Clip")
            awaitCondition(this) {
                !vm.uiState.value.isStreaming &&
                    vm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist
            }

            val firstConvId = vm.uiState.value.activeConversationId!!
            val otherConvId = chatRepo.createConversation("Other chat")
            vm.selectConversation(otherConvId)
            testScheduler.advanceUntilIdle()

            assertNull(vm.uiState.value.pendingMutatingAction)
            awaitCondition(this) {
                val msg = chatRepo.getMessagesSnapshot(firstConvId)
                    .firstOrNull { it.role == ChatRole.ASSISTANT.name && it.actionsJson != null }
                val tracked = AssistantActionHandler.fromTrackedJsonList(msg?.actionsJson)
                tracked.size == 2 && tracked.all { it.status == ChatActionStatus.DENIED }
            }

            // Switching back to firstConvId must never resurface stale approval dialogs
            vm.selectConversation(firstConvId)
            testScheduler.advanceUntilIdle()
            assertNull(vm.uiState.value.pendingMutatingAction)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun guestAuth_skipsTitlesGenerateEndpoint_andAppliesLocalExtractiveTitleFallback() = runTest(mainDispatcher) {
        prefs.setAssistantAiDisclosureAccepted(true)
        val sseReply = """
            event: response_start
            data: {"manifestRevision":"2026.12.01-v3","manifestSynced":true}

            event: response_complete
            data: {"fullText":"Here are your saved Kotlin links.","displayText":"Here are your saved Kotlin links.","actions":[]}

        """.trimIndent()

        val recordedPaths = mutableListOf<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedPaths.add(request.path.orEmpty())
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseReply)
            }
        }
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val vm = buildViewModel(
                LaterboxBackendAiClient(
                    baseUrlProvider = { baseUrl },
                    appCheckTokenProvider = { null },
                    authHeaderProvider = { _ -> LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to "guest-jwt" }
                )
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.sendMessage("Hey laterbox, can you please summarize my Kotlin coroutine notes?")
            awaitCondition(this) {
                !vm.uiState.value.isStreaming &&
                    vm.uiState.value.activeConversationTitle == "Summarize my Kotlin coroutine notes"
            }

            assertFalse(recordedPaths.any { it.contains("titles/generate") })
            assertEquals("Summarize my Kotlin coroutine notes", vm.uiState.value.activeConversationTitle)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun conversationSwitchDuringSuspendingValidation_doesNotExecuteOrLeakStatusNotice() = runTest(mainDispatcher) {
        prefs.setAssistantAiDisclosureAccepted(true)
        prefs.setAssistantCategoryPermission("LIBRARY_MANAGEMENT", true)

        val sseReply = """
            event: response_start
            data: {"manifestRevision":"2026.12.01-v3","manifestSynced":true}

            event: approval_required
            data: {"action":{"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"tvmaze","titleId":"66","add":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT"}

            event: response_complete
            data: {"fullText":"Adding Severance.","displayText":"Adding Severance.","actions":[]}

        """.trimIndent()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("ai/v1/chat/stream") == true -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(sseReply)
                    else -> MockResponse().setResponseCode(200).setBody("""{"title":"Severance","source":"gemini"}""")
                }
            }
        }
        server.start()
        try {
            val validationEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val validationGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            moviesRepo.onGetDetails = { _, _ ->
                validationEntered.complete(Unit)
                validationGate.await()
            }

            val baseUrl = server.url("/").toString().trimEnd('/')
            val vm = buildViewModel(
                LaterboxBackendAiClient(baseUrlProvider = { baseUrl }, appCheckTokenProvider = { "tok" })
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.sendMessage("Add Severance to watchlist")
            awaitCondition(this) { validationEntered.isCompleted }

            val firstConvId = vm.uiState.value.activeConversationId!!
            val secondConvId = chatRepo.createConversation("Second chat")
            vm.selectConversation(secondConvId)
            testScheduler.advanceUntilIdle()

            validationGate.complete(Unit)
            awaitCondition(this) {
                val msg = chatRepo.getMessagesSnapshot(firstConvId)
                    .firstOrNull { it.role == ChatRole.ASSISTANT.name && it.actionsJson != null }
                val tracked = AssistantActionHandler.fromTrackedJsonList(msg?.actionsJson)
                tracked.size == 1 && tracked[0].status == ChatActionStatus.DENIED
            }

            assertFalse(moviesRepo.observeIsInWatchlist("tvmaze:66").first())
            assertNull(vm.uiState.value.pendingMutatingAction)
            assertNull(vm.uiState.value.statusNotice)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun delayedReceiptFromAbandonedConversation_doesNotOverwriteActiveStatusNotice() = runTest(mainDispatcher) {
        prefs.setAssistantAiDisclosureAccepted(true)

        val sseReply = """
            event: response_start
            data: {"manifestRevision":"2026.12.01-v3","manifestSynced":true}

            event: approval_required
            data: {"action":{"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"tvmaze","titleId":"66","add":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT"}

            event: response_complete
            data: {"fullText":"Confirm to add Severance.","displayText":"Confirm to add Severance.","actions":[]}

        """.trimIndent()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("ai/v1/chat/stream") == true -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(sseReply)
                    else -> MockResponse().setResponseCode(200).setBody("""{"accepted":true,"status":"ok"}""")
                }
            }
        }
        server.start()
        try {
            val execEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val execGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            moviesRepo.onToggleWatchlist = {
                execEntered.complete(Unit)
                execGate.await()
            }

            val baseUrl = server.url("/").toString().trimEnd('/')
            val vm = buildViewModel(
                LaterboxBackendAiClient(baseUrlProvider = { baseUrl }, appCheckTokenProvider = { "tok" })
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.sendMessage("Add Severance to watchlist")
            awaitCondition(this) {
                !vm.uiState.value.isStreaming &&
                    vm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist
            }

            val firstConvId = vm.uiState.value.activeConversationId!!
            vm.confirmMutatingAction()
            awaitCondition(this) { execEntered.isCompleted }

            val secondConvId = chatRepo.createConversation("Second chat")
            vm.selectConversation(secondConvId)
            testScheduler.advanceUntilIdle()
            assertNull(vm.uiState.value.statusNotice)

            execGate.complete(Unit)
            awaitCondition(this) {
                val msg = chatRepo.getMessagesSnapshot(firstConvId)
                    .firstOrNull { it.role == ChatRole.ASSISTANT.name && it.actionsJson != null }
                val tracked = AssistantActionHandler.fromTrackedJsonList(msg?.actionsJson)
                tracked.size == 1 && tracked[0].status == ChatActionStatus.EXECUTED
            }

            // Receipt must NOT overwrite _statusNotice on secondConvId
            assertNull(vm.uiState.value.statusNotice)
            assertEquals(secondConvId, vm.uiState.value.activeConversationId)
        } finally {
            server.shutdown()
        }
    }

    private class NoopMediaRepository : MediaRepository {
        override fun getAssetsForLink(linkId: Long): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAllAssets(): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun filterAssets(mediaType: MediaType?, query: String): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAssetById(id: String): Flow<MediaAsset?> = flowOf(null)
        override fun getTasksForLink(linkId: Long): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getQueueTasks(): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getTaskById(taskId: String): Flow<DownloadTask?> = flowOf(null)
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
        override suspend fun retryDownload(taskId: String) {}
        override suspend fun deleteAsset(assetId: String) {}
        override suspend fun exportAsset(assetId: String) = Result.success("")
        override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
        override suspend fun runOrphanSweep() {}
        override suspend fun recoverInterruptedTasks() {}
        override fun getInspectionForLink(linkId: Long): Flow<InspectionResult?> = flowOf(null)
        override fun isInspectingLink(linkId: Long): Flow<Boolean> = flowOf(false)
        override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
        override suspend fun cancelInspection(linkId: Long) {}
    }

    private class FakeMoviesRepository : MoviesRepository {
        private val seeded = linkedMapOf<String, MovieTitleSummary>()
        private val watchlistState = MutableStateFlow<List<MovieTitleSummary>>(emptyList())
        val markedWatched = mutableListOf<String>()
        var onGetDetails: (suspend (String, String) -> Unit)? = null
        var onToggleWatchlist: (suspend (MovieTitleSummary) -> Unit)? = null

        fun seedTitle(summary: MovieTitleSummary) {
            seeded[summary.compositeId] = summary
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
            onGetDetails?.invoke(providerId, titleId)
            val summary = seeded["$providerId:$titleId"]
                ?: return Result.failure(NoSuchElementException("Not found"))
            return Result.success(MediaDetails(summary = summary))
        }
        override suspend fun getSeasons(providerId: String, seriesId: String) = Result.success(emptyList<SeasonSummary>())
        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int) = Result.success(emptyList<EpisodeItem>())
        override suspend fun resolvePlayback(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) =
            Result.failure<PlaybackResolution>(UnsupportedOperationException())
        override suspend fun getSubtitles(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) =
            Result.success(emptyList<SubtitleTrack>())
        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = watchlistState
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> =
            MutableStateFlow(watchlistState.value.any { it.compositeId == compositeId })
        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean {
            onToggleWatchlist?.invoke(item)
            val current = watchlistState.value.toMutableList()
            val existing = current.indexOfFirst { it.compositeId == item.compositeId }
            return if (existing >= 0) {
                current.removeAt(existing)
                watchlistState.value = current
                false
            } else {
                current.add(item)
                watchlistState.value = current
                true
            }
        }
        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override suspend fun saveWatchProgress(
            item: MovieTitleSummary, positionMs: Long, durationMs: Long,
            seasonNumber: Int?, episodeNumber: Int?, episodeLabel: String?
        ) {}
        override suspend fun getSavedPositionMs(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) = 0L
        override fun observeRecentSearches(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordSearchQuery(query: String) {}
        override suspend fun clearSearchHistory() {}
        override suspend fun markWatched(
            item: MovieTitleSummary, durationMs: Long, seasonNumber: Int?, episodeNumber: Int?,
            episodeLabel: String?, isRewatch: Boolean
        ) {
            markedWatched.add("${item.compositeId}:${episodeLabel ?: "movie"}")
        }
    }
}
