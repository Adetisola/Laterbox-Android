package com.laterbox.app.movies.data.provider

import com.laterbox.app.diagnostics.MediaNetworkDiagnosticsStore
import com.laterbox.app.movies.domain.model.PlaybackResolution
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class MovieBoxNativeStreamResolverTest {

    @Test
    fun canonicalSigningMatchesExpectedHmacFormatAndSortedQuery() {
        val url = "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/play-info?subjectId=3595&se=0&ep=0&host=api6.aoneroom.com".toHttpUrl()
        val canonical = MovieBoxNativeStreamResolver.buildCanonicalString(
            method = "GET",
            accept = "application/json",
            contentType = "application/json",
            url = url,
            bodyBytes = null,
            timestampMs = 1764000000123L,
        )
        val lines = canonical.split('\n')
        assertEquals(7, lines.size)
        assertEquals("GET", lines[0])
        assertEquals("application/json", lines[1])
        assertEquals("application/json", lines[2])
        assertEquals("", lines[3])
        assertEquals("1764000000123", lines[4])
        assertEquals("", lines[5])
        assertEquals("/wefeed-mobile-bff/subject-api/play-info?ep=0&host=api6.aoneroom.com&se=0&subjectId=3595", lines[6])

        val sig = MovieBoxNativeStreamResolver.generateTrSignature(
            method = "GET",
            accept = "application/json",
            contentType = "application/json",
            url = url,
            bodyBytes = null,
            timestampMs = 1764000000123L,
        )
        assertTrue(sig.startsWith("1764000000123|2|"))
        assertTrue(MovieBoxNativeStreamResolver.generateClientToken(1764000000123L).startsWith("1764000000123,"))
    }

    @Test
    fun nativePlayInfoResolvesCookielessBcdnWithMinimalOkHttpHeadersAndDiagnostics() = runBlocking {
        val server = MockWebServer()
        val diagStore = MediaNetworkDiagnosticsStore()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/wefeed-mobile-bff/tab/ranking-list") -> {
                            assertNotNull(request.getHeader("X-Tr-Signature"))
                            assertNotNull(request.getHeader("X-Client-Token"))
                            assertNotNull(request.getHeader("X-Client-Info"))
                            assertEquals(null, request.getHeader("X-Firebase-AppCheck"))
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("x-user", """{"token":"nat_secret_tok_999"}""")
                                .setBody("""{"code":0,"data":{"items":[]}}""")
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info") -> {
                            assertEquals("Bearer nat_secret_tok_999", request.getHeader("Authorization"))
                            assertTrue(path.contains("subjectId=3595"))
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(
                                    """
                                    {
                                      "code": 0,
                                      "data": {
                                        "cdnThrottleLevel": 0,
                                        "title": "The Beast",
                                        "streams": [
                                          {
                                            "id": "res-h265-1",
                                            "format": "MP4",
                                            "resolutions": "720,480",
                                            "codecName": "hevc",
                                            "size": "177211849",
                                            "duration": 8758,
                                            "url": "https://bcdn.hakunaymatata.com/resource/h265/res-h265-1.mp4"
                                          }
                                        ]
                                      }
                                    }
                                    """.trimIndent()
                                )
                        }
                        path.startsWith("/api/stream/3595/captions") -> MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"captions":[]}""")
                        path.startsWith("/api/stream/3595") -> {
                            throw AssertionError("Backend /api/stream should not be called when native succeeds")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val resolver = MovieBoxNativeStreamResolver(
                okHttpClient = OkHttpClient(),
                bootstrapBaseUrl = base,
                nativeApiBaseUrls = listOf(base),
                diagnosticsStore = diagStore,
                logger = {},
            )
            val provider = MovieBoxProvider(
                okHttpClient = OkHttpClient(),
                baseUrlProvider = { base },
                nativeStreamResolver = resolver,
            )

            val res = provider.resolvePlaybackSources("3595", null, null).getOrThrow()
            assertTrue(res is PlaybackResolution.Available)
            val available = res as PlaybackResolution.Available
            assertEquals("The Beast", available.title)
            assertEquals(2, available.sources.size)
            assertEquals(720, available.sources[0].qualityHeight)
            assertEquals("https://bcdn.hakunaymatata.com/resource/h265/res-h265-1.mp4", available.sources[0].url)
            assertEquals(mapOf("User-Agent" to "okhttp/4.9.3"), available.sources[0].headers)
            assertFalse(available.sources[0].headers.containsKey("Referer"))
            assertFalse(available.sources[0].headers.containsKey("Origin"))
            assertFalse(available.sources[0].headers.containsKey("Cookie"))

            val report = diagStore.exportText()
            assertTrue(report.contains("NATIVE_BOOTSTRAP"))
            assertTrue(report.contains("NATIVE_PLAY_INFO"))
            assertTrue(report.contains("cdnHosts=bcdn.hakunaymatata.com"))
            assertFalse(report.contains("nat_secret_tok_999"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stubOnlyAnd403NativeResponsesFallbackToBackendAndInvalidateSkipsNativeOnce() = runBlocking {
        val server = MockWebServer()
        val diagStore = MediaNetworkDiagnosticsStore()
        val nativePlayCalls = AtomicInteger(0)
        val backendStreamCalls = AtomicInteger(0)
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/wefeed-mobile-bff/tab/ranking-list") -> MockResponse()
                            .setResponseCode(200)
                            .addHeader("x-user", """{"token":"tok_abc"}""")
                            .setBody("""{"code":0}""")
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info?") -> {
                            val callIdx = nativePlayCalls.incrementAndGet()
                            if (callIdx == 1) {
                                MockResponse().setResponseCode(200).setBody(
                                    """
                                    {
                                      "data": {
                                        "cdnThrottleLevel": 1,
                                        "title": "The Beast",
                                        "streams": [
                                          {
                                            "format": "MP4",
                                            "resolutions": "1080,720,480",
                                            "size": "824720",
                                            "duration": 23,
                                            "url": "https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"
                                          }
                                        ]
                                      }
                                    }
                                    """.trimIndent()
                                )
                            } else {
                                MockResponse().setResponseCode(200).setBody(
                                    """
                                    {
                                      "data": {
                                        "cdnThrottleLevel": 0,
                                        "title": "The Beast",
                                        "streams": [
                                          {
                                            "format": "MP4",
                                            "resolutions": "720",
                                            "size": "120000000",
                                            "duration": 5400,
                                            "url": "https://bcdn.hakunaymatata.com/resource/h265/live.mp4"
                                          }
                                        ]
                                      }
                                    }
                                    """.trimIndent()
                                )
                            }
                        }
                        path.startsWith("/api/stream/3595/captions") -> MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"captions":[]}""")
                        path.startsWith("/api/stream/3595") -> {
                            backendStreamCalls.incrementAndGet()
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "title": "The Beast",
                                  "streams": [
                                    {
                                      "url": "https://bcdnxw.hakunaymatata.com/resource/h5.mp4?sign=1&t=2",
                                      "format": "MP4",
                                      "quality": "720p",
                                      "quality_value": 720,
                                      "headers": {"User-Agent": "Mozilla/5.0"}
                                    }
                                  ]
                                }
                                """.trimIndent()
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val resolver = MovieBoxNativeStreamResolver(
                okHttpClient = OkHttpClient(),
                bootstrapBaseUrl = base,
                nativeApiBaseUrls = listOf(base),
                diagnosticsStore = diagStore,
                logger = {},
            )
            val provider = MovieBoxProvider(
                okHttpClient = OkHttpClient(),
                baseUrlProvider = { base },
                nativeStreamResolver = resolver,
            )

            // 1. Native stub clip filtered -> seamless fallback to backend
            val first = provider.resolvePlaybackSources("3595", null, null).getOrThrow() as PlaybackResolution.Available
            assertEquals("https://bcdnxw.hakunaymatata.com/resource/h5.mp4?sign=1&t=2", first.sources.first().url)
            assertEquals(1, backendStreamCalls.get())
            assertTrue(diagStore.exportText().contains("rawHosts=macdn.aoneroom.com"))

            // 2. Next attempt after invalidation returns native bcdn stream
            provider.invalidatePlaybackCache("3595", null, null)
            val second = provider.resolvePlaybackSources("3595", null, null).getOrThrow() as PlaybackResolution.Available
            assertEquals("https://bcdn.hakunaymatata.com/resource/h265/live.mp4", second.sources.first().url)
            assertEquals(1, backendStreamCalls.get())

            // 3. Invalidating immediately after native success skips native once to test backend safety net
            provider.invalidatePlaybackCache("3595", null, null)
            val third = provider.resolvePlaybackSources("3595", null, null).getOrThrow() as PlaybackResolution.Available
            assertEquals("https://bcdnxw.hakunaymatata.com/resource/h5.mp4?sign=1&t=2", third.sources.first().url)
            assertEquals(2, backendStreamCalls.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun policyRecoveryExtractsDashManifestAndCookiesFromSignCookieOverStub() = runBlocking {
        val server = MockWebServer()
        val diagStore = MediaNetworkDiagnosticsStore()
        try {
            val prefix = "https://sbcdnw.hakunaymatata.com/dash/native_42/"
            val b64Prefix = Base64.getUrlEncoder().withoutPadding().encodeToString(prefix.toByteArray(Charsets.UTF_8))
            val signCookie = "Edge-Cache-Cookie=URLPrefix=$b64Prefix:Expires=1900000000:Signature=sig123"

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/wefeed-mobile-bff/tab/ranking-list") -> MockResponse()
                            .setResponseCode(200)
                            .addHeader("x-user", """{"token":"tok_dash"}""")
                            .setBody("""{"code":0}""")
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info") -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                {
                                  "data": {
                                    "streams": [
                                      {
                                        "format": "MP4",
                                        "resolutions": "1080,720",
                                        "size": "824720",
                                        "duration": 23,
                                        "url": "https://macdn.aoneroom.com/other/notice.mp4",
                                        "signHeaderKey": "X-MB-Token",
                                        "signCookie": "$signCookie"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val resolver = MovieBoxNativeStreamResolver(
                okHttpClient = OkHttpClient(),
                bootstrapBaseUrl = base,
                nativeApiBaseUrls = listOf(base),
                diagnosticsStore = diagStore,
                logger = {},
            )
            val outcome = resolver.resolveStreams("42", 0, 0)
            assertEquals(1, outcome.sources.size)
            val dash = outcome.sources.first()
            assertEquals("${prefix}index.mpd", dash.url)
            assertEquals("DASH", dash.format)
            assertEquals(1080, dash.qualityHeight)
            assertEquals(signCookie, dash.headers["Cookie"])
            assertEquals(signCookie, dash.headers["X-MB-Token"])
            assertEquals("https://moviebox.ph", dash.headers["Origin"])
            assertEquals("https://moviebox.ph/", dash.headers["Referer"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun nativeStubPlayInfoTriggersSanitizedResourceProbeAndBoundedRangeWhilePreservingFallback() = runBlocking {
        val server = MockWebServer()
        val diagStore = MediaNetworkDiagnosticsStore()
        val resourceCalls = AtomicInteger(0)
        val rangeCalls = AtomicInteger(0)
        val backendCalls = AtomicInteger(0)
        var capturedUserAgent: String? = null
        var capturedClientInfo: String? = null
        var capturedPlayModeHeader: String? = null
        var capturedRangeHeader: String? = null
        var capturedRangeUserAgent: String? = null
        var capturedRangeCookie: String? = null

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/wefeed-mobile-bff/tab/ranking-list") -> MockResponse()
                            .setResponseCode(200)
                            .addHeader("x-user", """{"token":"nat_secret_bootstrap_999"}""")
                            .setBody("""{"code":0}""")
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info") -> {
                            capturedUserAgent = request.getHeader("User-Agent")
                            capturedClientInfo = request.getHeader("X-Client-Info")
                            capturedPlayModeHeader = request.getHeader("X-Play-Mode")
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 0,
                                  "data": {
                                    "cdnThrottleLevel": 0,
                                    "title": "The Beast",
                                    "streams": [
                                      {
                                        "format": "MP4",
                                        "resolutions": "1080,720",
                                        "size": "824720",
                                        "duration": 23,
                                        "url": "https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/resource") -> {
                            resourceCalls.incrementAndGet()
                            assertTrue(path.contains("subjectId=3595"))
                            assertTrue(path.contains("perPage=8"))
                            val baseUrl = server.url("/").toString().trimEnd('/')
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 0,
                                  "data": {
                                    "list": [
                                      {
                                        "resourceId": "res-h265-9001",
                                        "resolution": 720,
                                        "size": "177211849",
                                        "duration": 8758,
                                        "se": 0,
                                        "ep": 0,
                                        "linkType": 1,
                                        "resourceLink": "$baseUrl/resource/h265/9001.mp4?sign=secret_sig_abc&t=99887766"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                        path.startsWith("/resource/h265/9001.mp4") -> {
                            rangeCalls.incrementAndGet()
                            capturedRangeHeader = request.getHeader("Range")
                            capturedRangeUserAgent = request.getHeader("User-Agent")
                            capturedRangeCookie = request.getHeader("Cookie")
                            // Return 4096 bytes so test proves bounded reader stops at <=1024 bytes.
                            val payload = "A".repeat(4096)
                            MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Range", "bytes 0-1023/177211849")
                                .setHeader("Content-Length", "1024")
                                .setHeader("Server", "Tengine")
                                .setBody(payload)
                        }
                        path.startsWith("/api/stream/3595/captions") -> MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"captions":[]}""")
                        path.startsWith("/api/stream/3595") -> {
                            backendCalls.incrementAndGet()
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "title": "The Beast",
                                  "streams": [
                                    {
                                      "url": "https://bcdnxw.hakunaymatata.com/resource/fallback.mp4?sign=1",
                                      "format": "MP4",
                                      "quality": "720p",
                                      "quality_value": 720,
                                      "headers": {"User-Agent": "Mozilla/5.0"}
                                    }
                                  ]
                                }
                                """.trimIndent()
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val resolver = MovieBoxNativeStreamResolver(
                okHttpClient = OkHttpClient(),
                bootstrapBaseUrl = base,
                nativeApiBaseUrls = listOf(base),
                diagnosticsStore = diagStore,
                logger = {},
            )
            val provider = MovieBoxProvider(
                okHttpClient = OkHttpClient(),
                baseUrlProvider = { base },
                nativeStreamResolver = resolver,
            )

            val resolution = provider.resolvePlaybackSources("3595", null, null).getOrThrow() as PlaybackResolution.Available
            // Production playback still falls back to backend /api/stream (diagnostic-only probe never alters production playback).
            assertEquals(1, backendCalls.get())
            assertEquals("https://bcdnxw.hakunaymatata.com/resource/fallback.mp4?sign=1", resolution.sources.first().url)
            assertEquals(1, resourceCalls.get())
            assertEquals(1, rangeCalls.get())

            // Verify official 3.0.01.0411.03 profile fixes: versionCode 50020038 + X-Play-Mode.
            assertTrue(capturedUserAgent.orEmpty().startsWith("com.community.oneroom/50020038"))
            assertTrue(capturedClientInfo.orEmpty().contains("\"version_code\":50020038"))
            assertTrue(capturedClientInfo.orEmpty().contains("\"X-Play-Mode\":\"2\""))
            assertEquals("2", capturedPlayModeHeader)

            // Verify bounded Range request headers.
            assertEquals("bytes=0-1023", capturedRangeHeader)
            assertEquals("okhttp/4.9.3", capturedRangeUserAgent)
            assertEquals(null, capturedRangeCookie)

            // Verify sanitized diagnostics and strict secret/query redaction.
            val report = diagStore.exportText()
            assertTrue(report.contains("NATIVE_PLAY_INFO"))
            assertTrue(report.contains("NATIVE_RESOURCE_PROBE"))
            assertTrue(report.contains("NATIVE_RESOURCE_RANGE"))
            assertTrue(report.contains("urlPath=/resource/h265/9001.mp4"))
            assertTrue(report.contains("bytesRead=1024"))
            assertTrue(report.contains("contentRange=bytes 0-1023/177211849"))
            assertTrue(report.contains("valid=true"))
            assertFalse(report.contains("secret_sig_abc"))
            assertFalse(report.contains("99887766"))
            assertFalse(report.contains("nat_secret_bootstrap_999"))
            assertFalse(report.contains("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resourceProbeRetriesR720VariantAndDetectsCookieRequiredAndRedirectWithoutLeakingSecrets() = runBlocking {
        val server = MockWebServer()
        val diagStore = MediaNetworkDiagnosticsStore()
        var rangeAttempts = 0

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/wefeed-mobile-bff/subject-api/resource") -> {
                            val playMode = request.getHeader("X-Play-Mode")
                            val baseUrl = server.url("/").toString().trimEnd('/')
                            if (playMode == "1") {
                                MockResponse().setResponseCode(200).setBody("""{"code":0,"data":{"list":[]}}""")
                            } else {
                                assertTrue(path.contains("perPage=10"))
                                assertTrue(path.contains("resolution=720"))
                                MockResponse()
                                    .setResponseCode(200)
                                    .addHeader("Set-Cookie", "Edge-Cache-Cookie=secret_cookie_val_xyz; Path=/")
                                    .setBody(
                                        """
                                        {
                                          "code": 0,
                                          "data": {
                                            "list": [
                                              {
                                                "resourceId": "res-1080",
                                                "resolution": 1080,
                                                "size": "450000000",
                                                "duration": 6200,
                                                "resourceLink": "$baseUrl/entry/1080.mp4?token=very_secret_query"
                                              }
                                            ]
                                          }
                                        }
                                        """.trimIndent()
                                    )
                            }
                        }
                        path.startsWith("/entry/1080.mp4") -> {
                            rangeAttempts++
                            if (request.getHeader("Cookie") == null) {
                                MockResponse().setResponseCode(403)
                            } else {
                                assertTrue(request.getHeader("Cookie").orEmpty().contains("secret_cookie_val_xyz"))
                                MockResponse()
                                    .setResponseCode(206)
                                    .setHeader("Content-Range", "bytes 0-1023/450000000")
                                    .setHeader("Server", "Tengine")
                                    .setBody("B".repeat(1024))
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val resolver = MovieBoxNativeStreamResolver(
                okHttpClient = OkHttpClient(),
                bootstrapBaseUrl = base,
                nativeApiBaseUrls = listOf(base),
                diagnosticsStore = diagStore,
                logger = {},
            )

            val summary = resolver.probeNativeResourceRoute(
                subjectId = "777",
                seasonNumber = 0,
                episodeNumber = 0,
                bearerToken = "tok_direct",
                preferredBaseUrl = base,
            )

            assertNotNull(summary)
            assertEquals("r720_stream", summary?.variant)
            assertEquals(1, summary?.nonStubItems)
            assertEquals(2, rangeAttempts)
            assertEquals(206, summary?.rangeProbe?.statusCode)
            assertTrue(summary?.rangeProbe?.cookieRequired == true)
            assertTrue(summary?.rangeProbe?.cookieApplied == true)

            val report = diagStore.exportText()
            assertTrue(report.contains("variant=r720_stream"))
            assertTrue(report.contains("cookieRequired=true"))
            assertTrue(report.contains("cookieApplied=true"))
            assertFalse(report.contains("secret_cookie_val_xyz"))
            assertFalse(report.contains("very_secret_query"))
            assertFalse(report.contains("tok_direct"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun canonicalSigningDecodesFailUrlBeforeHmac() {
        val url = "https://api3.aoneroom.com/wefeed-mobile-bff/subject-api/resource-position/v2"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("host", "api3.aoneroom.com")
            .addQueryParameter("subjectId", "9168495427082149592")
            .addQueryParameter("resourceId", "2541551146741688848")
            .addQueryParameter("failUrl", "https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4")
            .addQueryParameter("failCode", "404")
            .addQueryParameter("resourceNum", "0")
            .addQueryParameter("isVip", "true")
            .build()

        val canonical = MovieBoxNativeStreamResolver.buildCanonicalString(
            method = "GET",
            accept = "application/json",
            contentType = "application/json;charset=UTF-8",
            url = url,
            bodyBytes = null,
            timestampMs = 1764000000123L,
        )
        val lines = canonical.split('\n')
        assertEquals(7, lines.size)
        assertTrue(lines[6].contains("failUrl=https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"))
        assertFalse(lines[6].contains("%3A%2F%2F"))
    }

    @Test
    fun resourceProbeRunsVisitorLoginPlayInfoV2ResourceV2AndResourcePositionV2WhenStubOnly() = runBlocking {
        val server = MockWebServer()
        val diagStore = MediaNetworkDiagnosticsStore()
        var visitorCalled = false
        var playV2SawSeOrEp = false
        var resourceV2IsVip = false
        var recoveredViaPositionV2 = false

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val baseUrl = server.url("/").toString().trimEnd('/')
                    return when {
                        path.startsWith("/wefeed-mobile-bff/user-api/visitor-login") -> {
                            visitorCalled = true
                            assertEquals("POST", request.method)
                            assertEquals("4.0.02", request.getHeader("X-M-Version"))
                            MockResponse().setResponseCode(200).setBody(
                                """{"code":0,"data":{"token":"visitor_tok_secret_777","userId":"777001"}}"""
                            )
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info/v2") -> {
                            assertEquals("Bearer visitor_tok_secret_777", request.getHeader("Authorization"))
                            playV2SawSeOrEp = path.contains("se=") || path.contains("ep=")
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 0,
                                  "data": {
                                    "streams": [
                                      {
                                        "id": "1478687341031182664",
                                        "format": "MP4",
                                        "size": "824720",
                                        "duration": 23,
                                        "url": "https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/resource/v2") -> {
                            resourceV2IsVip = path.contains("isVip=true")
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 0,
                                  "data": {
                                    "list": [
                                      {
                                        "resourceId": "2541551146741688848",
                                        "resolution": 1080,
                                        "size": "824720",
                                        "duration": 6394,
                                        "resourceLink": "https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/resource-position/v2") -> {
                            recoveredViaPositionV2 = true
                            assertTrue(path.contains("resourceId=2541551146741688848"))
                            assertTrue(path.contains("isVip=true"))
                            assertTrue(path.contains("failCode=404"))
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 0,
                                  "data": {
                                    "list": [
                                      {
                                        "resourceId": "2541551146741688848",
                                        "resolution": 1080,
                                        "size": "177211849",
                                        "duration": 8758,
                                        "resourceLink": "$baseUrl/resource/h265/recovered.mp4?sign=sig_hidden_999"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/resource") -> {
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "code": 0,
                                  "data": {
                                    "list": [
                                      {
                                        "resourceId": "3456907771004832456",
                                        "resolution": 360,
                                        "size": "824720",
                                        "duration": 6394,
                                        "resourceLink": "https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                        path.startsWith("/resource/h265/recovered.mp4") -> {
                            MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Range", "bytes 0-1023/177211849")
                                .setHeader("Server", "Tengine")
                                .setBody("C".repeat(1024))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val resolver = MovieBoxNativeStreamResolver(
                okHttpClient = OkHttpClient(),
                bootstrapBaseUrl = base,
                nativeApiBaseUrls = listOf(base),
                diagnosticsStore = diagStore,
                logger = {},
            )

            val summary = resolver.probeNativeResourceRoute(
                subjectId = "9168495427082149592",
                seasonNumber = 0,
                episodeNumber = 0,
                bearerToken = "tok_initial_secret",
                preferredBaseUrl = base,
            )

            assertTrue(visitorCalled)
            assertFalse(playV2SawSeOrEp)
            assertTrue(resourceV2IsVip)
            assertTrue(recoveredViaPositionV2)
            assertNotNull(summary)
            assertEquals("resource_position_v2_vip", summary?.variant)
            assertEquals(1, summary?.nonStubItems)
            assertEquals(206, summary?.rangeProbe?.statusCode)
            assertEquals(1024L, summary?.rangeProbe?.bytesRead)

            val report = diagStore.exportText()
            assertTrue(report.contains("NATIVE_VISITOR_LOGIN"))
            assertTrue(report.contains("variant=play_info_v2_no_se_ep"))
            assertTrue(report.contains("variant=resource_v2_vip"))
            assertTrue(report.contains("variant=resource_position_v2_vip"))
            assertTrue(report.contains("urlPath=/resource/h265/recovered.mp4"))
            assertFalse(report.contains("visitor_tok_secret_777"))
            assertFalse(report.contains("sig_hidden_999"))
            assertFalse(report.contains("tok_initial_secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resolveStreamsAttachesClientBuildTagAndReturnsRecoveredMp4FromResourcePositionV2() = runBlocking {
        val server = MockWebServer()
        val diagStore = MediaNetworkDiagnosticsStore()
        var capturedBuildHeader: String? = null
        var capturedClientInfo: String? = null

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val baseUrl = server.url("/").toString().trimEnd('/')
                    return when {
                        path.startsWith("/wefeed-mobile-bff/tab/ranking-list") -> MockResponse()
                            .setResponseCode(200)
                            .addHeader("x-user", """{"token":"tok_v1_init"}""")
                            .setBody("""{"code":0}""")
                        path.startsWith("/wefeed-mobile-bff/user-api/visitor-login") -> {
                            capturedBuildHeader = request.getHeader("X-Client-Build")
                            capturedClientInfo = request.getHeader("X-Client-Info")
                            MockResponse().setResponseCode(200).setBody(
                                """{"code":0,"data":{"token":"tok_v4_vis"}}"""
                            )
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info/v2") -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"code":0,"data":{"streams":[{"id":"st1","size":"824720","duration":23,"url":"https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"}]}}"""
                            )
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info") -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"code":0,"data":{"title":"Steps","streams":[{"id":"st1","size":"824720","duration":23,"url":"https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"}]}}"""
                            )
                        path.startsWith("/wefeed-mobile-bff/subject-api/resource/v2") -> {
                            assertTrue(path.contains("se=0"))
                            assertTrue(path.contains("epFrom=0"))
                            assertTrue(path.contains("epTo=0"))
                            assertTrue(path.contains("isVip=true"))
                            MockResponse().setResponseCode(200).setBody(
                                """{"code":0,"data":{"list":[{"resourceId":"rid-2541","resolution":1080,"size":"824720","duration":6394,"resourceLink":"https://macdn.aoneroom.com/other/2026/10/10/80da52722ee7de7a4303290af6783424.mp4"}]}}"""
                            )
                        }
                        path.startsWith("/wefeed-mobile-bff/subject-api/resource-position/v2") -> MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """{"code":0,"data":{"list":[{"resourceId":"rid-2541","resolution":1080,"size":"1206633838","duration":6394,"resourceLink":"$baseUrl/resource/h265/bcdn_1080.mp4"}]}}"""
                            )
                        path.startsWith("/resource/h265/bcdn_1080.mp4") -> MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes 0-1023/1206633838")
                            .setBody("M".repeat(1024))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            val resolver = MovieBoxNativeStreamResolver(
                okHttpClient = OkHttpClient(),
                bootstrapBaseUrl = base,
                nativeApiBaseUrls = listOf(base),
                diagnosticsStore = diagStore,
                logger = {},
            )
            val outcome = resolver.resolveStreams("9168495427082149592", 0, 0)

            assertNotNull(capturedBuildHeader)
            val buildParts = capturedBuildHeader!!.split('.')
            assertEquals(2, buildParts.size)
            assertEquals(32, buildParts[1].length)
            assertTrue(capturedClientInfo.orEmpty().contains("\"X-Client-Build\":\"$capturedBuildHeader\""))

            assertEquals(1, outcome.sources.size)
            val recovered = outcome.sources.first()
            assertEquals("$base/resource/h265/bcdn_1080.mp4", recovered.url)
            assertEquals("MP4", recovered.format)
            assertEquals(1080, recovered.qualityHeight)
        } finally {
            server.shutdown()
        }
    }
}
