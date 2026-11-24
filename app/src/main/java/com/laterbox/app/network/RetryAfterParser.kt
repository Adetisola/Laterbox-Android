package com.laterbox.app.network

import okhttp3.Response
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Shared RFC 9110 Retry-After parser supporting delta-seconds and HTTP-date values
 * with optional server `Date` clock-offset compensation and context-specific bounds.
 */
object RetryAfterParser {

    const val MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS = 60L
    const val MAX_INLINE_MEDIA3_RETRY_DELAY_SECONDS = 5L
    const val MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS = 300L

    private val DELTA_SECONDS_REGEX = Regex("""^\d+$""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    private val RFC850_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("EEEE, dd-MMM-")
        .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
        .appendPattern(" HH:mm:ss z")
        .toFormatter(Locale.US)
        .withZone(ZoneOffset.UTC)

    private val ASCTIME_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("EEE MMM d HH:mm:ss yyyy")
        .toFormatter(Locale.US)
        .withZone(ZoneOffset.UTC)

    fun parseRetryAfterSeconds(
        retryAfterHeader: String?,
        dateHeader: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        maxDelaySeconds: Long = MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
    ): Long? {
        if (maxDelaySeconds <= 0L) return null
        val trimmed = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (DELTA_SECONDS_REGEX.matches(trimmed)) {
            val seconds = trimmed.toLongOrNull() ?: return maxDelaySeconds
            return if (seconds <= 0L) null else seconds.coerceAtMost(maxDelaySeconds)
        }

        val targetMs = parseHttpDateMillis(trimmed) ?: return null
        val baselineMs = parseHttpDateMillis(dateHeader) ?: nowMs
        val deltaMs = targetMs - baselineMs
        if (deltaMs <= 0L) return null

        val ceilSeconds = (deltaMs + 999L) / 1_000L
        return ceilSeconds.coerceIn(1L, maxDelaySeconds)
    }

    fun parseOkHttpResponse(
        response: Response,
        nowMs: Long = System.currentTimeMillis(),
        maxDelaySeconds: Long = MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
    ): Long? = parseRetryAfterSeconds(
        retryAfterHeader = response.header("Retry-After"),
        dateHeader = response.header("Date"),
        nowMs = nowMs,
        maxDelaySeconds = maxDelaySeconds
    )

    fun parseHeaderFields(
        headerFields: Map<out String?, List<String>>,
        nowMs: Long = System.currentTimeMillis(),
        maxDelaySeconds: Long = MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS
    ): Long? {
        val retryAfter = firstHeaderValue(headerFields, "Retry-After")
        val dateHeader = firstHeaderValue(headerFields, "Date")
        return parseRetryAfterSeconds(
            retryAfterHeader = retryAfter,
            dateHeader = dateHeader,
            nowMs = nowMs,
            maxDelaySeconds = maxDelaySeconds
        )
    }

    internal fun firstHeaderValue(headerFields: Map<out String?, List<String>>, headerName: String): String? =
        headerFields.entries
            .firstOrNull { (key, _) -> key != null && key.trim().equals(headerName, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    internal fun parseHttpDateMillis(raw: String?): Long? {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        runCatching {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(cleaned)).toEpochMilli()
        }
        runCatching {
            return ZonedDateTime.parse(cleaned, RFC850_FORMATTER).toInstant().toEpochMilli()
        }
        val normalizedSpaces = cleaned.replace(MULTI_SPACE_REGEX, " ")
        runCatching {
            return ZonedDateTime.parse(normalizedSpaces, ASCTIME_FORMATTER).toInstant().toEpochMilli()
        }
        return null
    }
}
