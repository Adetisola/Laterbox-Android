package com.laterbox.app.diagnostics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.repository.MoviesRepositoryImpl
import com.laterbox.app.movies.ui.MovieDetailViewModel
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NetworkWaterfallBaselineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: LaterboxDatabase

    @Before
    fun setUp() {
        NetworkWaterfallLogger.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/detail/") -> MockResponse()
                        .setBodyDelay(60, TimeUnit.MILLISECONDS)
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "subject_id":"10001",
                              "title":"Waterfall Series",
                              "subject_type":2,
                              "type":"Series",
                              "description":"Sample series description",
                              "release_date":"2024-01-01",
                              "duration":2700,
                              "duration_sec":2700,
                              "imdb_rating":"8.2",
                              "genre":"Drama",
                              "country":"United States",
                              "language":"English",
                              "cover":"https://cdn.example/c.jpg",
                              "detail_path":"waterfall-series",
                              "seasons":[{"season":1,"episode_count":2,"all_ep":[1,2],"resolutions":[1080,720]}],
                              "stars":[{"staff_id":"10","name":"Lead Actor","staff_type":1}],
                              "related":[]
                            }
                            """.trimIndent()
                        )
                    path.startsWith("/episodes/10001/metadata") -> MockResponse()
                        .setBodyDelay(40, TimeUnit.MILLISECONDS)
                        .setResponseCode(200)
                        .setBody("""{"episodes":[{"episode":1,"title":"Pilot","still_url":"https://cdn.example/s1e1.jpg"}]}""")
                    path.startsWith("/episodes/10001") -> MockResponse()
                        .setBodyDelay(50, TimeUnit.MILLISECONDS)
                        .setResponseCode(200)
                        .setBody("""{"subject_id":"10001","seasons":[{"season":1,"episode_count":2,"all_ep":[1,2],"resolutions":[1080,720]}]}""")
                    path.startsWith("/api/stream/10001/captions") -> MockResponse()
                        .setBodyDelay(50, TimeUnit.MILLISECONDS)
                        .setResponseCode(200)
                        .setBody("""{"captions":[{"id":"en1","language":"English","code":"en","url":"https://cdn.example/en.srt"}]}""")
                    path.startsWith("/api/stream/10001") -> MockResponse()
                        .setBodyDelay(60, TimeUnit.MILLISECONDS)
                        .setResponseCode(200)
                        .setBody("""{"streams":[{"url":"https://cdn.example/v.mp4","format":"MP4","quality":"720p","quality_value":720,"size_mb":320.0}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LaterboxDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun capturesDetailOpenWaterfallSpans() = runBlocking {
        val client = OkHttpClient.Builder().build()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val mb = MovieBoxProvider(
            okHttpClient = client,
            baseUrlProvider = { baseUrl },
            appCheckTokenProvider = { "test-token" },
        )
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client),
            tvMazeProvider = TvMazeSeriesProvider(client),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }),
            fixtureProvider = DevelopmentMoviesFixtureProvider(),
            movieBoxProvider = mb,
            isInternetArchiveEnabled = { false },
            isTvMazeEnabled = { false },
            isTmdbEnabled = { false },
            ioDispatcher = Dispatchers.IO,
            defaultDispatcher = Dispatchers.Default,
        )
        val vm = MovieDetailViewModel(
            moviesRepository = repo,
            computeDispatcher = Dispatchers.Default
        )
        vm.load("moviebox", "10001")
        val deadline = System.currentTimeMillis() + 8_000L
        while (NetworkWaterfallLogger.latestCompletedSessions().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L)
        }
        val coldSession = NetworkWaterfallLogger.latestCompletedSessions().lastOrNull()
        assertNotNull("Expected completed cold detail waterfall session", coldSession)
        println("PHASE1_WATERFALL_COLD: ${NetworkWaterfallLogger.formatWaterfallReport(coldSession!!)}")
        assertTrue(coldSession.spans.any { it.callGroup == "detail" })
        assertTrue(coldSession.spans.any { it.callGroup == "stream_fallback" })
        assertTrue(coldSession.spans.any { it.callGroup == "captions" })

        // Re-open with Room cache populated
        val vmCached = MovieDetailViewModel(
            moviesRepository = repo,
            computeDispatcher = Dispatchers.Default
        )
        vmCached.load("moviebox", "10001")
        val deadline2 = System.currentTimeMillis() + 8_000L
        while (NetworkWaterfallLogger.latestCompletedSessions().size < 2 && System.currentTimeMillis() < deadline2) {
            Thread.sleep(25L)
        }
        val cachedSession = NetworkWaterfallLogger.latestCompletedSessions().getOrNull(1)
        assertNotNull("Expected completed cached detail waterfall session", cachedSession)
        println("PHASE1_WATERFALL_CACHED: ${NetworkWaterfallLogger.formatWaterfallReport(cachedSession!!)}")
    }
}
