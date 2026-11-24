package com.laterbox.app.movies

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.media.domain.model.AcquisitionSource
import com.laterbox.app.media.domain.model.DownloadTask
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesDownloadBridgeTest {

    private lateinit var database: LaterboxDatabase
    private lateinit var fakeMediaRepository: RecordingMediaRepository
    private lateinit var bridge: MoviesDownloadBridge

    private data class EnqueuedCall(
        val candidate: MediaCandidate,
        val sourcePlatform: String,
        val title: String?,
        val artworkUrl: String?,
        val versionTag: String?,
        val contentCategory: String?,
        val sourceRef: String?,
        val urlExpiresAt: Long?
    )

    private class RecordingMediaRepository : MediaRepository {
        val enqueued = mutableListOf<EnqueuedCall>()

        override fun getAssetsForLink(linkId: Long): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAllAssets(): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun filterAssets(mediaType: MediaType?, query: String): Flow<List<MediaAsset>> = flowOf(emptyList())
        override fun getAssetById(id: String): Flow<MediaAsset?> = flowOf(null)
        override fun getTasksForLink(linkId: Long): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getQueueTasks(): Flow<List<DownloadTask>> = flowOf(emptyList())
        override fun getTaskById(taskId: String): Flow<DownloadTask?> = flowOf(null)
        override fun getStorageBreakdown(): Flow<Map<MediaType, Long>> = flowOf(emptyMap())
        override fun getAssetCount(): Flow<Int> = flowOf(0)

        override suspend fun enqueueDownload(
            linkId: Long,
            candidate: MediaCandidate,
            sourcePlatform: String,
            title: String?,
            artist: String?,
            album: String?,
            artworkUrl: String?,
            spotifyTrackId: String?,
            versionTag: String?
        ): String {
            error("Movies downloads should call enqueueStandaloneDownload")
        }

        override suspend fun enqueueStandaloneDownload(
            candidate: MediaCandidate,
            sourcePlatform: String,
            title: String?,
            artworkUrl: String?,
            versionTag: String?,
            contentCategory: String?,
            sourceRef: String?,
            urlExpiresAt: Long?
        ): String {
            enqueued.add(
                EnqueuedCall(
                    candidate = candidate,
                    sourcePlatform = sourcePlatform,
                    title = title,
                    artworkUrl = artworkUrl,
                    versionTag = versionTag,
                    contentCategory = contentCategory,
                    sourceRef = sourceRef,
                    urlExpiresAt = urlExpiresAt
                )
            )
            return "task_${enqueued.size}"
        }

        override suspend fun overrideCellularRequirement(taskId: String) {}
        override suspend fun pauseDownload(taskId: String) {}
        override suspend fun resumeDownload(taskId: String) {}
        override suspend fun cancelDownload(taskId: String) {}
        override suspend fun dismissTask(taskId: String) {}
        override suspend fun clearTerminalTasks() {}
        override suspend fun retryDownload(taskId: String) {}
        override suspend fun deleteAsset(assetId: String) {}
        override suspend fun exportAsset(assetId: String): Result<String> = Result.success("")
        override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
        override suspend fun runOrphanSweep() {}
        override suspend fun recoverInterruptedTasks() {}
        override fun getInspectionForLink(linkId: Long): Flow<InspectionResult?> = flowOf(null)
        override fun isInspectingLink(linkId: Long): Flow<Boolean> = flowOf(false)
        override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
        override suspend fun cancelInspection(linkId: Long) {}
    }

    private val sampleSummary = MovieTitleSummary(
        id = "his_girl_friday",
        providerId = "ia",
        kind = MediaKind.MOVIE,
        title = "His Girl Friday",
        releaseYear = 1940,
        posterUrl = "https://archive.org/services/img/his_girl_friday",
        sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
        isPlayableHint = true
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeMediaRepository = RecordingMediaRepository()
        bridge = MoviesDownloadBridge(fakeMediaRepository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enqueueAuthorisedDownload_enqueuesStandaloneWithoutInboxPollution() = runTest {
        val source720 = PlaybackSource(
            id = "src_720",
            url = "https://archive.org/download/his_girl_friday/his_girl_friday_720p.mp4?exp=1800000000",
            format = "MP4",
            qualityLabel = "720p",
            qualityHeight = 720,
            sizeMb = 620.0,
            codec = "h264",
            providerName = "Internet Archive Cinema",
            isAuthorised = true
        )

        val result = bridge.enqueueAuthorisedDownload(
            summary = sampleSummary,
            source = source720,
            episodeLabel = null
        )

        assertTrue(result.isSuccess)
        assertEquals("task_1", result.getOrNull())
        assertEquals(1, fakeMediaRepository.enqueued.size)
        assertEquals(0, database.savedLinkDao().getInboxCount())

        val call = fakeMediaRepository.enqueued.first()
        assertEquals("His Girl Friday (720p)", call.title)
        assertEquals("720p", call.versionTag)
        assertEquals("Internet Archive Cinema", call.sourcePlatform)
        assertEquals(MoviesDownloadBridge.CATEGORY_MOVIE, call.contentCategory)
        assertEquals("ia|his_girl_friday|0|0|720p", call.sourceRef)
        assertEquals(1800000000_000L, call.urlExpiresAt)
        assertEquals(MediaType.VIDEO, call.candidate.mediaType)
        assertEquals(720, call.candidate.height)
        assertEquals("mp4", call.candidate.ext)
        assertTrue(call.candidate.source is AcquisitionSource.DirectHttp)
    }

    @Test
    fun enqueueAuthorisedDownload_rejectsUnauthorisedOrNonHttpSources() = runTest {
        val unauthorised = PlaybackSource(
            id = "bad",
            url = "https://example.com/unauthorised.mp4",
            format = "MP4",
            qualityLabel = "720p",
            qualityHeight = 720,
            providerName = "External",
            isAuthorised = false
        )
        val res1 = bridge.enqueueAuthorisedDownload(sampleSummary, unauthorised)
        assertTrue(res1.isFailure)
        assertTrue(fakeMediaRepository.enqueued.isEmpty())

        val badScheme = PlaybackSource(
            id = "bad_scheme",
            url = "ftp://archive.org/video.mp4",
            format = "MP4",
            qualityLabel = "480p",
            qualityHeight = 480,
            providerName = "Internet Archive Cinema",
            isAuthorised = true
        )
        val res2 = bridge.enqueueAuthorisedDownload(sampleSummary, badScheme)
        assertTrue(res2.isFailure)
        assertTrue(fakeMediaRepository.enqueued.isEmpty())

        val adaptiveDash = PlaybackSource(
            id = "dash_1080",
            url = "https://cdn.example/index.mpd",
            format = "DASH",
            qualityLabel = "1080p (Adaptive)",
            qualityHeight = 1080,
            providerName = "MovieBox",
            isAuthorised = true
        )
        val res3 = bridge.enqueueAuthorisedDownload(sampleSummary, adaptiveDash)
        assertTrue(res3.isFailure)
        val adaptiveMsg = res3.exceptionOrNull()?.message.orEmpty()
        assertTrue(adaptiveMsg.contains("online", ignoreCase = true))
        assertFalse(adaptiveMsg.contains("Adaptive", ignoreCase = true))
        assertTrue(fakeMediaRepository.enqueued.isEmpty())
    }

    @Test
    fun downloadSourceRefresher_detectsExpiryParsesRefAndUpdatesTaskInRoom() = runTest {
        val now = 1_700_000_000_000L
        assertTrue(com.laterbox.app.media.data.download.DownloadSourceRefresher.isUrlExpiredOrExpiring(now + 30_000L, nowMs = now))
        assertTrue(!com.laterbox.app.media.data.download.DownloadSourceRefresher.isUrlExpiredOrExpiring(now + 120_000L, nowMs = now))
        assertTrue(!com.laterbox.app.media.data.download.DownloadSourceRefresher.isUrlExpiredOrExpiring(null, nowMs = now))

        val taskDao = database.downloadTaskDao()
        val initialEntity = com.laterbox.app.media.data.db.entity.DownloadTaskEntity(
            id = "task_refresh_1",
            savedLinkId = null,
            sourceUrl = "https://cdn.example/old.mp4?exp=1700000010",
            sourcePlatform = "MovieBox",
            candidateId = "c1",
            formatSelector = null,
            mediaType = MediaType.VIDEO.name,
            qualityLabel = "720p · MP4",
            title = "Severance · S1E2 (720p)",
            status = com.laterbox.app.media.domain.model.DownloadState.DOWNLOADING.name,
            createdAt = now - 10_000L,
            updatedAt = now - 10_000L,
            sourceRef = "moviebox|mb_series_1|1|2|720p",
            urlExpiresAt = 1_700_000_010_000L
        )
        taskDao.insertTask(initialEntity)

        val refreshed = com.laterbox.app.media.data.download.DownloadSourceRefresher.refreshTaskSource(
            task = initialEntity,
            taskDao = taskDao,
            resolvePlayback = { providerId, titleId, season, episode ->
                assertEquals("moviebox", providerId)
                assertEquals("mb_series_1", titleId)
                assertEquals(1, season)
                assertEquals(2, episode)
                Result.success(
                    com.laterbox.app.movies.domain.model.PlaybackResolution.Available(
                        title = "Severance (S1E2)",
                        sources = listOf(
                            PlaybackSource(
                                id = "s720_new",
                                url = "https://cdn.example/fresh.mp4?exp=1850000000",
                                format = "MP4",
                                qualityLabel = "720p",
                                qualityHeight = 720,
                                headers = mapOf("Referer" to "https://api3.aoneroom.com/"),
                                providerName = "MovieBox",
                                isAuthorised = true
                            )
                        )
                    )
                )
            }
        )

        assertTrue(refreshed != null)
        assertEquals("https://cdn.example/fresh.mp4?exp=1850000000", refreshed!!.sourceUrl)
        assertEquals(1_850_000_000_000L, refreshed.urlExpiresAt)
        val persisted = taskDao.getEntityById("task_refresh_1")!!
        assertEquals("https://cdn.example/fresh.mp4?exp=1850000000", persisted.sourceUrl)
        assertEquals(1_850_000_000_000L, persisted.urlExpiresAt)
        assertEquals("https://api3.aoneroom.com/", persisted.toDomain().headers["Referer"])
    }

    @Test
    fun enqueueAuthorisedDownload_classifiesShortDramaAsEpisodicAndPreservesSourceRef() = runTest {
        val shortDrama = MovieTitleSummary(
            id = "sd_777",
            providerId = "moviebox",
            kind = MediaKind.SHORT_DRAMA,
            title = "Secret Billionaire",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true,
            subjectType = 7
        )
        val source = PlaybackSource(
            id = "src_sd_720",
            url = "https://cdn.example/sd_777_s1e3.mp4",
            format = "MP4",
            qualityLabel = "720p",
            qualityHeight = 720,
            providerName = "Streaming Catalogue",
            isAuthorised = true
        )
        val result = bridge.enqueueAuthorisedDownload(
            summary = shortDrama,
            source = source,
            episodeLabel = "S1 · E3",
            seasonNumber = 1,
            episodeNumber = 3
        )
        assertTrue(result.isSuccess)
        val call = fakeMediaRepository.enqueued.single()
        assertEquals(MoviesDownloadBridge.CATEGORY_EPISODE, call.contentCategory)
        assertEquals("moviebox|sd_777|1|3|720p", call.sourceRef)
        assertEquals("Secret Billionaire · S1 · E3 (720p)", call.title)
    }

    @Test
    fun parseUrlExpiryMillis_preservesExplicitExpiriesAndIgnoresUnknownT() {
        val nowMs = 1_763_900_000_000L
        val futureExpSec = 1_763_903_600L // +1 hour
        val nearExpSec = (nowMs / 1_000L) + 30L // inside 60s expiry buffer
        val pastExpSec = (nowMs / 1_000L) - 120L // already expired

        // Explicit future, near-expiry, and already-expired exp/expires/Expires are preserved
        // so isUrlExpiredOrExpiring can detect expiry accurately.
        val futureParsed = MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?sign=s&exp=$futureExpSec")
        val nearParsed = MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?sign=s&expires=$nearExpSec")
        val pastParsed = MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?sign=s&Expires=$pastExpSec")

        assertEquals(futureExpSec * 1_000L, futureParsed)
        assertEquals(nearExpSec * 1_000L, nearParsed)
        assertEquals(pastExpSec * 1_000L, pastParsed)
        assertFalse(com.laterbox.app.media.data.download.DownloadSourceRefresher.isUrlExpiredOrExpiring(futureParsed, nowMs))
        assertTrue(com.laterbox.app.media.data.download.DownloadSourceRefresher.isUrlExpiredOrExpiring(nearParsed, nowMs))
        assertTrue(com.laterbox.app.media.data.download.DownloadSourceRefresher.isUrlExpiredOrExpiring(pastParsed, nowMs))

        // Unknown t= parameter must NEVER infer expiry regardless of timestamp
        val recentTSec = (nowMs / 1_000L) - 60L
        val futureTSec = (nowMs / 1_000L) + 600L
        assertEquals(null, MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?sign=s&t=$recentTSec"))
        assertEquals(null, MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?sign=s&t=$futureTSec"))

        // Invalid or non-positive exp values return null
        assertEquals(null, MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?exp=0"))
        assertEquals(null, MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?exp=-100"))
        assertEquals(null, MoviesDownloadBridge.parseUrlExpiryMillis("https://bcdnxw.hakunaymatata.com/v.mp4?exp=abc"))
    }

    @Test
    fun httpDownloader_propagatesRetryAfterOn429() = runTest {
        val mockServer = okhttp3.mockwebserver.MockWebServer()
        mockServer.start()
        try {
            mockServer.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(429)
                    .addHeader("Retry-After", "17")
            )
            val dest = java.io.File.createTempFile("dl_429_test", ".mp4").apply { delete() }
            try {
                val res = com.laterbox.app.media.data.download.HttpDownloader(okhttp3.OkHttpClient()).download(
                    url = mockServer.url("/movie.mp4").toString(),
                    destinationFile = dest,
                    onProgress = { _, _, _, _ -> }
                )
                assertTrue(res is com.laterbox.app.media.data.download.DownloadResult.Failure)
                val fail = res as com.laterbox.app.media.data.download.DownloadResult.Failure
                assertEquals(com.laterbox.app.media.domain.model.FailureCode.RATE_LIMITED, fail.code)
                assertEquals(429, fail.httpStatus)
                assertEquals(17L, fail.retryAfterSeconds)
            } finally {
                dest.delete()
            }
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun downloadSourceRefresher_preservesPartialAndEtagWhenPathAndQualityMatch() = runTest {
        val now = 1_750_000_000_000L
        val taskDao = database.downloadTaskDao()
        val partFile = java.io.File.createTempFile("refresh_compat", ".part").apply {
            writeText("ValidPrefixBytes")
        }
        try {
            val task = com.laterbox.app.media.data.db.entity.DownloadTaskEntity(
                id = "task_compat_1",
                savedLinkId = null,
                sourceUrl = "https://bcdnxw.hakunaymatata.com/resource/movie_720.mp4?sign=old&exp=1750000010",
                sourcePlatform = "MovieBox",
                candidateId = "c1",
                formatSelector = null,
                mediaType = MediaType.VIDEO.name,
                qualityLabel = "720p · MP4",
                title = "Sample Movie (720p)",
                status = com.laterbox.app.media.domain.model.DownloadState.DOWNLOADING.name,
                progress = 25,
                downloadedBytes = partFile.length(),
                totalBytes = 100L * 1024L * 1024L,
                etag = "\"etag-same\"",
                versionTag = "720p",
                createdAt = now - 5_000L,
                updatedAt = now - 5_000L,
                sourceRef = "moviebox|mb_1|0|0|720p",
                urlExpiresAt = 1_750_000_010_000L
            )
            taskDao.insertTask(task)

            val refreshed = com.laterbox.app.media.data.download.DownloadSourceRefresher.refreshTaskSource(
                task = task,
                taskDao = taskDao,
                partFile = partFile,
                resolvePlayback = { _, _, _, _ ->
                    Result.success(
                        com.laterbox.app.movies.domain.model.PlaybackResolution.Available(
                            title = "Sample Movie",
                            sources = listOf(
                                PlaybackSource(
                                    id = "s720",
                                    url = "https://bcdnxw.hakunaymatata.com/resource/movie_720.mp4?sign=new&exp=1850000000",
                                    format = "MP4",
                                    qualityLabel = "720p",
                                    qualityHeight = 720,
                                    sizeMb = 100.0,
                                    providerName = "MovieBox",
                                    isAuthorised = true
                                )
                            )
                        )
                    )
                }
            )!!

            assertTrue(partFile.exists())
            assertEquals("ValidPrefixBytes", partFile.readText())
            assertEquals("\"etag-same\"", refreshed.etag)
            assertEquals(partFile.length(), refreshed.downloadedBytes)
            assertEquals(25, refreshed.progress)
            val persisted = taskDao.getEntityById("task_compat_1")!!
            assertEquals("\"etag-same\"", persisted.etag)
            assertEquals(partFile.length(), persisted.downloadedBytes)
            assertEquals(25, persisted.progress)
        } finally {
            partFile.delete()
        }
    }

    @Test
    fun downloadSourceRefresher_resetsPartAndMetadataWhenQualityOrPathChanges() = runTest {
        val now = 1_750_000_000_000L
        val taskDao = database.downloadTaskDao()
        val partFile = java.io.File.createTempFile("refresh_incompat", ".part").apply {
            writeText("Old720pPartialData")
        }
        try {
            val task = com.laterbox.app.media.data.db.entity.DownloadTaskEntity(
                id = "task_incompat_1",
                savedLinkId = null,
                sourceUrl = "https://bcdnxw.hakunaymatata.com/resource/movie_720.mp4?sign=old",
                sourcePlatform = "MovieBox",
                candidateId = "c1",
                formatSelector = null,
                mediaType = MediaType.VIDEO.name,
                qualityLabel = "720p · MP4",
                title = "Sample Movie (720p)",
                status = com.laterbox.app.media.domain.model.DownloadState.DOWNLOADING.name,
                progress = 40,
                downloadedBytes = partFile.length(),
                totalBytes = 800L * 1024L * 1024L,
                etag = "\"etag-720p\"",
                versionTag = "720p",
                createdAt = now - 5_000L,
                updatedAt = now - 5_000L,
                sourceRef = "moviebox|mb_1|0|0|720p"
            )
            taskDao.insertTask(task)

            // Fallback returns a different quality (480p) on a different host/path
            val refreshed = com.laterbox.app.media.data.download.DownloadSourceRefresher.refreshTaskSource(
                task = task,
                taskDao = taskDao,
                excludeHost = "bcdnxw.hakunaymatata.com",
                partFile = partFile,
                resolvePlayback = { _, _, _, _ ->
                    Result.success(
                        com.laterbox.app.movies.domain.model.PlaybackResolution.Available(
                            title = "Sample Movie",
                            sources = listOf(
                                PlaybackSource(
                                    id = "s480_mirror",
                                    url = "https://mirror.example.net/resource/movie_480.mp4?sign=new",
                                    format = "MP4",
                                    qualityLabel = "480p",
                                    qualityHeight = 480,
                                    sizeMb = 350.0,
                                    providerName = "MovieBox",
                                    isAuthorised = true
                                )
                            )
                        )
                    )
                }
            )!!

            assertFalse(partFile.exists())
            assertEquals(null, refreshed.etag)
            assertEquals(0L, refreshed.downloadedBytes)
            assertEquals(0, refreshed.progress)
            assertEquals("480p · MP4", refreshed.qualityLabel)
            assertEquals("480p", refreshed.versionTag)
            assertEquals((350.0 * 1024.0 * 1024.0).toLong(), refreshed.totalBytes)

            val persisted = taskDao.getEntityById("task_incompat_1")!!
            assertEquals(null, persisted.etag)
            assertEquals(0L, persisted.downloadedBytes)
            assertEquals(0, persisted.progress)
            assertEquals("480p · MP4", persisted.qualityLabel)
            assertEquals("480p", persisted.versionTag)
            assertEquals((350.0 * 1024.0 * 1024.0).toLong(), persisted.totalBytes)
        } finally {
            partFile.delete()
        }
    }
}
