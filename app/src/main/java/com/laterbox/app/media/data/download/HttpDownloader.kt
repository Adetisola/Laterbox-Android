package com.laterbox.app.media.data.download

import com.laterbox.app.media.domain.model.DownloadStage
import com.laterbox.app.media.domain.model.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
        val etag: String? = null
    ) : DownloadResult()
    data class Failure(val code: FailureCode, val message: String, val httpStatus: Int? = null) : DownloadResult()
}

internal data class ContentRangeSpec(
    val start: Long,
    val end: Long,
    val total: Long?
)

class HttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    suspend fun download(
        url: String,
        destinationFile: File,
        headers: Map<String, String> = emptyMap(),
        expectedETag: String? = null,
        onTelemetry: (suspend (speedBps: Long?, etaSeconds: Long?, etag: String?) -> Unit)? = null,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?, percent: Int, stage: DownloadStage?) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        var restartedFromZero = false
        var finalResult: DownloadResult? = null

        while (finalResult == null) {
            val existingLength = if (!restartedFromZero && destinationFile.exists()) {
                destinationFile.length()
            } else {
                0L
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Laterbox) AppleWebKit/537.36")

            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            if (existingLength > 0L) {
                requestBuilder.header("Range", "bytes=$existingLength-")
                if (!expectedETag.isNullOrBlank()) {
                    requestBuilder.header("If-Range", expectedETag)
                }
            }

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
                        val code = when (resp.code) {
                            401, 403 -> FailureCode.PRIVATE_CONTENT
                            404, 410 -> FailureCode.UNSUPPORTED_URL
                            429 -> FailureCode.RATE_LIMITED
                            in 500..599 -> FailureCode.HTTP_ERROR
                            else -> FailureCode.HTTP_ERROR
                        }
                        return@use DownloadResult.Failure(
                            code = code,
                            message = "HTTP ${resp.code}: ${resp.message}",
                            httpStatus = resp.code
                        )
                    }

                    val responseETag = resp.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    val isPartial = resp.code == 206

                    if (isPartial && existingLength > 0L) {
                        val parsedRange = parseContentRange(resp.header("Content-Range"))
                        val eTagMismatch = !expectedETag.isNullOrBlank() &&
                            !responseETag.isNullOrBlank() &&
                            expectedETag != responseETag
                        val rangeInvalid = parsedRange == null ||
                            parsedRange.start != existingLength ||
                            parsedRange.end < parsedRange.start

                        if (eTagMismatch || rangeInvalid) {
                            if (!restartedFromZero) {
                                destinationFile.delete()
                                restartedFromZero = true
                                return@use null
                            }
                            return@use DownloadResult.Failure(
                                code = FailureCode.HTTP_ERROR,
                                message = "Invalid 206 Content-Range or ETag mismatch on resume"
                            )
                        }
                    }

                    val body = resp.body ?: return@use DownloadResult.Failure(
                        code = FailureCode.HTTP_ERROR,
                        message = "Response body is empty"
                    )

                    val appendMode = isPartial && existingLength > 0L
                    if (!appendMode && existingLength > 0L && destinationFile.exists()) {
                        destinationFile.delete()
                    }

                    val parsedRange = if (isPartial) parseContentRange(resp.header("Content-Range")) else null
                    val contentLength = body.contentLength()
                    val totalBytes = when {
                        isPartial && parsedRange?.total != null -> parsedRange.total
                        contentLength > 0L && appendMode -> existingLength + contentLength
                        contentLength > 0L -> contentLength
                        else -> null
                    }

                    onTelemetry?.invoke(null, null, responseETag)

                    var bytesDownloaded = if (appendMode) existingLength else 0L
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
