package com.laterbox.app.diagnostics

import com.laterbox.app.media.data.download.HttpDownloader
import com.laterbox.app.movies.data.download.SubtitleDownloadManager
import com.laterbox.app.movies.domain.model.SubtitleTrack
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaNetworkDiagnosticsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var origin: MockWebServer
    private lateinit var other: MockWebServer
    private val secretCookie = "Edge-Cache-Cookie=URLPrefix=abc:Expires=%d:KeyName=k:Signature=SUPERSECRETSIGNATURE"
    private val future get() = System.currentTimeMillis() / 1000L + 3600L

    @Before
    fun setUp() {
        origin = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
        MediaNetworkDiagnostics.default.clear()
    }

    @After
    fun tearDown() {
        runCatching { origin.shutdown() }
        runCatching { other.shutdown() }
        MediaNetworkDiagnostics.default.clear()
    }

    private fun originUrl(path: String) = origin.url(path).newBuilder().host("localhost").build()
    private fun otherUrl(path: String) = other.url(path).newBuilder().host("127.0.0.1").build()

    private fun redirectToOther(path: String) {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherUrl(path).toString()))
        other.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
    }

    @Test
    fun httpDownloader_stripsStreamCredentialsOnCrossSiteRedirect() = runTest {
        redirectToOther("/file.mp4")
        val dest = File(tmp.root, "a.bin")
        val result = HttpDownloader(OkHttpClient()).download(
            url = originUrl("/start.mp4").toString(),
            destinationFile = dest,
            headers = mapOf(
                "Cookie" to secretCookie.format(future),
                "Referer" to "https://provider.example/",
                "Origin" to "https://provider.example",
                "X-MB-Token" to "tok",
                "Authorization" to "Bearer x",
            ),
        ) { _, _, _, _ -> }
        assertTrue(result is com.laterbox.app.media.data.download.DownloadResult.Success)

        val first = origin.takeRequest()
        assertEquals("https://provider.example/", first.getHeader("Referer"))
        assertTrue(first.getHeader("Cookie")!!.startsWith("Edge-Cache-Cookie="))

        val second = other.takeRequest()
        assertNull(second.getHeader("Cookie"))
        assertNull(second.getHeader("Referer"))
        assertNull(second.getHeader("Origin"))
        assertNull(second.getHeader("X-MB-Token"))
        assertNull(second.getHeader("Authorization"))
    }

    @Test
    fun httpDownloader_keepsHeadersOnSameHostRedirect() = runTest {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", originUrl("/final.mp4").toString()))
        origin.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
        HttpDownloader(OkHttpClient()).download(
            url = originUrl("/start.mp4").toString(),
            destinationFile = File(tmp.root, "b.bin"),
            headers = mapOf("Cookie" to secretCookie.format(future), "Referer" to "https://provider.example/"),
        ) { _, _, _, _ -> }
        origin.takeRequest()
        val second = origin.takeRequest()
        assertTrue(second.getHeader("Cookie")!!.startsWith("Edge-Cache-Cookie="))
        assertEquals("https://provider.example/", second.getHeader("Referer"))
    }

    @Test
    fun subtitleManager_stripsStreamCredentialsOnCrossSiteRedirect() = runTest {
        redirectToOther("/en.srt")
        other.enqueue(MockResponse().setResponseCode(200).setBody("1\n00:00:01,000 --> 00:00:02,000\nHi\n"))
        val manager = SubtitleDownloadManager(File(tmp.root, "subs"), OkHttpClient())
        manager.downloadSubtitlesForAsset(
            assetId = "a1", sourceRef = "moviebox|t|0|0|1080p", providerId = "moviebox", titleId = "t",
            seasonNumber = null, episodeNumber = null,
            subtitles = listOf(
                SubtitleTrack(
                    id = "s", language = "English", languageCode = "en", label = "English",
                    url = originUrl("/en.srt").toString(), format = "srt",
                    headers = mapOf("Cookie" to "c=1", "Referer" to "https://provider.example/", "X-MB-Token" to "t"),
                )
            ),
            fallbackHeaders = emptyMap(),
        )
        origin.takeRequest()
        val second = other.takeRequest()
        assertNull(second.getHeader("Cookie"))
        assertNull(second.getHeader("Referer"))
        assertNull(second.getHeader("X-MB-Token"))
        assertTrue(MediaNetworkDiagnostics.default.snapshot().any { it.kind == MediaRequestKind.SUBTITLE })
    }

    @Test
    fun diagnostics_recordsRealFailingRequestWithoutSecrets() = runTest {
        val cookie = secretCookie.format(future)
        origin.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
                .setHeader("Date", "Mon, 24 Nov 2026 10:00:00 GMT").setBody("<html>Too many requests</html>")
        )
        val client = OkHttpClient().withMediaDiagnostics(MediaRequestKind.PLAYBACK)
        client.newCall(
            Request.Builder().url(originUrl("/movie.mp4"))
                .header("Cookie", cookie).header("Range", "bytes=0-0").header("Referer", "https://r.example/")
                .build()
        ).execute().close()

        val ev = MediaNetworkDiagnostics.default.snapshot().single()
        assertEquals(MediaRequestKind.PLAYBACK, ev.kind)
        assertEquals("PROGRESSIVE", ev.format)
        assertEquals("okhttp", ev.engine)
        assertEquals("429", ev.outcome)
        assertTrue(ev.signedCookieExpected)
        val hop = ev.hops.single()
        assertEquals(429, hop.status)
        assertEquals("bytes=0-0", hop.range)
        assertEquals("30", hop.responseHeaders["Retry-After"])
        assertTrue(hop.cookie.present)
        assertEquals(listOf("Edge-Cache-Cookie"), hop.cookie.names)
        assertTrue(hop.cookie.expiry.startsWith("valid"))
        assertTrue(hop.refererPresent)
        assertTrue(hop.requestHeaderNames.any { it.equals("Cookie", true) })
        assertEquals("localhost", hop.host)

        val text = MediaNetworkDiagnostics.default.exportText()
        assertTrue(text.contains("Retry-After=30"))
        assertTrue(text.contains("Edge-Cache-Cookie"))
        assertFalse(text.contains("SUPERSECRETSIGNATURE"))
        assertFalse(text.contains("URLPrefix"))
        assertFalse(text.contains("provider.example"))
        assertFalse(text.contains("/movie.mp4"))
    }

    @Test
    fun diagnostics_recordsRedirectChainWithInitialAndFinalHost() = runTest {
        redirectToOther("/x.mp4")
        val client = OkHttpClient().withMediaDiagnostics(MediaRequestKind.DOWNLOAD)
        client.newCall(Request.Builder().url(originUrl("/start.mp4")).header("Cookie", "a=b").build()).execute().close()
        val ev = MediaNetworkDiagnostics.default.snapshot().single()
        assertEquals("localhost", ev.initialHost)
        assertEquals("127.0.0.1", ev.finalHost)
        assertEquals(2, ev.hops.size)
        assertEquals(1, ev.redirectCount)
        assertEquals("127.0.0.1", ev.hops[0].redirectToHost)
        assertTrue(ev.hops[0].cookie.present)
        assertFalse(ev.hops[1].cookie.present)
    }

    @Test
    fun diagnostics_recordsTransportFailure() {
        val url = originUrl("/gone.mp4")
        origin.shutdown()
        val client = OkHttpClient().withMediaDiagnostics(MediaRequestKind.PLAYBACK)
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }
        val ev = MediaNetworkDiagnostics.default.snapshot().single()
        assertTrue(ev.outcome.startsWith("EXCEPTION"))
        assertEquals(-1, ev.finalStatus)
    }

    @Test
    fun signedCookieInfo_parsesExpiryWithoutExposingValues() {
        val now = 1_700_000_000_000L
        val valid = SignedCookieInfo.inspect("Edge-Cache-Cookie=URLPrefix=x:Expires=1700000100:Signature=s", now)
        assertTrue(valid.expiry.startsWith("valid"))
        val expired = SignedCookieInfo.inspect("Edge-Cache-Cookie=URLPrefix=x:Expires=1699999000:Signature=s", now)
        assertTrue(expired.expiry.startsWith("expired"))
        assertEquals("unknown", SignedCookieInfo.inspect("session=abc", now).expiry)
        assertFalse(SignedCookieInfo.inspect(null, now).present)
    }

    @Test
    fun diagnostics_recordsDashManifestVariantAndDownloadContentRangeWithoutSecrets() = runTest {
        origin.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setHeader("Content-Length", "6")
                .setBody("456789")
        )
        val partial = File(tmp.root, "resume.mp4").apply { writeText("0123") }
        val dlResult = HttpDownloader(OkHttpClient()).download(
            url = originUrl("/resource/h265/secret_id.mp4?sign=SECRET_SIGN&t=123").toString(),
            destinationFile = partial,
        ) { _, _, _, _ -> }
        assertTrue(dlResult is com.laterbox.app.media.data.download.DownloadResult.Success)

        origin.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
        val dashClient = OkHttpClient().withMediaDiagnostics(MediaRequestKind.PLAYBACK)
        dashClient.newCall(
            Request.Builder()
                .url(originUrl("/dash/3595914877702915152/index_web.mpd"))
                .header("Cookie", secretCookie.format(future))
                .build()
        ).execute().close()

        MediaNetworkDiagnostics.default.recordLifecycle(
            MediaLifecycleEvent(
                timestampMs = 1_700_000_000_000L,
                kind = MediaRequestKind.PLAYBACK,
                stage = "REFRESH_APPLIED",
                host = "sbcdnw.hakunaymatata.com",
                format = "DASH",
                statusCode = 403,
                refreshRequested = true,
                refreshedCredentialsApplied = true,
                failureCategory = "AUTH_EXPIRED_OR_FORBIDDEN",
            )
        )

        val text = MediaNetworkDiagnostics.default.exportText()
        assertTrue(text.contains("manifest=index_web.mpd"))
        assertTrue(text.contains("Content-Range=bytes 4-9/10"))
        assertTrue(text.contains("DOWNLOAD / PROGRESSIVE / localhost: 206x1"))
        assertTrue(text.contains("PLAYBACK / DASH_MANIFEST / localhost: 403x1"))
        assertTrue(text.contains("STATE & REFRESH TRAIL"))
        assertTrue(text.contains("refreshRequested=true refreshedCredentialsApplied=true"))
        assertFalse(text.contains("SECRET_SIGN"))
        assertFalse(text.contains("3595914877702915152"))
        assertFalse(text.contains("secret_id"))
        assertFalse(text.contains("SUPERSECRETSIGNATURE"))
    }
}
