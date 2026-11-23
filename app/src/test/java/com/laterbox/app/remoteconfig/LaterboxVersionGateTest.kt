package com.laterbox.app.remoteconfig

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LaterboxVersionGateTest {

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
    fun interceptorAttachesHeadersOnlyToBackendAndTriggersGateOn426() = runBlocking {
        var refreshCount = 0
        val gate = LaterboxVersionGate(
            currentVersionCode = 199,
            onForceRefreshRequested = { refreshCount++ },
        )
        val backendBase = server.url("/").toString().trimEnd('/')
        val interceptor = LaterboxClientHeadersInterceptor(
            clientVersionCode = 199,
            clientVersionName = "1.0.66",
            backendBaseUrlProvider = { backendBase },
            versionGate = gate,
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        // 1. Backend 426 triggers gate & attaches headers
        server.enqueue(
            MockResponse()
                .setResponseCode(426)
                .setBody(
                    """{"detail":{"code":"UNSUPPORTED_APP_VERSION","minimumSupportedVersionCode":205,"minimumSupportedTag":"v1.0.67","latestStableTag":"v1.0.67","updateUrl":"https://laterbox-website.vercel.app/releases/v1.0.67","message":"Please update to continue."}}"""
                )
        )
        val req = Request.Builder().url("$backendBase/ai/v1/capabilities/evaluate").build()
        client.newCall(req).execute().use { resp ->
            assertEquals(426, resp.code)
        }
        val recorded = server.takeRequest()
        assertEquals("android", recorded.getHeader("X-Laterbox-Client"))
        assertEquals("199", recorded.getHeader("X-Laterbox-Version-Code"))
        assertEquals("1.0.66", recorded.getHeader("X-Laterbox-Version-Name"))

        val block = gate.serverBlockState.value
        assertNotNull(block)
        assertEquals(205, block?.minimumSupportedVersionCode)
        assertEquals("v1.0.67", block?.minimumSupportedTag)
        assertTrue(refreshCount == 1)

        // 2. Effective block resolves both monitor-safe non-blocking and enforce-blocking
        val monitorPolicy = RemoteVersionPolicy(
            minimumSupportedVersionCode = 205,
            enforcementEnabled = false,
            enforcementMode = RemoteVersionPolicy.MODE_MONITOR,
            minimumSupportedTag = "v1.0.67",
        )
        assertEquals(
            false,
            RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(monitorPolicy, 199),
        )

        // 3. Arbitrary 200 on non-config endpoint must NOT clear an active 426 block; only authoritative NETWORK config sync clears it
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.newCall(Request.Builder().url("$backendBase/ai/v1/capabilities/evaluate").build()).execute().close()
        server.takeRequest()
        assertNotNull(gate.serverBlockState.value)

        val cacheSnapshot = RemoteConfigSnapshot.default().copy(
            source = RemoteConfigSource.CACHE,
            versionPolicy = RemoteVersionPolicy(minimumSupportedVersionCode = 0, enforcementMode = RemoteVersionPolicy.MODE_DISABLED),
        )
        gate.syncWithPublishedConfig(cacheSnapshot)
        assertNotNull(gate.serverBlockState.value)

        val networkAllowedSnapshot = cacheSnapshot.copy(source = RemoteConfigSource.NETWORK)
        gate.syncWithPublishedConfig(networkAllowedSnapshot)
        assertNull(gate.serverBlockState.value)

        // 4. Third-party URL must NOT receive Laterbox headers
        val thirdPartyInterceptor = LaterboxClientHeadersInterceptor(
            clientVersionCode = 199,
            clientVersionName = "1.0.66",
            backendBaseUrlProvider = { "https://api.laterbox.example.com" },
            versionGate = gate,
        )
        val thirdPartyClient = OkHttpClient.Builder().addInterceptor(thirdPartyInterceptor).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        thirdPartyClient.newCall(Request.Builder().url("$backendBase/external/ia.json").build()).execute().close()
        val extReq = server.takeRequest()
        assertNull(extReq.getHeader("X-Laterbox-Client"))
        assertNull(extReq.getHeader("X-Laterbox-Version-Code"))

        val taggedBackendUrl = com.laterbox.app.update.GitHubUpdateChecker.buildBackendUpdateUrl(
            baseUrl = "https://api.laterbox.example.com/",
            forceRefresh = true,
            targetTag = "v1.0.65",
        )
        assertEquals("https://api.laterbox.example.com/updates/laterbox/latest?refresh=1&tag=v1.0.65", taggedBackendUrl)
        assertEquals(
            "https://api.github.com/repos/Adetisola/Laterbox/releases/tags/v1.0.65",
            com.laterbox.app.update.GitHubUpdateChecker.buildPublicFallbackUrl("v1.0.65"),
        )
    }

    @Test
    fun malformedAndUnrelated426PayloadsNeverSynthesizeVersionBlock() {
        var refreshCount = 0
        val gate = LaterboxVersionGate(
            currentVersionCode = 199,
            onForceRefreshRequested = { refreshCount++ },
        )

        // Malformed / non-JSON proxy 426 must be ignored
        gate.onHttp426("<html>Upgrade Required</html>")
        assertNull(gate.serverBlockState.value)

        // Missing required code == UNSUPPORTED_APP_VERSION must be ignored
        gate.onHttp426("""{"detail":{"minimumSupportedVersionCode":205,"message":"Proxy block"}}""")
        assertNull(gate.serverBlockState.value)

        // Unrelated code must be ignored
        gate.onHttp426("""{"detail":{"code":"PROTOCOL_UPGRADE","minimumSupportedVersionCode":205}}""")
        assertNull(gate.serverBlockState.value)

        // minimumSupportedVersionCode <= 0 must be ignored
        gate.onHttp426("""{"detail":{"code":"UNSUPPORTED_APP_VERSION","minimumSupportedVersionCode":0,"message":"Bad"}}""")
        assertNull(gate.serverBlockState.value)
        assertEquals(0, refreshCount)

        // Already supported currentVersionCode >= minCode must be ignored
        gate.onHttp426("""{"detail":{"code":"UNSUPPORTED_APP_VERSION","minimumSupportedVersionCode":199}}""")
        assertNull(gate.serverBlockState.value)
        assertEquals(0, refreshCount)
    }
}
