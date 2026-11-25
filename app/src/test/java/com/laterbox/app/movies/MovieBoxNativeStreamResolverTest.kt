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
                        path.startsWith("/wefeed-mobile-bff/subject-api/play-info") -> {
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
}
