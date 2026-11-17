package com.laterbox.app.movies

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.repository.LinkRepository
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
import org.junit.Assert.assertNotNull
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
    private lateinit var linkRepository: LinkRepository
    private lateinit var fakeMediaRepository: RecordingMediaRepository
    private lateinit var bridge: MoviesDownloadBridge

    private data class EnqueuedCall(
        val linkId: Long,
        val candidate: MediaCandidate,
        val sourcePlatform: String,
        val title: String?,
        val artworkUrl: String?,
        val versionTag: String?
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
            enqueued.add(EnqueuedCall(linkId, candidate, sourcePlatform, title, artworkUrl, versionTag))
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
        linkRepository = LinkRepository(database.savedLinkDao())
        fakeMediaRepository = RecordingMediaRepository()
        bridge = MoviesDownloadBridge(linkRepository, fakeMediaRepository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enqueueAuthorisedDownload_createsParentSavedLinkAndEnqueuesSelectedResolution() = runTest {
        val source720 = PlaybackSource(
            id = "src_720",
            url = "https://archive.org/download/his_girl_friday/his_girl_friday_720p.mp4",
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

        val call = fakeMediaRepository.enqueued.first()
        val savedLink = database.savedLinkDao().getLinkById(call.linkId).first()
        assertNotNull(savedLink)
        assertEquals("His Girl Friday (720p)", call.title)
        assertEquals("720p", call.versionTag)
        assertEquals("Internet Archive Cinema", call.sourcePlatform)
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
    }
}
