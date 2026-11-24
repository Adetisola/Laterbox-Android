package com.laterbox.app.movies.data.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.laterbox.app.network.RetryAfterParser

/**
 * Custom Media3 [LoadErrorHandlingPolicy] that avoids rapid multi-retry storms on HTTP 429:
 * - Returns [C.TIME_UNSET] immediately when `Retry-After` is absent, exceeds interactive
 *   bounds, or fails on non-media loads (such as manifests/init), surfacing the 429 and
 *   its headers directly to `MoviePlayerViewModel` once.
 * - Allows at most one delayed retry on [C.DATA_TYPE_MEDIA] only when the server provides an
 *   explicit short `Retry-After` within [maxInlineRetrySeconds].
 * - Delegates all non-429 HTTP errors and ordinary network failures to [DefaultLoadErrorHandlingPolicy].
 */
@UnstableApi
class LaterboxLoadErrorHandlingPolicy(
    private val maxInlineRetrySeconds: Long = RetryAfterParser.MAX_INLINE_MEDIA3_RETRY_DELAY_SECONDS,
    private val clockMs: () -> Long = System::currentTimeMillis
) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpError = findInvalidResponseCodeException(loadErrorInfo.exception)
        if (httpError?.responseCode == 401 || httpError?.responseCode == 403) {
            return C.TIME_UNSET
        }
        if (httpError?.responseCode == 429) {
            if (loadErrorInfo.errorCount > 1) {
                return C.TIME_UNSET
            }
            if (loadErrorInfo.mediaLoadData.dataType != C.DATA_TYPE_MEDIA) {
                return C.TIME_UNSET
            }
            val parsedDelaySec = RetryAfterParser.parseHeaderFields(
                headerFields = httpError.headerFields,
                nowMs = clockMs(),
                maxDelaySeconds = RetryAfterParser.MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS
            ) ?: return C.TIME_UNSET

            if (parsedDelaySec > maxInlineRetrySeconds) {
                return C.TIME_UNSET
            }
            return parsedDelaySec * 1_000L
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        super.getMinimumLoadableRetryCount(dataType)

    companion object {
        internal fun findInvalidResponseCodeException(
            error: Throwable?
        ): HttpDataSource.InvalidResponseCodeException? {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 10) {
                if (current is HttpDataSource.InvalidResponseCodeException) {
                    return current
                }
                current = current.cause
                depth++
            }
            return null
        }
    }
}
