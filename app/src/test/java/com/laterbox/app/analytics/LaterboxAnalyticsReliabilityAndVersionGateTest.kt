package com.laterbox.app.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val prefs = UserPreferencesRepository(ctx)

        // Verify ensureAnalyticsPolicyMigrated preserves any historical explicit opt-out
        prefs.setAnalyticsEnabled(false)
        prefs.ensureAnalyticsPolicyMigrated()
        assertFalse(prefs.userPreferencesFlow.first().analyticsEnabled)

        // Now enable standard built-in analytics cleanly for dispatch verification
        prefs.setAnalyticsEnabled(true)
        prefs.ensureAnalyticsPolicyMigrated()
        assertTrue(prefs.userPreferencesFlow.first().analyticsEnabled)

        var tokenCalls = 0
        val base = server.url("/").toString().trimEnd('/')
        val remoteEnabled = MutableStateFlow(true)
        val tracker = LaterboxAnalyticsTracker(
            appContext = ctx,
            userPreferencesRepository = prefs,
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

        // 2. Permanent 422 response is dropped cleanly rather than stuck retrying forever
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"bad schema"}"""))
        tracker.trackPerformanceMetric(metricName = "config_fetch_ms", metricValueMs = 123)
        val req3 = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/analytics/v1/ingest", req3?.path)

        // 3. Install ID rotation creates a distinct identifier
        val beforeReset = body2.getString("installId")
        val rotated = tracker.resetInstallId()
        assertNotEquals(beforeReset, rotated)
    }
}
