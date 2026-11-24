package com.laterbox.app.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CdnFailureDomainAndRetryAfterTest {

    @Test
    fun failureDomainResolver_groupsEdgeNextMp4AliasesAndKeepsDashSeparate() {
        val playDomain = CdnFailureDomainResolver.resolve("https://bcdnxw.hakunaymatata.com/path/movie.mp4?sign=abc")
        val downloadDomain = CdnFailureDomainResolver.resolve("https://BCDNW.HAKUNAYMATATA.COM./dl/movie.mp4?sign=def")
        val dashDomain = CdnFailureDomainResolver.resolve("https://sbcdnw.hakunaymatata.com/dash/index.mpd")

        assertEquals(CdnFailureDomainResolver.DOMAIN_EDGENEXT_MP4, playDomain)
        assertEquals(CdnFailureDomainResolver.DOMAIN_EDGENEXT_MP4, downloadDomain)
        assertEquals(CdnFailureDomainResolver.DOMAIN_EDGENEXT_DASH, dashDomain)
        assertNotEquals(playDomain, dashDomain)
    }

    @Test
    fun failureDomainResolver_normalizesUnknownHostsWithoutDnsLookup() {
        assertEquals("cdn-alt.example", CdnFailureDomainResolver.resolve("CDN-ALT.Example:8443"))
        assertEquals("sacdn2.hakunaymatata.com", CdnFailureDomainResolver.resolve("https://sacdn2.hakunaymatata.com/v/1.mpd"))
        assertNull(CdnFailureDomainResolver.resolve(null))
        assertNull(CdnFailureDomainResolver.resolve("   "))
    }

    @Test
    fun retryAfterParser_handlesDeltaSecondsAndContextCaps() {
        assertEquals(
            18L,
            RetryAfterParser.parseRetryAfterSeconds(
                retryAfterHeader = " 18 ",
                maxDelaySeconds = RetryAfterParser.MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS
            )
        )
        assertEquals(
            60L,
            RetryAfterParser.parseRetryAfterSeconds(
                retryAfterHeader = "240",
                maxDelaySeconds = RetryAfterParser.MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS
            )
        )
        assertEquals(
            240L,
            RetryAfterParser.parseRetryAfterSeconds(
                retryAfterHeader = "240",
                maxDelaySeconds = RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
            )
        )
        assertEquals(
            300L,
            RetryAfterParser.parseRetryAfterSeconds(
                retryAfterHeader = "99999999999999999999999",
                maxDelaySeconds = RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
            )
        )
        assertNull(RetryAfterParser.parseRetryAfterSeconds("0"))
        assertNull(RetryAfterParser.parseRetryAfterSeconds("-5"))
        assertNull(RetryAfterParser.parseRetryAfterSeconds("not-a-number"))
        assertNull(RetryAfterParser.parseRetryAfterSeconds(null))
    }

    @Test
    fun retryAfterParser_handlesHttpDatesAndServerClockSkew() {
        val skewedDeviceNowMs = 1_000_000L // Way off server year 2026
        // Server Date: Tue, 24 Nov 2026 12:00:00 GMT; Retry-After: +25s
        val parsedRfc1123 = RetryAfterParser.parseRetryAfterSeconds(
            retryAfterHeader = "Tue, 24 Nov 2026 12:00:25 GMT",
            dateHeader = "Tue, 24 Nov 2026 12:00:00 GMT",
            nowMs = skewedDeviceNowMs,
            maxDelaySeconds = RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
        )
        assertEquals(25L, parsedRfc1123)

        // Expired relative to server Date header even if skewed device clock is behind
        assertNull(
            RetryAfterParser.parseRetryAfterSeconds(
                retryAfterHeader = "Tue, 24 Nov 2026 11:59:55 GMT",
                dateHeader = "Tue, 24 Nov 2026 12:00:00 GMT",
                nowMs = skewedDeviceNowMs
            )
        )

        // RFC 850 obsolete format + ANSI asctime format
        assertEquals(
            12L,
            RetryAfterParser.parseRetryAfterSeconds(
                retryAfterHeader = "Tuesday, 24-Nov-26 12:00:12 GMT",
                dateHeader = "Tue, 24 Nov 2026 12:00:00 GMT",
                nowMs = skewedDeviceNowMs
            )
        )
        assertEquals(
            9L,
            RetryAfterParser.parseRetryAfterSeconds(
                retryAfterHeader = "Tue Nov 24 12:00:09 2026",
                dateHeader = "Tue, 24 Nov 2026 12:00:00 GMT",
                nowMs = skewedDeviceNowMs
            )
        )
    }

    @Test
    fun retryAfterParser_readsOkHttpResponseAndMedia3HeaderMap() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://bcdnxw.hakunaymatata.com/m.mp4").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Date", "Tue, 24 Nov 2026 12:00:00 GMT")
            .header("Retry-After", "Tue, 24 Nov 2026 12:00:42 GMT")
            .build()
        assertEquals(
            42L,
            RetryAfterParser.parseOkHttpResponse(
                response = response,
                nowMs = 1_000L,
                maxDelaySeconds = RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
            )
        )

        val headerFields: Map<String?, List<String>> = mapOf(
            null to listOf("HTTP/1.1 429 Too Many Requests"),
            "date" to listOf("Tue, 24 Nov 2026 12:00:00 GMT"),
            "retry-after" to listOf("Tue, 24 Nov 2026 12:01:30 GMT")
        )
        assertEquals(
            60L,
            RetryAfterParser.parseHeaderFields(
                headerFields = headerFields,
                nowMs = 1_000L,
                maxDelaySeconds = RetryAfterParser.MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS
            )
        )
    }
}
