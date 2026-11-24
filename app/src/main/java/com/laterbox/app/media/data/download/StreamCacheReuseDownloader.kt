package com.laterbox.app.media.data.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import com.laterbox.app.media.domain.model.DownloadStage
import com.laterbox.app.media.domain.model.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Reuses cached byte ranges from Media3's [Cache] when downloading compatible progressive
 * HTTP streams, fetching only missing byte ranges over HTTP and falling back cleanly to
 * standard [HttpDownloader] on any mismatch or partial validation failure.
 */
@OptIn(UnstableApi::class)
class StreamCacheReuseDownloader(
    private val cache: Cache,
    private val client: OkHttpClient
) {

    internal data class ProbeMetadata(
        val totalBytes: Long,
        val etag: String?,
        val lastModified: String?,
        val acceptsByteRanges: Boolean
    )

    internal sealed class ProbeOutcome {
        data class Verified(val metadata: ProbeMetadata) : ProbeOutcome()
        data class AuthOrNotFoundFailure(val failure: DownloadResult.Failure) : ProbeOutcome()
        data object UnsupportedFallback : ProbeOutcome()
    }

    suspend fun tryDownloadReusingCache(
        url: String,
        destinationFile: File,
        headers: Map<String, String> = emptyMap(),
        onTelemetry: (suspend (speedBps: Long?, etaSeconds: Long?, etag: String?) -> Unit)? = null,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?, percent: Int, stage: DownloadStage?) -> Unit
    ): DownloadResult? = withContext(Dispatchers.IO) {
        if (!isProgressiveHttpUrl(url)) return@withContext null
        if (destinationFile.exists() && destinationFile.length() > 0L) return@withContext null

        val matchedEntry = findCandidateCacheEntry(url) ?: return@withContext null
        val (matchedKey, exactMatch, spans) = matchedEntry
        if (spans.isEmpty()) return@withContext null

        val cachedLength = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(matchedKey))
        }.getOrDefault(-1L)

        val probe = probeRemoteStream(url, headers)
        val meta = when (probe) {
            is ProbeOutcome.Verified -> probe.metadata
            is ProbeOutcome.AuthOrNotFoundFailure -> return@withContext probe.failure
            ProbeOutcome.UnsupportedFallback -> return@withContext null
        }

        if (cachedLength > 0L && cachedLength != meta.totalBytes) {
            return@withContext null
        }

        val validSpans = spans
            .filter { it.isCached && it.file != null && it.file!!.exists() && it.length > 0L && it.position < meta.totalBytes }
            .sortedBy { it.position }
        if (validSpans.isEmpty()) return@withContext null

        if (!exactMatch) {
            val verifiedByNormalizedMatch = verifyNormalizedMatchWithRemote(
                matchedKey = matchedKey,
                cachedLength = cachedLength,
                remoteMeta = meta,
                spans = validSpans,
                url = url,
                headers = headers
            )
            if (!verifiedByNormalizedMatch) return@withContext null
        }

        val totalBytes = meta.totalBytes
        val cachedCoveredBytes = computeCoveredBytes(validSpans, totalBytes)
        if (cachedCoveredBytes <= 0L) return@withContext null
        if (cachedCoveredBytes < totalBytes && !meta.acceptsByteRanges) {
            return@withContext null
        }

        return@withContext runCatching {
            assembleFromCacheAndMissingRanges(
                url = url,
                headers = headers,
                meta = meta,
                spans = validSpans,
                destinationFile = destinationFile,
                onTelemetry = onTelemetry,
                onProgress = onProgress
            )
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            runCatching { if (destinationFile.exists()) destinationFile.delete() }
            null
        }
    }

    private suspend fun assembleFromCacheAndMissingRanges(
        url: String,
        headers: Map<String, String>,
        meta: ProbeMetadata,
        spans: List<CacheSpan>,
        destinationFile: File,
        onTelemetry: (suspend (speedBps: Long?, etaSeconds: Long?, etag: String?) -> Unit)?,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?, percent: Int, stage: DownloadStage?) -> Unit
    ): DownloadResult? {
        val totalBytes = meta.totalBytes
        destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        var cursor = 0L
        var reusedBytes = 0L
        var networkBytes = 0L
        var lastProgressTime = 0L
        var lastReportedPercent = -1
        val buffer = ByteArray(32 * 1024)

        onTelemetry?.invoke(null, null, meta.etag)
        onProgress(0L, totalBytes, 0, DownloadStage.DOWNLOADING)

        fun abortUnsafeAssembly(): DownloadResult? {
            runCatching { if (destinationFile.exists()) destinationFile.delete() }
            return null
        }

        suspend fun preservePrefixAndFail(
            code: FailureCode,
            message: String,
            httpStatus: Int? = null,
            retryAfterSeconds: Long? = null
        ): DownloadResult.Failure {
            val verifiedFileSize = runCatching { destinationFile.length() }.getOrDefault(-1L)
            if (cursor > 0L && verifiedFileSize == cursor) {
                val pct = ((cursor * 100L) / totalBytes).toInt().coerceIn(0, 99)
                onProgress(cursor, totalBytes, pct, DownloadStage.DOWNLOADING)
            } else {
                runCatching { if (destinationFile.exists()) destinationFile.delete() }
            }
            return DownloadResult.Failure(
                code = code,
                message = message,
                httpStatus = httpStatus,
                retryAfterSeconds = retryAfterSeconds
            )
        }

        FileOutputStream(destinationFile, false).use { output ->
            var spanIdx = 0
            while (cursor < totalBytes) {
                coroutineContext.ensureActive()

                while (spanIdx < spans.size && (spans[spanIdx].position + spans[spanIdx].length) <= cursor) {
                    spanIdx++
                }

                val currentSpan = spans.getOrNull(spanIdx)
                if (currentSpan != null && currentSpan.position <= cursor) {
                    val spanEnd = (currentSpan.position + currentSpan.length).coerceAtMost(totalBytes)
                    val offsetInSpan = cursor - currentSpan.position
                    val bytesToCopy = spanEnd - cursor
                    val spanFile = currentSpan.file
                    if (spanFile == null || !spanFile.exists() || spanFile.length() < offsetInSpan + bytesToCopy) {
                        output.close()
                        return abortUnsafeAssembly()
                    }
                    FileInputStream(spanFile).use { input ->
                        var skipped = 0L
                        while (skipped < offsetInSpan) {
                            val s = input.skip(offsetInSpan - skipped)
                            if (s <= 0L) {
                                val byteRead = input.read()
                                if (byteRead == -1) {
                                    output.close()
                                    return abortUnsafeAssembly()
                                }
                                skipped++
                            } else {
                                skipped += s
                            }
                        }
                        var remaining = bytesToCopy
                        while (remaining > 0L) {
                            coroutineContext.ensureActive()
                            val chunk = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (chunk <= 0) {
                                output.close()
                                return abortUnsafeAssembly()
                            }
                            output.write(buffer, 0, chunk)
                            remaining -= chunk
                            cursor += chunk
                            reusedBytes += chunk
                        }
                    }

                    val now = System.currentTimeMillis()
                    val percent = ((cursor * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    if (percent != lastReportedPercent || now - lastProgressTime >= 250L) {
                        onProgress(cursor, totalBytes, percent, DownloadStage.DOWNLOADING)
                        lastReportedPercent = percent
                        lastProgressTime = now
                    }
                } else {
                    val gapEnd = (currentSpan?.position ?: totalBytes).coerceAtMost(totalBytes)
                    if (gapEnd <= cursor) {
                        output.close()
                        return abortUnsafeAssembly()
                    }

                    val reqBuilder = Request.Builder()
                        .url(url)
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("Range", "bytes=$cursor-${gapEnd - 1}")
                    headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                    meta.etag?.let { reqBuilder.header("If-Range", it) }

                    val response = client.newCall(reqBuilder.build()).execute()
                    response.use { resp ->
                        when (resp.code) {
                            429 -> {
                                val retryAfterSec = com.laterbox.app.network.RetryAfterParser.parseOkHttpResponse(
                                    response = resp,
                                    maxDelaySeconds = com.laterbox.app.network.RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
                                )
                                output.flush()
                                output.close()
                                val msg = if (retryAfterSec != null) {
                                    "Streaming server is rate-limiting downloads (HTTP 429). Retry in ${retryAfterSec}s."
                                } else {
                                    "Streaming server is temporarily busy (HTTP 429). Please retry shortly."
                                }
                                return preservePrefixAndFail(
                                    code = FailureCode.RATE_LIMITED,
                                    message = msg,
                                    httpStatus = 429,
                                    retryAfterSeconds = retryAfterSec
                                )
                            }
                            401, 403 -> {
                                output.flush()
                                output.close()
                                return preservePrefixAndFail(
                                    code = FailureCode.PRIVATE_CONTENT,
                                    message = "HTTP ${resp.code}: ${resp.message}",
                                    httpStatus = resp.code
                                )
                            }
                            404, 410 -> {
                                output.flush()
                                output.close()
                                return preservePrefixAndFail(
                                    code = FailureCode.UNSUPPORTED_URL,
                                    message = "HTTP ${resp.code}: ${resp.message}",
                                    httpStatus = resp.code
                                )
                            }
                        }
                        if (resp.code != 206) {
                            output.close()
                            return abortUnsafeAssembly()
                        }
                        val respEtag = resp.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                        if (meta.etag != null && respEtag != null && meta.etag != respEtag) {
                            output.close()
                            return abortUnsafeAssembly()
                        }
                        val parsedRange = HttpDownloader.parseContentRange(resp.header("Content-Range"))
                        if (parsedRange == null ||
                            parsedRange.start != cursor ||
                            parsedRange.end < cursor ||
                            (parsedRange.total != null && parsedRange.total != totalBytes)
                        ) {
                            output.close()
                            return abortUnsafeAssembly()
                        }
                        val body = resp.body ?: run {
                            output.close()
                            return abortUnsafeAssembly()
                        }
                        var remaining = gapEnd - cursor
                        body.byteStream().use { input ->
                            while (remaining > 0L) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                remaining -= read
                                cursor += read
                                networkBytes += read

                                val now = System.currentTimeMillis()
                                val percent = ((cursor * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastReportedPercent || now - lastProgressTime >= 250L) {
                                    onProgress(cursor, totalBytes, percent, DownloadStage.DOWNLOADING)
                                    lastReportedPercent = percent
                                    lastProgressTime = now
                                }
                            }
                        }
                        if (remaining > 0L) {
                            // Mid-gap network stream cut short after verified contiguous writes up to cursor.
                            output.flush()
                            output.close()
                            return preservePrefixAndFail(
                                code = FailureCode.NETWORK_ERROR,
                                message = "Incomplete transfer during stream assembly ($cursor of $totalBytes bytes)"
                            )
                        }
                    }
                }
            }
        }

        if (cursor != totalBytes) return abortUnsafeAssembly()
        onTelemetry?.invoke(null, 0L, meta.etag)
        onProgress(totalBytes, totalBytes, 100, DownloadStage.FINALIZING)
        return DownloadResult.Success(
            downloadedBytes = totalBytes,
            totalBytes = totalBytes,
            etag = meta.etag,
            cacheReusedBytes = reusedBytes,
            networkBytesFetched = networkBytes
        )
    }

    private data class CandidateMatch(
        val cacheKey: String,
        val exactMatch: Boolean,
        val spans: List<CacheSpan>
    )

    private fun findCandidateCacheEntry(url: String): CandidateMatch? {
        val exactSpans = runCatching { cache.getCachedSpans(url) }.getOrNull()
        if (!exactSpans.isNullOrEmpty() && exactSpans.any { it.isCached && it.length > 0L }) {
            return CandidateMatch(url, exactMatch = true, spans = exactSpans.toList())
        }

        val targetPath = normalizeUrlPath(url) ?: return null
        val keys = runCatching { cache.keys.toList() }.getOrDefault(emptyList())
        for (key in keys) {
            if (normalizeUrlPath(key) == targetPath) {
                val spans = runCatching { cache.getCachedSpans(key) }.getOrNull()
                if (!spans.isNullOrEmpty() && spans.any { it.isCached && it.length > 0L }) {
                    return CandidateMatch(key, exactMatch = false, spans = spans.toList())
                }
            }
        }
        return null
    }

    private fun verifyNormalizedMatchWithRemote(
        matchedKey: String,
        cachedLength: Long,
        remoteMeta: ProbeMetadata,
        spans: List<CacheSpan>,
        url: String,
        headers: Map<String, String>
    ): Boolean {
        if (cachedLength <= 0L || cachedLength != remoteMeta.totalBytes) {
            return false
        }
        val metadata = runCatching { cache.getContentMetadata(matchedKey) }.getOrNull()
        val cachedEtag = metadata?.let { runCatching { it.get("laterbox_etag", "") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        val cachedLastMod = metadata?.let { runCatching { it.get("laterbox_last_mod", "") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        val etagOrLastModMatches = when {
            cachedEtag != null && remoteMeta.etag != null -> cachedEtag == remoteMeta.etag
            cachedLastMod != null && remoteMeta.lastModified != null -> cachedLastMod == remoteMeta.lastModified
            else -> false
        }
        if (etagOrLastModMatches) return true

        // Fallback byte-level validator check: verify first 64 bytes at offset 0 match if cached from 0
        if (!remoteMeta.acceptsByteRanges) return false
        val firstSpan = spans.firstOrNull { it.position == 0L && it.length >= 64L } ?: return false
        val spanFile = firstSpan.file ?: return false
        val localPrefix = ByteArray(64)
        val localRead = runCatching {
            FileInputStream(spanFile).use { it.read(localPrefix) }
        }.getOrDefault(0)
        if (localRead < 64) return false

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Range", "bytes=0-63")
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        return runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code != 206) return@use false
                val remoteBytes = resp.body?.bytes() ?: return@use false
                remoteBytes.size == 64 && remoteBytes.contentEquals(localPrefix)
            }
        }.getOrDefault(false)
    }

    private fun probeRemoteStream(url: String, headers: Map<String, String>): ProbeOutcome {
        val headBuilder = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", DEFAULT_USER_AGENT)
        headers.forEach { (k, v) -> headBuilder.header(k, v) }

        val headResult = runCatching {
            client.newCall(headBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    401, 403 -> return ProbeOutcome.AuthOrNotFoundFailure(
                        DownloadResult.Failure(FailureCode.PRIVATE_CONTENT, "HTTP ${resp.code}: ${resp.message}", resp.code)
                    )
                    404, 410 -> return ProbeOutcome.AuthOrNotFoundFailure(
                        DownloadResult.Failure(FailureCode.UNSUPPORTED_URL, "HTTP ${resp.code}: ${resp.message}", resp.code)
                    )
                    429 -> {
                        val retryAfterSec = com.laterbox.app.network.RetryAfterParser.parseOkHttpResponse(
                            response = resp,
                            maxDelaySeconds = com.laterbox.app.network.RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
                        )
                        return ProbeOutcome.AuthOrNotFoundFailure(
                            DownloadResult.Failure(
                                FailureCode.RATE_LIMITED,
                                "HTTP 429: ${resp.message}",
                                429,
                                retryAfterSeconds = retryAfterSec
                            )
                        )
                    }
                    in 200..299 -> {
                        val len = resp.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                        val acceptRanges = resp.header("Accept-Ranges")
                            ?.contains("bytes", ignoreCase = true) == true
                        val etag = resp.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                        val lastMod = resp.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
                        if (len > 0L) {
                            return ProbeOutcome.Verified(ProbeMetadata(len, etag, lastMod, acceptRanges))
                        }
                    }
                }
            }
        }
        if (headResult.isFailure) {
            return ProbeOutcome.UnsupportedFallback
        }

        // Range 0-0 probe fallback when HEAD omits Content-Length or returns 405
        val rangeBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Range", "bytes=0-0")
        headers.forEach { (k, v) -> rangeBuilder.header(k, v) }

        return runCatching {
            client.newCall(rangeBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    401, 403 -> ProbeOutcome.AuthOrNotFoundFailure(
                        DownloadResult.Failure(FailureCode.PRIVATE_CONTENT, "HTTP ${resp.code}: ${resp.message}", resp.code)
                    )
                    404, 410 -> ProbeOutcome.AuthOrNotFoundFailure(
                        DownloadResult.Failure(FailureCode.UNSUPPORTED_URL, "HTTP ${resp.code}: ${resp.message}", resp.code)
                    )
                    429 -> {
                        val retryAfterSec = com.laterbox.app.network.RetryAfterParser.parseOkHttpResponse(
                            response = resp,
                            maxDelaySeconds = com.laterbox.app.network.RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
                        )
                        ProbeOutcome.AuthOrNotFoundFailure(
                            DownloadResult.Failure(
                                FailureCode.RATE_LIMITED,
                                "HTTP 429: ${resp.message}",
                                429,
                                retryAfterSeconds = retryAfterSec
                            )
                        )
                    }
                    206 -> {
                        val parsed = HttpDownloader.parseContentRange(resp.header("Content-Range"))
                        val total = parsed?.total ?: -1L
                        if (total > 0L) {
                            ProbeOutcome.Verified(
                                ProbeMetadata(
                                    totalBytes = total,
                                    etag = resp.header("ETag")?.trim()?.takeIf { it.isNotEmpty() },
                                    lastModified = resp.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() },
                                    acceptsByteRanges = true
                                )
                            )
                        } else {
                            ProbeOutcome.UnsupportedFallback
                        }
                    }
                    else -> ProbeOutcome.UnsupportedFallback
                }
            }
        }.getOrDefault(ProbeOutcome.UnsupportedFallback)
    }

    private fun computeCoveredBytes(spans: List<CacheSpan>, totalBytes: Long): Long {
        var covered = 0L
        var lastEnd = 0L
        for (span in spans) {
            val start = span.position.coerceAtLeast(lastEnd).coerceAtMost(totalBytes)
            val end = (span.position + span.length).coerceAtMost(totalBytes)
            if (end > start) {
                covered += (end - start)
                lastEnd = end
            }
        }
        return covered
    }

    internal companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Laterbox) AppleWebKit/537.36"

        fun isProgressiveHttpUrl(url: String): Boolean {
            val lower = url.trim().lowercase(Locale.US)
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            val pathPart = lower.substringBefore('?').substringBefore('#')
            return !pathPart.endsWith(".m3u8") && !pathPart.endsWith(".mpd")
        }

        fun normalizeUrlPath(rawUrl: String): String? = runCatching {
            val uri = URI(rawUrl.trim())
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching null
            val host = uri.host?.lowercase(Locale.US) ?: return@runCatching null
            val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty()
            "$scheme://$host$portSuffix$path"
        }.getOrNull()
    }
}
