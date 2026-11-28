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
                chatRepo.getGrantedActionTypes(convA).isNotEmpty()
        }

        assertEquals(1, mediaRepo.inspectedLinkIds.size)
        val convAGrants = chatRepo.getGrantedActionTypes(convA)
        assertTrue("LIBRARY_MANAGEMENT" in convAGrants)
        assertTrue("RETRY_INSPECTION" in convAGrants)
        assertTrue(kotlinx.coroutines.runBlocking { prefs.userPreferencesFlow.first().grantedAssistantActionCategories.isEmpty() })

        // Repeat Library Management action runs automatically without prompting in Conv A
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

        // Switching to Conv B prompts again automatically since Always Allow is conversation-scoped
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

    @Test
    fun backendStream_autoExecutesRiskBasedOnceAndTracksOutOfSyncManifest() = runTest(mainDispatcher) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val sseBody = """
                event: response_start
                data: {"manifestRevision":"rev-new","manifestSynced":false}

                event: text_delta
                data: {"chunk":"Opening Severance now."}

                event: action_proposed
                data: {"action":{"type":"OPEN_MOVIE_DETAIL","providerId":"tvmaze","titleId":"66","title":"Severance"},"category":"NAVIGATION_DISCOVERY","requiresApproval":false}

                event: action_proposed
                data: {"action":{"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"tvmaze","titleId":"66","add":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT","requiresApproval":false}

                event: action_proposed
                data: {"action":{"type":"DELETE_DOWNLOAD","taskId":"task-9","title":"Old Clip"},"category":"LIBRARY_MANAGEMENT","requiresApproval":true}

                event: response_complete
                data: {"fullText":"Opening Severance now.","displayText":"Opening Severance now.","actions":[{"type":"OPEN_MOVIE_DETAIL","providerId":"tvmaze","titleId":"66","title":"Severance"},{"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"tvmaze","titleId":"66","add":true,"title":"Severance"},{"type":"DELETE_DOWNLOAD","taskId":"task-9","title":"Old Clip"}],"webGrounded":false,"groundingSources":[]}

            """.trimIndent()
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody)
            )
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"manifestVersion":2,"manifestRevision":"rev-new","manifestHash":"mismatch","clientInSync":false,"items":[]}""")
            )
            // action-result acknowledgements
            repeat(3) {
                server.enqueue(
                    okhttp3.mockwebserver.MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"accepted":true,"status":"executed","followUpMessage":"Done"}""")
                )
            }

            moviesRepo.seedTitle(
                MovieTitleSummary(
                    id = "66",
                    providerId = "tvmaze",
                    kind = MediaKind.TV_SERIES,
                    title = "Severance",
                    releaseYear = 2022,
                    sourceBadge = DataSourceBadge.LIVE_TVMAZE
                )
            )
            prefs.setAssistantAiDisclosureAccepted(true)
            // Global category permissions are ignored; all actions start awaiting inline approval.
            prefs.setAssistantCategoryPermission("LIBRARY_MANAGEMENT", true)

            val activityRepo = ActivityEventRepository(db.activityEventDao())
            val backendClient = com.laterbox.app.ai.LaterboxBackendAiClient(
                baseUrlProvider = { base },
                appCheckTokenProvider = { "app-check" }
            )
            val streamVm = ChatViewModel(
                chatRepository = chatRepo,
                userPreferencesRepository = prefs,
                laterboxContextEngine = LaterboxContextEngine(db, activityRepo, prefs),
                linkRepository = linkRepo,
                mediaRepository = mediaRepo,
                moviesRepository = moviesRepo,
                collectionRepository = collectionRepo,
                reminderScheduler = ReminderScheduler(ctx),
                backendAiClient = backendClient
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { streamVm.uiState.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { streamVm.pendingUiActionDispatch.collect {} }

            streamVm.sendMessage("Open Severance and add it to my watchlist")
            awaitCondition(this, timeoutMs = 5_000L) {
                streamVm.uiState.value.pendingMutatingAction is ChatAction.OpenMovieDetail
            }
            assertEquals(null, streamVm.pendingUiActionDispatch.value)
            assertTrue(moviesRepo.observeWatchlist().first().isEmpty())
            assertTrue(streamVm.uiState.value.manifestOutOfSync)

            // 1. User approves navigation action inline -> dispatches ONCE -> UI reports outcome
            streamVm.confirmMutatingAction()
            awaitCondition(this, timeoutMs = 5_000L) { streamVm.pendingUiActionDispatch.value != null }
            val dispatch = streamVm.pendingUiActionDispatch.value!!
            assertTrue(dispatch.action is ChatAction.OpenMovieDetail)
            streamVm.reportUiActionOutcome(
                requestId = dispatch.requestId,
                messageId = dispatch.messageId,
                actionId = dispatch.actionId,
                action = dispatch.action,
                executed = true,
                detail = "Opened details for Severance"
            )
            awaitCondition(this) { streamVm.pendingUiActionDispatch.value == null }

            // 2. Watchlist action waits inline even when legacy global grant was set -> approve once
            awaitCondition(this, timeoutMs = 5_000L) {
                streamVm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist
            }
            streamVm.confirmMutatingAction()
            awaitCondition(this, timeoutMs = 5_000L) {
                moviesRepo.observeWatchlist().first().any { it.id == "66" } &&
                    streamVm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload
            }
            assertEquals(1, moviesRepo.observeWatchlist().first().count { it.id == "66" })
            assertTrue(streamVm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload)

            val convId = streamVm.uiState.value.activeConversationId!!
            val assistantMsg = chatRepo.getMessagesSnapshot(convId)
                .first { it.role == com.laterbox.app.data.repository.ChatRole.ASSISTANT.name && it.actionsJson != null }
            val tracked = com.laterbox.app.ai.AssistantActionHandler.fromTrackedJsonList(assistantMsg.actionsJson)
                .associateBy { it.action.actionName }
            assertEquals(com.laterbox.app.ai.ChatActionStatus.EXECUTED, tracked.values.first { it.action is ChatAction.OpenMovieDetail }.status)
            assertEquals(com.laterbox.app.ai.ChatActionStatus.EXECUTED, tracked.values.first { it.action is ChatAction.ToggleMovieWatchlist }.status)
            assertEquals(com.laterbox.app.ai.ChatActionStatus.AWAITING_APPROVAL, tracked.values.first { it.action is ChatAction.DeleteDownload }.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun multipleReadOnlyUiActions_executeSequentiallyInFifoOrderAndClearOnConversationSwitch() = runTest(mainDispatcher) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val server = okhttp3.mockwebserver.MockWebServer()
        val sseBody = """
            event: response_complete
            data: {"fullText":"Sure","displayText":"Sure","actions":[{"type":"OPEN_MOVIES_HOME"},{"type":"OPEN_MOVIE_DETAIL","providerId":"moviebox","titleId":"m100","title":"Inception"}]}

        """.trimIndent()
        server.enqueue(
            okhttp3.mockwebserver.MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"accepted":true,"status":"executed"}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"accepted":true,"status":"executed"}"""))
        server.start()
        try {
            prefs.setAssistantAiDisclosureAccepted(true)
            val baseUrl = server.url("/").toString().trimEnd('/')
            val activityRepo = ActivityEventRepository(db.activityEventDao())
            val backendClient = com.laterbox.app.ai.LaterboxBackendAiClient(
                baseUrlProvider = { baseUrl },
                appCheckTokenProvider = { "tok" }
            )
            val streamVm = ChatViewModel(
                chatRepository = chatRepo,
                userPreferencesRepository = prefs,
                laterboxContextEngine = LaterboxContextEngine(db, activityRepo, prefs),
                linkRepository = linkRepo,
                mediaRepository = mediaRepo,
                moviesRepository = RecordingFakeMoviesRepository(),
                collectionRepository = collectionRepo,
                reminderScheduler = ReminderScheduler(ctx),
                backendAiClient = backendClient
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { streamVm.uiState.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { streamVm.pendingUiActionDispatch.collect {} }

            streamVm.sendMessage("Open movies home then open Inception")
            awaitCondition(this, timeoutMs = 5_000L) {
                streamVm.uiState.value.pendingMutatingAction is ChatAction.OpenMoviesHome
            }
            streamVm.confirmMutatingAction()
            awaitCondition(this, timeoutMs = 5_000L) {
                streamVm.pendingUiActionDispatch.value?.action is ChatAction.OpenMoviesHome
            }

            val first = streamVm.pendingUiActionDispatch.value!!
            streamVm.reportUiActionOutcome(
                requestId = first.requestId,
                messageId = first.messageId,
                actionId = first.actionId,
                action = first.action,
                executed = true,
                detail = "Opened Movies & TV"
            )

            // Second READ_ONLY action prompts inline sequentially in FIFO order instead of being dropped!
            awaitCondition(this, timeoutMs = 5_000L) {
                streamVm.uiState.value.pendingMutatingAction is ChatAction.OpenMovieDetail
            }
            streamVm.confirmMutatingAction()
            awaitCondition(this, timeoutMs = 5_000L) {
                streamVm.pendingUiActionDispatch.value?.action is ChatAction.OpenMovieDetail
            }
            val second = streamVm.pendingUiActionDispatch.value!!
            streamVm.reportUiActionOutcome(
                requestId = second.requestId,
                messageId = second.messageId,
                actionId = second.actionId,
                action = second.action,
                executed = true,
                detail = "Opened details for Inception"
            )
            awaitCondition(this) { streamVm.pendingUiActionDispatch.value == null }

            val convId = streamVm.uiState.value.activeConversationId!!
            val assistantMsg = chatRepo.getMessagesSnapshot(convId)
                .first { it.role == com.laterbox.app.data.repository.ChatRole.ASSISTANT.name && it.actionsJson != null }
            val tracked = com.laterbox.app.ai.AssistantActionHandler.fromTrackedJsonList(assistantMsg.actionsJson)
            assertEquals(2, tracked.size)
            assertTrue(tracked.all { it.status == com.laterbox.app.ai.ChatActionStatus.EXECUTED })

            // Verify switching conversations clears pending UI dispatches cleanly
            streamVm.startNewChat()
            assertEquals(null, streamVm.pendingUiActionDispatch.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun multipleMutatingActions_queueInFifoOrder_reEvaluateCategoryGrants_andEmitDeleteDownloadReceiptKey() = runTest(mainDispatcher) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val server = okhttp3.mockwebserver.MockWebServer()
        val sseReply1 = """
            event: response_start
            data: {"manifestRevision":"2026.11.27-v3","manifestSynced":true}

            event: approval_required
            data: {"action":{"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"tvmaze","titleId":"66","add":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT"}

            event: approval_required
            data: {"action":{"type":"MARK_MOVIE_WATCHED","providerId":"tvmaze","titleId":"66","seasonNumber":1,"episodeNumber":1,"watched":true,"title":"Severance"},"category":"LIBRARY_MANAGEMENT"}

            event: approval_required
            data: {"action":{"type":"DELETE_DOWNLOAD","taskId":"task-del","title":"Old Clip"},"category":"LIBRARY_MANAGEMENT"}

            event: response_complete
            data: {"fullText":"I can update Severance and remove Old Clip.","displayText":"I can update Severance and remove Old Clip.","actions":[]}

        """.trimIndent()
        val sseReply2 = """
            event: response_complete
            data: {"fullText":"Got it.","displayText":"Got it.","actions":[]}

        """.trimIndent()

        // Path-routing dispatcher isolates /ai/v1/titles/generate from /ai/v1/chat/stream & /ai/v1/chat/action-result
        val streamRequestBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val actionResultBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        var streamCounter = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                return when {
                    path.contains("ai/v1/chat/stream") -> {
                        streamRequestBodies.add(body)
                        val reply = if (streamCounter++ == 0) sseReply1 else sseReply2
                        okhttp3.mockwebserver.MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(reply)
                    }
                    path.contains("ai/v1/chat/action-result") -> {
                        actionResultBodies.add(body)
                        okhttp3.mockwebserver.MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"accepted":true,"status":"ok"}""")
                    }
                    path.contains("ai/v1/titles/generate") -> {
                        okhttp3.mockwebserver.MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"title":"Severance Updates","source":"gemini"}""")
                    }
                    else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            moviesRepo.seedTitle(
                MovieTitleSummary(
                    id = "66",
                    providerId = "tvmaze",
                    kind = MediaKind.TV_SERIES,
                    title = "Severance",
                    releaseYear = 2022,
                    sourceBadge = DataSourceBadge.LIVE_TVMAZE
                )
            )
            prefs.setAssistantAiDisclosureAccepted(true)

            val baseUrl = server.url("/").toString().trimEnd('/')
            val activityRepo = ActivityEventRepository(db.activityEventDao())
            val backendClient = com.laterbox.app.ai.LaterboxBackendAiClient(
                baseUrlProvider = { baseUrl },
                appCheckTokenProvider = { "tok" }
            )
            val streamVm = ChatViewModel(
                chatRepository = chatRepo,
                userPreferencesRepository = prefs,
                laterboxContextEngine = LaterboxContextEngine(db, activityRepo, prefs),
                linkRepository = linkRepo,
                mediaRepository = mediaRepo,
                moviesRepository = moviesRepo,
                collectionRepository = collectionRepo,
                reminderScheduler = ReminderScheduler(ctx),
                backendAiClient = backendClient
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { streamVm.uiState.collect {} }

            streamVm.sendMessage("Add Severance to watchlist, mark S1E1 watched, and delete Old Clip")
            awaitCondition(this, timeoutMs = 5_000L) {
                !streamVm.uiState.value.isStreaming &&
                    streamVm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist
            }

            // All 3 mutating actions must be AWAITING_APPROVAL (none dropped)
            val convId = streamVm.uiState.value.activeConversationId!!
            val msg = chatRepo.getMessagesSnapshot(convId)
                .first { it.role == com.laterbox.app.data.repository.ChatRole.ASSISTANT.name && it.actionsJson != null }
            val initialTracked = com.laterbox.app.ai.AssistantActionHandler.fromTrackedJsonList(msg.actionsJson)
            assertEquals(3, initialTracked.size)
            assertTrue(initialTracked.all { it.status == com.laterbox.app.ai.ChatActionStatus.AWAITING_APPROVAL })

            // "Always allow" on #1 -> executes #1, auto-executes #2 (MarkMovieWatched), and surfaces #3 (DeleteDownload)!
            streamVm.alwaysAllowMutatingActionInConversation()
            awaitCondition(this, timeoutMs = 5_000L) {
                moviesRepo.observeIsInWatchlist("tvmaze:66").first() &&
                    moviesRepo.markedWatched.contains("tvmaze:66:S1E1") &&
                    streamVm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload
            }
            assertTrue(streamVm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload)

            // Confirm #3 (DeleteDownload) -> reports "DELETE_DOWNLOAD" (NEVER "DELETE_DOWNLOADED_FILE")
            streamVm.confirmMutatingAction()
            awaitCondition(this, timeoutMs = 5_000L) {
                streamVm.uiState.value.pendingMutatingAction == null &&
                    actionResultBodies.size >= 3
            }

            // Send next message so extractRecentActionReceipts serializes into /ai/v1/chat/stream
            streamVm.sendMessage("Thanks!")
            awaitCondition(this, timeoutMs = 5_000L) {
                !streamVm.uiState.value.isStreaming && streamRequestBodies.size >= 2
            }

            assertTrue(actionResultBodies.any { it.contains("\"actionType\":\"DELETE_DOWNLOAD\"") })
            assertFalse(actionResultBodies.any { it.contains("DELETE_DOWNLOADED_FILE") })

            val reqStream2Body = streamRequestBodies[1]
            assertTrue(reqStream2Body.contains("\"actionType\":\"DELETE_DOWNLOAD\""))
            assertFalse(reqStream2Body.contains("DELETE_DOWNLOADED_FILE"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun multipleMutatingActions_sequentialAllowOnceAndDismiss_neverDropsQueuedDialogs() = runTest(mainDispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        moviesRepo.seedTitle(
            MovieTitleSummary(
                id = "66",
                providerId = "tvmaze",
                kind = MediaKind.TV_SERIES,
                title = "Severance",
                releaseYear = 2022,
                sourceBadge = DataSourceBadge.LIVE_TVMAZE
            )
        )
        val convId = chatRepo.createConversation("Sequential Queue")
        vm.selectConversation(convId)
        awaitCondition(this) { vm.uiState.value.activeConversationId == convId }

        val futureMs = System.currentTimeMillis() + 600_000L
        vm.requestMutatingActionConfirmation(ChatAction.ToggleMovieWatchlist("tvmaze", "66", true, "Severance"))
        vm.requestMutatingActionConfirmation(ChatAction.ScheduleMovieReminder("tvmaze", "66", futureMs, title = "Severance"))
        vm.requestMutatingActionConfirmation(ChatAction.DeleteDownload(taskId = "t-del", title = "Old.mp4"))

        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist }
        assertTrue(vm.uiState.value.pendingMutatingAction is ChatAction.ToggleMovieWatchlist)

        // Allow once #1 -> advances to #2
        vm.confirmMutatingAction()
        awaitCondition(this) {
            moviesRepo.observeIsInWatchlist("tvmaze:66").first() &&
                vm.uiState.value.pendingMutatingAction is ChatAction.ScheduleMovieReminder
        }
        assertTrue(vm.uiState.value.pendingMutatingAction is ChatAction.ScheduleMovieReminder)

        // Dismiss #2 -> advances to #3
        vm.dismissMutatingAction()
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload }
        assertTrue(moviesRepo.scheduledReminders.isEmpty())
        assertTrue(vm.uiState.value.pendingMutatingAction is ChatAction.DeleteDownload)

        // Dismiss #3 -> queue empty
        vm.dismissMutatingAction()
        awaitCondition(this) { vm.uiState.value.pendingMutatingAction == null }
        assertNull(vm.uiState.value.pendingMutatingAction)
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
