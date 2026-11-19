package com.laterbox.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Focused regression tests for updater DNS / retry / fallback behaviour. DNS failures are simulated
 * through [UpdateNetworkSupport.connectionFactory] so the tests never depend on real resolvers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterResilienceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sleeps = mutableListOf<Long>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val payload = ByteArray(200_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        sleeps.clear()
        UpdateNetworkSupport.resetSeams()
        GitHubUpdateChecker.resetFallbackCache()
        UpdateNetworkSupport.sleeper = { sleeps.add(it) }
        File(context.cacheDir, "updates").deleteRecursively()
    }

    @After
    fun tearDown() {
        UpdateNetworkSupport.resetSeams()
        GitHubUpdateChecker.resetFallbackCache()
        GitHubUpdateChecker.clock = { System.currentTimeMillis() }
        File(context.cacheDir, "updates").deleteRecursively()
    }

    /** Fails with [error] for any URL accepted by [matches]; other URLs open normally. */
    private fun failConnections(matches: (URL) -> Boolean, error: () -> Exception, times: Int = Int.MAX_VALUE): () -> Int {
        var count = 0
        UpdateNetworkSupport.connectionFactory = { url ->
            if (matches(url) && count < times) {
                count++
                throw error()
            }
            url.openConnection() as HttpURLConnection
        }
        return { count }
    }

    private fun downloadErrors(
        resolverUrl: String,
        fallback: String?,
        expected: Long = payload.size.toLong()
    ): Pair<File?, List<ApkInstallerHelper.DownloadProgress>> = runBlocking {
        val events = mutableListOf<ApkInstallerHelper.DownloadProgress>()
        val file = ApkInstallerHelper.downloadApk(
            context = context,
            downloadUrl = resolverUrl,
            versionName = "9.9.9",
            assetName = "Laterbox-ARM64.apk",
            expectedSizeBytes = expected,
            appCheckTokenProvider = { "token" },
            publicFallbackUrl = fallback,
            onProgress = { events.add(it) }
        )
        file to events
    }

    // ---- 1 & 2: host correctness ---------------------------------------------------------

    @Test
    fun `production backend host is the official one and wrong hosts are detected`() {
        assertTrue(UpdateNetworkSupport.isOfficialBackendHost("https://laterbox-moviebox-api.vercel.app/"))
        assertTrue(UpdateNetworkSupport.isOfficialBackendHost("https://LATERBOX-moviebox-api.vercel.app/updates/laterbox/latest"))
        assertFalse(UpdateNetworkSupport.isOfficialBackendHost("https://letterboxd-moviebox-api.vercel.app/"))
        assertFalse(UpdateNetworkSupport.isOfficialBackendHost("not a url"))
        assertEquals(
            "https://laterbox-moviebox-api.vercel.app/",
            "https://${UpdateNetworkSupport.OFFICIAL_BACKEND_HOST}/"
        )
    }

    // ---- 13: classification and messages -------------------------------------------------

    @Test
    fun `throwables and http statuses are classified`() {
        assertEquals(UpdateFailureKind.DNS, UpdateNetworkSupport.classifyThrowable(UnknownHostException("x")))
        assertEquals(UpdateFailureKind.TIMEOUT, UpdateNetworkSupport.classifyThrowable(SocketTimeoutException()))
        assertEquals(UpdateFailureKind.CONNECT, UpdateNetworkSupport.classifyThrowable(ConnectException()))
        assertEquals(UpdateFailureKind.INTERRUPTED, UpdateNetworkSupport.classifyThrowable(java.io.IOException()))
        assertEquals(UpdateFailureKind.AUTH, UpdateNetworkSupport.classifyHttpStatus(401))
        assertEquals(UpdateFailureKind.AUTH, UpdateNetworkSupport.classifyHttpStatus(403))
        assertEquals(UpdateFailureKind.EXPIRED_URL, UpdateNetworkSupport.classifyHttpStatus(410))
        assertEquals(UpdateFailureKind.RATE_LIMIT, UpdateNetworkSupport.classifyHttpStatus(429))
        assertEquals(UpdateFailureKind.SERVER, UpdateNetworkSupport.classifyHttpStatus(503))
        assertEquals(UpdateFailureKind.CONFIG, UpdateNetworkSupport.classifyHttpStatus(404))
        assertTrue(UpdateFailureKind.DNS.isBackendUnreachable)
        assertFalse(UpdateFailureKind.AUTH.isBackendUnreachable)
    }

    @Test
    fun `user messages never leak hosts or urls`() {
        UpdateFailureKind.values().forEach {
            val msg = UpdateNetworkSupport.userMessage(it, 503)
            assertFalse(msg, msg.contains("vercel", ignoreCase = true))
            assertFalse(msg, msg.contains("://"))
            assertTrue(msg.isNotBlank())
        }
        assertTrue(UpdateNetworkSupport.userMessage(UpdateFailureKind.DNS).contains("Private DNS"))
    }

    @Test
    fun `permanent errors are never retried and dns retries are bounded`() {
        listOf(UpdateFailureKind.AUTH, UpdateFailureKind.RATE_LIMIT, UpdateFailureKind.CONFIG, UpdateFailureKind.EXPIRED_URL)
            .forEach { assertFalse(UpdateNetworkSupport.shouldRetry(it, 1, 1)) }
        assertTrue(UpdateNetworkSupport.shouldRetry(UpdateFailureKind.DNS, 1, 1))
        assertFalse(UpdateNetworkSupport.shouldRetry(UpdateFailureKind.DNS, 2, 2))
        assertFalse(UpdateNetworkSupport.shouldRetry(UpdateFailureKind.INTERRUPTED, 1, UpdateNetworkSupport.MAX_TOTAL_ATTEMPTS))
    }

    @Test
    fun `asset url allowlist accepts github hosts and rejects others`() {
        assertTrue(UpdateNetworkSupport.isAllowedAssetUrl("https://github.com/Adetisola/Laterbox/releases/download/v1/a.apk"))
        assertTrue(UpdateNetworkSupport.isAllowedAssetUrl("https://release-assets.githubusercontent.com/x"))
        assertTrue(UpdateNetworkSupport.isAllowedAssetUrl("http://127.0.0.1:1/x"))
        assertFalse(UpdateNetworkSupport.isAllowedAssetUrl("http://github.com/x"))
        assertFalse(UpdateNetworkSupport.isAllowedAssetUrl("https://evil.example.com/x"))
        assertFalse(UpdateNetworkSupport.isAllowedAssetUrl("https://githubusercontent.com.evil.com/x"))
        assertNull(UpdateNetworkSupport.publicAssetUrl("1.0.1", "../x.apk"))
        assertEquals(
            "https://github.com/Adetisola/Laterbox/releases/download/v1.0.58/Laterbox-ARM64.apk",
            UpdateNetworkSupport.publicAssetUrl("1.0.58", "Laterbox-ARM64.apk")
        )
    }

    // ---- 3 & 5: backend DNS failure, persistent ------------------------------------------

    @Test
    fun `persistent backend dns failure makes exactly two attempts with one backoff`() {
        val backend = MockWebServer().apply { start() }
        try {
            val count = failConnections({ it.port == backend.port }, { UnknownHostException("simulated") })
            val (file, events) = downloadErrors(backend.url("/updates/laterbox/assets/1/download-url").toString(), null)

            assertNull(file)
            assertEquals(2, count())
            assertEquals(listOf(1000L), sleeps)
            val err = events.last() as ApkInstallerHelper.DownloadProgress.Error
            assertEquals(UpdateFailureKind.DNS, err.kind)
            assertFalse(err.isAuthOrRateLimit)
            assertFalse(err.message.contains("://"))
        } finally {
            backend.shutdown()
        }
    }

    // ---- 4: transient DNS recovery -------------------------------------------------------

    @Test
    fun `transient dns failure recovers on the second attempt`() {
        val cdn = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
            start()
        }
        val backend = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"download_url":"${cdn.url("/asset")}"}"""))
            start()
        }
        try {
            failConnections({ it.port == backend.port }, { UnknownHostException("simulated") }, times = 1)
            val (file, _) = downloadErrors(backend.url("/updates/laterbox/assets/2/download-url").toString(), null)
            assertNotNull(file)
            assertArrayEquals(payload, file!!.readBytes())
            assertEquals(listOf(1000L), sleeps)
        } finally {
            backend.shutdown(); cdn.shutdown()
        }
    }

    // ---- 7 & 12: backend down -> public GitHub fallback ----------------------------------

    @Test
    fun `backend dns failure falls back to public release download with integrity intact`() {
        val publicServer = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
            start()
        }
        val backend = MockWebServer().apply { start() }
        try {
            failConnections({ it.port == backend.port }, { UnknownHostException("simulated") })
            val (file, events) = downloadErrors(
                backend.url("/updates/laterbox/assets/3/download-url").toString(),
                publicServer.url("/Adetisola/Laterbox/releases/download/v9.9.9/Laterbox-ARM64.apk").toString()
            )
            assertNotNull(file)
            assertArrayEquals(payload, file!!.readBytes())
            assertTrue(events.last() is ApkInstallerHelper.DownloadProgress.Completed)
            assertEquals(1, publicServer.requestCount)
            assertNull(publicServer.takeRequest().getHeader("X-Firebase-AppCheck"))
        } finally {
            backend.shutdown(); publicServer.shutdown()
        }
    }

    @Test
    fun `backend auth failure never triggers public fallback`() {
        val publicServer = MockWebServer().apply { start() }
        val backend = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":{"code":"github_auth_failed","message":"nope"}}"""))
            start()
        }
        try {
            val (file, events) = downloadErrors(
                backend.url("/updates/laterbox/assets/4/download-url").toString(),
                publicServer.url("/x.apk").toString()
            )
            assertNull(file)
            assertEquals(0, publicServer.requestCount)
            val err = events.last() as ApkInstallerHelper.DownloadProgress.Error
            assertTrue(err.isAuthOrRateLimit)
            assertTrue(sleeps.isEmpty())
        } finally {
            backend.shutdown(); publicServer.shutdown()
        }
    }

    @Test
    fun `backend 429 is not retried and does not fall back`() {
        val publicServer = MockWebServer().apply { start() }
        val backend = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"slow down"}"""))
            start()
        }
        try {
            val (file, events) = downloadErrors(
                backend.url("/updates/laterbox/assets/5/download-url").toString(),
                publicServer.url("/x.apk").toString()
            )
            assertNull(file)
            assertEquals(1, backend.requestCount)
            assertEquals(0, publicServer.requestCount)
            assertEquals(UpdateFailureKind.RATE_LIMIT, (events.last() as ApkInstallerHelper.DownloadProgress.Error).kind)
        } finally {
            backend.shutdown(); publicServer.shutdown()
        }
    }

    // ---- 8: slow CDN ---------------------------------------------------------------------

    @Test
    fun `slow cdn still completes without extra retries`() {
        val cdn = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
                    .throttleBody(100_000, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            )
            start()
        }
        try {
            val (file, _) = downloadErrors(cdn.url("/asset.apk").toString(), null)
            assertNotNull(file)
            assertEquals(1, cdn.requestCount)
            assertTrue(sleeps.isEmpty())
        } finally {
            cdn.shutdown()
        }
    }

    // ---- 10: interrupted download resumes with Range ---------------------------------------

    @Test
    fun `existing partial file is resumed with a Range request`() {
        val half = payload.size / 2
        val updates = File(context.cacheDir, "updates").apply { mkdirs() }
        File(updates, ApkInstallerHelper.getApkFileName("9.9.9", "Laterbox-ARM64.apk"))
            .writeBytes(payload.copyOfRange(0, half))
        val cdn = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $half-${payload.size - 1}/${payload.size}")
                    .setBody(Buffer().write(payload.copyOfRange(half, payload.size)))
            )
            start()
        }
        try {
            val (file, _) = downloadErrors(cdn.url("/asset.apk").toString(), null)
            assertArrayEquals(payload, file!!.readBytes())
            assertEquals("bytes=$half-", cdn.takeRequest().getHeader("Range"))
        } finally {
            cdn.shutdown()
        }
    }

    // ---- CDN status policy -----------------------------------------------------------------

    @Test
    fun `cdn 404 and 429 are single attempts and 5xx is bounded`() {
        fun attemptsFor(code: Int): Triple<Int, Int, UpdateFailureKind> {
            sleeps.clear()
            val cdn = MockWebServer().apply {
                repeat(10) { enqueue(MockResponse().setResponseCode(code)) }
                start()
            }
            try {
                val (file, events) = downloadErrors(cdn.url("/asset.apk").toString(), null)
                assertNull(file)
                return Triple(cdn.requestCount, sleeps.size, (events.last() as ApkInstallerHelper.DownloadProgress.Error).kind)
            } finally {
                cdn.shutdown()
            }
        }
        assertEquals(Triple(1, 0, UpdateFailureKind.CONFIG), attemptsFor(404))
        assertEquals(Triple(1, 0, UpdateFailureKind.RATE_LIMIT), attemptsFor(429))
        val (requests, backoffs, kind) = attemptsFor(503)
        assertEquals(3, requests)
        assertEquals(2, backoffs)
        assertEquals(UpdateFailureKind.SERVER, kind)
    }

    @Test
    fun `redirect to a disallowed host is blocked without retry`() {
        val cdn = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example.com/a.apk"))
            start()
        }
        try {
            val (file, events) = downloadErrors(cdn.url("/asset.apk").toString(), null)
            assertNull(file)
            assertEquals(1, cdn.requestCount)
            assertEquals(UpdateFailureKind.CONFIG, (events.last() as ApkInstallerHelper.DownloadProgress.Error).kind)
        } finally {
            cdn.shutdown()
        }
    }

    @Test
    fun `relative redirects are followed`() {
        val cdn = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/real.apk"))
            enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
            start()
        }
        try {
            val (file, _) = downloadErrors(cdn.url("/asset.apk").toString(), null)
            assertArrayEquals(payload, file!!.readBytes())
        } finally {
            cdn.shutdown()
        }
    }

    // ---- 11: delta patch verification ------------------------------------------------------

    @Test
    fun `patch with wrong sha256 is rejected and deleted`() = runBlocking {
        val cdn = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
            start()
        }
        try {
            val patch = DeltaPatchInfo(
                architecture = "arm64-v8a", architectureLabel = "ARM64", baseVersion = "9.9.8",
                baseVersionCode = 1L, baseApkSha256 = "a".repeat(64),
                targetApkFilename = "Laterbox-ARM64.apk", targetApkSha256 = "b".repeat(64),
                targetApkSize = 1L, patchFilename = "Laterbox-ARM64-9.9.8-to-9.9.9.patch",
                patchSha256 = "c".repeat(64), patchSize = payload.size.toLong(),
                patchAlgorithm = "BSDIFF40", downloadUrl = cdn.url("/p.patch").toString()
            )
            val events = mutableListOf<ApkInstallerHelper.DownloadProgress>()
            val result = ApkInstallerHelper.downloadPatch(context, patch, null, null) { events.add(it) }
            assertNull(result)
            assertFalse(File(File(context.cacheDir, "updates"), patch.patchFilename).exists())
            val err = events.last() as ApkInstallerHelper.DownloadProgress.Error
            assertFalse(err.isAuthOrRateLimit)
            assertTrue(err.message.contains("SHA-256"))
        } finally {
            cdn.shutdown()
        }
    }

    // ---- checker fallback ------------------------------------------------------------------

    private fun releaseJson(tag: String) = """
        {"tag_name":"$tag","name":"Laterbox $tag","body":"notes","html_url":"https://github.com/Adetisola/Laterbox/releases/tag/$tag",
         "draft":false,"prerelease":false,
         "assets":[{"name":"Laterbox-ARM64.apk","browser_download_url":"https://github.com/Adetisola/Laterbox/releases/download/$tag/Laterbox-ARM64.apk","size":1000}]}
    """.trimIndent()

    private fun check(backendUrl: String, publicUrl: String?, force: Boolean = false) = runBlocking {
        GitHubUpdateChecker.checkForUpdate(
            currentVersionName = "1.0.0",
            releasesUrl = backendUrl,
            deviceSupportedAbis = arrayOf("arm64-v8a"),
            context = null,
            appCheckTokenProvider = { "token" },
            forceRefresh = force,
            publicFallbackUrl = publicUrl
        )
    }

    @Test
    fun `update check falls back to public github when backend dns fails and caches it`() {
        val publicServer = MockWebServer().apply {
            enqueue(MockResponse().setBody(releaseJson("v9.9.9")))
            start()
        }
        val backend = MockWebServer().apply { start() }
        try {
            failConnections({ it.port == backend.port }, { UnknownHostException("simulated") })
            val backendUrl = backend.url("/updates/laterbox/latest").toString()
            val publicUrl = publicServer.url("/repos/Adetisola/Laterbox/releases/latest").toString()

            val first = check(backendUrl, publicUrl)
            assertTrue(first.toString(), first is UpdateCheckResult.UpdateAvailable)
            assertEquals("9.9.9", (first as UpdateCheckResult.UpdateAvailable).updateInfo.versionName)
            assertNull(publicServer.takeRequest().getHeader("X-Firebase-AppCheck"))

            val second = check(backendUrl, publicUrl)
            assertTrue(second is UpdateCheckResult.UpdateAvailable)
            assertEquals("cached, no second public request", 1, publicServer.requestCount)
        } finally {
            backend.shutdown(); publicServer.shutdown()
        }
    }

    @Test
    fun `update check does not fall back on auth or rate limit errors`() {
        val publicServer = MockWebServer().apply { start() }
        val backend = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad token"}"""))
            enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad token"}"""))
            enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"slow"}"""))
            start()
        }
        try {
            val backendUrl = backend.url("/updates/laterbox/latest").toString()
            val publicUrl = publicServer.url("/latest").toString()
            assertTrue(check(backendUrl, publicUrl) is UpdateCheckResult.AuthError)
            assertTrue(check(backendUrl, publicUrl) is UpdateCheckResult.RateLimited)
            assertEquals(0, publicServer.requestCount)
        } finally {
            backend.shutdown(); publicServer.shutdown()
        }
    }

    @Test
    fun `update check reports a network error when both backend and public fail`() {
        val publicServer = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(500))
            start()
        }
        val backend = MockWebServer().apply { start() }
        try {
            failConnections({ it.port == backend.port }, { UnknownHostException("simulated") })
            val result = check(
                backend.url("/updates/laterbox/latest").toString(),
                publicServer.url("/latest").toString()
            )
            assertTrue(result.toString(), result is UpdateCheckResult.NetworkError)
            assertTrue((result as UpdateCheckResult.NetworkError).cause is UnknownHostException)
        } finally {
            backend.shutdown(); publicServer.shutdown()
        }
    }

    @Test
    fun `no public fallback for non-backend update urls`() {
        val publicServer = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() }
        try {
            failConnections({ it.port == other.port }, { UnknownHostException("simulated") })
            val result = check(other.url("/something/else").toString(), publicServer.url("/latest").toString())
            assertTrue(result is UpdateCheckResult.NetworkError)
            assertEquals(0, publicServer.requestCount)
        } finally {
            other.shutdown(); publicServer.shutdown()
        }
    }
}
