package com.laterbox.app.movies

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.repository.MoviesRepositoryImpl
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackResolution
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesRepositoryTest {

    private lateinit var db: LaterboxDatabase
    private lateinit var server: MockWebServer
    private var fakeClockMs = 1_000_000L
    private var useFixtures = false
    private var tmdbKey = ""

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun createRepository(): MoviesRepositoryImpl {
        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        return MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { tmdbKey }, baseUrl = base),
            fixtureProvider = DevelopmentMoviesFixtureProvider(),
            useSampleFixturesOnlyProvider = { useFixtures },
            nowMs = { fakeClockMs }
        )
    }

    @Test
    fun homeFeed_mergesLiveProvidersCachesWithinTtlAndFallsBackOfflineWhenExpired() = runTest {
        var failNetwork = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failNetwork) return MockResponse().setResponseCode(503)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/advancedsearch.php") -> MockResponse().setResponseCode(200).setBody(
                        """{"response":{"numFound":1,"docs":[{"identifier":"ia_1","title":"Archive Film","year":1941}]}}"""
                    )
                    path.startsWith("/shows?page=") -> MockResponse().setResponseCode(200).setBody(
                        """[{"id":50,"name":"Live Series","weight":90,"genres":["Drama"],"premiered":"2020-01-01","rating":{"average":8.5}}]"""
                    )
                    path.startsWith("/schedule") -> MockResponse().setResponseCode(200).setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repo = createRepository()
        val liveFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertFalse(liveFeed.isOfflineCached)
        assertFalse(liveFeed.containsSampleFixtures)
        assertTrue(liveFeed.featuredBanners.any { it.providerId == "ia" })
        assertTrue(liveFeed.featuredBanners.any { it.providerId == "tvmaze" })

        // Break network: within TTL returns fresh cache cleanly
        failNetwork = true
        val cachedFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertFalse(cachedFeed.isOfflineCached)

        // Advance clock past 6h TTL with network still down: falls back to stale cache tagged offline
        fakeClockMs += MoviesRepositoryImpl.CACHE_TTL_MS + 60_000L
        val offlineFallback = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertTrue(offlineFallback.isOfflineCached)
        assertTrue(offlineFallback.activeProviderNotice.orEmpty().contains("Offline cache"))
    }

    @Test
    fun sampleFixturesMode_staysStrictlyIsolatedAndTaggedAsFixture() = runTest {
        useFixtures = true
        val repo = createRepository()
        val feed = repo.getHomeFeed().getOrThrow()
        assertTrue(feed.containsSampleFixtures)
        assertTrue(feed.featuredBanners.all { it.sourceBadge == DataSourceBadge.SAMPLE_FIXTURE })

        val ep1Playable = repo.resolvePlayback("fixture", "cosmos_series_fixture", 1, 1).getOrThrow()
        assertTrue(ep1Playable is PlaybackResolution.Available)

        val ep2Unavailable = repo.resolvePlayback("fixture", "cosmos_series_fixture", 1, 2).getOrThrow()
        assertTrue(ep2Unavailable is PlaybackResolution.Unavailable)
    }

    @Test
    fun watchlistAndWatchProgress_persistCorrectlyAndResetAt96Percent() = runTest {
        val repo = createRepository()
        val item = MovieTitleSummary(
            id = "his_girl_friday",
            providerId = "ia",
            kind = MediaKind.MOVIE,
            title = "His Girl Friday",
            sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
            isPlayableHint = true
        )

        assertTrue(repo.toggleWatchlist(item))
        assertTrue(repo.observeIsInWatchlist(item.compositeId).first())
        assertEquals(1, repo.observeWatchlist().first().size)

        repo.saveWatchProgress(item, positionMs = 120_000L, durationMs = 1_000_000L)
        assertEquals(120_000L, repo.getSavedPositionMs("ia", "his_girl_friday"))
        assertEquals(1, repo.observeContinueWatching().first().size)

        // >= 96% completed restarts from 0
        repo.saveWatchProgress(item, positionMs = 970_000L, durationMs = 1_000_000L)
        assertEquals(0L, repo.getSavedPositionMs("ia", "his_girl_friday"))

        assertFalse(repo.toggleWatchlist(item))
        assertTrue(repo.observeWatchlist().first().isEmpty())
    }

    @Test
    fun unifiedResolver_prefersMovieBoxAndFallsBackToLiveOpenArchiveOrMovieBoxStaleCache() = runTest {
        var failMovieBox = false
        var failOpenArchive = false
        val iaOrTvMazeHits = mutableListOf<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/advancedsearch.php") || path.startsWith("/shows") || path.startsWith("/schedule")) {
                    iaOrTvMazeHits.add(path)
                    if (failOpenArchive) return MockResponse().setResponseCode(503)
                    return when {
                        path.startsWith("/advancedsearch.php") -> MockResponse().setResponseCode(200).setBody(
                            """{"response":{"numFound":1,"docs":[{"identifier":"ia_fallback","title":"Fallback Classic","year":1950}]}}"""
                        )
                        path.startsWith("/shows") -> MockResponse().setResponseCode(200).setBody("[]")
                        else -> MockResponse().setResponseCode(200).setBody("[]")
                    }
                }
                if (failMovieBox) {
                    return MockResponse().setResponseCode(502).setBody("""{"detail":"MovieBox upstream offline"}""")
                }
                return when {
                    path == "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "code": 0,
                          "data": {
                            "operatingList": [
                              {
                                "type": "SUBJECTS_MOVIE",
                                "title": "MovieBox Picks",
                                "subjects": [
                                  {
                                    "subjectId": "777",
                                    "subjectType": 1,
                                    "title": "MovieBox Feature",
                                    "releaseDate": "2024-05-01",
                                    "imdbRatingValue": "8.4",
                                    "cover": {"url": "https://cdn.example/mb.jpg"},
                                    "detailPath": "moviebox-feature"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/search?") -> MockResponse().setResponseCode(200).setBody(
                        """{"query":"Feature","page":1,"results":[{"subject_id":"777","title":"MovieBox Feature","type":"Movie","subject_type":1}]}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        val mbProvider = com.laterbox.app.movies.data.provider.MovieBoxProvider(client, baseUrlProvider = { base })
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }, baseUrl = base),
            movieBoxProvider = mbProvider,
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )

        // When MovieBox fails initially while IA is up, unified resolver falls back cleanly with notice
        failMovieBox = true
        failOpenArchive = false
        val fallbackFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertFalse(fallbackFeed.isOfflineCached)
        assertEquals(MoviesRepositoryImpl.MOVIEBOX_FALLBACK_NOTICE, fallbackFeed.activeProviderNotice)
        assertTrue(fallbackFeed.featuredBanners.any { it.providerId == "ia" })
        assertTrue(iaOrTvMazeHits.isNotEmpty())

        // Recover MovieBox: caches MovieBox response in Room and clears fallback notice
        iaOrTvMazeHits.clear()
        failMovieBox = false
        val liveFeed = repo.getHomeFeed(forceRefresh = true).getOrThrow()
        assertFalse(liveFeed.isOfflineCached)
        assertEquals("777", liveFeed.featuredBanners.first().id)
        assertEquals("moviebox", liveFeed.featuredBanners.first().providerId)
        assertEquals(DataSourceBadge.LIVE_MOVIEBOX, liveFeed.featuredBanners.first().sourceBadge)
        assertTrue(iaOrTvMazeHits.isEmpty())

        // Search routes to MovieBox primary first and appends unique supplementary matches
        val searchPage = repo.search("Feature").getOrThrow()
        assertTrue(searchPage.items.isNotEmpty())
        assertEquals(1, searchPage.primaryCount)
        assertEquals("moviebox", searchPage.items.first().providerId)
        assertEquals(DataSourceBadge.LIVE_MOVIEBOX, searchPage.items.first().sourceBadge)

        // Break BOTH MovieBox and Open Archive after stale TTL -> returns MovieBox's own stale offline cache
        failMovieBox = true
        failOpenArchive = true
        fakeClockMs += MoviesRepositoryImpl.CACHE_TTL_MS + 60_000L
        val staleFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertTrue(staleFeed.isOfflineCached)
        assertEquals("moviebox", staleFeed.featuredBanners.first().providerId)
        assertTrue(staleFeed.activeProviderNotice.orEmpty().contains("Offline cache", ignoreCase = true))
        assertFalse(staleFeed.activeProviderNotice.orEmpty().contains("MovieBox", ignoreCase = true))
    }

    @Test
    fun canonicalIdentity_linksAcrossProvidersPromotesPlayableWatchlistAndUnlinksCleanly() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/shows/169") && !path.contains("/seasons") && !path.contains("/episodes") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "id": 169,
                          "name": "Breaking Bad",
                          "premiered": "2008-01-20",
                          "runtime": 47,
                          "externals": {"imdb": "tt0903747"},
                          "rating": {"average": 9.5},
                          "genres": ["Drama"],
                          "_embedded": {"seasons": [{"id": 1, "number": 1, "name": "Season 1", "episodeOrder": 7}]}
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/detail/mb_bb") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "mb_bb",
                          "subject_type": 2,
                          "title": "Breaking Bad",
                          "release_date": "2008-01-20",
                          "duration": 2820,
                          "imdb_id": "tt0903747",
                          "description": "Linked MovieBox entry.",
                          "dubs": []
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/episodes/mb_bb") -> MockResponse().setResponseCode(200).setBody(
                        """{"subject_id":"mb_bb","seasons":[{"season":1,"episode_count":7}]}"""
                    )
                    path.startsWith("/api/stream/mb_bb/captions") -> MockResponse().setResponseCode(200).setBody(
                        """{"subject_id":"mb_bb","captions":[]}"""
                    )
                    path.startsWith("/api/stream/mb_bb") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "streams": [
                            {"id": "s1", "url": "https://cdn.example/bb_720.mp4", "format": "MP4", "quality": "720p", "quality_value": 720}
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }, baseUrl = base),
            movieBoxProvider = com.laterbox.app.movies.data.provider.MovieBoxProvider(client, baseUrlProvider = { base }),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )

        val tvDetails = repo.getDetails("tvmaze", "169").getOrThrow()
        val mbDetails = repo.getDetails("moviebox", "mb_bb").getOrThrow()
        assertEquals(tvDetails.summary.canonicalId, mbDetails.summary.canonicalId)

        val links = repo.getProviderLinks("tvmaze", "169")
        assertEquals(2, links.size)

        // Watchlist saved on MovieBox matches TVMaze & canonical composite IDs
        assertTrue(repo.toggleWatchlist(mbDetails.summary))
        assertTrue(repo.observeIsInWatchlist("tvmaze:169").first())
        assertTrue(repo.observeIsInWatchlist("canonical:${mbDetails.summary.canonicalId}").first())

        // Playback on TVMaze promoted automatically to linked MovieBox source
        val promotedPlayback = repo.resolvePlayback("tvmaze", "169", seasonNumber = 1, episodeNumber = 1).getOrThrow()
        assertTrue(promotedPlayback is PlaybackResolution.Available)

        // Unlink TVMaze separates canonical identity and stops playback promotion
        val newCanonical = repo.unlinkProviderTitle("tvmaze", "169")
        assertTrue(!newCanonical.isNullOrBlank() && newCanonical != mbDetails.summary.canonicalId)
        assertEquals(1, repo.getProviderLinks("tvmaze", "169").size)
        assertEquals(1, repo.getProviderLinks("moviebox", "mb_bb").size)
        assertFalse(repo.observeIsInWatchlist("tvmaze:169").first())
        val unlinkedPlayback = repo.resolvePlayback("tvmaze", "169", seasonNumber = 1, episodeNumber = 1).getOrThrow()
        assertTrue(unlinkedPlayback is PlaybackResolution.Unavailable)
    }

    @Test
    fun productionSourcesContainZeroUnauthorisedMovieBoxHostsOrSecrets() {
        val forbiddenTokens = listOf(
            "aoneroom.com",
            "inmoviebox.com",
            "moviebox.ph",
            "h5-api.aoneroom",
            "x-tr-signature",
            "com.community.mbox.in"
        )
        val mainSrcDir = listOf(
            File("app/src/main/java"),
            File("Laterbox/app/src/main/java")
        ).firstOrNull { it.exists() } ?: File("src/main/java")

        assertTrue("Expected main source directory at ${mainSrcDir.absolutePath}", mainSrcDir.exists())
        mainSrcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val content = file.readText()
                forbiddenTokens.forEach { token ->
                    assertFalse(
                        "Forbidden token '$token' found in ${file.path}",
                        content.contains(token, ignoreCase = true)
                    )
                }
            }
    }

    @Test
    fun viewingHistory_recordsTrailersIsolatedFromContinueWatchingAndSupportsSelectiveDeletes() = runTest {
        val repo = createRepository()
        val movie = MovieTitleSummary(
            id = "dune_2",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Dune: Part Two",
            posterUrl = "https://cdn.example/dune_poster.jpg",
            backdropUrl = "https://cdn.example/dune_backdrop.jpg",
            trailerUrl = "https://cdn.example/dune_trailer.mp4",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        val series = MovieTitleSummary(
            id = "sev_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            backdropUrl = "https://cdn.example/sev_backdrop.jpg",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        // Save feature film progress & trailer watch
        repo.saveWatchProgress(movie, positionMs = 240_000L, durationMs = 1_800_000L)
        fakeClockMs += 5_000L
        repo.recordTrailerWatch(series, positionMs = 18_000L, durationMs = 120_000L)

        // Continue Watching strictly excludes trailers & preserves crisp 16:9 backdropUrl
        val continueList = repo.observeContinueWatching().first()
        assertEquals(1, continueList.size)
        assertEquals("moviebox:dune_2", continueList.first().compositeId)
        assertEquals("https://cdn.example/dune_backdrop.jpg", continueList.first().backdropUrl)
        assertFalse(continueList.first().isTrailer)

        // Viewing History includes both regular watch entries and trailers
        val allHistory = repo.observeViewingHistory().first()
        assertEquals(2, allHistory.size)
        assertTrue(allHistory.any { it.compositeId == "moviebox:dune_2" && !it.isTrailer })
        val trailerEntry = allHistory.first { it.compositeId == "moviebox:sev_1" && it.isTrailer }
        assertEquals("https://cdn.example/sev_backdrop.jpg", trailerEntry.backdropUrl)

        // Selective trailer clear leaves main movie entry intact
        repo.clearViewingHistory(trailersOnly = true)
        val afterClearTrailers = repo.observeViewingHistory().first()
        assertEquals(1, afterClearTrailers.size)
        assertEquals("moviebox:dune_2", afterClearTrailers.first().compositeId)

        // Single-item delete & full clear
        val movieKey = afterClearTrailers.first().historyKey
        repo.deleteViewingHistoryEntry(movieKey)
        assertTrue(repo.observeViewingHistory().first().isEmpty())

        repo.saveWatchProgress(movie, positionMs = 300_000L, durationMs = 1_800_000L)
        repo.recordTrailerWatch(series, positionMs = 10_000L, durationMs = 90_000L)
        assertEquals(2, repo.observeViewingHistory().first().size)
        repo.clearViewingHistory(trailersOnly = null)
        assertTrue(repo.observeViewingHistory().first().isEmpty())
    }

    @Test
    fun personalLibrary_splitHistoryRewatchRestartSeriesProgressAndHideControls() = runTest {
        val repo = createRepository()
        val movie = MovieTitleSummary(
            id = "interstellar_1",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Interstellar",
            durationMinutes = 169,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        val series = MovieTitleSummary(
            id = "andor_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Andor",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        // 1. Record movie, episode, and trailer separately
        repo.saveWatchProgress(movie, positionMs = 300_000L, durationMs = 1_000_000L)
        fakeClockMs += 1_000L
        repo.saveWatchProgress(
            series,
            positionMs = 980_000L,
            durationMs = 1_000_000L,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeLabel = "S1 · E1"
        )
        fakeClockMs += 1_000L
        repo.saveWatchProgress(
            series,
            positionMs = 120_000L,
            durationMs = 1_000_000L,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeLabel = "S1 · E2"
        )
        fakeClockMs += 1_000L
        repo.recordTrailerWatch(movie, positionMs = 15_000L, durationMs = 90_000L)

        assertEquals(1, repo.observeMovieWatchHistory().first().size)
        assertEquals(2, repo.observeEpisodeWatchHistory().first().size)
        assertEquals(1, repo.observeTrailerHistory().first().size)

        // Next unwatched episode skips S1E1 (completed) and returns S1E2
        val seasons = listOf(
            com.laterbox.app.movies.domain.model.SeasonSummary(seasonNumber = 1, episodeCount = 3)
        )
        assertEquals(1 to 2, repo.computeNextUnwatchedEpisode("moviebox", "andor_1", seasons))

        // Mark S1E2 watched -> next unwatched becomes S1E3
        repo.markWatched(series, durationMs = 1_000_000L, seasonNumber = 1, episodeNumber = 2, episodeLabel = "S1 · E2")
        assertEquals(1 to 3, repo.computeNextUnwatchedEpisode("moviebox", "andor_1", seasons))

        // Series progress reflects 2 completed episodes
        val progress = repo.observeSeriesProgress("moviebox", "andor_1", "Andor").first()
        assertEquals(2, progress.totalWatchedEpisodes)
        assertTrue(progress.episodeStates[1 to 1]?.isCompleted == true)
        assertTrue(progress.episodeStates[1 to 2]?.isCompleted == true)

        // 2. Restarting completed movie preserves completion & rewatchCount, and completing again increments rewatchCount
        repo.markWatched(movie, durationMs = 1_000_000L)
        val afterComplete = repo.observeMovieWatchHistory().first().first()
        assertTrue(afterComplete.isCompleted)
        assertEquals(0, afterComplete.rewatchCount)

        fakeClockMs += 2_000L
        repo.restartFromBeginning("moviebox", "interstellar_1")
        val afterRestart = repo.observeMovieWatchHistory().first().first()
        assertEquals(0L, afterRestart.positionMs)
        assertTrue(afterRestart.isCompleted)
        assertEquals(0, afterRestart.rewatchCount)

        // Watching partway and then completing again increments rewatchCount -> 1
        fakeClockMs += 2_000L
        repo.saveWatchProgress(movie, positionMs = 50_000L, durationMs = 1_000_000L)
        assertTrue(repo.observeContinueWatching().first().any { it.compositeId == "moviebox:interstellar_1" })

        fakeClockMs += 2_000L
        repo.saveWatchProgress(movie, positionMs = 970_000L, durationMs = 1_000_000L)
        val afterRewatch = repo.observeMovieWatchHistory().first().first()
        assertEquals(1, afterRewatch.rewatchCount)
        assertTrue(afterRewatch.isCompleted)

        // 3. Remove from Continue Watching & Hide/Unhide Title
        repo.saveWatchProgress(movie, positionMs = 200_000L, durationMs = 1_000_000L)
        repo.removeFromContinueWatching("moviebox", "interstellar_1")
        assertFalse(repo.observeContinueWatching().first().any { it.compositeId == "moviebox:interstellar_1" })

        repo.setTitleHidden("moviebox", "interstellar_1", hidden = true)
        assertTrue(repo.observeMovieWatchHistory().first().isEmpty())
        assertTrue(repo.observeHiddenHistory().first().any { it.compositeId == "moviebox:interstellar_1" })

        repo.setTitleHidden("moviebox", "interstellar_1", hidden = false)
        assertEquals(1, repo.observeMovieWatchHistory().first().size)

        // 4. Mark unwatched removes episode completion
        repo.markUnwatched("moviebox", "andor_1", seasonNumber = 1, episodeNumber = 1)
        assertEquals(1 to 1, repo.computeNextUnwatchedEpisode("moviebox", "andor_1", seasons))
    }

    @Test
    fun personalLibrary_recentlyViewedCollectionsSeriesPrefsRemindersAndOfflinePlayback() = runTest {
        val mediaFile = File.createTempFile("laterbox_offline_test", ".mp4").apply {
            writeText("fake video bytes")
            deleteOnExit()
        }
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tvMazeProvider = TvMazeSeriesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tmdbProvider = TmdbOptionalMoviesProvider(OkHttpClient(), apiKeyProvider = { "" }, baseUrl = server.url("/").toString().trimEnd('/')),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs },
            downloadedAssetDao = db.downloadedAssetDao(),
            localMediaUriResolver = { relPath -> if (relPath == "offline/clip.mp4") mediaFile.toURI().toString() else null }
        )

        val item = MovieTitleSummary(
            id = "arrival_99",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Arrival",
            releaseYear = 2016,
            genres = listOf("Sci-Fi"),
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        // Recently viewed
        repo.recordRecentlyViewed(item)
        assertEquals(1, repo.observeRecentlyViewed().first().size)
        assertEquals("Arrival", repo.observeRecentlyViewed().first().first().title)

        // Collections membership
        val colId = db.collectionDao().insertCollection(
            com.laterbox.app.data.local.entity.CollectionEntity(name = "Sci-Fi Classics")
        )
        repo.addMediaToCollection(item, colId)
        assertEquals(listOf(colId), repo.observeCollectionIdsForMedia("moviebox", "arrival_99").first())
        assertEquals(1, repo.observeMediaInCollection(colId).first().size)

        // Series preferences & reminder mute
        repo.saveSeriesAudioPreference("moviebox", "arrival_99", language = "ja", label = "Japanese")
        repo.saveSeriesSubtitlePreference("moviebox", "arrival_99", language = "en", label = "English", disabled = false)
        repo.setSeriesRemindersMuted("moviebox", "arrival_99", muted = true)
        val prefs = repo.getSeriesPreferences("moviebox", "arrival_99")
        assertEquals("ja", prefs?.preferredAudioLanguage)
        assertEquals("en", prefs?.preferredSubtitleLanguage)
        assertFalse(prefs?.subtitlesDisabled ?: true)
        assertTrue(prefs?.remindersMuted ?: false)

        // Viewing reminder
        repo.scheduleViewingReminder(item, triggerAtMillis = fakeClockMs + 60_000L, note = "Watch tonight")
        assertEquals(1, repo.observeActiveViewingReminders().first().size)
        assertEquals("Watch tonight", repo.observeViewingReminderForTitle("moviebox", "arrival_99").first()?.note)
        repo.dismissViewingReminder("moviebox", "arrival_99")
        assertTrue(repo.observeActiveViewingReminders().first().isEmpty())

        // Offline playback availability and fallback when remote provider is unreachable
        db.downloadedAssetDao().insertAsset(
            com.laterbox.app.media.data.db.entity.DownloadedAssetEntity(
                id = "off_1",
                savedLinkId = null,
                sourcePlatform = "MovieBox",
                originalSourceUrl = "https://cdn.example/arrival.mp4",
                title = "Arrival",
                mimeType = "video/mp4",
                mediaType = "VIDEO",
                contentCategory = "MOVIE",
                localPath = "offline/clip.mp4",
                fileStatus = "AVAILABLE",
                fileSize = 4096L,
                checksum = "sum",
                qualityLabel = "1080p",
                sourceRef = "moviebox|arrival_99|0|0|1080p",
                createdAt = fakeClockMs
            )
        )
        assertEquals(1, repo.observeOfflineAvailability().first().size)
        server.shutdown() // Force remote network error -> offline source still plays!
        val offlinePlay = repo.resolvePlayback("moviebox", "arrival_99").getOrThrow()
        assertTrue(offlinePlay is PlaybackResolution.Available)
        val available = offlinePlay as PlaybackResolution.Available
        assertTrue(available.sources.first().isOfflineLocal)
        assertEquals(mediaFile.toURI().toString(), available.sources.first().url)
    }

    @Test
    fun removeRecentlyViewed_deletesCanonicalAndProviderKeysWithoutTouchingHistoryWatchlistOrProgress() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(404)
            }
        }
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tvMazeProvider = TvMazeSeriesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tmdbProvider = TmdbOptionalMoviesProvider(OkHttpClient(), apiKeyProvider = { "" }, baseUrl = server.url("/").toString().trimEnd('/')),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )
        val item = MovieTitleSummary(
            id = "dune_rv",
            providerId = "moviebox",
            title = "Dune: Part Two",
            kind = MediaKind.MOVIE,
            releaseYear = 2024,
            durationMinutes = 166,
            isPlayableHint = true,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )

        repo.toggleWatchlist(item)
        repo.recordRecentlyViewed(item)
        repo.saveWatchProgress(
            item = item,
            positionMs = 1_200_000L,
            durationMs = 5_000_000L,
            seasonNumber = null,
            episodeNumber = null,
            episodeLabel = null
        )

        assertEquals(1, repo.observeRecentlyViewed(10).first().size)
        assertEquals(1, repo.observeMovieWatchHistory(10).first().size)
        assertTrue(repo.observeIsInWatchlist("moviebox:dune_rv").first())
        assertEquals(1_200_000L, repo.getSavedPositionMs("moviebox", "dune_rv", null, null))

        repo.removeRecentlyViewed("moviebox", "dune_rv")

        assertTrue(repo.observeRecentlyViewed(10).first().isEmpty())
        assertEquals(1, repo.observeMovieWatchHistory(10).first().size)
        assertTrue(repo.observeIsInWatchlist("moviebox:dune_rv").first())
        assertEquals(1_200_000L, repo.getSavedPositionMs("moviebox", "dune_rv", null, null))
    }

    @Test
    fun resolvePlayback_attachesOfflineSubtitlesWithZeroNetworkCallsAndStrictEpisodeIsolation() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/subs/en.srt" -> MockResponse().setResponseCode(200)
                        .setBody("1\n00:00:01,000 --> 00:00:03,000\nOffline S1E2 English\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val mediaFile = File.createTempFile("laterbox_ep_offline", ".mp4").apply {
            writeText("offline mp4 bytes")
            deleteOnExit()
        }
        val subDir = java.nio.file.Files.createTempDirectory("repo_offline_subs").toFile().apply { deleteOnExit() }
        val subManager = com.laterbox.app.movies.data.download.SubtitleDownloadManager(subtitlesRootDir = subDir)
        subManager.downloadSubtitlesForAsset(
            assetId = "off_ep_1_2",
            sourceRef = "moviebox|series_sub|1|2|1080p",
            providerId = "moviebox",
            titleId = "series_sub",
            seasonNumber = 1,
            episodeNumber = 2,
            subtitles = listOf(
                com.laterbox.app.movies.domain.model.SubtitleTrack("s_en", "English", "en", "English", server.url("/subs/en.srt").toString(), "srt")
            )
        )

        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tvMazeProvider = TvMazeSeriesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tmdbProvider = TmdbOptionalMoviesProvider(OkHttpClient(), apiKeyProvider = { "" }, baseUrl = server.url("/").toString().trimEnd('/')),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs },
            downloadedAssetDao = db.downloadedAssetDao(),
            localMediaUriResolver = { relPath -> if (relPath == "offline/ep12.mp4") mediaFile.toURI().toString() else null },
            subtitleDownloadManager = subManager
        )

        db.downloadedAssetDao().insertAsset(
            com.laterbox.app.media.data.db.entity.DownloadedAssetEntity(
                id = "off_ep_1_2",
                savedLinkId = null,
                sourcePlatform = "MovieBox",
                originalSourceUrl = "https://cdn.example/s1e2.mp4",
                title = "Episode 2",
                mimeType = "video/mp4",
                mediaType = "VIDEO",
                contentCategory = "MOVIES",
                localPath = "offline/ep12.mp4",
                fileStatus = "AVAILABLE",
                fileSize = 4096L,
                checksum = "sum12",
                qualityLabel = "1080p",
                sourceRef = "moviebox|series_sub|1|2|1080p",
                createdAt = fakeClockMs
            )
        )

        // Force zero network availability
        server.shutdown()

        val ep2Playback = repo.resolvePlayback("moviebox", "series_sub", 1, 2).getOrThrow()
        assertTrue(ep2Playback is PlaybackResolution.Available)
        val avail = ep2Playback as PlaybackResolution.Available
        assertTrue(avail.sources.first().isOfflineLocal)
        assertEquals(1, avail.subtitles.size)
        assertTrue(avail.subtitles.first().isOfflineLocal)
        assertTrue(avail.subtitles.first().url.startsWith("file:"))

        // Episode 1 must NOT receive Episode 2's offline video or subtitles
        val ep1Playback = repo.resolvePlayback("moviebox", "series_sub", 1, 1)
        assertTrue(ep1Playback.isFailure)
    }

    @Test
    fun shortDrama_persistsEpisodeProgressUpgradesLegacyCanonicalAndSupportsCatalogue() = runTest {
        val repo = createRepository()
        // Simulate a pre-existing legacy record where a MovieBox subjectType=7 item was stored as tv_series
        val legacyItem = MovieTitleSummary(
            id = "sd_legacy",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Billionaire CEO Bride",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        repo.toggleWatchlist(legacyItem)
        val legacyCanonical = db.moviesDao().getProviderLink("moviebox", "sd_legacy")?.canonicalId
        assertTrue(!legacyCanonical.isNullOrBlank())

        // Now MovieBox returns subjectType=7 -> MediaKind.SHORT_DRAMA
        val upgradedItem = legacyItem.copy(kind = MediaKind.SHORT_DRAMA, subjectType = 7, canonicalId = null)
        repo.saveWatchProgress(
            item = upgradedItem,
            positionMs = 45_000L,
            durationMs = 180_000L,
            seasonNumber = 1,
            episodeNumber = 4,
            episodeLabel = "S1 · E4"
        )

        val canonicalEntity = db.moviesDao().getCanonicalById(legacyCanonical!!)!!
        assertEquals(MediaKind.SHORT_DRAMA.wireValue, canonicalEntity.kind)

        val cw = repo.observeContinueWatching().first()
        assertEquals(1, cw.size)
        assertEquals(MediaKind.SHORT_DRAMA, cw.first().kind)
        assertEquals(legacyCanonical, cw.first().canonicalId)

        val epHistory = repo.observeEpisodeWatchHistory().first()
        assertEquals(1, epHistory.size)
        assertEquals(MediaKind.SHORT_DRAMA, epHistory.first().kind)
        assertEquals(4, epHistory.first().episodeNumber)
    }

    private fun createRepositoryWithMovieBox(withMovieBox: Boolean): MoviesRepositoryImpl {
        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        return MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { tmdbKey }, baseUrl = base),
            fixtureProvider = DevelopmentMoviesFixtureProvider(),
            movieBoxProvider = if (withMovieBox) {
                com.laterbox.app.movies.data.provider.MovieBoxProvider(client, baseUrlProvider = { base })
            } else {
                null
            },
            useSampleFixturesOnlyProvider = { useFixtures },
            nowMs = { fakeClockMs },
            downloadedAssetDao = db.downloadedAssetDao()
        )
    }

    @Test
    fun supplementarySearch_preservesMovieBoxOrderAppendsUniqueAndDeduplicatesCanonicalMatches() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search?") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "query": "dune",
                          "page": 1,
                          "results": [
                            {"subject_id": "mb_z", "subject_type": 1, "title": "Dune", "release_date": "2021-10-22", "imdb_id": "tt1160419"},
                            {"subject_id": "mb_a", "subject_type": 2, "title": "Dune Chronicles", "release_date": "2000-12-03", "imdb_id": "tt0142032"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/advancedsearch.php") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "response": {
                            "numFound": 2,
                            "docs": [
                              {"identifier": "ia_dup_dune", "title": "Dune", "year": "2021"},
                              {"identifier": "ia_unique_1984", "title": "Dune Classic Archive", "year": "1984"}
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/search/shows") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "score": 1.0,
                            "show": {
                              "id": 901,
                              "name": "Dune Chronicles",
                              "premiered": "2000-12-03",
                              "externals": {"imdb": "tt0142032"}
                            }
                          },
                          {
                            "score": 0.9,
                            "show": {
                              "id": 902,
                              "name": "Dune Prophecy Guide",
                              "premiered": "2024-11-17"
                            }
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repo = createRepositoryWithMovieBox(withMovieBox = true)
        val page = repo.search("dune", kind = null, page = 1, pageSize = 20).getOrThrow()

        // MovieBox exact order is preserved verbatim in primary slots
        assertEquals(2, page.primaryCount)
        assertEquals(listOf("mb_z", "mb_a"), page.items.take(page.primaryCount).map { it.id })

        // Canonical T1 match (TVMaze 901 matching mb_a via tt0142032) is deduplicated;
        // IA item without corroborating T1/T2 metadata is NOT falsely deduplicated by title alone.
        val supplementary = page.items.drop(page.primaryCount)
        assertEquals(listOf("ia_dup_dune", "ia_unique_1984", "902"), supplementary.map { it.id })
        assertTrue(supplementary.none { it.title == "Dune Chronicles" })
    }

    @Test
    fun supplementarySearch_independentProviderFailureStillReturnsMovieBoxResults() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search?") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "query": "solar",
                          "page": 1,
                          "results": [
                            {"subject_id": "mb_solar", "subject_type": 1, "title": "Solaris"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/advancedsearch.php") -> MockResponse().setResponseCode(503)
                    path.startsWith("/search/shows") -> MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repo = createRepositoryWithMovieBox(withMovieBox = true)
        val page = repo.search("solar", kind = null).getOrThrow()
        assertEquals(1, page.primaryCount)
        assertEquals(listOf("mb_solar"), page.items.map { it.id })
    }

    @Test
    fun librarySuggestions_mergesSourcesExcludesHiddenPreservesShortDramaAndTagsOffline() = runTest {
        val repo = createRepositoryWithMovieBox(withMovieBox = false)

        // 1. Short Drama in Continue Watching + downloaded offline asset
        val shortDrama = MovieTitleSummary(
            id = "sd_ceo",
            providerId = "moviebox",
            kind = MediaKind.SHORT_DRAMA,
            title = "CEO Hidden Contract",
            releaseYear = 2026,
            posterUrl = "https://cdn.example/ceo.jpg",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true,
            subjectType = 7
        )
        fakeClockMs = 2_000_000L
        repo.saveWatchProgress(shortDrama, positionMs = 15_000L, durationMs = 120_000L, seasonNumber = 1, episodeNumber = 2)
        db.downloadedAssetDao().insertAsset(
            com.laterbox.app.media.data.db.entity.DownloadedAssetEntity(
                id = "dl_ceo",
                savedLinkId = null,
                sourcePlatform = "MovieBox",
                originalSourceUrl = "https://cdn.example/ceo.mp4",
                title = "CEO Hidden Contract",
                mimeType = "video/mp4",
                mediaType = "VIDEO",
                contentCategory = "EPISODE",
                localPath = "offline/ceo.mp4",
                fileStatus = "AVAILABLE",
                fileSize = 2048L,
                checksum = "sum",
                qualityLabel = "720p",
                sourceRef = "moviebox|sd_ceo|1|2|720p",
                createdAt = fakeClockMs
            )
        )

        // 2. Movie in Watchlist
        val watchlistMovie = MovieTitleSummary(
            id = "m_alpha",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Contract Killers",
            releaseYear = 2022,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        fakeClockMs = 2_010_000L
        repo.toggleWatchlist(watchlistMovie)

        // 3. Collection item
        val colId = db.collectionDao().insertCollection(
            com.laterbox.app.data.local.entity.CollectionEntity(
                name = "Sci-Fi",
                createdAt = fakeClockMs,
                updatedAt = fakeClockMs
            )
        )
        val colItem = MovieTitleSummary(
            id = "tv_col",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Solar Contract",
            releaseYear = 2023,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        fakeClockMs = 2_020_000L
        repo.addMediaToCollection(colItem, colId)

        // 4. Recently viewed item that was hidden -> must be excluded
        val hiddenItem = MovieTitleSummary(
            id = "m_hidden",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Hidden Contract",
            releaseYear = 2021,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        fakeClockMs = 2_030_000L
        repo.recordRecentlyViewed(hiddenItem)
        repo.setTitleHidden(hiddenItem, hidden = true)

        // Query "contract": prefix ("Contract Killers") ranks before substring, Short Drama & Offline preserved, hidden excluded
        val queryMatches = repo.searchLibrarySuggestions("contract", limit = 6)
        assertEquals(listOf("m_alpha", "sd_ceo", "tv_col"), queryMatches.map { it.summary.id })
        val ceoSuggestion = queryMatches.first { it.summary.id == "sd_ceo" }
        assertEquals(MediaKind.SHORT_DRAMA, ceoSuggestion.summary.kind)
        assertTrue(ceoSuggestion.isDownloaded)
        assertEquals("Continue Watching", ceoSuggestion.sourceLabel)
        assertTrue(queryMatches.none { it.summary.id == "m_hidden" })

        // Empty query returns local library items ordered by priority/recency
        val emptyMatches = repo.searchLibrarySuggestions("", limit = 6)
        assertEquals(3, emptyMatches.size)
        assertEquals("sd_ceo", emptyMatches.first().summary.id)
    }

    @Test
    fun recentSearches_deduplicatesCaseInsensitivelyAndNormalizesWhitespace() = runTest {
        val repo = createRepositoryWithMovieBox(withMovieBox = false)
        fakeClockMs = 1_000_000L
        repo.recordSearchQuery("  CEO   Bride  ")
        fakeClockMs = 1_005_000L
        repo.recordSearchQuery("ceo bride")
        fakeClockMs = 1_010_000L
        repo.recordSearchQuery("Dune")

        val recent = repo.observeRecentSearches(10).first()
        assertEquals(listOf("Dune", "ceo bride"), recent)
    }

    @Test
    fun resolvePlayback_returnsVerifiedLocalFileImmediatelyWithoutNetworkAndPrefersHighestQuality() = runTest {
        val file720 = File.createTempFile("lb_720", ".mp4").apply {
            writeText("720p bytes")
            deleteOnExit()
        }
        val file1080 = File.createTempFile("lb_1080", ".mp4").apply {
            writeText("1080p bytes")
            deleteOnExit()
        }
        server.shutdown()

        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(OkHttpClient(), "http://127.0.0.1:1"),
            tvMazeProvider = TvMazeSeriesProvider(OkHttpClient(), "http://127.0.0.1:1"),
            tmdbProvider = TmdbOptionalMoviesProvider(OkHttpClient(), apiKeyProvider = { "" }, baseUrl = "http://127.0.0.1:1"),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs },
            downloadedAssetDao = db.downloadedAssetDao(),
            downloadPlaybackProgressDao = db.downloadPlaybackProgressDao(),
            localMediaUriResolver = { relPath ->
                val target = when (relPath) {
                    "offline/arrival_720.mp4" -> file720
                    "offline/arrival_1080.mp4" -> file1080
                    else -> null
                }
                target?.takeIf { it.exists() && it.length() > 0L }?.toURI()?.toString()
            }
        )

        db.downloadedAssetDao().insertAsset(
            com.laterbox.app.media.data.db.entity.DownloadedAssetEntity(
                id = "off_720",
                savedLinkId = null,
                sourcePlatform = "MovieBox",
                originalSourceUrl = "https://cdn.example/arrival_720.mp4",
                title = "Arrival 720p",
                mimeType = "video/mp4",
                mediaType = "VIDEO",
                contentCategory = "MOVIE",
                localPath = "offline/arrival_720.mp4",
                checksum = "sum720",
                qualityLabel = "720p",
                sourceRef = "moviebox|arrival_99|0|0|720p",
                fileStatus = "AVAILABLE",
                fileSize = 100L,
                createdAt = 1_000L
            )
        )
        db.downloadedAssetDao().insertAsset(
            com.laterbox.app.media.data.db.entity.DownloadedAssetEntity(
                id = "off_1080",
                savedLinkId = null,
                sourcePlatform = "MovieBox",
                originalSourceUrl = "https://cdn.example/arrival_1080.mp4",
                title = "Arrival 1080p",
                mimeType = "video/mp4",
                mediaType = "VIDEO",
                contentCategory = "MOVIE",
                localPath = "offline/arrival_1080.mp4",
                checksum = "sum1080",
                qualityLabel = "1080p",
                sourceRef = "moviebox|arrival_99|0|0|1080p",
                fileStatus = "AVAILABLE",
                fileSize = 200L,
                createdAt = 2_000L
            )
        )

        val res = repo.resolvePlayback("moviebox", "arrival_99").getOrThrow() as PlaybackResolution.Available
        assertEquals(file1080.toURI().toString(), res.sources.first().url)
        assertTrue(res.sources.first().isOfflineLocal)
        assertEquals(listOf("1080p (Offline)", "720p (Offline)"), res.sources.map { it.qualityLabel })

        // Verify stale file cleanup on disk removes offline availability per verified file
        assertEquals(2, repo.observeOfflineAvailability().first().size)
        assertTrue(file1080.delete())
        assertEquals(1, repo.observeOfflineAvailability().first().size)
        assertTrue(file720.delete())
        assertTrue(repo.observeOfflineAvailability().first().isEmpty())
    }

    @Test
    fun downloadPlaybackProgress_sharesMovieProgressAcrossQualities_isolatesGenericAssets_andSyncsWithWatchHistory() = runTest {
        val progressDao = db.downloadPlaybackProgressDao()
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tvMazeProvider = TvMazeSeriesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tmdbProvider = TmdbOptionalMoviesProvider(OkHttpClient(), apiKeyProvider = { "" }, baseUrl = server.url("/").toString().trimEnd('/')),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs },
            downloadedAssetDao = db.downloadedAssetDao(),
            downloadPlaybackProgressDao = progressDao,
            localMediaUriResolver = { null }
        )

        val movie = MovieTitleSummary(
            id = "dune_2",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Dune Part Two",
            releaseYear = 2024,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        // Saving watch progress updates download_playback_progress with canonical quality-free key
        fakeClockMs = 5_000_000L
        repo.saveWatchProgress(movie, positionMs = 310_000L, durationMs = 720_000L)
        val canonicalKey = com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.forMediaItem(
            providerId = "moviebox",
            titleId = "dune_2",
            seasonNumber = null,
            episodeNumber = null
        )
        assertEquals("media:moviebox|dune_2|0|0", canonicalKey)
        assertEquals(310_000L, progressDao.getByKey(canonicalKey)?.positionMs)

        // Multi-quality assets for same movie resolve to the exact same canonical key
        val asset720Key = com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.fromSourceRef("moviebox|dune_2|0|0|720p")
        val asset1080Key = com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.fromSourceRef("moviebox|dune_2|0|0|1080p")
        assertEquals(canonicalKey, asset720Key)
        assertEquals(canonicalKey, asset1080Key)

        // Generic assets without Movie/TV sourceRef stay isolated per asset id
        val generic1 = com.laterbox.app.media.domain.model.MediaAsset(
            id = "gen_1",
            savedLinkId = 1L,
            sourcePlatform = "youtube",
            originalSourceUrl = "https://example.com/1",
            title = "Clip 1",
            mimeType = "video/mp4",
            mediaType = com.laterbox.app.media.domain.model.MediaType.VIDEO,
            localPath = "video/1.mp4",
            exportedUri = null,
            fileSize = 100L,
            durationMs = null,
            width = null,
            height = null,
            qualityLabel = "720p",
            checksum = "chk1",
            createdAt = 1L,
            sourceRef = null
        )
        val generic2 = generic1.copy(id = "gen_2", localPath = "video/2.mp4")
        assertEquals("asset:gen_1", com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.forAsset(generic1))
        assertEquals("asset:gen_2", com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.forAsset(generic2))

        // Fresher local download playback progress surfaces via getSavedPositionMs
        progressDao.upsert(
            com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressEntity(
                progressKey = canonicalKey,
                assetId = "off_dune_1080",
                sourceRef = "moviebox|dune_2|0|0|1080p",
                positionMs = 420_000L,
                durationMs = 720_000L,
                updatedAt = 5_100_000L
            )
        )
        assertEquals(420_000L, repo.getSavedPositionMs("moviebox", "dune_2"))
    }

    @Test
    fun movieDuration_preservedFromSummaryCacheWhenDetailsOmitsIt_andMappedFromTmdbRuntime() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "code": 0,
                          "data": {
                            "operatingList": [
                              {
                                "type": "SUBJECTS_MOVIE",
                                "title": "Featured",
                                "subjects": [
                                  {
                                    "subjectId": "dur_1",
                                    "subjectType": 1,
                                    "title": "Two Hour Movie",
                                    "releaseDate": "2024-01-10",
                                    "duration": 7200,
                                    "cover": {"url": "https://cdn.example/dur1.jpg"},
                                    "detailPath": "two-hour-movie"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/detail") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject": {
                            "subjectId": "dur_1",
                            "subjectType": 1,
                            "title": "Two Hour Movie",
                            "releaseDate": "2024-01-10",
                            "description": "Details payload omits duration."
                          },
                          "seasons": []
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/movie/99") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "id": 99,
                          "title": "TMDB Timed Film",
                          "release_date": "2023-07-19",
                          "runtime": 135,
                          "overview": "Runtime in minutes from TMDB."
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        val mbProvider = com.laterbox.app.movies.data.provider.MovieBoxProvider(client, baseUrlProvider = { base })
        val tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "tmdb-key" }, baseUrl = base)
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = tmdbProvider,
            movieBoxProvider = mbProvider,
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )

        val feed = repo.getHomeFeed(forceRefresh = true).getOrThrow()
        assertEquals(120, feed.featuredBanners.first { it.id == "dur_1" }.durationMinutes)

        val details = repo.getDetails("moviebox", "dur_1").getOrThrow()
        assertEquals(120, details.summary.durationMinutes)

        val tmdbDetails = tmdbProvider.getDetails("99").getOrThrow()
        assertEquals(135, tmdbDetails.summary.durationMinutes)
    }

    @Test
    fun enrichMissingMovieDurations_batchesMovieBoxAppliesToMoviesOnlyAndRespectsNegativeCooldown() = runTest {
        var batchCalls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/movies/metadata/runtimes" -> {
                        batchCalls++
                        val body = request.body.readUtf8()
                        assertFalse("Series must not be queried for movie duration", body.contains("tv_1"))
                        MockResponse().setResponseCode(200).setBody(
                            """{"runtimes":{"mb_1":118},"unresolved":["mb_2"]}"""
                        )
                    }
                    path.startsWith("/detail/mb_1") -> MockResponse().setResponseCode(200).setBody(
                        """{"subject":{"subjectId":"mb_1","subjectType":1,"title":"Resolved Film","releaseDate":"2022-05-01","description":"Initial miss"}}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        val mbProvider = com.laterbox.app.movies.data.provider.MovieBoxProvider(client, baseUrlProvider = { base })
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }, baseUrl = base),
            movieBoxProvider = mbProvider,
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )

        // Pre-seed detail cache entry without duration so enrichment patches Room too
        val seededDetail = repo.getDetails("moviebox", "mb_1").getOrThrow()
        assertEquals(null, seededDetail.summary.durationMinutes)

        val movieResolved = MovieTitleSummary(
            id = "mb_1",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Resolved Film",
            releaseYear = 2022
        )
        val movieUnresolved = MovieTitleSummary(
            id = "mb_2",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Unknown Film",
            releaseYear = 2021
        )
        val seriesItem = MovieTitleSummary(
            id = "tv_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Some Series",
            releaseYear = 2023
        )

        val firstPass = repo.enrichMissingMovieDurations(listOf(movieResolved, movieUnresolved, seriesItem))
        assertEquals(118, firstPass["moviebox:mb_1"])
        assertFalse(firstPass.containsKey("moviebox:mb_2"))
        assertFalse(firstPass.containsKey("moviebox:tv_1"))
        assertEquals(1, batchCalls)

        // Cached detail entry in Room must now have the enriched duration
        val patchedDetail = repo.getDetails("moviebox", "mb_1", forceRefresh = false).getOrThrow()
        assertEquals(118, patchedDetail.summary.durationMinutes)

        // Re-running within cooldown must NOT issue another network call for mb_1 (hit) or mb_2 (cooldown miss)
        val secondPass = repo.enrichMissingMovieDurations(listOf(movieResolved, movieUnresolved))
        assertEquals(118, secondPass["moviebox:mb_1"])
        assertEquals(1, batchCalls)
    }
}
