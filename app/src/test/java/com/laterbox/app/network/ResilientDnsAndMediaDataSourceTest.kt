package com.laterbox.app.network

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.laterbox.app.movies.data.cache.OkHttpMediaDataSourceFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class ResilientDnsAndMediaDataSourceTest {

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
    fun resilientDns_usesSystemDnsWhenAvailable_withoutHittingDoh() {
        var dohCalls = 0
        val dohClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                dohCalls++
                chain.proceed(chain.request())
            }
            .build()
        val expectedIp = InetAddress.getByName("127.0.0.1")
        val dns = ResilientDns(
            systemDns = object : Dns {
                override fun lookup(hostname: String) = listOf(expectedIp)
            },
            dohClient = dohClient,
            dohEndpoints = listOf(server.url("/resolve").toString()),
        )

        val result = dns.lookup("bcdnxw.hakunaymatata.com")
        assertEquals(listOf(expectedIp), result)
        assertEquals(0, dohCalls)
    }

    @Test
    fun resilientDns_fallsBackToDohWhenSystemDnsFails_cachesAndSkipsCname() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "Status": 0,
                      "Answer": [
                        {"name": "bcdnxw.hakunaymatata.com.", "type": 5, "data": "u855cyvod2.v.enacdn.net."},
                        {"name": "u855cyvod2.v.enacdn.net.", "type": 1, "data": "151.242.229.116"},
                        {"name": "u855cyvod2.v.enacdn.net.", "type": 1, "data": "143.14.203.102"}
                      ]
                    }
                    """.trimIndent()
                )
        )
        var fakeTime = 1_000L
        val dns = ResilientDns(
            systemDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    throw UnknownHostException("Unable to resolve host \"$hostname\"")
                }
            },
            dohClient = OkHttpClient(),
            dohEndpoints = listOf(server.url("/resolve").toString()),
            cacheTtlMs = 60_000L,
            nowMs = { fakeTime },
        )

        val resolved = dns.lookup("bcdnxw.hakunaymatata.com")
        assertEquals(listOf("151.242.229.116", "143.14.203.102"), resolved.map { it.hostAddress })
        assertEquals(1, server.requestCount)

        // Second lookup within TTL hits in-memory cache
        val cached = dns.lookup("bcdnxw.hakunaymatata.com")
        assertEquals(listOf("151.242.229.116", "143.14.203.102"), cached.map { it.hostAddress })
        assertEquals(1, server.requestCount)

        // Re-throws UnknownHostException if DoH returns error / no records
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            dns.lookup("missing.example.invalid")
            fail("Expected UnknownHostException")
        } catch (_: UnknownHostException) {
            // expected
        }
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun okHttpMediaDataSource_streamsWithHeadersAndMapsHttp429() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-10/11")
                .setBody("hello-world")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("Too Many Requests")
        )

        val factory = OkHttpMediaDataSourceFactory(
            callFactory = OkHttpClient(),
            userAgent = "LaterboxPlayer/1.0",
        ).setDefaultRequestProperties(
            mapOf(
                "Referer" to "https://moviebox.ph/",
                "X-MB-Token" to "tok-123",
            )
        )

        val ds = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(server.url("/video.mp4").toString()))
            .setPosition(0)
            .setLength(11)
            .build()
        val len = ds.open(dataSpec)
        assertEquals(11L, len)
        val buf = ByteArray(11)
        val read = ds.read(buf, 0, 11)
        assertEquals(11, read)
        assertEquals("hello-world", String(buf))
        ds.close()

        val recorded = server.takeRequest()
        assertEquals("LaterboxPlayer/1.0", recorded.getHeader("User-Agent"))
        assertEquals("https://moviebox.ph/", recorded.getHeader("Referer"))
        assertEquals("tok-123", recorded.getHeader("X-MB-Token"))
        assertEquals("bytes=0-10", recorded.getHeader("Range"))

        // Second request receives 429 -> InvalidResponseCodeException(429)
        val ds2 = factory.createDataSource()
        try {
            ds2.open(DataSpec(Uri.parse(server.url("/video.mp4").toString())))
            fail("Expected InvalidResponseCodeException")
        } catch (err: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(429, err.responseCode)
            assertTrue(String(err.responseBody).contains("Too Many Requests"))
        } finally {
            ds2.close()
        }
    }
}
