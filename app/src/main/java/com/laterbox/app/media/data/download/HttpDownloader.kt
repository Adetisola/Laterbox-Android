package com.laterbox.app.media.data.download

import com.laterbox.app.media.domain.model.DownloadStage
import com.laterbox.app.media.domain.model.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.laterbox.app.diagnostics.MediaDiagContext
import com.laterbox.app.diagnostics.MediaRequestKind
import com.laterbox.app.diagnostics.withMediaDiagnostics
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed class DownloadResult {
    data class Success(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val etag: String? = null,
        val cacheReusedBytes: Long = 0L,
        val networkBytesFetched: Long = downloadedBytes
    ) : DownloadResult()
    data class Failure(
        val code: FailureCode,
        val message: String,
        val httpStatus: Int? = null,
        val retryAfterSeconds: Long? = null
    ) : DownloadResult()
}

internal data class ContentRangeSpec(
    val start: Long,
    val end: Long,
    val total: Long?
)

class HttpDownloader(
    baseClient: OkHttpClient = sharedClient
) {
    /** Adds redirect-safe credential scoping + media network diagnostics to every download hop. */
    private val client: OkHttpClient = baseClient.withMediaDiagnostics(MediaRequestKind.DOWNLOAD)
    internal val httpClient: OkHttpClient get() = client

    suspend fun download(
        url: String,
        destinationFile: File,
        headers: Map<String, String> = emptyMap(),
        expectedETag: String? = null,
        expectedTotalBytes: Long? = null,
        onTelemetry: (suspend (speedBps: Long?, etaSeconds: Long?, etag: String?) -> Unit)? = null,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?, percent: Int, stage: DownloadStage?) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        var restartedFromZero = false
        var finalResult: DownloadResult? = null

        while (finalResult == null) {
            val rawExistingLength = if (!restartedFromZero && destinationFile.exists()) {
                destinationFile.length()
            } else {
                0L
            }
            val existingLength = if (
                rawExistingLength > 0L &&
                expectedTotalBytes != null &&
                expectedTotalBytes > 0L &&
                rawExistingLength > expectedTotalBytes
            ) {
                destinationFile.delete()
                restartedFromZero = true
                0L
            } else {
                rawExistingLength
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36")

            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            if (existingLength > 0L) {
                requestBuilder.header("Range", "bytes=$existingLength-")
                if (!expectedETag.isNullOrBlank()) {
                    requestBuilder.header("If-Range", expectedETag)
                }
            }

            requestBuilder.tag(
                MediaDiagContext::class.java,
                MediaDiagContext(
                    kind = MediaRequestKind.DOWNLOAD,
                    format = url.toHttpUrlOrNull()?.let { MediaDiagContext.inferFormat(it) } ?: "UNKNOWN",
                    signedCookieExpected = headers.keys.any { it.equals("Cookie", ignoreCase = true) && headers[it].orEmpty().isNotBlank() },
                )
            )
            val request = requestBuilder.build()

            try {
                val response = client.newCall(request).execute()
                finalResult = response.use { resp ->
                    if (resp.code == 416 && existingLength > 0L && !restartedFromZero) {
                        destinationFile.delete()
                        restartedFromZero = true
                        return@use null
                    }

                    if (!resp.isSuccessful && resp.code != 206) {
                        val retryAfterSec = com.laterbox.app.network.RetryAfterParser.parseOkHttpResponse(
                            response = resp,
                            maxDelaySeconds = com.laterbox.app.network.RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS
                        )
                        val code = when (resp.code) {
                            401, 403 -> FailureCode.PRIVATE_CONTENT
                            404, 410 -> FailureCode.UNSUPPORTED_URL
                            429 -> FailureCode.RATE_LIMITED
                            in 500..599 -> FailureCode.HTTP_ERROR
                            else -> FailureCode.HTTP_ERROR
                        }
                        val msg = when (resp.code) {
                            429 -> if (retryAfterSec != null) {
                                "Downloads are temporarily busy. Please try again in ${retryAfterSec}s."
                            } else {
                                "Downloads are temporarily busy. Please try again shortly."
                            }
                            401, 403 -> "This download couldn't be started right now. Please try again."
                            404, 410 -> "This file is no longer available to download."
                            else -> "Unable to download right now. Please try again shortly."
                        }
                        return@use DownloadResult.Failure(
                            code = code,
                            message = msg,
                            httpStatus = resp.code,
                            retryAfterSeconds = retryAfterSec
                        )
                    }

                    val responseETag = resp.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    val isPartial = resp.code == 206
                    val parsedRange = if (isPartial) parseContentRange(resp.header("Content-Range")) else null

                    val body = resp.body ?: return@use DownloadResult.Failure(
                        code = FailureCode.HTTP_ERROR,
                        message = "Download interrupted. Please try again."
                    )
                    val contentLength = body.contentLength()

                    if (isPartial) {
                        val eTagMismatch = existingLength > 0L &&
                            !expectedETag.isNullOrBlank() &&
                            !responseETag.isNullOrBlank() &&
                            expectedETag != responseETag
                        val rangeInvalid = parsedRange == null ||
                            parsedRange.start != existingLength ||
                            parsedRange.end < parsedRange.start
                        val totalMismatch = existingLength > 0L &&
                            parsedRange?.total != null &&
                            expectedTotalBytes != null &&
                            expectedTotalBytes > 0L &&
                            parsedRange.total != expectedTotalBytes
                        val expectedSliceLength = parsedRange?.let { it.end - it.start + 1L }
                        val sliceContentLengthMismatch = contentLength > 0L &&
                            expectedSliceLength != null &&
                            contentLength != expectedSliceLength

                        if (eTagMismatch || rangeInvalid || totalMismatch || sliceContentLengthMismatch) {
                            if (existingLength > 0L && !restartedFromZero) {
                                destinationFile.delete()
                                restartedFromZero = true
                                return@use null
                            }
                            return@use DownloadResult.Failure(
                                code = FailureCode.HTTP_ERROR,
                                message = "Unable to resume this download cleanly. Please retry."
                            )
                        }
                    }

                    val appendMode = isPartial && existingLength > 0L
                    if (!appendMode && existingLength > 0L && destinationFile.exists()) {
                        destinationFile.delete()
                    }

                    val totalBytes = when {
                        isPartial && parsedRange?.total != null -> parsedRange.total
                        isPartial && parsedRange != null -> parsedRange.end + 1L
                        contentLength > 0L && appendMode -> existingLength + contentLength
                        contentLength > 0L -> contentLength
                        expectedTotalBytes != null && expectedTotalBytes > 0L -> expectedTotalBytes
                        else -> null
                    }
                    val expectedResponseBytes = when {
                        isPartial && parsedRange != null -> parsedRange.end - parsedRange.start + 1L
                        contentLength > 0L -> contentLength
                        else -> null
                    }

                    onTelemetry?.invoke(null, null, responseETag)

                    var bytesDownloaded = if (appendMode) existingLength else 0L
                    var responseBytesRead = 0L
                    destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

                    val buffer = ByteArray(32 * 1024)
                    var lastProgressTime = 0L
                    var lastReportedPercent = -1
                    var lastTelemetryTime = System.currentTimeMillis()
                    var lastTelemetryBytes = bytesDownloaded
                    var smoothedSpeedBps: Double? = null

                    FileOutputStream(destinationFile, appendMode).use { output ->
                        body.byteStream().use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                coroutineContext.ensureActive()
                                output.write(buffer, 0, read)
                                bytesDownloaded += read
                                responseBytesRead += read

                                val now = System.currentTimeMillis()
                                val percent = if (totalBytes != null && totalBytes > 0L) {
                                    ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else 0

                                if (percent != lastReportedPercent || now - lastProgressTime >= 250L) {
                                    onProgress(bytesDownloaded, totalBytes, percent, DownloadStage.DOWNLOADING)
                                    lastReportedPercent = percent
                                    lastProgressTime = now
                                }

                                val elapsedMs = now - lastTelemetryTime
                                if (elapsedMs >= 500L) {
                                    val deltaBytes = bytesDownloaded - lastTelemetryBytes
                                    val instantBps = if (elapsedMs > 0L && deltaBytes >= 0L) {
                                        (deltaBytes * 1000.0) / elapsedMs.toDouble()
                                    } else 0.0
                                    val updatedSpeed = smoothedSpeedBps?.let { prev ->
                                        0.3 * instantBps + 0.7 * prev
                                    } ?: instantBps
                                    smoothedSpeedBps = updatedSpeed
                                    val speedLong = updatedSpeed.toLong().coerceAtLeast(0L)
                                    val eta = if (totalBytes != null && totalBytes > bytesDownloaded && speedLong > 0L) {
                                        (totalBytes - bytesDownloaded) / speedLong
                                    } else null
                                    onTelemetry?.invoke(speedLong, eta, responseETag)
                                    lastTelemetryTime = now
                                    lastTelemetryBytes = bytesDownloaded
                                }
                            }
                        }
                    }

                    val shortResponseTransfer = expectedResponseBytes != null &&
                        responseBytesRead != expectedResponseBytes
                    val shortTotalTransfer = totalBytes != null &&
                        totalBytes > 0L &&
                        bytesDownloaded != totalBytes
                    if (shortResponseTransfer || shortTotalTransfer) {
                        val currentPercent = if (totalBytes != null && totalBytes > 0L) {
                            ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                        } else 0
                        onProgress(bytesDownloaded, totalBytes, currentPercent, DownloadStage.DOWNLOADING)
                        return@use DownloadResult.Failure(
                            code = FailureCode.NETWORK_ERROR,
                            message = if (totalBytes != null) {
                                "Incomplete transfer: received $bytesDownloaded of $totalBytes bytes"
                            } else {
                                "Incomplete transfer: response stream ended prematurely"
                            }
                        )
                    }

                    onTelemetry?.invoke(null, 0L, responseETag)
                    onProgress(bytesDownloaded, totalBytes, 100, DownloadStage.FINALIZING)
                    DownloadResult.Success(
                        downloadedBytes = bytesDownloaded,
                        totalBytes = totalBytes ?: bytesDownloaded,
                        etag = responseETag
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val failureCode = when (e) {
                    is UnknownHostException, is ConnectException, is SocketTimeoutException -> FailureCode.NETWORK_ERROR
                    is IOException -> FailureCode.NETWORK_ERROR
                    else -> FailureCode.UNKNOWN
                }
                finalResult = DownloadResult.Failure(
                    code = failureCode,
                    message = e.localizedMessage ?: "Download failed: ${e.javaClass.simpleName}"
                )
            }
        }

        finalResult
    }

    internal companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(com.laterbox.app.network.ResilientDns.INSTANCE)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        private val CONTENT_RANGE_REGEX = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

        fun parseContentRange(header: String?): ContentRangeSpec? {
            if (header.isNullOrBlank()) return null
            val match = CONTENT_RANGE_REGEX.matchEntire(header) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val totalStr = match.groupValues[3]
            val total = if (totalStr == "*") null else totalStr.toLongOrNull()
            if (start < 0L || end < start) return null
            if (total != null && end >= total) return null
            return ContentRangeSpec(start = start, end = end, total = total)
        }
    }
}
