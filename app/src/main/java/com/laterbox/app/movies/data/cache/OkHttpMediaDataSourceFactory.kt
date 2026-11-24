package com.laterbox.app.movies.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import okhttp3.Call
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Media3 [HttpDataSource.Factory] backed by [OkHttpClient] so ExoPlayer media playback
 * benefits from [com.laterbox.app.network.ResilientDns] DoH fallback, session cookie
 * handling across manifest/segment requests, and consistent HTTP headers/timeouts without
 * requiring an external media3-datasource-okhttp artifact.
 */
@UnstableApi
class OkHttpMediaDataSourceFactory(
    callFactory: Call.Factory,
    private val userAgent: String? = null,
    private val cacheControl: CacheControl? = null,
) : HttpDataSource.Factory {

    private val sessionCookieJar = MediaSessionCookieJar()
    private val callFactory: Call.Factory = if (callFactory is OkHttpClient) {
        val builder = callFactory.newBuilder()
        if (callFactory.cookieJar === CookieJar.NO_COOKIES) {
            // Save-only: BridgeInterceptor must not overwrite the Cookie header built in makeRequest().
            builder.cookieJar(object : CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) =
                    sessionCookieJar.saveFromResponse(url, cookies)
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = emptyList()
            })
        }
        builder.addNetworkInterceptor(CrossDomainAuthHeaderGuardInterceptor())
        builder.build()
    } else {
        callFactory
    }

    private val defaultRequestProperties = HttpDataSource.RequestProperties()

    override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory {
        defaultRequestProperties.clearAndSet(properties)
        return this
    }

    override fun createDataSource(): HttpDataSource {
        return OkHttpMediaDataSource(
            callFactory = callFactory,
            sessionCookieJar = sessionCookieJar,
            userAgent = userAgent,
            cacheControl = cacheControl,
            defaultRequestProperties = defaultRequestProperties,
        )
    }

    private class OkHttpMediaDataSource(
        private val callFactory: Call.Factory,
        private val sessionCookieJar: MediaSessionCookieJar,
        private val userAgent: String?,
        private val cacheControl: CacheControl?,
        private val defaultRequestProperties: HttpDataSource.RequestProperties,
    ) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

        private val requestProperties = HttpDataSource.RequestProperties()
        private var dataSpec: DataSpec? = null
        private var response: Response? = null
        private var responseByteStream: InputStream? = null
        private var opened = false
        private var bytesToRead = 0L
        private var bytesRead = 0L

        override fun setRequestProperty(name: String, value: String) {
            requestProperties.set(name, value)
        }

        override fun clearRequestProperty(name: String) {
            requestProperties.remove(name)
        }

        override fun clearAllRequestProperties() {
            requestProperties.clear()
        }

        override fun getResponseCode(): Int = response?.code ?: -1

        override fun getResponseHeaders(): Map<String, List<String>> =
            response?.headers?.toMultimap() ?: emptyMap()

        override fun getUri(): Uri? =
            response?.request?.url?.toString()?.let { Uri.parse(it) } ?: dataSpec?.uri

        override fun open(dataSpec: DataSpec): Long {
            this.dataSpec = dataSpec
            bytesRead = 0L
            bytesToRead = 0L
            transferInitializing(dataSpec)

            val request = makeRequest(dataSpec)
            val resp: Response = try {
                callFactory.newCall(request).execute()
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e,
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            response = resp
            val responseBody = resp.body
            responseByteStream = responseBody?.byteStream()

            val responseCode = resp.code
            if (!resp.isSuccessful) {
                if (responseCode == 416) {
                    val documentSize = HttpUtil.getDocumentSize(resp.header("Content-Range"))
                    if (dataSpec.position == documentSize) {
                        opened = true
                        transferStarted(dataSpec)
                        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
                    }
                }
                val errorBody = runCatching { responseBody?.bytes() }.getOrNull() ?: ByteArray(0)
                val headerMap = resp.headers.toMultimap()
                closeConnectionQuietly()
                val cause = if (responseCode == 416) {
                    androidx.media3.datasource.DataSourceException(
                        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                    )
                } else {
                    null
                }
                throw HttpDataSource.InvalidResponseCodeException(
                    responseCode,
                    resp.message,
                    cause,
                    headerMap,
                    dataSpec,
                    errorBody,
                )
            }

            val bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) {
                dataSpec.position
            } else {
                0L
            }

            bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                val contentLength = responseBody?.contentLength() ?: -1L
                if (contentLength != -1L) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
            }

            opened = true
            transferStarted(dataSpec)

            if (bytesToSkip > 0L) {
                try {
                    skipFully(bytesToSkip, dataSpec)
                } catch (e: HttpDataSource.HttpDataSourceException) {
                    closeConnectionQuietly()
                    throw e
                }
            }

            return bytesToRead
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return try {
                readInternal(buffer, offset, length)
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e,
                    dataSpec!!,
                    HttpDataSource.HttpDataSourceException.TYPE_READ,
                )
            }
        }

        override fun close() {
            if (opened) {
                opened = false
                transferEnded()
                closeConnectionQuietly()
            }
        }

        private fun makeRequest(dataSpec: DataSpec): Request {
            val url = dataSpec.uri.toString().toHttpUrlOrNull()
                ?: throw HttpDataSource.HttpDataSourceException(
                    "Malformed URL",
                    dataSpec,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )

            val builder = Request.Builder().url(url)
            cacheControl?.let { builder.cacheControl(it) }

            val lowerPath = url.encodedPath.lowercase(Locale.ROOT)
            val isSubtitleAsset = lowerPath.endsWith(".srt") ||
                lowerPath.endsWith(".vtt") ||
                lowerPath.endsWith(".ass") ||
                lowerPath.endsWith(".ssa") ||
                lowerPath.endsWith(".ttml")

            val mergedHeaders = LinkedHashMap<String, String>()
            defaultRequestProperties.snapshot.forEach { (k, v) ->
                if (!isSubtitleAsset || !isStreamScopedAuthHeader(k)) {
                    mergedHeaders[k] = v
                }
            }
            requestProperties.snapshot.forEach { (k, v) ->
                if (!isSubtitleAsset || !isStreamScopedAuthHeader(k)) {
                    mergedHeaders[k] = v
                }
            }
            mergedHeaders.putAll(dataSpec.httpRequestHeaders)

            val jarCookies = sessionCookieJar.loadForRequest(url)
            val headersBuilder = Headers.Builder()
            var hasUserAgent = false
            var explicitCookieHeader: String? = null
            for ((k, v) in mergedHeaders) {
                if (k.equals("User-Agent", ignoreCase = true)) {
                    hasUserAgent = true
                }
                if (k.equals("Cookie", ignoreCase = true)) {
                    explicitCookieHeader = v
                    continue
                }
                headersBuilder.set(k, v)
            }
            val mergedCookieHeader = mergeCookieHeaders(explicitCookieHeader, jarCookies)
            if (!mergedCookieHeader.isNullOrBlank()) {
                headersBuilder.set("Cookie", mergedCookieHeader)
            }
            if (!hasUserAgent && !userAgent.isNullOrBlank()) {
                headersBuilder.set("User-Agent", userAgent)
            }
            if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
                headersBuilder.set("Accept-Encoding", "identity")
            }
            val rangeHeader = HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)
            if (rangeHeader != null) {
                headersBuilder.set("Range", rangeHeader)
            }
            builder.headers(headersBuilder.build())
            builder.tag(InitialMediaHost::class.java, InitialMediaHost(url.host))

            val body = dataSpec.httpBody?.let { bytes ->
                val contentType = mergedHeaders.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.toMediaTypeOrNull()
                bytes.toRequestBody(contentType)
            } ?: if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
                ByteArray(0).toRequestBody(null)
            } else {
                null
            }
            builder.method(dataSpec.getHttpMethodString(), body)
            return builder.build()
        }

        private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
            if (bytesToSkip == 0L) return
            val stream = responseByteStream ?: throw HttpDataSource.HttpDataSourceException(
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
            val skipBuffer = ByteArray(4096)
            var remaining = bytesToSkip
            try {
                while (remaining > 0L) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException()
                    }
                    val toRead = min(remaining, skipBuffer.size.toLong()).toInt()
                    val read = stream.read(skipBuffer, 0, toRead)
                    if (read == -1) {
                        throw HttpDataSource.HttpDataSourceException(
                            dataSpec,
                            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                        )
                    }
                    remaining -= read
                    bytesTransferred(read)
                }
            } catch (e: IOException) {
                if (e is HttpDataSource.HttpDataSourceException) throw e
                throw HttpDataSource.HttpDataSourceException(
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
        }

        private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val expectedLength = if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                val bytesRemaining = bytesToRead - bytesRead
                if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
                min(length.toLong(), bytesRemaining).toInt()
            } else {
                length
            }
            val stream = responseByteStream ?: return C.RESULT_END_OF_INPUT
            val read = stream.read(buffer, offset, expectedLength)
            if (read == -1) {
                if (bytesToRead != C.LENGTH_UNSET.toLong() && bytesRead < bytesToRead) {
                    throw EOFException()
                }
                return C.RESULT_END_OF_INPUT
            }
            bytesRead += read
            bytesTransferred(read)
            return read
        }

        private fun closeConnectionQuietly() {
            runCatching { responseByteStream?.close() }
            runCatching { response?.close() }
            responseByteStream = null
            response = null
        }
    }

    private data class InitialMediaHost(val host: String)

    private class MediaSessionCookieJar : CookieJar {
        private val lock = Any()
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(lock) {
                val now = System.currentTimeMillis()
                for (cookie in cookies) {
                    val key = cookie.domain.lowercase(Locale.ROOT)
                    val list = store.getOrPut(key) { mutableListOf() }
                    list.removeAll { existing ->
                        existing.expiresAt <= now ||
                            (existing.name == cookie.name &&
                                existing.domain.equals(cookie.domain, ignoreCase = true) &&
                                existing.path == cookie.path)
                    }
                    if (cookie.expiresAt > now) {
                        list.add(cookie)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val matching = mutableListOf<Cookie>()
                val iterator = store.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    entry.value.removeAll { it.expiresAt <= now }
                    if (entry.value.isEmpty()) {
                        iterator.remove()
                        continue
                    }
                    for (cookie in entry.value) {
                        if (cookie.matches(url)) {
                            matching.add(cookie)
                        }
                    }
                }
                return matching
            }
        }
    }

    private inner class CrossDomainAuthHeaderGuardInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val initialHost = req.tag(InitialMediaHost::class.java)?.host
            if (initialHost == null || isSameSiteHost(initialHost, req.url.host)) {
                return chain.proceed(req)
            }
            val cleanedBuilder = req.newBuilder()
            for (name in req.headers.names()) {
                if (isStreamScopedAuthHeader(name) || name.equals("Authorization", ignoreCase = true)) {
                    cleanedBuilder.removeHeader(name)
                }
            }
            val targetCookies = sessionCookieJar.loadForRequest(req.url)
            if (targetCookies.isNotEmpty()) {
                cleanedBuilder.header(
                    "Cookie",
                    targetCookies.joinToString("; ") { "${it.name}=${it.value}" }
                )
            }
            return chain.proceed(cleanedBuilder.build())
        }
    }

    private companion object {
        fun isStreamScopedAuthHeader(name: String): Boolean {
            val lower = name.trim().lowercase(Locale.ROOT)
            return lower == "cookie" ||
                lower == "x-mb-token" ||
                lower == "referer" ||
                lower == "origin" ||
                (lower.startsWith("x-") && lower.endsWith("-token"))
        }

        fun mergeCookieHeaders(explicitHeader: String?, jarCookies: List<Cookie>): String? {
            if (jarCookies.isEmpty()) return explicitHeader?.takeIf { it.isNotBlank() }
            if (explicitHeader.isNullOrBlank()) {
                return jarCookies.joinToString("; ") { "${it.name}=${it.value}" }
            }
            val ordered = LinkedHashMap<String, String>()
            explicitHeader.split(";").forEach { rawPair ->
                val pair = rawPair.trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isNotEmpty()) ordered[name] = value
                }
            }
            for (cookie in jarCookies) {
                ordered[cookie.name] = cookie.value
            }
            return if (ordered.isEmpty()) null else ordered.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        fun isSameSiteHost(hostA: String, hostB: String): Boolean {
            val a = hostA.trim().trimEnd('.').lowercase(Locale.ROOT)
            val b = hostB.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (a == b) return true
            if (a.all { it.isDigit() || it == '.' } || b.all { it.isDigit() || it == '.' } || ':' in a || ':' in b) {
                return false
            }
            val partsA = a.split('.')
            val partsB = b.split('.')
            if (partsA.size >= 2 && partsB.size >= 2) {
                val siteA = "${partsA[partsA.size - 2]}.${partsA[partsA.size - 1]}"
                val siteB = "${partsB[partsB.size - 2]}.${partsB[partsB.size - 1]}"
                return siteA == siteB
            }
            return false
        }
    }
}
