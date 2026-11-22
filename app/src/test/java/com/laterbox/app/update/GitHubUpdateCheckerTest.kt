package com.laterbox.app.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubUpdateCheckerTest {

    private val sampleManifestJson = """
    {
      "schemaVersion": 1,
      "targetVersion": "1.0.35",
      "targetVersionCode": 135,
      "generatedAt": 1731420000000,
      "patches": [
        {
          "architecture": "arm64-v8a",
          "architectureLabel": "ARM64 (arm64-v8a)",
          "baseVersion": "1.0.34",
          "baseVersionCode": 134,
          "baseApkSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "targetApkFilename": "Laterbox-ARM64.apk",
          "targetApkSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
          "targetApkSize": 60000000,
          "patchFilename": "Laterbox-ARM64-1.0.34-to-1.0.35.patch",
          "patchSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
          "patchSize": 4500000,
          "patchAlgorithm": "BSDIFF40"
        },
        {
          "architecture": "armeabi-v7a",
          "architectureLabel": "ARM32 (armeabi-v7a)",
          "baseVersion": "1.0.34",
          "baseVersionCode": 134,
          "baseApkSha256": "1111111111111111111111111111111111111111111111111111111111111111",
          "targetApkFilename": "Laterbox-ARM32.apk",
          "targetApkSha256": "2222222222222222222222222222222222222222222222222222222222222222",
          "targetApkSize": 53000000,
          "patchFilename": "Laterbox-ARM32-1.0.34-to-1.0.35.patch",
          "patchSha256": "3333333333333333333333333333333333333333333333333333333333333333",
          "patchSize": 4200000,
          "patchAlgorithm": "BSDIFF40"
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `fromJson parses valid delta manifest correctly`() {
        val manifest = DeltaManifest.fromJson(sampleManifestJson)
        assertNotNull(manifest)
        assertEquals(1, manifest!!.schemaVersion)
        assertEquals("1.0.35", manifest.targetVersion)
        assertEquals(135L, manifest.targetVersionCode)
        assertEquals(2, manifest.patches.size)

        val arm64 = manifest.patches[0]
        assertEquals("arm64-v8a", arm64.architecture)
        assertEquals("1.0.34", arm64.baseVersion)
        assertEquals("Laterbox-ARM64-1.0.34-to-1.0.35.patch", arm64.patchFilename)
        assertEquals(4500000L, arm64.patchSize)
        assertEquals("BSDIFF40", arm64.patchAlgorithm)
    }

    @Test
    fun `fromJson handles corrupted JSON safely and returns null`() {
        val corrupted = "{ schemaVersion: 1, targetVersion: "
        val manifest = DeltaManifest.fromJson(corrupted)
        assertNull(manifest)
    }

    @Test
    fun `findCompatiblePatch matches installed APK SHA-256 and ABI`() {
        val manifest = DeltaManifest.fromJson(sampleManifestJson)!!
        val installedSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a")

        val match = manifest.findCompatiblePatch(installedSha, supportedAbis)
        assertNotNull(match)
        assertEquals("arm64-v8a", match!!.architecture)
        assertEquals("Laterbox-ARM64-1.0.34-to-1.0.35.patch", match.patchFilename)
        assertEquals(4500000L, match.patchSize)
    }

    @Test
    fun `findCompatiblePatch returns null on base APK SHA-256 mismatch`() {
        val manifest = DeltaManifest.fromJson(sampleManifestJson)!!
        val modifiedInstalledSha = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        val supportedAbis = arrayOf("arm64-v8a")

        val match = manifest.findCompatiblePatch(modifiedInstalledSha, supportedAbis)
        assertNull(match)
    }

    @Test
    fun `findCompatiblePatch returns null on unsupported ABI`() {
        val manifest = DeltaManifest.fromJson(sampleManifestJson)!!
        val arm64Sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val x86Abis = arrayOf("x86_64", "x86")

        val match = manifest.findCompatiblePatch(arm64Sha, x86Abis)
        assertNull(match)
    }

    @Test
    fun `effective download size reflects delta patch when available and full APK otherwise`() {
        val patchInfo = DeltaPatchInfo(
            architecture = "arm64-v8a",
            architectureLabel = "ARM64 (arm64-v8a)",
            baseVersion = "1.0.34",
            baseVersionCode = 134L,
            baseApkSha256 = "base",
            targetApkFilename = "Laterbox-ARM64.apk",
            targetApkSha256 = "target",
            targetApkSize = 60_000_000L,
            patchFilename = "Laterbox-ARM64.patch",
            patchSha256 = "abc",
            patchSize = 4_500_000L,
            patchAlgorithm = "BSDIFF40",
            downloadUrl = "https://example.com/patch"
        )

        val deltaUpdate = AppUpdateInfo(
            versionName = "1.0.35",
            releaseTitle = "Laterbox v1.0.35",
            releaseNotes = "Notes",
            downloadUrl = "https://example.com/full.apk",
            releasePageUrl = "https://example.com/release",
            apkSize = 60_000_000L,
            targetAbiLabel = "ARM64",
            assetName = "Laterbox-ARM64.apk",
            deltaPatch = patchInfo
        )

        assertTrue(deltaUpdate.isDeltaAvailable)
        assertEquals(4_500_000L, deltaUpdate.effectiveDownloadSize)

        val fullOnlyUpdate = AppUpdateInfo(
            versionName = "1.0.35",
            releaseTitle = "Laterbox v1.0.35",
            releaseNotes = "Notes",
            downloadUrl = "https://example.com/full.apk",
            releasePageUrl = "https://example.com/release",
            apkSize = 60_000_000L,
            targetAbiLabel = "ARM64",
            assetName = "Laterbox-ARM64.apk",
            deltaPatch = null
        )

        assertFalse(fullOnlyUpdate.isDeltaAvailable)
        assertEquals(60_000_000L, fullOnlyUpdate.effectiveDownloadSize)
    }

    @Test
    fun `buildBackendUpdateUrl normalizes trailing slash and appends refresh query`() {
        assertEquals(
            "https://laterbox-moviebox-api.vercel.app/updates/laterbox/latest",
            GitHubUpdateChecker.buildBackendUpdateUrl("https://laterbox-moviebox-api.vercel.app/", forceRefresh = false)
        )
        assertEquals(
            "https://laterbox-moviebox-api.vercel.app/updates/laterbox/latest?refresh=1",
            GitHubUpdateChecker.buildBackendUpdateUrl("https://laterbox-moviebox-api.vercel.app", forceRefresh = true)
        )
    }

    @Test
    fun `backend release check sends App Check header retries on 401 and resolves download_path`() = runBlocking {
        val server = MockWebServer()
        val seenTokens = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenTokens.add(request.getHeader("X-Firebase-AppCheck"))
                return if (seenTokens.size == 1) {
                    MockResponse().setResponseCode(401).setBody("""{"detail":"expired app check token"}""")
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "tag_name": "v1.0.54",
                          "name": "Laterbox v1.0.54",
                          "body": "Private repo update notes",
                          "html_url": "https://github.com/Adetisola/Laterbox/releases/tag/v1.0.54",
                          "draft": false,
                          "prerelease": false,
                          "assets": [
                            {
                              "id": 9001,
                              "name": "Laterbox-ARM64.apk",
                              "size": 61416178,
                              "download_path": "/updates/laterbox/assets/9001/download-url"
                            },
                            {
                              "id": 9002,
                              "name": "Laterbox-ARM32.apk",
                              "size": 53000000,
                              "download_path": "/updates/laterbox/assets/9002/download-url"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
            }
        }
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val result = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.53",
                releasesUrl = GitHubUpdateChecker.buildBackendUpdateUrl(baseUrl, forceRefresh = true),
                deviceSupportedAbis = arrayOf("arm64-v8a", "armeabi-v7a"),
                appCheckTokenProvider = { force -> if (force) "fresh-appcheck" else "stale-appcheck" },
                forceRefresh = true
            )

            assertTrue(result is UpdateCheckResult.UpdateAvailable)
            val info = (result as UpdateCheckResult.UpdateAvailable).updateInfo
            assertEquals("1.0.54", info.versionName)
            assertEquals("Private repo update notes", info.releaseNotes)
            assertEquals("Laterbox-ARM64.apk", info.assetName)
            assertEquals("$baseUrl/updates/laterbox/assets/9001/download-url", info.downloadUrl)
            assertEquals(listOf("stale-appcheck", "fresh-appcheck"), seenTokens)
            val secondReq = server.takeRequest().also { server.takeRequest() }
            assertNotNull(secondReq)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 503 github_auth_failed maps to AuthError and never UpToDate`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"detail":{"code":"github_auth_failed","message":"GitHub release authentication is not configured on the server."}}"""
            )
        )
        server.start()
        try {
            val url = GitHubUpdateChecker.buildBackendUpdateUrl(server.url("/").toString())
            val result = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.53",
                releasesUrl = url,
                appCheckTokenProvider = { "appcheck-tok" }
            )
            assertTrue(result is UpdateCheckResult.AuthError)
            assertFalse(result is UpdateCheckResult.UpToDate)
            val auth = result as UpdateCheckResult.AuthError
            assertEquals(503, auth.statusCode)
            assertTrue(auth.message.contains("authentication", ignoreCase = true))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 429 and 403 rate limit responses map to RateLimited`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "45")
                .setBody("""{"detail":{"code":"github_rate_limited","message":"Rate limit reached","retry_after_seconds":45}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("Retry-After", "30")
                .setBody("""{"message":"API rate limit exceeded"}""")
        )
        server.start()
        try {
            val url = GitHubUpdateChecker.buildBackendUpdateUrl(server.url("/").toString())
            val r1 = GitHubUpdateChecker.checkForUpdate("1.0.53", releasesUrl = url)
            assertTrue(r1 is UpdateCheckResult.RateLimited)
            assertEquals(45, (r1 as UpdateCheckResult.RateLimited).retryAfterSeconds)

            val r2 = GitHubUpdateChecker.checkForUpdate("1.0.53", releasesUrl = url)
            assertTrue(r2 is UpdateCheckResult.RateLimited)
            assertEquals(30, (r2 as UpdateCheckResult.RateLimited).retryAfterSeconds)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 404 and same-version responses are distinct and missing arm32 asset returns NoCompatibleApk`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"detail":{"code":"release_not_found","message":"No published stable release found in repository."}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "v1.0.53",
                  "name": "Laterbox v1.0.53",
                  "assets": [{"id": 1, "name": "Laterbox-ARM64.apk", "size": 1000, "download_path": "/updates/laterbox/assets/1/download-url"}]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "tag_name": "v1.0.54",
                  "name": "Laterbox v1.0.54",
                  "assets": [{"id": 1, "name": "Laterbox-ARM64.apk", "size": 1000, "download_path": "/updates/laterbox/assets/1/download-url"}]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val url = GitHubUpdateChecker.buildBackendUpdateUrl(server.url("/").toString())
            val notFound = GitHubUpdateChecker.checkForUpdate("1.0.53", releasesUrl = url)
            assertTrue(notFound is UpdateCheckResult.ApiError)
            assertEquals(404, (notFound as UpdateCheckResult.ApiError).statusCode)

            val upToDate = GitHubUpdateChecker.checkForUpdate("1.0.53", releasesUrl = url)
            assertTrue(upToDate is UpdateCheckResult.UpToDate)

            val incompatible = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.53",
                releasesUrl = url,
                deviceSupportedAbis = arrayOf("armeabi-v7a")
            )
            assertTrue(incompatible is UpdateCheckResult.NoCompatibleApk)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend inline delta_manifest selects compatible patch asset and wires backend resolver url`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val fakeInstalledApk = java.io.File.createTempFile("installed_base", ".apk")
        fakeInstalledApk.writeText("fake-installed-apk-bytes-1.0.53")
        val prevSourceDir = context.applicationInfo.sourceDir
        context.applicationInfo.sourceDir = fakeInstalledApk.absolutePath

        val server = MockWebServer()
        try {
            val installedSha = InstalledApkHelper.computeSha256(fakeInstalledApk)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.54",
                      "name": "Laterbox v1.0.54",
                      "body": "Delta + APK",
                      "assets": [
                        {
                          "id": 9001,
                          "name": "Laterbox-ARM64.apk",
                          "size": 60000000,
                          "download_path": "/updates/laterbox/assets/9001/download-url"
                        },
                        {
                          "id": 9011,
                          "name": "Laterbox-ARM64-1.0.53-to-1.0.54.patch",
                          "size": 4500000,
                          "download_path": "/updates/laterbox/assets/9011/download-url"
                        }
                      ],
                      "delta_manifest": {
                        "schemaVersion": 1,
                        "targetVersion": "1.0.54",
                        "targetVersionCode": 154,
                        "generatedAt": 1731420000000,
                        "patches": [
                          {
                            "architecture": "arm64-v8a",
                            "architectureLabel": "ARM64 (arm64-v8a)",
                            "baseVersion": "1.0.53",
                            "baseVersionCode": 153,
                            "baseApkSha256": "$installedSha",
                            "targetApkFilename": "Laterbox-ARM64.apk",
                            "targetApkSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            "targetApkSize": 60000000,
                            "patchFilename": "Laterbox-ARM64-1.0.53-to-1.0.54.patch",
                            "patchSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                            "patchSize": 4500000,
                            "patchAlgorithm": "BSDIFF40"
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            )
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            val result = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.53",
                releasesUrl = GitHubUpdateChecker.buildBackendUpdateUrl(baseUrl),
                deviceSupportedAbis = arrayOf("arm64-v8a"),
                context = context,
                appCheckTokenProvider = { "tok" }
            )

            assertTrue(result is UpdateCheckResult.UpdateAvailable)
            val info = (result as UpdateCheckResult.UpdateAvailable).updateInfo
            assertTrue(info.isDeltaAvailable)
            assertEquals("Laterbox-ARM64-1.0.53-to-1.0.54.patch", info.deltaPatch?.patchFilename)
            assertEquals("$baseUrl/updates/laterbox/assets/9011/download-url", info.deltaPatch?.downloadUrl)
            assertEquals(4500000L, info.effectiveDownloadSize)
            assertEquals(DeltaDiagnosticReason.SELECTED, info.deltaDiagnosticReason)
        } finally {
            context.applicationInfo.sourceDir = prevSourceDir
            fakeInstalledApk.delete()
            server.shutdown()
        }
    }

    private fun createFakeZipApk(abi: String): java.io.File {
        val file = java.io.File.createTempFile("installed_${abi}_", ".apk")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("lib/$abi/libpython.zip.so"))
            zos.write("so-bytes-$abi".toByteArray())
            zos.closeEntry()
        }
        return file
    }

    @Test
    fun `rolling manifest selects ARM32 patch on 64-bit device running ARM32 APK and aligns target asset`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val installedArm32Apk = createFakeZipApk("armeabi-v7a")
        val prevSourceDir = context.applicationInfo.sourceDir
        context.applicationInfo.sourceDir = installedArm32Apk.absolutePath
        val secretAppCheck = "secret-appcheck-token-value"

        val server = MockWebServer()
        try {
            val installedSha = InstalledApkHelper.computeSha256(installedArm32Apk)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.55",
                      "name": "Laterbox v1.0.55",
                      "body": "Rolling delta window",
                      "assets": [
                        {"id": 9101, "name": "Laterbox-ARM64.apk", "size": 61658218, "download_path": "/updates/laterbox/assets/9101/download-url"},
                        {"id": 9102, "name": "Laterbox-ARM32.apk", "size": 55004494, "download_path": "/updates/laterbox/assets/9102/download-url"},
                        {"id": 9201, "name": "Laterbox-ARM64-1.0.54-to-1.0.55.patch", "size": 4100000, "download_path": "/updates/laterbox/assets/9201/download-url"},
                        {"id": 9202, "name": "Laterbox-ARM32-1.0.53-to-1.0.55.patch", "size": 3800000, "download_path": "/updates/laterbox/assets/9202/download-url"}
                      ],
                      "delta_manifest": {
                        "schemaVersion": 1,
                        "targetVersion": "1.0.55",
                        "targetVersionCode": 168,
                        "generatedAt": 1795045554000,
                        "patches": [
                          {
                            "architecture": "arm64-v8a",
                            "baseArchitecture": "arm64-v8a",
                            "architectureLabel": "ARM64 (arm64-v8a)",
                            "baseVersion": "1.0.54",
                            "baseVersionCode": 167,
                            "baseApkSha256": "${"a".repeat(64)}",
                            "targetApkFilename": "Laterbox-ARM64.apk",
                            "targetApkSha256": "${"b".repeat(64)}",
                            "targetApkSize": 61658218,
                            "patchFilename": "Laterbox-ARM64-1.0.54-to-1.0.55.patch",
                            "patchSha256": "${"c".repeat(64)}",
                            "patchSize": 4100000,
                            "patchAlgorithm": "BSDIFF40"
                          },
                          {
                            "architecture": "armeabi-v7a",
                            "baseArchitecture": "armeabi-v7a",
                            "architectureLabel": "ARM32 (armeabi-v7a)",
                            "baseVersion": "1.0.53",
                            "baseVersionCode": 166,
                            "baseApkSha256": "$installedSha",
                            "targetApkFilename": "Laterbox-ARM32.apk",
                            "targetApkSha256": "${"e".repeat(64)}",
                            "targetApkSize": 55004494,
                            "patchFilename": "Laterbox-ARM32-1.0.53-to-1.0.55.patch",
                            "patchSha256": "${"f".repeat(64)}",
                            "patchSize": 3800000,
                            "patchAlgorithm": "BSDIFF40"
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            )
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            val result = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.53",
                releasesUrl = GitHubUpdateChecker.buildBackendUpdateUrl(baseUrl),
                deviceSupportedAbis = arrayOf("arm64-v8a", "armeabi-v7a"),
                context = context,
                appCheckTokenProvider = { secretAppCheck }
            )

            assertTrue(result is UpdateCheckResult.UpdateAvailable)
            val info = (result as UpdateCheckResult.UpdateAvailable).updateInfo
            assertEquals(DeltaDiagnosticReason.SELECTED, info.deltaDiagnosticReason)
            assertEquals("Laterbox-ARM32-1.0.53-to-1.0.55.patch", info.deltaPatch?.patchFilename)
            assertEquals("Laterbox-ARM32.apk", info.assetName)
            assertEquals("ARM32 (armeabi-v7a)", info.targetAbiLabel)
            assertEquals(55004494L, info.apkSize)
            assertEquals(3800000L, info.effectiveDownloadSize)
            assertEquals("$baseUrl/updates/laterbox/assets/9102/download-url", info.downloadUrl)
            assertEquals("$baseUrl/updates/laterbox/assets/9202/download-url", info.deltaPatch?.downloadUrl)
            assertFalse((info.deltaDiagnosticDetail ?: "").contains(secretAppCheck))
        } finally {
            context.applicationInfo.sourceDir = prevSourceDir
            installedArm32Apk.delete()
            server.shutdown()
        }
    }

    @Test
    fun `diagnostic reasons distinguish missing context, inaccessible APK, unavailable manifest, and missing patch asset`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val installedApk = createFakeZipApk("arm64-v8a")
        val prevSourceDir = context.applicationInfo.sourceDir
        val server = MockWebServer()
        try {
            val installedSha = InstalledApkHelper.computeSha256(installedApk)

            // 1. Null context -> INSTALLED_APK_CONTEXT_UNAVAILABLE
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.55",
                      "assets": [{"id": 1, "name": "Laterbox-ARM64.apk", "size": 60000000, "download_path": "/updates/laterbox/assets/1/download-url"}]
                    }
                    """.trimIndent()
                )
            )
            // 2. Inaccessible sourceDir -> INSTALLED_APK_INACCESSIBLE
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.55",
                      "assets": [{"id": 1, "name": "Laterbox-ARM64.apk", "size": 60000000, "download_path": "/updates/laterbox/assets/1/download-url"}]
                    }
                    """.trimIndent()
                )
            )
            // 3. Missing manifest -> INVALID_OR_UNAVAILABLE_MANIFEST
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.55",
                      "assets": [{"id": 1, "name": "Laterbox-ARM64.apk", "size": 60000000, "download_path": "/updates/laterbox/assets/1/download-url"}]
                    }
                    """.trimIndent()
                )
            )
            // 4. Matching manifest entry but missing patch asset -> PATCH_ASSET_UNAVAILABLE
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.55",
                      "assets": [{"id": 1, "name": "Laterbox-ARM64.apk", "size": 60000000, "download_path": "/updates/laterbox/assets/1/download-url"}],
                      "delta_manifest": {
                        "schemaVersion": 1,
                        "targetVersion": "1.0.55",
                        "targetVersionCode": 168,
                        "generatedAt": 1795045554000,
                        "patches": [
                          {
                            "architecture": "arm64-v8a",
                            "baseArchitecture": "arm64-v8a",
                            "architectureLabel": "ARM64 (arm64-v8a)",
                            "baseVersion": "1.0.53",
                            "baseVersionCode": 166,
                            "baseApkSha256": "$installedSha",
                            "targetApkFilename": "Laterbox-ARM64.apk",
                            "targetApkSha256": "${"b".repeat(64)}",
                            "targetApkSize": 60000000,
                            "patchFilename": "Laterbox-ARM64-1.0.53-to-1.0.55.patch",
                            "patchSha256": "${"c".repeat(64)}",
                            "patchSize": 4000000,
                            "patchAlgorithm": "BSDIFF40"
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            )

            server.start()
            val url = GitHubUpdateChecker.buildBackendUpdateUrl(server.url("/").toString())

            val r1 = GitHubUpdateChecker.checkForUpdate("1.0.53", url, arrayOf("arm64-v8a"), context = null)
            assertEquals(DeltaDiagnosticReason.INSTALLED_APK_CONTEXT_UNAVAILABLE, (r1 as UpdateCheckResult.UpdateAvailable).updateInfo.deltaDiagnosticReason)
            assertNull(r1.updateInfo.deltaPatch)

            context.applicationInfo.sourceDir = "/non/existent/laterbox.apk"
            val r2 = GitHubUpdateChecker.checkForUpdate("1.0.53", url, arrayOf("arm64-v8a"), context = context)
            assertEquals(DeltaDiagnosticReason.INSTALLED_APK_INACCESSIBLE, (r2 as UpdateCheckResult.UpdateAvailable).updateInfo.deltaDiagnosticReason)
            assertNull(r2.updateInfo.deltaPatch)

            context.applicationInfo.sourceDir = installedApk.absolutePath
            val r3 = GitHubUpdateChecker.checkForUpdate("1.0.53", url, arrayOf("arm64-v8a"), context = context)
            assertEquals(DeltaDiagnosticReason.INVALID_OR_UNAVAILABLE_MANIFEST, (r3 as UpdateCheckResult.UpdateAvailable).updateInfo.deltaDiagnosticReason)
            assertNull(r3.updateInfo.deltaPatch)

            val r4 = GitHubUpdateChecker.checkForUpdate("1.0.53", url, arrayOf("arm64-v8a"), context = context)
            assertEquals(DeltaDiagnosticReason.PATCH_ASSET_UNAVAILABLE, (r4 as UpdateCheckResult.UpdateAvailable).updateInfo.deltaDiagnosticReason)
            assertNull(r4.updateInfo.deltaPatch)
        } finally {
            context.applicationInfo.sourceDir = prevSourceDir
            installedApk.delete()
            server.shutdown()
        }
    }

    @Test
    fun `backend update check sends X-Laterbox version headers and detects higher versionCode cleanly`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.66",
                      "versionCode": 205,
                      "assets": [{"id": 7, "name": "Laterbox-Universal.apk", "size": 50000000, "download_path": "/updates/laterbox/assets/7/download-url"}]
                    }
                    """.trimIndent()
                )
            )
            server.start()
            val url = GitHubUpdateChecker.buildBackendUpdateUrl(server.url("/").toString())
            val res = GitHubUpdateChecker.checkForUpdate(
                currentVersionName = "1.0.66",
                releasesUrl = url,
                deviceSupportedAbis = arrayOf("arm64-v8a"),
                context = null,
                currentVersionCode = 199,
            )
            val req = server.takeRequest()
            assertEquals("android", req.getHeader("X-Laterbox-Client"))
            assertEquals("199", req.getHeader("X-Laterbox-Version-Code"))
            assertTrue(res is UpdateCheckResult.UpdateAvailable)
            assertEquals(205, (res as UpdateCheckResult.UpdateAvailable).updateInfo.versionCode)
        } finally {
            server.shutdown()
        }
    }
}


