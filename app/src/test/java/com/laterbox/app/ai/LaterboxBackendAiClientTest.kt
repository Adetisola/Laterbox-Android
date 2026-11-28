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

    @Test
    fun streamChat_includesRecentActionResultsInRequestBody() = runBlocking {
        val sseBody = """
            event: response_complete
            data: {"fullText":"Done","displayText":"Done","actions":[]}

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val base = server.url("/").toString().trimEnd('/')
        val client = LaterboxBackendAiClient(
            baseUrlProvider = { base },
            appCheckTokenProvider = { "tok" }
        )

        val events = client.streamChat(
            conversationId = "conv-receipts",
            turns = listOf(GeminiTurn(GeminiRole.USER, "Did that work?")),
            capabilitySnapshot = CapabilitySnapshot(clientVersionCode = 64),
            runtimeContextBlock = "",
            recentActionResults = listOf(
                RecentActionReceipt(
                    actionId = "act-1",
                    actionType = "OPEN_MOVIE_DETAIL",
                    category = "NAVIGATION_DISCOVERY",
                    status = "executed",
                    detail = "Opened details for Dune"
                )
            )
        ).toList()

        assertTrue(events.any { it is BackendAiStreamEvent.Completed })
        val recorded = server.takeRequest()
        val bodyStr = recorded.body.readUtf8()
        assertTrue(bodyStr.contains("recentActionResults"))
        assertTrue(bodyStr.contains("act-1"))
        assertTrue(bodyStr.contains("Opened details for Dune"))
    }

    @Test
    fun streamChat_parsesApprovalRequiredSseEvent() = runBlocking {
        val sseBody = """
            event: response_start
            data: {"manifestRevision":"2026.11.27-v3","manifestSynced":true}

            event: approval_required
            data: {"action":{"type":"DELETE_DOWNLOAD","taskId":"task-9","title":"Old Clip"},"category":"LIBRARY_MANAGEMENT"}

            event: response_complete
            data: {"fullText":"Ready to delete.","displayText":"Ready to delete.","actions":[]}

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val base = server.url("/").toString().trimEnd('/')
        val client = LaterboxBackendAiClient(
            baseUrlProvider = { base },
            appCheckTokenProvider = { "tok" }
        )

        val events = client.streamChat(
            conversationId = "conv-approval",
            turns = listOf(GeminiTurn(GeminiRole.USER, "Delete old clip")),
            capabilitySnapshot = CapabilitySnapshot(clientVersionCode = 84),
            runtimeContextBlock = ""
        ).toList()

        val approval = events.filterIsInstance<BackendAiStreamEvent.ApprovalRequired>().single()
        assertTrue(approval.action is ChatAction.DeleteDownload)
        assertEquals("DELETE_DOWNLOAD", approval.action.actionType)
        assertEquals("LIBRARY_MANAGEMENT", approval.category)
    }

    @Test
    fun fetchCapabilityManifest_retriesOn401WithFreshToken_andDoesNotRetryGuest403() = runBlocking {
        // 1. 401 -> forceRefresh=true -> 200
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "manifestVersion": 3,
                      "manifestRevision": "${AppCapabilityRegistry.MANIFEST_REVISION}",
                      "manifestHash": "${AppCapabilityRegistry.MANIFEST_HASH}",
                      "clientInSync": true,
                      "items": [{"id":"a"}]
                    }
                    """.trimIndent()
                )
        )

        val base = server.url("/").toString().trimEnd('/')
        var refreshCalls = 0
        val refreshingClient = LaterboxBackendAiClient(
            baseUrlProvider = { base },
            appCheckTokenProvider = { null },
            authHeaderProvider = { forceRefresh ->
                if (forceRefresh) {
                    refreshCalls++
                    "X-Firebase-AppCheck" to "fresh-token"
                } else {
                    "X-Firebase-AppCheck" to "stale-token"
                }
            }
        )

        val manifest = refreshingClient.fetchCapabilityManifest()
        assertNotNull(manifest)
        assertTrue(manifest!!.clientInSync)
        assertEquals(1, refreshCalls)
        assertEquals("stale-token", server.takeRequest().getHeader("X-Firebase-AppCheck"))
        assertEquals("fresh-token", server.takeRequest().getHeader("X-Firebase-AppCheck"))

        // 2. Guest 403 -> must NOT force-refresh retry
        server.enqueue(MockResponse().setResponseCode(403))
        var guestRefreshCalls = 0
        val guestClient = LaterboxBackendAiClient(
            baseUrlProvider = { base },
            appCheckTokenProvider = { null },
            authHeaderProvider = { forceRefresh ->
                if (forceRefresh) guestRefreshCalls++
                com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to "guest-jwt"
            }
        )
        val guestResult = guestClient.fetchCapabilityManifest()
        assertEquals(null, guestResult)
        assertEquals(0, guestRefreshCalls)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun guestAuth_skipsHttpCallsForGenerateConversationTitleAndReportActionResult() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val guestClient = LaterboxBackendAiClient(
            baseUrlProvider = { base },
            appCheckTokenProvider = { null },
            authHeaderProvider = { _ ->
                com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to "guest-jwt"
            }
        )

        val titleResult = guestClient.generateConversationTitle(
            firstUserMessage = "Hey laterbox, please find my kotlin links",
            firstAssistantReply = "Sure!",
            currentTitle = "Hey laterbox, please find my kotlin links"
        )
        assertEquals(null, titleResult)

        val ack = guestClient.reportActionResult(
            conversationId = "conv-guest",
            actionType = "TOGGLE_MOVIE_WATCHLIST",
            category = "LIBRARY_MANAGEMENT",
            status = "executed",
            detail = "Added"
        )
        assertNotNull(ack)
        assertTrue(ack!!.accepted)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun generateConversationTitle_returnsNullOnAppCheck403WithoutInfiniteRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Forbidden"}"""))
        val base = server.url("/").toString().trimEnd('/')
        var tokenCalls = 0
        val client = LaterboxBackendAiClient(
            baseUrlProvider = { base },
            appCheckTokenProvider = {
                tokenCalls++
                "static-app-check"
            }
        )

        val result = client.generateConversationTitle(
            firstUserMessage = "Summarize my saved articles"
        )
        assertEquals(null, result)
        assertEquals(1, server.requestCount)
        assertEquals(2, tokenCalls) // initial + single forceRefresh check returning identical token -> no second HTTP request
    }
}
