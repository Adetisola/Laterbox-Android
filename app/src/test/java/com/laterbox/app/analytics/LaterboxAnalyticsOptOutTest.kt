package com.laterbox.app.analytics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.ThemeMode
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.preferences.userDataStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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
    fun legacyOptOutFlags_areRetiredSafelyAndIdempotently_preservingInstallIdAndUnrelatedPrefs() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sharedPrefs = ctx.getSharedPreferences("laterbox_analytics_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()

        val legacyAnalyticsKey = booleanPreferencesKey("analytics_enabled")
        val legacyMigratedKey = booleanPreferencesKey("analytics_policy_migrated_v1")
        val userNameKey = stringPreferencesKey("user_name")
        ctx.userDataStore.edit { prefs ->
            prefs[legacyAnalyticsKey] = false
            prefs[legacyMigratedKey] = true
            prefs[userNameKey] = "Laterbox Reader"
        }

        val repo = UserPreferencesRepository(ctx)
        repo.setThemeMode(ThemeMode.DARK)
        repo.retireLegacyAnalyticsOptOutPreferences()
        // Idempotent second invocation must not fail or alter unrelated keys
        repo.retireLegacyAnalyticsOptOutPreferences()

        val afterDataStore = ctx.userDataStore.data.first()
        assertFalse(afterDataStore.contains(legacyAnalyticsKey))
        assertFalse(afterDataStore.contains(legacyMigratedKey))
        assertEquals("Laterbox Reader", repo.userPreferencesFlow.first().userName)
        assertEquals(ThemeMode.DARK, repo.userPreferencesFlow.first().themeMode)

        // Seed legacy SharedPreferences opt-out flag alongside existing install ID
        val preservedInstallId = "11111111-2222-3333-4444-555555555555"
        sharedPrefs.edit()
            .putString("anonymous_install_id", preservedInstallId)
            .putBoolean("analytics_user_opted_out", true)
            .commit()

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"acceptedCount":1,"paused":false}"""))
        val base = server.url("/").toString().trimEnd('/')
        val remoteEnabled = MutableStateFlow(true)
        val tracker = LaterboxAnalyticsTracker(
            appContext = ctx,
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { base },
            appCheckTokenProvider = { null },
            remoteCollectionFlow = remoteEnabled,
        )

        // Legacy flag retired immediately without wiping install ID, and analytics collects by default
        assertFalse(sharedPrefs.contains("analytics_user_opted_out"))
        assertEquals(preservedInstallId, sharedPrefs.getString("anonymous_install_id", null))

        tracker.trackScreenView("inbox")
        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req)
        val event = JSONObject(req!!.body.readUtf8()).getJSONArray("events").getJSONObject(0)
        assertEquals("screen_viewed", event.getString("eventType"))
        assertEquals(preservedInstallId, event.getString("installId"))
    }

    @Test
    fun globalAdminPauseAndResume_areOnlyPauseMechanism_withoutRestart() = runBlocking {
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
        )

        // Active by default -> emits
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"acceptedCount":1,"paused":false}"""))
        tracker.trackFeatureUsed("assistant_chat")
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        // Global pause -> suppresses emission immediately
        remoteEnabled.value = false
        kotlinx.coroutines.delay(50L)
        tracker.trackFeatureUsed("media_share")
        assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))

        // Global resume -> resumes emission cleanly without restart
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"acceptedCount":1,"paused":false}"""))
        remoteEnabled.value = true
        kotlinx.coroutines.delay(50L)
        tracker.trackFeatureUsed("deep_link_open")
        val resumedReq = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(resumedReq)
    }
}
