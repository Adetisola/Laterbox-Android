package com.laterbox.app.ui.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.ai.AssistantActionHandler
import com.laterbox.app.ai.ChatAction
import com.laterbox.app.ai.ChatActionStatus
import com.laterbox.app.ai.LaterboxBackendAiClient
import com.laterbox.app.ai.LaterboxContextEngine
import com.laterbox.app.ai.TrackedChatAction
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ChatMessageStatus
import com.laterbox.app.data.repository.ChatRepository
import com.laterbox.app.data.repository.ChatRole
import com.laterbox.app.data.repository.LinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRetryAndActionsTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var db: LaterboxDatabase
    private lateinit var chatRepo: ChatRepository
    private lateinit var prefs: UserPreferencesRepository
    private lateinit var vm: ChatViewModel
    private lateinit var server: MockWebServer

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
        prefs = UserPreferencesRepository(ctx)
        kotlinx.coroutines.runBlocking {
            prefs.setAssistantEnabled(true)
            prefs.setAssistantAiDisclosureAccepted(true)
            prefs.userPreferencesFlow.first()
        }
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val activityRepo = ActivityEventRepository(db.activityEventDao())
        val backendClient = LaterboxBackendAiClient(
            baseUrlProvider = { baseUrl },
            appCheckTokenProvider = { "app-check" }
        )
        vm = ChatViewModel(
            chatRepository = chatRepo,
            userPreferencesRepository = prefs,
            laterboxContextEngine = LaterboxContextEngine(db, activityRepo, prefs),
            linkRepository = LinkRepository(db.savedLinkDao(), ctx),
            mediaRepository = NoOpMediaRepository(),
            backendAiClient = backendClient
        )
    }

    private class NoOpMediaRepository : com.laterbox.app.media.domain.repository.MediaRepository {
        override fun getAssetsForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
        override fun getAllAssets() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
        override fun filterAssets(mediaType: com.laterbox.app.media.domain.model.MediaType?, query: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
        override fun getAssetById(id: String) = kotlinx.coroutines.flow.flowOf<com.laterbox.app.media.domain.model.MediaAsset?>(null)
        override fun getTasksForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
        override fun getQueueTasks() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
        override fun getTaskById(taskId: String) = kotlinx.coroutines.flow.flowOf<com.laterbox.app.media.domain.model.DownloadTask?>(null)
        override fun getStorageBreakdown() = kotlinx.coroutines.flow.flowOf(emptyMap<com.laterbox.app.media.domain.model.MediaType, Long>())
        override fun getAssetCount() = kotlinx.coroutines.flow.flowOf(0)
        override suspend fun enqueueDownload(
            linkId: Long, candidate: com.laterbox.app.media.domain.model.MediaCandidate,
            sourcePlatform: String, title: String?, artist: String?, album: String?,
            artworkUrl: String?, spotifyTrackId: String?, versionTag: String?
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
        override fun getInspectionForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf<com.laterbox.app.media.domain.model.InspectionResult?>(null)
        override fun isInspectingLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(false)
        override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
        override suspend fun cancelInspection(linkId: Long) {}
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun awaitUntil(
        scope: kotlinx.coroutines.test.TestScope,
        predicate: suspend () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            scope.testScheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(10)
        }
        scope.testScheduler.advanceUntilIdle()
    }

    @Test
    fun retryMessage_onLatestTurnReplacesOnlyLatestReply_andOnEarlierTurnReSendsEarlierPrompt() = runTest(mainDispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val convId = chatRepo.createConversation("Two turn chat")
        chatRepo.renameConversation(convId, "Two turn chat")
        db.chatDao().upsertMessage(
            com.laterbox.app.data.local.entity.ChatMessageEntity(
                id = "u-1", conversationId = convId, role = ChatRole.USER.name,
                content = "First prompt about documentaries", status = ChatMessageStatus.COMPLETE.name, createdAt = 1000L
            )
        )
        db.chatDao().upsertMessage(
            com.laterbox.app.data.local.entity.ChatMessageEntity(
                id = "a-1", conversationId = convId, role = ChatRole.ASSISTANT.name,
                content = "First reply", status = ChatMessageStatus.COMPLETE.name, createdAt = 2000L
            )
        )
        db.chatDao().upsertMessage(
            com.laterbox.app.data.local.entity.ChatMessageEntity(
                id = "u-2", conversationId = convId, role = ChatRole.USER.name,
                content = "Second prompt about sci-fi", status = ChatMessageStatus.COMPLETE.name, createdAt = 3000L
            )
        )
        db.chatDao().upsertMessage(
            com.laterbox.app.data.local.entity.ChatMessageEntity(
                id = "a-2", conversationId = convId, role = ChatRole.ASSISTANT.name,
                content = "Second reply", status = ChatMessageStatus.COMPLETE.name, createdAt = 4000L
            )
        )
        val a1Id = "a-1"
        val a2Id = "a-2"

        vm.selectConversation(convId)
        awaitUntil(this) { vm.uiState.value.messages.size == 4 }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: response_complete\ndata: {\"fullText\":\"Fresh sci-fi reply\",\"displayText\":\"Fresh sci-fi reply\",\"actions\":[]}\n\n")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: response_complete\ndata: {\"fullText\":\"Fresh documentary reply\",\"displayText\":\"Fresh documentary reply\",\"actions\":[]}\n\n")
        )

        // 1. Retry latest assistant turn -> deletes a2 and uses Second prompt without duplicating user turn
        vm.retryMessage(a2Id)
        awaitUntil(this) {
            val msgs = chatRepo.getMessagesSnapshot(convId)
            !vm.uiState.value.isStreaming && msgs.any { it.content == "Fresh sci-fi reply" }
        }
        val req1Body = JSONObject(server.takeRequest().body.readUtf8())
        val req1Turns = req1Body.getJSONArray("messages")
        assertEquals("Second prompt about sci-fi", req1Turns.getJSONObject(req1Turns.length() - 1).getString("text"))

        val afterFirstRetry = chatRepo.getMessagesSnapshot(convId)
        assertFalse(afterFirstRetry.any { it.id == a2Id })
        assertEquals(1, afterFirstRetry.count { it.role == ChatRole.USER.name && it.content == "Second prompt about sci-fi" })

        // 2. Retry earlier assistant turn a1 -> re-sends First prompt while preserving later conversation
        vm.retryMessage(a1Id)
        awaitUntil(this) {
            val msgs = chatRepo.getMessagesSnapshot(convId)
            !vm.uiState.value.isStreaming && msgs.any { it.content == "Fresh documentary reply" }
        }
        val req2Body = JSONObject(server.takeRequest().body.readUtf8())
        val req2Turns = req2Body.getJSONArray("messages")
        assertEquals("First prompt about documentaries", req2Turns.getJSONObject(req2Turns.length() - 1).getString("text"))
        val afterSecondRetry = chatRepo.getMessagesSnapshot(convId)
        assertTrue(afterSecondRetry.any { it.id == a1Id })
        assertTrue(afterSecondRetry.any { it.content == "Fresh sci-fi reply" })
    }

    @Test
    fun retryMessage_isBlockedWhenExecutedOrRunningMutatingActionIsAttached() = runTest(mainDispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val convId = chatRepo.createConversation("Mutating retry guard")
        chatRepo.renameConversation(convId, "Mutating retry guard")
        chatRepo.addUserMessage(convId, "Add Arrival to watchlist")
        val a1 = chatRepo.createAssistantPlaceholder(convId, createdAt = System.currentTimeMillis() + 50L)
        val trackedJson = AssistantActionHandler.toTrackedJsonList(
            listOf(
                TrackedChatAction(
                    actionId = "act_exec",
                    action = ChatAction.ToggleMovieWatchlist("moviebox", "tt1", true, "Arrival"),
                    status = ChatActionStatus.EXECUTED
                )
            )
        )
        chatRepo.updateAssistantMessage(
            messageId = a1.id,
            conversationId = convId,
            content = "Added Arrival to your watchlist.",
            status = ChatMessageStatus.COMPLETE,
            actionsJson = trackedJson
        )

        vm.selectConversation(convId)
        awaitUntil(this) { vm.uiState.value.messages.size == 2 }

        assertTrue(ChatViewModel.hasBlockingMutatingActionForRetry(trackedJson))
        vm.retryMessage(a1.id)
        awaitUntil(this) { vm.uiState.value.statusNotice != null }

        assertEquals(0, server.requestCount)
        assertEquals(2, chatRepo.getMessagesSnapshot(convId).size)
        assertTrue(vm.uiState.value.statusNotice!!.contains("in-app changes"))
    }
}
