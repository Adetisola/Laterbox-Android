package com.laterbox.app.update

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.backup.BackupManager
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.ui.settings.SettingsViewModel
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsUpdateCheckTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var database: LaterboxDatabase
    private lateinit var preferencesRepository: UserPreferencesRepository
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val directExecutor = Executor { runnable -> runnable.run() }
        database = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        preferencesRepository = UserPreferencesRepository(context)
        runBlocking {
            preferencesRepository.recordUpdateCheck(0L)
        }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            userPreferencesRepository = preferencesRepository,
            backupManager = BackupManager(database),
            appContext = context,
            database = database,
            backendBaseUrlProvider = { server.url("/").toString().trimEnd('/') },
            appCheckTokenProvider = { "appcheck-settings-token" }
        )
    }

    @Test
    fun `checkForUpdates surfaces GitHub AuthError explicitly and never reports up to date`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody(
                """{"detail":{"code":"github_auth_failed","message":"Unable to authenticate with GitHub repository."}}"""
            )
        )
        val viewModel = createViewModel()
        val latch = CountDownLatch(1)
        var feedback = ""

        viewModel.checkForUpdates(currentVersion = "1.0.53") { msg ->
            feedback = msg
            latch.countDown()
        }

        assertTrue("Expected feedback callback", latch.await(5, TimeUnit.SECONDS))
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/updates/laterbox/latest?refresh=1", recorded!!.path)
        assertEquals("appcheck-settings-token", recorded.getHeader("X-Firebase-AppCheck"))
        assertTrue(feedback.contains("authentication failed", ignoreCase = true))
        assertFalse(feedback.contains("up to date", ignoreCase = true))
        assertEquals(0L, preferencesRepository.userPreferencesFlow.first().lastUpdateCheckTime)
    }

    @Test
    fun `checkForUpdates surfaces RateLimited retry window clearly`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "42")
                .setBody("""{"detail":{"code":"github_rate_limited","message":"GitHub rate limit reached.","retry_after_seconds":42}}""")
        )
        val viewModel = createViewModel()
        val latch = CountDownLatch(1)
        var feedback = ""

        viewModel.checkForUpdates(currentVersion = "1.0.53") { msg ->
            feedback = msg
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(feedback.contains("rate limit", ignoreCase = true))
        assertTrue(feedback.contains("42s"))
    }

    @Test
    fun `checkForUpdates emits UpdateAvailable dialog state and records check timestamp`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "v1.0.54",
                  "name": "Laterbox v1.0.54",
                  "body": "Private release ready",
                  "assets": [
                    {
                      "id": 9001,
                      "name": "Laterbox-Universal.apk",
                      "size": 70000000,
                      "download_path": "/updates/laterbox/assets/9001/download-url"
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        val viewModel = createViewModel()
        viewModel.checkForUpdates(currentVersion = "1.0.53")

        val deadline = System.currentTimeMillis() + 5_000L
        while (viewModel.updateCheckResult.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }

        val info = viewModel.updateCheckResult.value
        assertNotNull(info)
        assertEquals("1.0.54", info!!.versionName)
        assertTrue(info.downloadUrl.endsWith("/updates/laterbox/assets/9001/download-url"))
        assertTrue(preferencesRepository.userPreferencesFlow.first().lastUpdateCheckTime > 0L)
    }
}
