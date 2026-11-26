package com.laterbox.app.ui.chat

import android.content.Context
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.ai.LaterboxBackendAiClient
import com.laterbox.app.ai.LaterboxContextEngine
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.ChatMessageEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ChatMessageStatus
import com.laterbox.app.data.repository.ChatRepository
import com.laterbox.app.data.repository.ChatRole
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatReadAloudTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var db: LaterboxDatabase
    private lateinit var prefs: UserPreferencesRepository
    private lateinit var server: MockWebServer
    private lateinit var vm: ChatViewModel
    private lateinit var fakePlayer: FakeTtsPlayer

    private class FakeTtsPlayer : AssistantTtsAudioPlayer {
        val playedChunks = mutableListOf<ByteArray>()
        var stopCount = 0
        var completionCallback: (() -> Unit)? = null

        override fun play(wavBytes: ByteArray, onCompletion: () -> Unit, onError: () -> Unit) {
            playedChunks += wavBytes
            completionCallback = onCompletion
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {}
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

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        prefs = UserPreferencesRepository(ctx)
        kotlinx.coroutines.runBlocking {
            prefs.setAssistantEnabled(true)
            prefs.setAssistantAiDisclosureAccepted(true)
            prefs.userPreferencesFlow.first()
        }
        server = MockWebServer()
        server.start()
        fakePlayer = FakeTtsPlayer()
        val backendClient = LaterboxBackendAiClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            appCheckTokenProvider = { "app-check" }
        )
        vm = ChatViewModel(
            chatRepository = ChatRepository(db.chatDao()),
            userPreferencesRepository = prefs,
            laterboxContextEngine = LaterboxContextEngine(db, ActivityEventRepository(db.activityEventDao()), prefs),
            linkRepository = LinkRepository(db.savedLinkDao(), ctx),
            mediaRepository = NoOpMediaRepository(),
            backendAiClient = backendClient,
            ttsAudioPlayer = fakePlayer
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun toggleReadAloud_fetchesAndCachesPerMessageIdAndTogglesStop() = runTest(mainDispatcher) {
        val wavHeader = "RIFF\u0024\u0000\u0000\u0000WAVE".toByteArray()
        val encoded = Base64.encodeToString(wavHeader, Base64.NO_WRAP)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"audio_base64":"$encoded","mime_type":"audio/wav","duration_estimate_ms":400}""")
        )

        val collector = backgroundScope.launch { vm.uiState.collect {} }

        vm.toggleReadAloud("msg-1", "Hello from Laterbox")
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals("/api/assistant/tts", server.takeRequest().path)
        assertEquals(1, fakePlayer.playedChunks.size)
        assertEquals("msg-1", vm.uiState.value.ttsPlayingMessageId)
        assertNull(vm.uiState.value.ttsLoadingMessageId)

        // Second click while playing stops playback
        vm.toggleReadAloud("msg-1", "Hello from Laterbox")
        advanceUntilIdle()
        assertNull(vm.uiState.value.ttsPlayingMessageId)
        assertEquals(2, fakePlayer.stopCount)

        // Third click hits session cache without another backend request
        vm.toggleReadAloud("msg-1", "Hello from Laterbox")
        advanceUntilIdle()
        assertEquals(1, server.requestCount)
        assertEquals(2, fakePlayer.playedChunks.size)
        assertEquals("msg-1", vm.uiState.value.ttsPlayingMessageId)
        collector.cancel()
    }

    @Test
    fun toggleReadAloud_503FailureEmitsUnavailableToastWithoutRetry() = runTest(mainDispatcher) {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"error":"tts_unavailable"}""")
        )

        val errors = mutableListOf<String>()
        val errJob = backgroundScope.launch {
            vm.ttsErrorEvents.collect { errors += it }
        }
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.toggleReadAloud("msg-2", "Unavailable test")
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals(listOf("Read-aloud is unavailable right now."), errors)
        assertNull(vm.uiState.value.ttsLoadingMessageId)
        assertNull(vm.uiState.value.ttsPlayingMessageId)
        errJob.cancel()
        stateJob.cancel()
    }

    @Test
    fun chatScreen_rendersReadAloudAndStopStatesOnAssistantMessages() {
        val assistantMsg = ChatMessageEntity(
            id = "a-1",
            conversationId = "c-1",
            role = ChatRole.ASSISTANT.name,
            content = "You saved 3 articles this week.",
            createdAt = 1000L,
            status = ChatMessageStatus.COMPLETE.name
        )
        var clickedMsgId: String? = null
        var playingId: String? = null

        composeRule.setContent {
            LaterboxTheme {
                ChatScreen(
                    uiState = ChatUiState(
                        assistantEnabled = true,
                        aiDisclosureAccepted = true,
                        messages = listOf(assistantMsg),
                        ttsPlayingMessageId = playingId
                    ),
                    onMenuClick = {},
                    onNewChatClick = {},
                    onAcceptAiDisclosure = {},
                    onOpenAiSettings = {},
                    onComposerTextChange = {},
                    onSendMessage = {},
                    onStopStreaming = {},
                    onRetryMessage = {},
                    onQuickPromptClick = {},
                    onActionClick = {},
                    onConfirmMutatingAction = {},
                    onDismissMutatingAction = {},
                    onDismissNotice = {},
                    onReadAloudClick = { id, _ ->
                        clickedMsgId = id
                        playingId = id
                    }
                )
            }
        }

        val btn = composeRule.onNodeWithTag("chat_quick_read_aloud_a-1")
        btn.assertIsDisplayed()
        composeRule.onNodeWithText("Read aloud").assertIsDisplayed()
        btn.performClick()
        assertEquals("a-1", clickedMsgId)
    }
}
