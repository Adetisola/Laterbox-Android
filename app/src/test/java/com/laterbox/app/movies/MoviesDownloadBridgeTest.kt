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
}
