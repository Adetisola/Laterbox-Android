package com.laterbox.app.media.data.download

import com.laterbox.app.media.data.storage.ChecksumUtil
import com.laterbox.app.media.domain.model.FailureCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpDownloader

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        downloader = HttpDownloader()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadDirectFile_writesToDestinationAndReportsProgress() = runTest {
        val payload = "Hello Laterbox Media Downloader Payload! 1234567890"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.toByteArray().size)
                .setBody(payload)
        )

        val destFile = File(tempFolder.root, "test_download.bin")
        val progressUpdates = mutableListOf<Int>()

        val result = downloader.download(
            url = server.url("/media.bin").toString(),
            destinationFile = destFile,
            onProgress = { _, _, percent, _ ->
                progressUpdates.add(percent)
            }
        )

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(payload.length.toLong(), success.downloadedBytes)
        assertEquals(payload, destFile.readText())
        assertEquals(ChecksumUtil.calculateSha256(destFile), ChecksumUtil.calculateSha256(payload.byteInputStream()))
        assertTrue(progressUpdates.contains(100))
    }

    @Test
    fun download_supportsRangeResume_whenPartFileExists() = runTest {
        val firstPart = "Part1Bytes"
        val secondPart = "Part2BytesFinished"
        val fullContent = firstPart + secondPart

        val destFile = File(tempFolder.root, "resume_test.part")
        destFile.writeText(firstPart)
        assertEquals(firstPart.length.toLong(), destFile.length())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes ${firstPart.length}-${fullContent.length - 1}/${fullContent.length}")
                .setHeader("Content-Length", secondPart.length)
                .setBody(secondPart)
        )

        val result = downloader.download(
            url = server.url("/resumable_media.mp4").toString(),
            destinationFile = destFile,
            onProgress = { _, _, _, _ -> }
        )

        val recordedRequest = server.takeRequest()
        assertEquals("bytes=${firstPart.length}-", recordedRequest.getHeader("Range"))

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(fullContent.length.toLong(), success.downloadedBytes)
        assertEquals(fullContent, destFile.readText())
    }

    @Test
    fun download_handlesServerErrorGracefully() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val destFile = File(tempFolder.root, "not_found.bin")
        val result = downloader.download(
            url = server.url("/missing.jpg").toString(),
            destinationFile = destFile,
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(result is DownloadResult.Failure)
        val failure = result as DownloadResult.Failure
        assertEquals(FailureCode.UNSUPPORTED_URL, failure.code)
        assertEquals(404, failure.httpStatus)
    }

    @Test
    fun download_restartsCleanlyWhen206HasMismatchedContentRangeOffset() = runTest {
        val fullContent = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val destFile = File(tempFolder.root, "wrong_offset.part")
        destFile.writeText("0123456789") // 10 bytes

        // Server lies in 206 Content-Range (starts at 0 instead of 10), then serves full 200 cleanly on restart
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-${fullContent.length - 1}/${fullContent.length}")
                .setHeader("Content-Length", fullContent.length)
                .setBody(fullContent)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", fullContent.length)
                .setBody(fullContent)
        )

        val result = downloader.download(
            url = server.url("/video.mp4").toString(),
            destinationFile = destFile,
            onProgress = { _, _, _, _ -> }
        )

        val firstReq = server.takeRequest()
        assertEquals("bytes=10-", firstReq.getHeader("Range"))
        val secondReq = server.takeRequest()
        assertEquals(null, secondReq.getHeader("Range"))

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(fullContent.length.toLong(), success.downloadedBytes)
        assertEquals(fullContent, destFile.readText())
    }

    @Test
    fun download_truncatesAndOverwritesCleanlyWhenServerReturns200OnResume() = runTest {
        val fullContent = "FreshFullFilePayloadAfterServerIgnoredRange"
        val destFile = File(tempFolder.root, "full_200_resume.part")
        destFile.writeText("StalePartialBytes")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag-fresh\"")
                .setHeader("Content-Length", fullContent.length)
                .setBody(fullContent)
        )

        val result = downloader.download(
            url = server.url("/video200.mp4").toString(),
            destinationFile = destFile,
            expectedETag = "\"etag-old\"",
            onProgress = { _, _, _, _ -> }
        )

        val req = server.takeRequest()
        assertEquals("bytes=${"StalePartialBytes".length}-", req.getHeader("Range"))
        assertEquals("\"etag-old\"", req.getHeader("If-Range"))

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(fullContent.length.toLong(), success.downloadedBytes)
        assertEquals("\"etag-fresh\"", success.etag)
        assertEquals(fullContent, destFile.readText())
    }

    @Test
    fun download_restartsFromZeroWhen206EtagChanges() = runTest {
        val fullContent = "VersionTwoCleanPayloadContent"
        val destFile = File(tempFolder.root, "etag_mismatch.part")
        destFile.writeText("VersionOne")

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v2\"")
                .setHeader("Content-Range", "bytes 10-${fullContent.length - 1}/${fullContent.length}")
                .setBody(fullContent.substring(10))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v2\"")
                .setHeader("Content-Length", fullContent.length)
                .setBody(fullContent)
        )

        val result = downloader.download(
            url = server.url("/changed.mp4").toString(),
            destinationFile = destFile,
            expectedETag = "\"v1\"",
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(result is DownloadResult.Success)
        assertEquals(fullContent, destFile.readText())
        assertEquals("\"v2\"", (result as DownloadResult.Success).etag)
    }

    @Test
    fun download_reportsSpeedEtaAndEtagTelemetry() = runTest {
        val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
        val payload = chunk + chunk + chunk // 192 KB
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"speed-v1\"")
                .setHeader("Content-Length", payload.size)
                .throttleBody(64L * 1024L, 300L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBody(okio.Buffer().write(payload))
        )

        val destFile = File(tempFolder.root, "telemetry.bin")
        val telemetryEvents = mutableListOf<Triple<Long?, Long?, String?>>()

        val result = downloader.download(
            url = server.url("/telemetry.bin").toString(),
            destinationFile = destFile,
            onTelemetry = { speed, eta, etag ->
                telemetryEvents.add(Triple(speed, eta, etag))
            },
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(result is DownloadResult.Success)
        assertTrue(telemetryEvents.any { it.third == "\"speed-v1\"" })
        assertTrue(telemetryEvents.any { (it.first ?: 0L) > 0L })
        assertEquals(0L, telemetryEvents.last().second)
    }

    @Test
    fun download_failsCleanlyWhenInitial206HasNonZeroContentRangeStart() = runTest {
        val destFile = File(tempFolder.root, "bad_init_206.part")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 5-14/50")
                .setBody("0123456789")
        )

        val result = downloader.download(
            url = server.url("/bad_init_206.mp4").toString(),
            destinationFile = destFile,
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(result is DownloadResult.Failure)
        assertEquals(com.laterbox.app.media.domain.model.FailureCode.HTTP_ERROR, (result as DownloadResult.Failure).code)
        assertFalse(destFile.exists())
    }

    @Test
    fun download_restartsFromZeroWhen206SliceContentLengthMismatchesRange() = runTest {
        val fullContent = "CompleteFreshPayloadForSliceMismatch"
        val destFile = File(tempFolder.root, "slice_mismatch.part")
        destFile.writeText("0123456789")

        // Claims 10..29 (20 bytes) but sends Content-Length: 5
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 10-29/${fullContent.length}")
                .setBody("12345")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody(fullContent)
        )

        val result = downloader.download(
            url = server.url("/slice_mismatch.mp4").toString(),
            destinationFile = destFile,
            expectedETag = "\"v1\"",
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(result is DownloadResult.Success)
        assertEquals(fullContent, destFile.readText())
    }

    @Test
    fun download_restartsFromZeroWhen206TotalMismatchesExpectedTotalBytes() = runTest {
        val fullContent = "NewEncodingDifferentTotalBytesPayload"
        val destFile = File(tempFolder.root, "total_mismatch.part")
        destFile.writeText("OldPrefix")

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 9-${fullContent.length - 1}/${fullContent.length}")
                .setBody(fullContent.substring(9))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"v2\"")
                .setBody(fullContent)
        )

        val result = downloader.download(
            url = server.url("/total_mismatch.mp4").toString(),
            destinationFile = destFile,
            expectedETag = "\"v1\"",
            expectedTotalBytes = 999L,
            onProgress = { _, _, _, _ -> }
        )

        assertTrue(result is DownloadResult.Success)
        assertEquals(fullContent, destFile.readText())
        assertEquals("\"v2\"", (result as DownloadResult.Success).etag)
    }

    @Test
    fun download_rejectsIncompleteTransferWhenTotalBytesNotReachedAndPreservesPartForResume() = runTest {
        val destFile = File(tempFolder.root, "short_206.part")
        // Valid 206 slice of 10 bytes for a 30-byte resource -> cannot complete as Success
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 0-9/30")
                .setBody("0123456789")
        )

        val progressUpdates = mutableListOf<Triple<Long, Long?, Int>>()
        val result = downloader.download(
            url = server.url("/short_206.mp4").toString(),
            destinationFile = destFile,
            onProgress = { bytes, total, pct, _ ->
                progressUpdates.add(Triple(bytes, total, pct))
            }
        )

        assertTrue(result is DownloadResult.Failure)
        assertEquals(com.laterbox.app.media.domain.model.FailureCode.NETWORK_ERROR, (result as DownloadResult.Failure).code)
        assertEquals(10L, destFile.length())
        assertTrue(progressUpdates.any { it.first == 10L && it.second == 30L && it.third < 100 })

        // Subsequent resume with matching ETag and expectedTotalBytes completes cleanly
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 10-29/30")
                .setBody("ABCDEFGHIJKLMNOPQRST")
        )
        val resumed = downloader.download(
            url = server.url("/short_206.mp4").toString(),
            destinationFile = destFile,
            expectedETag = "\"v1\"",
            expectedTotalBytes = 30L,
            onProgress = { _, _, _, _ -> }
        )
        assertTrue(resumed is DownloadResult.Success)
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRST", destFile.readText())
    }
}
