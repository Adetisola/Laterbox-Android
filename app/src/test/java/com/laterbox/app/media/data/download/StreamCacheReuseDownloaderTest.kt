package com.laterbox.app.media.data.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamCacheReuseDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var cache: SimpleCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        cacheDir = tempFolder.newFolder("exo_cache_${System.nanoTime()}")
        cache = SimpleCache(cacheDir, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }

    @After
    fun tearDown() {
        runCatching { cache.release() }
        server.shutdown()
    }

    private fun seedCacheRange(key: String, totalBytes: Long, start: Long, bytes: ByteArray) {
        val span = cache.startReadWrite(key, start, bytes.size.toLong())
        val writable = cache.startFile(key, start, bytes.size.toLong())
        writable.writeBytes(bytes)
        cache.commitFile(writable, bytes.size.toLong())
        cache.releaseHoleSpan(span)

        val mutations = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(mutations, totalBytes)
        cache.applyContentMetadataMutations(key, mutations)
    }

    @Test
    fun partialCacheHit_reusesCachedPrefixAndFetchesOnlyMissingBytes() = runTest {
        val fullPayload = ByteArray(1024) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val url = server.url("/streams/movie_720.mp4").toString()
        seedCacheRange(url, totalBytes = 1024L, start = 0L, bytes = fullPayload.copyOfRange(0, 600))

        val requestedRanges = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Length", "1024")
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("ETag", "\"etag-v1\"")
                }
                val rangeHeader = request.getHeader("Range")
                requestedRanges.add(rangeHeader)
                return if (rangeHeader == "bytes=600-1023") {
                    val body = Buffer().write(fullPayload, 600, 424)
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 600-1023/1024")
                        .setHeader("Content-Length", "424")
                        .setHeader("ETag", "\"etag-v1\"")
                        .setBody(body)
                } else {
                    MockResponse().setResponseCode(416)
                }
            }
        }

        val dest = tempFolder.newFile("reuse_partial.part").apply { delete() }
        val downloader = StreamCacheReuseDownloader(cache, OkHttpClient())
        val result = downloader.tryDownloadReusingCache(
            url = url,
            destinationFile = dest,
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(1024L, success.totalBytes)
        assertEquals(600L, success.cacheReusedBytes)
        assertEquals(424L, success.networkBytesFetched)
        assertEquals(listOf("bytes=600-1023"), requestedRanges)
        assertArrayEquals(fullPayload, dest.readBytes())
    }

    @Test
    fun normalizedPathMatchWithRotatedQueryToken_verifiesAndReusesCache() = runTest {
        val fullPayload = ByteArray(512) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val cachedKey = server.url("/cdn/video.mp4?token=old_exp").toString()
        val liveUrl = server.url("/cdn/video.mp4?token=new_fresh").toString()
        seedCacheRange(cachedKey, totalBytes = 512L, start = 0L, bytes = fullPayload.copyOfRange(0, 256))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Length", "512")
                        .setHeader("Accept-Ranges", "bytes")
                }
                return when (request.getHeader("Range")) {
                    "bytes=0-63" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-63/512")
                        .setBody(Buffer().write(fullPayload, 0, 64))
                    "bytes=256-511" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 256-511/512")
                        .setBody(Buffer().write(fullPayload, 256, 256))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val dest = tempFolder.newFile("reuse_norm.part").apply { delete() }
        val result = StreamCacheReuseDownloader(cache, OkHttpClient()).tryDownloadReusingCache(
            url = liveUrl,
            destinationFile = dest,
            onProgress = { _, _, _, _ -> }
        ) as DownloadResult.Success

        assertEquals(256L, result.cacheReusedBytes)
        assertEquals(256L, result.networkBytesFetched)
        assertArrayEquals(fullPayload, dest.readBytes())
    }

    @Test
    fun contentLengthMismatch_fallsBackCleanlyReturningNull() = runTest {
        val url = server.url("/streams/changed.mp4").toString()
        seedCacheRange(url, totalBytes = 1024L, start = 0L, bytes = ByteArray(256))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", "2048")
                    .setHeader("Accept-Ranges", "bytes")
            }
        }

        val dest = tempFolder.newFile("mismatch.part").apply { delete() }
        val result = StreamCacheReuseDownloader(cache, OkHttpClient()).tryDownloadReusingCache(
            url = url,
            destinationFile = dest,
            onProgress = { _, _, _, _ -> }
        )

        assertNull(result)
        assertFalse(dest.exists())
    }

    @Test
    fun fullCacheHit_completesWithZeroNetworkPayloadBytes() = runTest {
        val fullPayload = ByteArray(768) { idx -> (idx and 0xFF).toByte() }
        val url = server.url("/streams/full.mp4").toString()
        seedCacheRange(url, totalBytes = 768L, start = 0L, bytes = fullPayload)

        var getCalls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Length", "768")
                        .setHeader("Accept-Ranges", "bytes")
                }
                getCalls++
                return MockResponse().setResponseCode(500)
            }
        }

        val dest = tempFolder.newFile("full_cache.part").apply { delete() }
        val result = StreamCacheReuseDownloader(cache, OkHttpClient()).tryDownloadReusingCache(
            url = url,
            destinationFile = dest,
            onProgress = { _, _, _, _ -> }
        ) as DownloadResult.Success

        assertEquals(768L, result.cacheReusedBytes)
        assertEquals(0L, result.networkBytesFetched)
        assertEquals(0, getCalls)
        assertArrayEquals(fullPayload, dest.readBytes())
    }

    @Test
    fun head429_returnsRateLimitedWithRetryAfterImmediately_withoutRangeProbe() = runTest {
        val url = server.url("/streams/rate_limited.mp4").toString()
        seedCacheRange(url, totalBytes = 1024L, start = 0L, bytes = ByteArray(256) { 1 })

        var headCalls = 0
        var rangeCalls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    headCalls++
                    return MockResponse()
                        .setResponseCode(429)
                        .addHeader("Retry-After", "19")
                }
                rangeCalls++
                return MockResponse().setResponseCode(500)
            }
        }

        val dest = tempFolder.newFile("ratelimited.part").apply { delete() }
        val result = StreamCacheReuseDownloader(cache, OkHttpClient()).tryDownloadReusingCache(
            url = url,
            destinationFile = dest,
            onProgress = { _, _, _, _ -> }
        ) as DownloadResult.Failure

        assertEquals(1, headCalls)
        assertEquals(0, rangeCalls)
        assertEquals(com.laterbox.app.media.domain.model.FailureCode.RATE_LIMITED, result.code)
        assertEquals(429, result.httpStatus)
        assertEquals(19L, result.retryAfterSeconds)
    }
}
