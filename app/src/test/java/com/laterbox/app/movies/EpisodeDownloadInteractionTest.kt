package com.laterbox.app.movies

import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.DownloadTask
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.EpisodeDownloadStatus
import com.laterbox.app.movies.ui.MovieDetailViewModel
import com.laterbox.app.movies.ui.computeEpisodeDownloadStates
import com.laterbox.app.movies.ui.filterDownloadableProgressiveSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeDownloadInteractionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testSource(
        id: String,
        url: String,
        format: String,
        qualityLabel: String,
        height: Int,
        isAuthorised: Boolean = true
    ) = PlaybackSource(
        id = id,
        url = url,
        format = format,
        qualityLabel = qualityLabel,
        qualityHeight = height,
        sizeMb = 240.0,
        isAuthorised = isAuthorised,
        providerName = "MovieBox"
    )

    @Test
    fun filterDownloadableProgressiveSources_excludesAdaptiveAndUnauthorisedAndSortsDescending() {
        val sources = listOf(
            testSource("unauth", "https://cdn.example/1080.mp4", "MP4", "1080p", 1080, isAuthorised = false),
            testSource("hls", "https://cdn.example/master.m3u8", "HLS", "Auto", 0),
            testSource("dash", "https://cdn.example/manifest.mpd", "DASH", "1080p", 1080),
            testSource("sd", "https://cdn.example/360.mp4", "MP4", "360p", 360),
            testSource("fhd", "https://cdn.example/1080.mp4", "MP4", "1080p", 1080),
            testSource("hd", "https://cdn.example/720.mp4", "MP4", "720p", 720),
            testSource("hd_dup", "https://cdn.example/720_b.mp4", "MP4", "720p", 720)
        )

        val filtered = filterDownloadableProgressiveSources(sources)
        assertEquals(listOf("1080p", "720p", "360p"), filtered.map { it.qualityLabel })
    }

    @Test
    fun computeEpisodeDownloadStates_mapsAllLifecycleTransitionsAccurately() {
        val ep1 = EpisodeItem("ep1", 1, 1, "Pilot", null, null, null, null)
        val ep2 = EpisodeItem("ep2", 1, 2, "Second", null, null, null, null)
        val ep3 = EpisodeItem("ep3", 1, 3, "Third", null, null, null, null)
        val ep4 = EpisodeItem("ep4", 1, 4, "Fourth", null, null, null, null)
        val ep5 = EpisodeItem("ep5", 1, 5, "Fifth", null, null, null, null)
        val ep6 = EpisodeItem("ep6", 1, 6, "Sixth", null, null, null, null)

        val baseTime = 1_700_000_000_000L
        fun taskFor(ep: Int, status: DownloadState, progress: Int, error: String? = null) = DownloadTask(
            id = "task_$ep",
            savedLinkId = null,
            sourceUrl = "https://cdn.example/ep$ep.mp4",
            sourcePlatform = "MovieBox",
            candidateId = "src_$ep",
            formatSelector = null,
            mediaType = MediaType.VIDEO,
            qualityLabel = "720p",
            title = "S1 · E$ep",
            assetId = null,
            status = status,
            progress = progress,
            downloadedBytes = 1024L,
            totalBytes = 4096L,
            errorMessage = error,
            createdAt = baseTime,
            updatedAt = baseTime,
            sourceRef = MoviesDownloadBridge.buildSourceRef("moviebox", "show_1", 1, ep, "720p")
        )

        val states = computeEpisodeDownloadStates(
            providerId = "moviebox",
            titleId = "show_1",
            episodes = listOf(ep1, ep2, ep3, ep4, ep5, ep6),
            tasks = listOf(
                taskFor(3, DownloadState.DOWNLOADING, 42),
                taskFor(4, DownloadState.PAUSED, 67),
                taskFor(6, DownloadState.FAILED, 15, "Network dropped")
            ),
            offlineMatches = listOf(
                OfflineMediaMatch(
                    assetId = "asset_5",
                    sourceRef = MoviesDownloadBridge.buildSourceRef("moviebox", "show_1", 1, 5, "720p"),
                    localPath = "/tmp/ep5.mp4",
                    qualityLabel = "720p",
                    fileSizeBytes = 240_000_000L,
                    seasonNumber = 1,
                    episodeNumber = 5
                )
            ),
            startingKeys = setOf(1 to 2)
        )

        assertEquals(EpisodeDownloadStatus.IDLE, states[1 to 1]?.status)
        assertEquals(EpisodeDownloadStatus.STARTING, states[1 to 2]?.status)
        assertEquals(EpisodeDownloadStatus.DOWNLOADING, states[1 to 3]?.status)
        assertEquals(42, states[1 to 3]?.progressPercent)
        assertEquals(EpisodeDownloadStatus.PAUSED, states[1 to 4]?.status)
        assertEquals(67, states[1 to 4]?.progressPercent)
        assertEquals(EpisodeDownloadStatus.COMPLETED, states[1 to 5]?.status)
        assertEquals(EpisodeDownloadStatus.FAILED, states[1 to 6]?.status)
        assertEquals("Network dropped", states[1 to 6]?.errorMessage)
    }

    @Test
    fun singleResolutionAvailable_skipsPickerAndStartsImmediatelyWithoutDuplicates() = runTest(testDispatcher) {
        val fakeMediaRepo = FakeEpisodeMediaRepository()
        val fakeMoviesRepo = FakeEpisodeMoviesRepository(
            sourcesByEp = mapOf(
                1 to listOf(
                    testSource("s1_720", "https://cdn.example/s1e1_720.mp4", "MP4", "720p", 720)
                )
            )
        )
        val vm = MovieDetailViewModel(
            moviesRepository = fakeMoviesRepo,
            moviesDownloadBridge = MoviesDownloadBridge(fakeMediaRepo),
            mediaRepository = fakeMediaRepo
        )
        vm.load("moviebox", "show_1")
        advanceUntilIdle()

        val ep1 = EpisodeItem("ep_1", 1, 1, "Pilot", null, null, null, null)
        vm.onEpisodeDownloadButtonClick(ep1)
        // Rapid duplicate tap while resolving/starting must not enqueue twice
        vm.onEpisodeDownloadButtonClick(ep1)
        advanceUntilIdle()

        assertNull("Picker must be skipped when only 1 resolution exists", vm.uiState.value.episodeResolutionPicker)
        assertEquals(1, fakeMediaRepo.enqueuedRefs.size)
        assertEquals("moviebox|show_1|1|1|720p", fakeMediaRepo.enqueuedRefs.single())
        assertEquals(EpisodeDownloadStatus.DOWNLOADING, vm.uiState.value.episodeDownloads[1 to 1]?.status)
    }

    @Test
    fun multipleResolutions_opensAnchoredPickerAndHandlesPauseResumeRetry() = runTest(testDispatcher) {
        val fakeMediaRepo = FakeEpisodeMediaRepository()
        val src1080 = testSource("s1_1080", "https://cdn.example/s1e2_1080.mp4", "MP4", "1080p", 1080)
        val src720 = testSource("s1_720", "https://cdn.example/s1e2_720.mp4", "MP4", "720p", 720)
        val fakeMoviesRepo = FakeEpisodeMoviesRepository(
            sourcesByEp = mapOf(2 to listOf(src720, src1080))
        )
        val vm = MovieDetailViewModel(
            moviesRepository = fakeMoviesRepo,
            moviesDownloadBridge = MoviesDownloadBridge(fakeMediaRepo),
            mediaRepository = fakeMediaRepo
        )
        vm.load("moviebox", "show_1")
        advanceUntilIdle()

        val ep2 = EpisodeItem("ep_2", 1, 2, "Episode 2", null, null, null, null)
        vm.onEpisodeDownloadButtonClick(ep2)
        advanceUntilIdle()

        val picker = vm.uiState.value.episodeResolutionPicker
        assertNotNull(picker)
        assertEquals(listOf("1080p", "720p"), picker?.sources?.map { it.qualityLabel })

        // Dismissing outside cancels picker cleanly without starting download
        vm.dismissEpisodeResolutionPicker()
        assertNull(vm.uiState.value.episodeResolutionPicker)
        assertTrue(fakeMediaRepo.enqueuedRefs.isEmpty())

        // Re-tap uses cached sources immediately & opens picker, then pick 1080p
        vm.onEpisodeDownloadButtonClick(ep2)
        val selectedSource = vm.uiState.value.episodeResolutionPicker!!.sources.first()
        vm.startEpisodeDownloadWithSource(1, 2, ep2.title, selectedSource)
        advanceUntilIdle()

        assertNull(vm.uiState.value.episodeResolutionPicker)
        assertEquals(1, fakeMediaRepo.enqueuedRefs.size)
        assertEquals(EpisodeDownloadStatus.DOWNLOADING, vm.uiState.value.episodeDownloads[1 to 2]?.status)

        // Tap active downloading -> Pauses
        vm.onEpisodeDownloadButtonClick(ep2)
        advanceUntilIdle()
        assertEquals(EpisodeDownloadStatus.PAUSED, vm.uiState.value.episodeDownloads[1 to 2]?.status)

        // Tap paused -> Resumes
        vm.onEpisodeDownloadButtonClick(ep2)
        advanceUntilIdle()
        assertEquals(EpisodeDownloadStatus.DOWNLOADING, vm.uiState.value.episodeDownloads[1 to 2]?.status)

        // Simulate failure -> UI enters FAILED -> tap retries cleanly
        fakeMediaRepo.markTaskFailed("task_1", "Connection lost")
        advanceUntilIdle()
        assertEquals(EpisodeDownloadStatus.FAILED, vm.uiState.value.episodeDownloads[1 to 2]?.status)

        vm.onEpisodeDownloadButtonClick(ep2)
        advanceUntilIdle()
        assertEquals(EpisodeDownloadStatus.DOWNLOADING, vm.uiState.value.episodeDownloads[1 to 2]?.status)
    }

    private class FakeEpisodeMediaRepository : MediaRepository {
        private val tasksFlow = MutableStateFlow<List<DownloadTask>>(emptyList())
        val enqueuedRefs = mutableListOf<String>()

        override fun getAssetsForLink(linkId: Long): Flow<List<MediaAsset>> = emptyFlow()
        override fun getAllAssets(): Flow<List<MediaAsset>> = emptyFlow()
        override fun filterAssets(mediaType: MediaType?, query: String): Flow<List<MediaAsset>> = emptyFlow()
        override fun getAssetById(id: String): Flow<MediaAsset?> = flowOf(null)
        override fun getTasksForLink(linkId: Long): Flow<List<DownloadTask>> = emptyFlow()
        override fun getQueueTasks(): Flow<List<DownloadTask>> = tasksFlow
        override fun getTaskById(taskId: String): Flow<DownloadTask?> = emptyFlow()
        override fun observeTasksBySourceRefPrefix(prefix: String): Flow<List<DownloadTask>> = tasksFlow
        override fun getStorageBreakdown(): Flow<Map<MediaType, Long>> = emptyFlow()
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
        ): String = enqueueStandaloneDownload(
            candidate = candidate,
            sourcePlatform = sourcePlatform,
            title = title,
            artworkUrl = artworkUrl,
            versionTag = versionTag
        )

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
            val cleanRef = sourceRef.orEmpty()
            val existing = tasksFlow.value.firstOrNull {
                it.sourceRef == cleanRef && it.status in setOf(
                    DownloadState.QUEUED,
                    DownloadState.WAITING_FOR_WIFI,
                    DownloadState.INSPECTING,
                    DownloadState.READY,
                    DownloadState.DOWNLOADING,
                    DownloadState.PROCESSING,
                    DownloadState.PAUSED
                )
            }
            if (existing != null) return existing.id

            enqueuedRefs.add(cleanRef)
            val id = "task_${enqueuedRefs.size}"
            val task = DownloadTask(
                id = id,
                savedLinkId = null,
                sourceUrl = "https://cdn.example/video.mp4",
                sourcePlatform = sourcePlatform,
                candidateId = candidate.candidateId,
                formatSelector = null,
                mediaType = candidate.mediaType,
                qualityLabel = candidate.formatLabel,
                title = title,
                assetId = null,
                status = DownloadState.DOWNLOADING,
                progress = 25,
                downloadedBytes = 1024L,
                totalBytes = 4096L,
                createdAt = 1000L,
                updatedAt = 1000L + enqueuedRefs.size,
                sourceRef = sourceRef
            )
            tasksFlow.value = tasksFlow.value + task
            return id
        }

        override suspend fun overrideCellularRequirement(taskId: String) {}
        override suspend fun pauseDownload(taskId: String) {
            tasksFlow.value = tasksFlow.value.map {
                if (it.id == taskId) it.copy(status = DownloadState.PAUSED, updatedAt = it.updatedAt + 1) else it
            }
        }
        override suspend fun resumeDownload(taskId: String) {
            tasksFlow.value = tasksFlow.value.map {
                if (it.id == taskId) it.copy(status = DownloadState.DOWNLOADING, updatedAt = it.updatedAt + 1) else it
            }
        }
        override suspend fun cancelDownload(taskId: String) {}
        override suspend fun dismissTask(taskId: String) {}
        override suspend fun clearTerminalTasks() {}
        override suspend fun retryDownload(taskId: String) {
            tasksFlow.value = tasksFlow.value.map {
                if (it.id == taskId) it.copy(status = DownloadState.DOWNLOADING, errorMessage = null, updatedAt = it.updatedAt + 1) else it
            }
        }
        override suspend fun deleteAsset(assetId: String) {}
        override suspend fun exportAsset(assetId: String): Result<String> = Result.success("")
        override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
        override suspend fun runOrphanSweep() {}
        override suspend fun recoverInterruptedTasks() {}
        override fun getInspectionForLink(linkId: Long): Flow<InspectionResult?> = emptyFlow()
        override fun isInspectingLink(linkId: Long): Flow<Boolean> = flowOf(false)
        override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
        override suspend fun cancelInspection(linkId: Long) {}

        fun markTaskFailed(taskId: String, msg: String) {
            tasksFlow.value = tasksFlow.value.map {
                if (it.id == taskId) it.copy(status = DownloadState.FAILED, errorMessage = msg, updatedAt = it.updatedAt + 1) else it
            }
        }
    }

    private class FakeEpisodeMoviesRepository(
        private val sourcesByEp: Map<Int, List<PlaybackSource>>
    ) : MoviesRepository {
        private val show = MovieTitleSummary(
            id = "show_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Test Show",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        override suspend fun getHomeFeed(forceRefresh: Boolean): Result<MoviesHomeFeed> =
            Result.success(MoviesHomeFeed())

        override suspend fun getCatalogue(
            kind: MediaKind?,
            page: Int,
            pageSize: Int,
            genre: String?,
            sort: CatalogueSort,
            country: String?,
            year: String?
        ): Result<CataloguePage> = Result.success(CataloguePage(items = emptyList(), page = page))

        override suspend fun getGenres(): Result<List<String>> = Result.success(emptyList())

        override suspend fun search(
            query: String,
            kind: MediaKind?,
            page: Int,
            pageSize: Int
        ): Result<CataloguePage> = Result.success(CataloguePage(items = emptyList(), page = page))

        override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
            Result.success(emptyList())

        override fun observeRecentSearches(limit: Int): Flow<List<String>> = emptyFlow()
        override suspend fun recordSearchQuery(query: String) {}
        override suspend fun clearSearchHistory() {}

        override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> {
            return Result.success(
                MediaDetails(
                    summary = show,
                    seasons = listOf(SeasonSummary(seasonNumber = 1, episodeCount = 2))
                )
            )
        }

        override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> =
            Result.success(listOf(SeasonSummary(seasonNumber = 1, episodeCount = 2)))

        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> {
            return Result.success(
                listOf(
                    EpisodeItem("ep_1", 1, 1, "Pilot", null, null, null, null),
                    EpisodeItem("ep_2", 1, 2, "Episode 2", null, null, null, null)
                )
            )
        }

        override suspend fun resolvePlayback(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<PlaybackResolution> {
            val srcs = sourcesByEp[episodeNumber ?: 1].orEmpty()
            return Result.success(
                PlaybackResolution.Available(
                    title = show.title,
                    sources = srcs,
                    subtitles = emptyList()
                )
            )
        }

        override suspend fun getSubtitles(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<List<SubtitleTrack>> = Result.success(emptyList())

        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = emptyFlow()
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean = true
        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = emptyFlow()
        override suspend fun saveWatchProgress(
            item: MovieTitleSummary,
            positionMs: Long,
            durationMs: Long,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeLabel: String?
        ) {}
        override suspend fun getSavedPositionMs(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Long = 0L
    }
}
