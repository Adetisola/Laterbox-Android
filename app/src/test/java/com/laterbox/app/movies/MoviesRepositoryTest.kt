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
}
