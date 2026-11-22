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

        // 3. Clearing via non-allowlisted 200 clears server block
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.newCall(Request.Builder().url("$backendBase/ai/v1/capabilities/evaluate").build()).execute().close()
        server.takeRequest()
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
    }
}
