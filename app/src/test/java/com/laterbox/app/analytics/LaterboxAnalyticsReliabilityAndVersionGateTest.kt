package com.laterbox.app.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaterboxAnalyticsReliabilityAndVersionGateTest {

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
    fun tracker_enabledByDefault_retriesAppCheckAndDrops422PoisonPillAndHonorsPause() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("laterbox_analytics_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        var tokenCalls = 0
        val base = server.url("/").toString().trimEnd('/')
        val remoteEnabled = MutableStateFlow(true)
        val tracker = LaterboxAnalyticsTracker(
            appContext = ctx,
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { base },
            appCheckTokenProvider = { force ->
                tokenCalls++
                if (force) "fresh-appcheck" else "stale-appcheck"
            },
            remoteCollectionFlow = remoteEnabled,
        )

        // 1. First event hits 401 -> retries once with forceRefresh token and succeeds
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"stale token"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"acceptedCount":1,"paused":false}"""))

        tracker.trackErrorObserved(errorCategory = "network_timeout", httpStatus = 504, screenId = "movies")

        val req1 = server.takeRequest(2, TimeUnit.SECONDS)!!
        val req2 = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("stale-appcheck", req1.getHeader("X-Firebase-AppCheck"))
        assertEquals("fresh-appcheck", req2.getHeader("X-Firebase-AppCheck"))
        val req2BodyText = req2.body.readUtf8()
        val body2 = JSONObject(req2BodyText).getJSONArray("events").getJSONObject(0)
        assertEquals("app_error_observed", body2.getString("eventType"))
        assertEquals("network_timeout", body2.getString("errorCategory"))
        assertEquals(504, body2.getInt("httpStatus"))
        assertTrue(tokenCalls >= 2)

        // 2. Permanent 422 response is dropped cleanly with safe diagnostic rather than stuck retrying forever
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"bad schema"}"""))
        tracker.trackPerformanceMetric(metricName = "config_refresh_ms", metricValueMs = 123)
        val req3 = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/analytics/v1/ingest", req3.path)
        val body3 = JSONObject(req3.body.readUtf8()).getJSONArray("events").getJSONObject(0)
        assertEquals("config_refresh_ms", body3.getString("metricName"))
        assertEquals(123, body3.getInt("durationMs"))
        assertFalse(body3.has("metricValueMs"))
        val dropDeadline = System.currentTimeMillis() + 2_000L
        while (tracker.getPermanentDropCount() < 1 && System.currentTimeMillis() < dropDeadline) {
            kotlinx.coroutines.delay(20L)
        }
        assertEquals(1, tracker.getPermanentDropCount())
        assertTrue(tracker.lastPermanentDropReason?.contains("HTTP 422") == true)

        // 3. Install ID rotation creates a distinct identifier
        val beforeReset = body2.getString("installId")
        val rotated = tracker.resetInstallId()
        assertNotEquals(beforeReset, rotated)
    }

    @Test
    fun tracker_isolatesMalformedOfflineBatchItem_andIncrementsForegroundEpoch() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("laterbox_analytics_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val base = server.url("/").toString().trimEnd('/')
        val remoteEnabled = MutableStateFlow(true)
        val tracker = LaterboxAnalyticsTracker(
            appContext = ctx,
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { base },
            appCheckTokenProvider = { "token-1" },
            remoteCollectionFlow = remoteEnabled,
            heartbeatIntervalMs = 60_000L,
        )

        val acceptedEvents = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val arr = JSONObject(request.body.readUtf8()).getJSONArray("events")
                if (arr.length() > 1) {
                    return MockResponse().setResponseCode(422).setBody("""{"detail":"one bad item"}""")
                }
                val item = arr.getJSONObject(0)
                if (item.optString("eventId") == "bad_event_123456") {
                    return MockResponse().setResponseCode(422).setBody("""{"detail":"bad item"}""")
                }
                acceptedEvents += item
                return MockResponse().setResponseCode(200).setBody("""{"acceptedCount":1,"paused":false}""")
            }
        }

        val seededQueue = org.json.JSONArray()
            .put(
                AnalyticsEventRecord(
                    eventId = "bad_event_123456",
                    eventType = "feature_used",
                    installId = "inst_12345678",
                    sessionId = "sess_12345678",
                    versionCode = 68,
                    versionName = "1.0.68",
                    manifestRevision = "rev",
                    featureId = "assistant_chat",
                    timestampMs = 1760000000000L,
                    isOfflineReplay = true,
                ).toJson()
            )
            .put(
                AnalyticsEventRecord(
                    eventId = "good_event_12345",
                    eventType = "feature_used",
                    installId = "inst_12345678",
                    sessionId = "sess_12345678",
                    versionCode = 68,
                    versionName = "1.0.68",
                    manifestRevision = "rev",
                    featureId = "media_share",
                    timestampMs = 1760000001000L,
                    isOfflineReplay = true,
                ).toJson()
            )
        prefs.edit().putString("offline_event_queue_v1", seededQueue.toString()).commit()

        val initialEpoch = tracker.foregroundEpoch.value
        tracker.onAppForegrounded()
        assertEquals(initialEpoch + 1, tracker.foregroundEpoch.value)
        tracker.trackScreenRoute("movies/viewing_history")

        val d = System.currentTimeMillis() + 4_000L
        while (
            (!acceptedEvents.any { it.optString("eventId") == "good_event_12345" } ||
                tracker.getPermanentDropCount() < 1) &&
            System.currentTimeMillis() < d
        ) {
            kotlinx.coroutines.delay(25L)
        }
        assertTrue(acceptedEvents.any { it.optString("eventId") == "good_event_12345" })
        assertTrue(acceptedEvents.any { it.optString("eventType") == "screen_viewed" && it.optString("screenId") == "movies" })
        assertEquals(1, tracker.getPermanentDropCount())
        assertTrue(prefs.getString("offline_event_queue_v1", null).isNullOrBlank())
        tracker.onAppBackgrounded()
    }

    @Test
    fun tracker_resumesHeartbeatWithoutDuplicateSessionStart_andDropsServerPausedBatch() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("laterbox_analytics_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val base = server.url("/").toString().trimEnd('/')
        val remoteEnabled = MutableStateFlow(true)
        val tracker = LaterboxAnalyticsTracker(
            appContext = ctx,
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { base },
            appCheckTokenProvider = { "token-1" },
            remoteCollectionFlow = remoteEnabled,
            heartbeatIntervalMs = 60L,
        )

        val recordedEventTypes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mockCode = java.util.concurrent.atomic.AtomicInteger(200)
        val mockBody = java.util.concurrent.atomic.AtomicReference("""{"acceptedCount":1,"paused":false}""")
        val receivedLatch = java.util.concurrent.atomic.AtomicInteger(0)

        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val raw = request.body.readUtf8()
                runCatching {
                    val arr = JSONObject(raw).getJSONArray("events")
                    for (i in 0 until arr.length()) {
                        recordedEventTypes.add(arr.getJSONObject(i).getString("eventType"))
                    }
                }
                receivedLatch.incrementAndGet()
                return MockResponse().setResponseCode(mockCode.get()).setBody(mockBody.get())
            }
        }

        // 1. Initial foreground -> 1 session_started + heartbeat
        tracker.onAppForegrounded()
        val d1 = System.currentTimeMillis() + 3_000L
        while (!recordedEventTypes.contains("app_session_heartbeat") && System.currentTimeMillis() < d1) {
            kotlinx.coroutines.delay(25L)
        }
        assertEquals(1, recordedEventTypes.count { it == "app_session_started" })
        assertTrue(recordedEventTypes.any { it == "app_session_heartbeat" })

        // 2. Pause remotely -> heartbeat stops & tracked events ignored
        remoteEnabled.value = false
        kotlinx.coroutines.delay(140L)
        val countWhilePaused = recordedEventTypes.size
        tracker.trackScreenView("movies")
        kotlinx.coroutines.delay(140L)
        assertEquals(countWhilePaused, recordedEventTypes.size)

        // 3. Resume remotely -> heartbeat resumes cleanly WITHOUT duplicate app_session_started
        val hbBeforeResume = recordedEventTypes.count { it == "app_session_heartbeat" }
        remoteEnabled.value = true
        val d2 = System.currentTimeMillis() + 3_000L
        while (recordedEventTypes.count { it == "app_session_heartbeat" } <= hbBeforeResume && System.currentTimeMillis() < d2) {
            kotlinx.coroutines.delay(25L)
        }
        assertEquals(1, recordedEventTypes.count { it == "app_session_started" })
        assertTrue(recordedEventTypes.count { it == "app_session_heartbeat" } > hbBeforeResume)

        tracker.onAppBackgrounded()
        kotlinx.coroutines.delay(100L)

        // 4. Queue event during offline 503, then flush during server paused=true -> rejected batch is dropped cleanly
        val prefsStore = ctx.getSharedPreferences("laterbox_analytics_prefs", Context.MODE_PRIVATE)
        mockCode.set(503)
        mockBody.set("""{"detail":"offline"}""")
        val before503 = receivedLatch.get()
        tracker.trackFeatureUsed("smart_reminders")
        val d3 = System.currentTimeMillis() + 2_000L
        while ((receivedLatch.get() == before503 || prefsStore.getString("offline_event_queue_v1", null).isNullOrBlank()) &&
            System.currentTimeMillis() < d3
        ) {
            kotlinx.coroutines.delay(20L)
        }
        assertFalse(prefsStore.getString("offline_event_queue_v1", null).isNullOrBlank())

        mockCode.set(200)
        mockBody.set("""{"acceptedCount":0,"paused":true}""")
        val beforePausedFlush = receivedLatch.get()
        tracker.onAppBackgrounded()
        val d4 = System.currentTimeMillis() + 2_000L
        while ((receivedLatch.get() == beforePausedFlush || !prefsStore.getString("offline_event_queue_v1", null).isNullOrBlank()) &&
            System.currentTimeMillis() < d4
        ) {
            kotlinx.coroutines.delay(20L)
        }
        assertTrue(receivedLatch.get() > beforePausedFlush)
        assertTrue(prefsStore.getString("offline_event_queue_v1", null).isNullOrBlank())

        // 5. Flip remote false -> true clears sticky serverPausedUntilMs so subsequent tracking works immediately
        remoteEnabled.value = false
        kotlinx.coroutines.delay(50L)
        mockBody.set("""{"acceptedCount":1,"paused":false}""")
        remoteEnabled.value = true
        kotlinx.coroutines.delay(50L)
        val beforeResumedEvent = receivedLatch.get()
        tracker.trackFeatureUsed("deep_link_open")
        val d5 = System.currentTimeMillis() + 2_000L
        while (receivedLatch.get() == beforeResumedEvent && System.currentTimeMillis() < d5) {
            kotlinx.coroutines.delay(20L)
        }
        assertTrue(receivedLatch.get() > beforeResumedEvent)
    }
}
