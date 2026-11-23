package com.laterbox.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.auth.LaterboxBackendAuthProvider
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterAuthIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        UpdateNetworkSupport.resetSeams()
        GitHubUpdateChecker.resetFallbackCache()
        UpdateNetworkSupport.sleeper = {}
        File(context.cacheDir, "updates").deleteRecursively()
    }

    @After
    fun tearDown() {
        UpdateNetworkSupport.resetSeams()
        GitHubUpdateChecker.resetFallbackCache()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    private fun sampleReleasePayload(assetId: Int = 77) = """
        {
          "tag_name": "v1.0.99",
          "name": "Laterbox v1.0.99",
          "body": "Release notes",
          "html_url": "https://github.com/Adetisola/Laterbox/releases/tag/v1.0.99",
          "versionCode": 99,
          "assets": [
            {
              "id": $assetId,
              "name": "Laterbox-Universal.apk",
              "size": 1024,
              "download_path": "/updates/laterbox/assets/$assetId/download-url"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `updates latest and download-url work with both Play Integrity AppCheck and Emulator GuestAuth`() = runBlocking {
        val cdnPayload = ByteArray(1024) { (it % 127).toByte() }
        val server = MockWebServer()
        val seenLatestHeaders = mutableListOf<Pair<String?, String?>>()
        val seenDownloadHeaders = mutableListOf<Pair<String?, String?>>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val appCheck = request.getHeader(LaterboxBackendAuthProvider.HEADER_APP_CHECK)
                val guestAuth = request.getHeader(LaterboxBackendAuthProvider.HEADER_GUEST_AUTH)
                val authorized = appCheck == "valid-appcheck" || guestAuth == "valid-guest-jwt"
                return when {
                    path.startsWith("/updates/laterbox/latest") -> {
                        seenLatestHeaders.add(appCheck to guestAuth)
                        if (!authorized) {
                            MockResponse().setResponseCode(401).setBody("""{"detail":"Unauthorized"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody(sampleReleasePayload())
                        }
                    }
                    path.startsWith("/updates/laterbox/assets/77/download-url") -> {
                        seenDownloadHeaders.add(appCheck to guestAuth)
                        if (!authorized) {
                            MockResponse().setResponseCode(401).setBody("""{"detail":"Unauthorized"}""")
                        } else {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"download_url":"${server.url("/cdn/Laterbox-Universal.apk")}"}""")
                        }
                    }
                    path == "/cdn/Laterbox-Universal.apk" -> {
                        MockResponse().setResponseCode(200).setBody(Buffer().write(cdnPayload))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val latestUrl = GitHubUpdateChecker.buildBackendUpdateUrl(server.url("/").toString())

            // 1. Verified Android device via X-Firebase-AppCheck
            val appCheckProvider: suspend (Boolean) -> Pair<String, String>? = {
                LaterboxBackendAuthProvider.HEADER_APP_CHECK to "valid-appcheck"
            }
            val appCheckRes = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.67",
                releasesUrl = latestUrl,
                deviceSupportedAbis = arrayOf("arm64-v8a"),
                context = null,
                authHeaderProvider = appCheckProvider,
            )
            assertTrue(appCheckRes is UpdateCheckResult.UpdateAvailable)
            val appCheckEvents = mutableListOf<ApkInstallerHelper.DownloadProgress>()
            val appCheckFile = ApkInstallerHelper.downloadApk(
                context = context,
                downloadUrl = (appCheckRes as UpdateCheckResult.UpdateAvailable).updateInfo.downloadUrl,
                versionName = "1.0.99",
                assetName = "Laterbox-Universal.apk",
                expectedSizeBytes = cdnPayload.size.toLong(),
                publicFallbackUrl = null,
                authHeaderProvider = appCheckProvider,
                onProgress = { appCheckEvents.add(it) },
            )
            assertNotNull(appCheckFile)
            assertArrayEquals(cdnPayload, appCheckFile!!.readBytes())
            appCheckFile.delete()

            // 2. Emulator device via X-Firebase-Auth guest session
            val guestProvider: suspend (Boolean) -> Pair<String, String>? = {
                LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to "valid-guest-jwt"
            }
            val guestRes = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.67",
                releasesUrl = latestUrl,
                deviceSupportedAbis = arrayOf("arm64-v8a"),
                context = null,
                authHeaderProvider = guestProvider,
            )
            assertTrue(guestRes is UpdateCheckResult.UpdateAvailable)
            val guestEvents = mutableListOf<ApkInstallerHelper.DownloadProgress>()
            val guestFile = ApkInstallerHelper.downloadApk(
                context = context,
                downloadUrl = (guestRes as UpdateCheckResult.UpdateAvailable).updateInfo.downloadUrl,
                versionName = "1.0.99",
                assetName = "Laterbox-Universal.apk",
                expectedSizeBytes = cdnPayload.size.toLong(),
                publicFallbackUrl = null,
                authHeaderProvider = guestProvider,
                onProgress = { guestEvents.add(it) },
            )
            assertNotNull(guestFile)
            assertArrayEquals(cdnPayload, guestFile!!.readBytes())

            // Verify header separation
            assertEquals("valid-appcheck" to null, seenLatestHeaders[0])
            assertEquals("valid-appcheck" to null, seenDownloadHeaders[0])
            assertEquals(null to "valid-guest-jwt", seenLatestHeaders[1])
            assertEquals(null to "valid-guest-jwt", seenDownloadHeaders[1])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `expired guest token refreshes once on 401 across update check and asset resolver`() = runBlocking {
        val cdnPayload = ByteArray(512) { 42 }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val guestAuth = request.getHeader(LaterboxBackendAuthProvider.HEADER_GUEST_AUTH)
                val appCheck = request.getHeader(LaterboxBackendAuthProvider.HEADER_APP_CHECK)
                assertNull("Guest path must not send AppCheck header", appCheck)
                return when {
                    path.startsWith("/updates/laterbox/latest") -> {
                        if (guestAuth == "fresh-guest-token") {
                            MockResponse().setResponseCode(200).setBody(sampleReleasePayload())
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"detail":"Expired guest token"}""")
                        }
                    }
                    path.startsWith("/updates/laterbox/assets/77/download-url") -> {
                        if (guestAuth == "fresh-guest-token") {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"download_url":"${server.url("/cdn/Laterbox-Universal.apk")}"}""")
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"detail":"Expired guest token"}""")
                        }
                    }
                    path == "/cdn/Laterbox-Universal.apk" -> {
                        MockResponse().setResponseCode(200).setBody(Buffer().write(cdnPayload))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val latestCalls = AtomicInteger(0)
            val latestAuthProvider: suspend (Boolean) -> Pair<String, String>? = { force ->
                latestCalls.incrementAndGet()
                val tok = if (force) "fresh-guest-token" else "expired-guest-token"
                LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to tok
            }

            val latestUrl = GitHubUpdateChecker.buildBackendUpdateUrl(server.url("/").toString())
            val res = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.67",
                releasesUrl = latestUrl,
                deviceSupportedAbis = arrayOf("arm64-v8a"),
                context = null,
                authHeaderProvider = latestAuthProvider,
            )
            assertTrue("Expected retry with fresh guest token to succeed", res is UpdateCheckResult.UpdateAvailable)
            assertEquals(2, latestCalls.get())

            val downloadCalls = AtomicInteger(0)
            val downloadAuthProvider: suspend (Boolean) -> Pair<String, String>? = { force ->
                downloadCalls.incrementAndGet()
                val tok = if (force) "fresh-guest-token" else "expired-guest-token"
                LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to tok
            }
            val downloaded = ApkInstallerHelper.downloadApk(
                context = context,
                downloadUrl = (res as UpdateCheckResult.UpdateAvailable).updateInfo.downloadUrl,
                versionName = "1.0.99",
                assetName = "Laterbox-Universal.apk",
                expectedSizeBytes = cdnPayload.size.toLong(),
                publicFallbackUrl = null,
                authHeaderProvider = downloadAuthProvider,
                onProgress = {},
            )
            assertNotNull(downloaded)
            assertEquals(2, downloadCalls.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `repeated 401 returns AuthError and AUTH failure kind without public fallback or CORRUPT classification`() = runBlocking {
        val publicServer = MockWebServer().apply { start() }
        val backend = MockWebServer()
        backend.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(401).setBody("""{"detail":"Update authentication failed"}""")
            }
        }
        backend.start()
        try {
            val refreshableProvider: suspend (Boolean) -> Pair<String, String>? = { force ->
                LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to if (force) "still-rejected-2" else "still-rejected-1"
            }
            val latestRes = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.67",
                releasesUrl = GitHubUpdateChecker.buildBackendUpdateUrl(backend.url("/").toString()),
                deviceSupportedAbis = arrayOf("arm64-v8a"),
                context = null,
                publicFallbackUrl = publicServer.url("/latest").toString(),
                authHeaderProvider = refreshableProvider,
            )
            assertTrue(latestRes is UpdateCheckResult.AuthError)
            assertEquals(401, (latestRes as UpdateCheckResult.AuthError).statusCode)
            assertEquals("Public fallback must never mask 401 auth failure", 0, publicServer.requestCount)

            val events = mutableListOf<ApkInstallerHelper.DownloadProgress>()
            val downloadRes = ApkInstallerHelper.downloadApk(
                context = context,
                downloadUrl = backend.url("/updates/laterbox/assets/77/download-url").toString(),
                versionName = "1.0.99",
                assetName = "Laterbox-Universal.apk",
                expectedSizeBytes = 1024L,
                publicFallbackUrl = publicServer.url("/fallback.apk").toString(),
                authHeaderProvider = refreshableProvider,
                onProgress = { events.add(it) },
            )
            assertNull(downloadRes)
            assertEquals("Public APK fallback must not bypass 401 resolver auth failure", 0, publicServer.requestCount)
            val err = events.filterIsInstance<ApkInstallerHelper.DownloadProgress.Error>().lastOrNull()
            assertNotNull(err)
            assertEquals(UpdateFailureKind.AUTH, err!!.kind)
            assertTrue(err.isAuthOrRateLimit)
            assertFalse(err.message.contains("corrupt", ignoreCase = true))
        } finally {
            backend.shutdown()
            publicServer.shutdown()
        }
    }

    @Test
    fun `guest authenticated delta patch and APK download still enforces SHA-256 and APK verification`() {
        runBlocking {
            val corruptedBytes = "not-a-valid-patch-or-apk".toByteArray()
            val expectedSha = MessageDigest.getInstance("SHA-256")
                .digest("real-expected-patch-bytes".toByteArray())
                .joinToString("") { "%02x".format(it) }

            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/updates/laterbox/assets/88/download-url") -> {
                            assertEquals("valid-guest-jwt", request.getHeader(LaterboxBackendAuthProvider.HEADER_GUEST_AUTH))
                            MockResponse().setResponseCode(200)
                                .setBody("""{"download_url":"${server.url("/cdn/bad.patch")}"}""")
                        }
                        path == "/cdn/bad.patch" -> {
                            MockResponse().setResponseCode(200).setBody(Buffer().write(corruptedBytes))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            try {
                val patch = DeltaPatchInfo(
                    architecture = "arm64-v8a",
                    architectureLabel = "ARM64",
                    baseVersion = "1.0.67",
                    baseVersionCode = 67,
                    baseApkSha256 = "a".repeat(64),
                    targetApkFilename = "Laterbox-ARM64.apk",
                    targetApkSha256 = "b".repeat(64),
                    targetApkSize = 60_000_000L,
                    patchFilename = "Laterbox-ARM64-1.0.67-to-1.0.99.patch",
                    patchSha256 = expectedSha,
                    patchSize = corruptedBytes.size.toLong(),
                    patchAlgorithm = "BSDIFF40",
                    downloadUrl = server.url("/updates/laterbox/assets/88/download-url").toString(),
                )
                val events = mutableListOf<ApkInstallerHelper.DownloadProgress>()
                val result = ApkInstallerHelper.downloadPatch(
                    context = context,
                    deltaPatch = patch,
                    appCheckTokenProvider = null,
                    publicFallbackUrl = null,
                    authHeaderProvider = { LaterboxBackendAuthProvider.HEADER_GUEST_AUTH to "valid-guest-jwt" },
                    onProgress = { events.add(it) },
                )
                assertNull("Corrupted patch must be rejected even under valid guest auth", result)
                val err = events.filterIsInstance<ApkInstallerHelper.DownloadProgress.Error>().lastOrNull()
                assertNotNull(err)
                assertTrue(err!!.message.contains("SHA-256"))

                // Also confirm verifyApk rejects an invalid/corrupt APK file under Robolectric
                val badApk = File(context.cacheDir, "corrupt.apk").apply { writeBytes(corruptedBytes) }
                assertFalse(ApkInstallerHelper.verifyApk(context, badApk))
                badApk.delete()
            } finally {
                server.shutdown()
            }
        }
    }
}
