package com.laterbox.app.movies

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.backup.BackupManager
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.repository.MoviesRepositoryImpl
import com.laterbox.app.movies.data.resolver.MediaResolver
import com.laterbox.app.movies.domain.engine.MoviesDiscoveryEngine
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.MovieDetailViewModel
import com.laterbox.app.movies.ui.MoviesCatalogueViewModel
import com.laterbox.app.movies.ui.MoviesHomeViewModel
import com.laterbox.app.ui.settings.SettingsViewModel
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesOptimisticAndHiddenTitlesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private val tempDbFiles = mutableListOf<String>()
    private val tempPrefDirs = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        tempDbFiles.forEach { context.deleteDatabase(it) }
        tempPrefDirs.forEach { it.deleteRecursively() }
    }

    private fun openDiskDb(name: String): LaterboxDatabase {
        tempDbFiles.add(name)
        return Room.databaseBuilder(context, LaterboxDatabase::class.java, name)
            .addMigrations(LaterboxDatabase.MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()
    }

    private fun createRepository(db: LaterboxDatabase, clock: () -> Long = { 10_000L }): MoviesRepositoryImpl {
        val client = OkHttpClient()
        return MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, "http://127.0.0.1:1"),
            tvMazeProvider = TvMazeSeriesProvider(client, "http://127.0.0.1:1"),
            tmdbProvider = TmdbOptionalMoviesProvider(client, { "" }, "http://127.0.0.1:1"),
            useSampleFixturesOnlyProvider = { false },
            nowMs = clock,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
    }

    private fun createPrefs(scope: CoroutineScope): UserPreferencesRepository {
        val dir = kotlin.io.path.createTempDirectory("prefs_test").toFile()
        tempPrefDirs.add(dir)
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        ) { File(dir, "prefs.preferences_pb") }
        return UserPreferencesRepository(context, ds)
    }

    private open class StubMoviesRepository : MoviesRepository {
        var homeFeedResult: Result<MoviesHomeFeed> = Result.success(MoviesHomeFeed())
        val detailsMap = mutableMapOf<String, Result<MediaDetails>>()
        val playbackMap = mutableMapOf<String, Result<PlaybackResolution>>()

        override suspend fun getHomeFeed(forceRefresh: Boolean): Result<MoviesHomeFeed> = homeFeedResult
        override suspend fun getCatalogue(
            kind: MediaKind?,
            page: Int,
            pageSize: Int,
            genre: String?,
            sort: CatalogueSort,
            country: String?,
            year: String?
        ): Result<CataloguePage> = Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))

        override suspend fun getGenres(): Result<List<String>> = Result.success(emptyList())
        override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int): Result<CataloguePage> =
            Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))

        override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
            Result.success(emptyList())

        override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> =
            detailsMap["$providerId:$titleId"] ?: Result.failure(NoSuchElementException("Not found"))

        override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> =
            Result.success(emptyList())

        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> =
            Result.success(emptyList())

        override suspend fun resolvePlayback(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<PlaybackResolution> =
            playbackMap["$providerId:$titleId"] ?: Result.success(PlaybackResolution.Unavailable("Test", "none", providerId))

        override suspend fun getSubtitles(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<List<SubtitleTrack>> = Result.success(emptyList())

        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = flowOf(emptyList())
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean = false
        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override fun observeViewingHistory(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override fun observeMovieWatchHistory(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override fun observeEpisodeWatchHistory(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override fun observeTrailerHistory(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override fun observeHiddenHistory(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override fun observeRecentlyViewed(limit: Int): Flow<List<MovieTitleSummary>> = flowOf(emptyList())
        override fun observeOfflineAvailability() = flowOf(emptyList<com.laterbox.app.movies.domain.model.OfflineMediaMatch>())
        override suspend fun saveWatchProgress(
            item: MovieTitleSummary,
            positionMs: Long,
            durationMs: Long,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeLabel: String?
        ) = Unit

        override suspend fun getSavedPositionMs(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Long = 0L

        override fun observeRecentSearches(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit
    }

    @Test
    fun `hiding unwatched and watched titles persists across restart and preserves watch history`() = runTest {
        val dbName = "hidden_titles_persistence_${System.nanoTime()}.db"
        var db = openDiskDb(dbName)
        var repo = createRepository(db)

        val watchedMovie = MovieTitleSummary(
            id = "m_watched",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Watched Movie",
            posterUrl = "https://img/watched.jpg",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val unwatchedMovie = MovieTitleSummary(
            id = "m_unwatched",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Unwatched Movie",
            posterUrl = "https://img/unwatched.jpg",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val sameIdSeries = MovieTitleSummary(
            id = "m_unwatched",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Unwatched Series Same Id",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )

        repo.saveWatchProgress(watchedMovie, positionMs = 35_000L, durationMs = 120_000L)
        assertEquals(1, repo.observeMovieWatchHistory().first().size)
        assertEquals(1, repo.observeContinueWatching().first().size)

        // Hide both watched and unwatched titles (while leaving sameIdSeries unhidden)
        repo.setTitleHidden(watchedMovie, hidden = true)
        repo.setTitleHidden(unwatchedMovie, hidden = true)

        assertTrue(repo.observeMovieWatchHistory().first().isEmpty())
        assertTrue(repo.observeContinueWatching().first().isEmpty())
        val hiddenNow = repo.observeHiddenHistory().first()
        assertEquals(2, hiddenNow.size)
        assertTrue(hiddenNow.any { it.titleId == "m_unwatched" && it.title == "Unwatched Movie" })
        assertTrue(hiddenNow.any { it.titleId == "m_watched" && it.title == "Watched Movie" })

        // Verify distinct (providerId, titleId, kind) isolation by hiding sameIdSeries separately
        repo.setTitleHidden(sameIdSeries, hidden = true)
        assertEquals(3, repo.observeHiddenHistory().first().size)

        // Simulate app restart by closing and reopening database
        db.close()
        db = openDiskDb(dbName)
        repo = createRepository(db)

        assertEquals(3, repo.observeHiddenHistory().first().size)
        assertTrue(repo.observeMovieWatchHistory().first().isEmpty())

        // Unhide watchedMovie -> original progress and viewing history must still be intact!
        repo.setTitleHidden(watchedMovie, hidden = false)
        val restoredHistory = repo.observeMovieWatchHistory().first()
        assertEquals(1, restoredHistory.size)
        assertEquals(35_000L, restoredHistory.first().positionMs)
        assertEquals(2, repo.observeHiddenHistory().first().size)

        db.close()
    }

    @Test
    fun `removeFromContinueWatching does not mark title globally hidden`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = createRepository(db)
            val movie = MovieTitleSummary(
                id = "cw_only",
                providerId = "moviebox",
                kind = MediaKind.MOVIE,
                title = "Continue Watching Movie",
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
            )
            repo.toggleWatchlist(movie)
            repo.saveWatchProgress(movie, positionMs = 45_000L, durationMs = 100_000L)
            assertEquals(1, repo.observeContinueWatching().first().size)

            repo.removeFromContinueWatching("moviebox", "cw_only")

            assertTrue(repo.observeContinueWatching().first().isEmpty())
            assertTrue("Should not pollute hidden_titles", repo.observeHiddenHistory().first().isEmpty())
            assertEquals(1, repo.observeWatchlist().first().size)
            assertEquals(1, repo.observeMovieWatchHistory().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `mediaResolver resolveOrRegisterBatch registers many items cleanly`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val resolver = MediaResolver(db.moviesDao(), nowMs = { 5_000L })
            val items = (1..50).map { idx ->
                MovieTitleSummary(
                    id = "item_$idx",
                    providerId = "moviebox",
                    kind = if (idx % 2 == 0) MediaKind.TV_SERIES else MediaKind.MOVIE,
                    title = "Sample Title $idx",
                    releaseYear = 2020 + (idx % 5),
                    sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                )
            }
            val resolved = resolver.resolveOrRegisterBatch(items)
            assertEquals(50, resolved.size)
            assertTrue(resolved.all { !it.canonicalId.isNullOrBlank() })
            assertEquals(50, db.moviesDao().getAllProviderLinks().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `homeViewModel applies optimistic removals immediately and rolls back on failure`() = runTest {
        val prefs = createPrefs(this)
        val movie1 = MovieTitleSummary(
            id = "m1",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Optimistic Film",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val cw1 = WatchHistoryItem(
            compositeId = movie1.compositeId,
            providerId = movie1.providerId,
            titleId = movie1.id,
            kind = movie1.kind,
            title = movie1.title,
            positionMs = 20_000L,
            durationMs = 100_000L,
            lastWatchedAt = 1000L
        )

        val gate = CompletableDeferred<Unit>()
        var failNext = false
        val cwFlow = MutableStateFlow(listOf(cw1))
        val rvFlow = MutableStateFlow(listOf(movie1))
        val wlFlow = MutableStateFlow(listOf(movie1))
        val hiddenFlow = MutableStateFlow<List<WatchHistoryItem>>(emptyList())

        val delegate = StubMoviesRepository().apply {
            homeFeedResult = Result.success(MoviesHomeFeed(featuredBanners = listOf(movie1)))
        }
        val repo = object : MoviesRepository by delegate {
            override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = cwFlow
            override fun observeRecentlyViewed(limit: Int): Flow<List<MovieTitleSummary>> = rvFlow
            override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = wlFlow
            override fun observeHiddenHistory(limit: Int): Flow<List<WatchHistoryItem>> = hiddenFlow

            override suspend fun removeFromContinueWatching(providerId: String, titleId: String) {
                gate.await()
                if (failNext) error("DB write error")
            }

            override suspend fun removeRecentlyViewed(providerId: String, titleId: String) {
                if (failNext) error("DB write error")
                rvFlow.value = emptyList()
            }

            override suspend fun setTitleHidden(item: MovieTitleSummary, hidden: Boolean) {
                if (failNext) error("DB write error")
            }
        }

        val vm = MoviesHomeViewModel(repo, prefs)
        vm.uiState.first { !it.isLoading && it.continueWatching.size == 1 }

        // 1. Optimistic update happens IMMEDIATELY while DB gate is suspended
        failNext = true
        vm.removeFromContinueWatching(cw1)
        assertTrue("CW must be removed immediately before DB completes", vm.uiState.value.continueWatching.isEmpty())

        // 2. When DB fails, UI rolls back and sets actionErrorMessage
        gate.complete(Unit)
        assertEquals(1, vm.uiState.value.continueWatching.size)
        assertNotNull(vm.uiState.value.actionErrorMessage)
        vm.clearActionError()
        assertNull(vm.uiState.value.actionErrorMessage)

        // 3. Optimistic removeRecentlyViewed & hideTitle rollback on DB error
        vm.removeRecentlyViewed(movie1)
        assertEquals(1, vm.uiState.value.recentlyViewed.size)
        assertNotNull(vm.uiState.value.actionErrorMessage)
        vm.clearActionError()

        vm.hideTitle(movie1)
        assertFalse(vm.uiState.value.hiddenMediaKeys.contains(movie1.stableMediaKey))
        assertNotNull(vm.uiState.value.actionErrorMessage)
    }

    @Test
    fun `catalogue detail and settings viewModels roll back optimistic mutations on db failure`() = runTest {
        val prefs = createPrefs(this)
        val movie = MovieTitleSummary(
            id = "m_err",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Rollback Test",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val histEntry = WatchHistoryItem(
            compositeId = movie.compositeId,
            providerId = movie.providerId,
            titleId = movie.id,
            kind = movie.kind,
            title = movie.title,
            positionMs = 10_000L,
            durationMs = 90_000L,
            lastWatchedAt = 2_000L,
            historyKey = "moviebox:m_err:0:0"
        )
        val historyFlow = MutableStateFlow(listOf(histEntry))
        val gate = CompletableDeferred<Unit>()
        val delegate = StubMoviesRepository().apply {
            detailsMap[movie.compositeId] = Result.success(MediaDetails(summary = movie, fullDescription = "Desc"))
            playbackMap[movie.compositeId] = Result.success(PlaybackResolution.Unavailable("Rollback Test", "none", "moviebox"))
        }
        val failingRepo = object : MoviesRepository by delegate {
            override fun observeViewingHistory(limit: Int): Flow<List<WatchHistoryItem>> = historyFlow
            override suspend fun setTitleHidden(item: MovieTitleSummary, hidden: Boolean) {
                error("Disk full")
            }
            override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean {
                error("Disk full")
            }
            override suspend fun deleteViewingHistoryEntry(historyKey: String) {
                gate.await()
                error("Disk full")
            }
        }

        // CatalogueViewModel rollback
        val catVm = MoviesCatalogueViewModel(failingRepo)
        catVm.hideTitle(movie)
        assertFalse(catVm.uiState.value.hiddenMediaKeys.contains(movie.stableMediaKey))
        assertNotNull(catVm.uiState.value.actionErrorMessage)

        // MovieDetailViewModel rollback
        val detailVm = MovieDetailViewModel(failingRepo, null, prefs)
        detailVm.load(movie.providerId, movie.id)
        detailVm.uiState.first { !it.isLoading }
        detailVm.toggleWatchlist()
        assertFalse(detailVm.uiState.value.isInWatchlist)
        assertNotNull(detailVm.uiState.value.actionErrorMessage)

        // SettingsViewModel viewing history optimistic + rollback
        val dir = kotlin.io.path.createTempDirectory("backup_test").toFile()
        tempPrefDirs.add(dir)
        val db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backupManager = BackupManager(db)
            val settingsVm = SettingsViewModel(
                userPreferencesRepository = prefs,
                backupManager = backupManager,
                moviesRepository = failingRepo
            )
            // Start collection on viewingHistoryUiState (SharingStarted.WhileSubscribed)
            val collected = MutableStateFlow<Int?>(null)
            val job = backgroundScope.launch {
                settingsVm.viewingHistoryUiState.collect { collected.value = it.historyItems.size }
            }
            settingsVm.viewingHistoryUiState.first { !it.isLoading && it.historyItems.size == 1 }

            settingsVm.deleteViewingHistoryEntry("moviebox:m_err:0:0")
            assertTrue(settingsVm.viewingHistoryUiState.value.historyItems.isEmpty())

            gate.complete(Unit)
            assertEquals(1, settingsVm.viewingHistoryUiState.value.historyItems.size)
            assertNotNull(settingsVm.viewingHistoryUiState.value.actionErrorMessage)
            job.cancel()
        } finally {
            db.close()
        }
    }

    @Test
    fun `discovery engine parseSortableDateKey handles valid and edge dates`() {
        assertTrue(MoviesDiscoveryEngine.parseSortableDateKey("2024-05-10") > MoviesDiscoveryEngine.parseSortableDateKey("2024-05-09"))
        assertTrue(MoviesDiscoveryEngine.parseSortableDateKey("2024-02-30") > 0L)
        assertTrue(MoviesDiscoveryEngine.parseSortableDateKey("2024") > 0L)
        assertEquals(0L, MoviesDiscoveryEngine.parseSortableDateKey("invalid"))
        assertEquals(0L, MoviesDiscoveryEngine.parseSortableDateKey(null))
    }
}
