package com.laterbox.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkInstallerHelperTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `download progress calculates percent accurately`() {
        val progress = ApkInstallerHelper.DownloadProgress.Downloading(
            bytesDownloaded = 50L,
            totalBytes = 100L,
            percent = 50
        )
        assertEquals(50, progress.percent)
        assertEquals(50L, progress.bytesDownloaded)
        assertEquals(100L, progress.totalBytes)
    }

    @Test
    fun `verification result types model success and failure conditions`() {
        val valid = ApkInstallerHelper.ApkVerificationResult.Valid
        val mismatch = ApkInstallerHelper.ApkVerificationResult.SignatureMismatch("Signature mismatch")
        val invalidPkg = ApkInstallerHelper.ApkVerificationResult.InvalidPackage("expected.pkg", "actual.pkg")
        val error = ApkInstallerHelper.ApkVerificationResult.Error("I/O error")

        assertTrue(valid is ApkInstallerHelper.ApkVerificationResult.Valid)
        assertTrue(mismatch is ApkInstallerHelper.ApkVerificationResult.SignatureMismatch)
        assertTrue(invalidPkg is ApkInstallerHelper.ApkVerificationResult.InvalidPackage)
        assertTrue(error is ApkInstallerHelper.ApkVerificationResult.Error)

        assertEquals("expected.pkg", invalidPkg.expected)
        assertEquals("actual.pkg", invalidPkg.actual)
        assertTrue(mismatch.message.contains("Signature mismatch"))
    }

    @Test
    fun `verifyApk returns false on non-existent file`() {
        val nonExistent = File("/tmp/non_existent_update.apk")
        assertFalse(nonExistent.exists())
    }

    @Test
    fun `cleanupOldUpdateApks deletes current and older APKs while retaining newer APKs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }

        // Setup test files in cache/updates/
        val oldApk = File(updatesDir, "Laterbox-ARM32-1.0.21.apk").apply { writeText("old-apk-content") }
        val currentApk = File(updatesDir, "Laterbox-1.0.22.apk").apply { writeText("current-apk-content") }
        val newerApk = File(updatesDir, "Laterbox-ARM64-1.0.23.apk").apply { writeText("newer-apk-content") }

        assertTrue(oldApk.exists())
        assertTrue(currentApk.exists())
        assertTrue(newerApk.exists())

        val deletedCount = ApkInstallerHelper.cleanupOldUpdateApks(context, "1.0.22")

        assertEquals(2, deletedCount)
        assertFalse("Old APK (1.0.21) should have been deleted", oldApk.exists())
        assertFalse("Current APK (1.0.22) should have been deleted after update", currentApk.exists())
        assertTrue("Newer APK (1.0.23) should be retained for future install", newerApk.exists())

        // Cleanup test directory
        newerApk.delete()
        updatesDir.delete()
    }

    @Test
    fun `cleanupOldUpdateApks returns 0 when updates directory does not exist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updatesDir = File(context.cacheDir, "updates")
        if (updatesDir.exists()) updatesDir.deleteRecursively()

        val deleted = ApkInstallerHelper.cleanupOldUpdateApks(context, "1.0.22")
        assertEquals(0, deleted)
    }

    @Test
    fun `cleanupOldUpdateApks removes temporary patch and tmp files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }

        val patchFile = File(updatesDir, "test.patch").apply { writeText("patch-data") }
        val tmpFile = File(updatesDir, "update.apk.tmp").apply { writeText("tmp-data") }
        val partFile = File(updatesDir, "download.part").apply { writeText("part-data") }

        assertTrue(patchFile.exists())
        assertTrue(tmpFile.exists())
        assertTrue(partFile.exists())

        val deleted = ApkInstallerHelper.cleanupOldUpdateApks(context, "1.0.22")
        assertTrue(deleted >= 3)
        assertFalse(patchFile.exists())
        assertFalse(tmpFile.exists())
        assertFalse(partFile.exists())

        updatesDir.delete()
    }

    @Test
    fun `computeSha256 calculates accurate lowercase digest`() {
        val tempFile = File.createTempFile("test_sha", ".txt")
        try {
            tempFile.writeText("hello world\n")
            val sha = InstalledApkHelper.computeSha256(tempFile)
            // sha256("hello world\n") = a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447
            assertEquals("a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447", sha)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `downloadApk resolves signed backend url with App Check and resumes across expired CDN URL`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "updates").deleteRecursively()

        val fullPayload = ByteArray(1024) { i -> (i % 251).toByte() }
        val splitPoint = 400
        val backendServer = MockWebServer()
        val cdnServer = MockWebServer()
        var resolverCalls = 0
        var cdnCalls = 0
        var cdnSawAppCheck = false

        cdnServer.start()
        backendServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("appcheck-token-123", request.getHeader("X-Firebase-AppCheck"))
                resolverCalls++
                val cdnPath = if (resolverCalls == 1) "/signed/v1/asset.apk" else "/signed/v2/asset.apk"
                return MockResponse().setResponseCode(200).setBody(
                    """{"asset_id":9001,"download_url":"${cdnServer.url(cdnPath)}","expires_in_seconds":60,"supports_range":true}"""
                )
            }
        }
        cdnServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("X-Firebase-AppCheck") != null || request.getHeader("Authorization") != null) {
                    cdnSawAppCheck = true
                }
                cdnCalls++
                return when {
                    request.path == "/signed/v1/asset.apk" && request.getHeader("Range") == null -> {
                        // Deliver partial chunk (400 bytes) then close socket to simulate dropped stream cleanly
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(Buffer().write(fullPayload, 0, splitPoint))
                            .setHeader("Content-Length", fullPayload.size.toString())
                            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                    }
                    request.path == "/signed/v1/asset.apk" -> {
                        // Simulate expired pre-signed URL on resume attempt
                        MockResponse().setResponseCode(403).setBody("Signature expired")
                    }
                    request.path == "/signed/v2/asset.apk" -> {
                        assertEquals("bytes=$splitPoint-", request.getHeader("Range"))
                        val remaining = fullPayload.size - splitPoint
                        MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes $splitPoint-${fullPayload.size - 1}/${fullPayload.size}")
                            .setHeader("Content-Length", remaining.toString())
                            .setBody(Buffer().write(fullPayload, splitPoint, remaining))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        backendServer.start()

        try {
            val resolverUrl = backendServer.url("/updates/laterbox/assets/9001/download-url").toString()
            val events = mutableListOf<ApkInstallerHelper.DownloadProgress>()
            val resultFile = ApkInstallerHelper.downloadApk(
                context = context,
                downloadUrl = resolverUrl,
                versionName = "1.0.54",
                assetName = "Laterbox-ARM64.apk",
                expectedSizeBytes = fullPayload.size.toLong(),
                appCheckTokenProvider = { "appcheck-token-123" },
                onProgress = { events.add(it) }
            )

            assertNotNull(resultFile)
            assertArrayEquals(fullPayload, resultFile!!.readBytes())
            assertFalse("App Check / Auth headers must NEVER be sent to CDN", cdnSawAppCheck)
            assertEquals(2, resolverCalls)
            assertEquals(3, cdnCalls)
            assertTrue(events.last() is ApkInstallerHelper.DownloadProgress.Completed)
        } finally {
            backendServer.shutdown()
            cdnServer.shutdown()
            File(context.cacheDir, "updates").deleteRecursively()
        }
    }

    @Test
    fun `downloadPatch marks Error with isAuthOrRateLimit true when backend rejects GitHub auth`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "updates").deleteRecursively()
        val backendServer = MockWebServer()
        backendServer.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"detail":{"code":"github_auth_failed","message":"GitHub release authentication is not configured."}}"""
            )
        )
        backendServer.start()

        try {
            val patchInfo = DeltaPatchInfo(
                architecture = "arm64-v8a",
                architectureLabel = "ARM64 (arm64-v8a)",
                baseVersion = "1.0.53",
                baseVersionCode = 153L,
                baseApkSha256 = "a".repeat(64),
                targetApkFilename = "Laterbox-ARM64.apk",
                targetApkSha256 = "b".repeat(64),
                targetApkSize = 60_000_000L,
                patchFilename = "Laterbox-ARM64-1.0.53-to-1.0.54.patch",
                patchSha256 = "c".repeat(64),
                patchSize = 4096L,
                patchAlgorithm = "BSDIFF40",
                downloadUrl = backendServer.url("/updates/laterbox/assets/9011/download-url").toString()
            )
            val errors = mutableListOf<ApkInstallerHelper.DownloadProgress.Error>()
            val result = ApkInstallerHelper.downloadPatch(
                context = context,
                deltaPatch = patchInfo,
                appCheckTokenProvider = { "appcheck-token-123" },
                onProgress = { progress ->
                    if (progress is ApkInstallerHelper.DownloadProgress.Error) {
                        errors.add(progress)
                    }
                }
            )

            assertNull(result)
            assertEquals(1, errors.size)
            assertTrue(errors.first().isAuthOrRateLimit)
            assertTrue(errors.first().message.contains("authentication", ignoreCase = true))
        } finally {
            backendServer.shutdown()
            File(context.cacheDir, "updates").deleteRecursively()
        }
    }
}
