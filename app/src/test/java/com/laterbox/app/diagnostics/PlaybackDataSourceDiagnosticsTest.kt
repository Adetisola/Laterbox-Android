package com.laterbox.app.diagnostics

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.laterbox.app.movies.data.cache.OkHttpMediaDataSourceFactory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Proves the real Media3 data source feeds the media network report even when playback throws. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlaybackDataSourceDiagnosticsTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        MediaNetworkDiagnostics.default.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        MediaNetworkDiagnostics.default.clear()
    }

    @Test
    fun http429FromPlaybackDataSourceIsRecordedAndReported_withoutSecrets() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12").setBody("busy"))
        val factory = OkHttpMediaDataSourceFactory(OkHttpClient(), "LaterboxPlayer/1.0")
            .setDefaultRequestProperties(
                mapOf(
                    "Cookie" to "Edge-Cache-Cookie=URLPrefix=zzz:Expires=4102444800:Signature=TOPSECRETSIG",
                    "Referer" to "https://provider.example/",
                    "X-MB-Token" to "tok-secret",
                )
            )
        val dataSource = factory.createDataSource()
        try {
            dataSource.open(DataSpec.Builder().setUri(Uri.parse(server.url("/v/movie.mp4").toString())).setPosition(0).setLength(1).build())
            fail("expected 429")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(429, e.responseCode)
        }

        val ev = MediaNetworkDiagnostics.default.snapshot().single()
        assertEquals(MediaRequestKind.PLAYBACK, ev.kind)
        assertEquals("PROGRESSIVE", ev.format)
        assertEquals("429", ev.outcome)
        assertTrue(ev.signedCookieExpected)
        val hop = ev.hops.single()
        assertEquals("bytes=0-0", hop.range)
        assertTrue(hop.cookie.present)
        assertTrue(hop.tokenHeaderPresent && hop.refererPresent)
        assertEquals("12", hop.responseHeaders["Retry-After"])

        val text = MediaNetworkDiagnostics.default.exportText()
        assertTrue(text.contains("MEDIA NETWORK DIAGNOSTICS"))
        listOf("TOPSECRETSIG", "tok-secret", "URLPrefix", "provider.example").forEach { assertFalse(text.contains(it)) }
    }
}
