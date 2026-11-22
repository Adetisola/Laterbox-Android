package com.laterbox.app.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaterboxAnalyticsOptOutTest {

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
    fun tracker_whenOptedOutInDataStore_emitsNothingDuringColdStart() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(ctx)
        prefs.setAnalyticsEnabled(false)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"acceptedCount":1}"""))
        val base = server.url("/").toString().trimEnd('/')
        val tracker = LaterboxAnalyticsTracker(
            appContext = ctx,
            userPreferencesRepository = prefs,
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { base },
            appCheckTokenProvider = { null }
        )

        // Fire startup calls immediately before DataStore collector has necessarily run
        tracker.onAppForegrounded()
        tracker.trackScreenView("inbox")
        tracker.trackFeatureUsed("assistant_chat")
        tracker.onAppBackgrounded()

        kotlinx.coroutines.delay(400)
        assertEquals(0, server.requestCount)
    }
}
