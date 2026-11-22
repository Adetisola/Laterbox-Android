package com.laterbox.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LaterboxBackendAiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun trackedActions_preserveLifecycleStatusesAndBackwardsCompat() {
        val actions = listOf(
            ChatAction.OpenMoviePlayer("moviebox", "99", title = "Inception"),
            ChatAction.ToggleMovieWatchlist("moviebox", "99", add = true, title = "Inception")
        )
        val tracked = AssistantActionHandler.toTrackedActions(actions)
        assertEquals(2, tracked.size)
        assertEquals(ChatActionStatus.PROPOSED, tracked[0].status)

        val json = AssistantActionHandler.toTrackedJsonList(tracked)
        assertNotNull(json)
        // Legacy parser remains backward compatible
        assertEquals(actions, AssistantActionHandler.fromJsonList(json))

        val updatedJson = AssistantActionHandler.updateTrackedActionStatus(
            actionsJson = json,
            actionId = tracked[0].actionId,
            status = ChatActionStatus.EXECUTED,
            statusDetail = "Opened player"
        )
        val restoredTracked = AssistantActionHandler.fromTrackedJsonList(updatedJson)
        assertEquals(ChatActionStatus.EXECUTED, restoredTracked[0].status)
        assertEquals("Opened player", restoredTracked[0].statusDetail)
        assertEquals(ChatActionStatus.PROPOSED, restoredTracked[1].status)
    }

    @Test
    fun fetchCapabilityManifest_andReportActionResult_parseServerPayloads() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "manifestVersion": 2,
                      "manifestRevision": "${AppCapabilityRegistry.MANIFEST_REVISION}",
                      "manifestHash": "${AppCapabilityRegistry.MANIFEST_HASH}",
                      "clientInSync": true,
                      "items": [{"id":"a"},{"id":"b"}]
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "manifestVersion": 2,
                      "manifestRevision": "${AppCapabilityRegistry.MANIFEST_REVISION}",
                      "manifestHash": "${AppCapabilityRegistry.MANIFEST_HASH}",
                      "clientInSync": false,
                      "items": [{"id":"a"}]
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "accepted": true,
                      "status": "executed",
                      "followUpMessage": "Added Inception to your watchlist."
                    }
                    """.trimIndent()
                )
        )

        val base = server.url("/").toString().trimEnd('/')
        val client = LaterboxBackendAiClient(
            baseUrlProvider = { base },
            appCheckTokenProvider = { "app-check-tok" }
        )

        val manifest = client.fetchCapabilityManifest()
        assertNotNull(manifest)
        assertTrue(manifest!!.clientInSync)
        assertEquals(2, manifest.serverCapabilitiesCount)
        assertEquals(false, client.checkCapabilityManifestSync())
        val ack = client.reportActionResult(
            conversationId = "conv-1",
            actionType = "TOGGLE_MOVIE_WATCHLIST",
            category = "LIBRARY_MANAGEMENT",
            status = "executed",
            detail = "Added Inception to your watchlist.",
            actionId = "act_0_123"
        )
        assertNotNull(ack)
        assertTrue(ack!!.accepted)
        assertEquals("executed", ack.status)
        assertEquals("Added Inception to your watchlist.", ack.followUpMessage)
    }

    @Test
    fun streamChat_cancellationAbortsOkHttpSocketWithoutWaitingForTimeout() = runBlocking {
        // Send headers + first SSE event, then stall body indefinitely; cancellation must abort promptly
        val stalledSse = """
            event: response_start
            data: {"manifestRevision":"rev-2","manifestSynced":true}

            event: text_delta
            data: {"chunk":"Hello "}

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(stalledSse)
                .throttleBody(4, 200, TimeUnit.MILLISECONDS)
        )

        val base = server.url("/").toString().trimEnd('/')
        val okHttp = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val client = LaterboxBackendAiClient(
            okHttpClient = okHttp,
            baseUrlProvider = { base },
            appCheckTokenProvider = { "tok" }
        )

        val events = mutableListOf<BackendAiStreamEvent>()
        var caughtCancellation = false
        val startMs = System.currentTimeMillis()
        val job = launch {
            try {
                client.streamChat(
                    conversationId = "conv-cancel",
                    turns = listOf(GeminiTurn(GeminiRole.USER, "Hi")),
                    capabilitySnapshot = CapabilitySnapshot(clientVersionCode = 64),
                    runtimeContextBlock = ""
                ).collect { ev ->
                    events.add(ev)
                }
            } catch (ce: CancellationException) {
                caughtCancellation = true
                throw ce
            }
        }

        delay(250)
        job.cancelAndJoin()
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("Expected cancellation to finish quickly (<5s), took ${elapsedMs}ms", elapsedMs < 5_000)
        assertFalse("Should not emit Error after coroutine cancel", events.any { it is BackendAiStreamEvent.Error })
    }
}
