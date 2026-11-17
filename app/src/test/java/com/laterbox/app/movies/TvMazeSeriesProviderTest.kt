package com.laterbox.app.movies

import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.PlaybackResolution
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvMazeSeriesProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: TvMazeSeriesProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = TvMazeSeriesProvider(
            okHttpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun detailsAndEpisodes_parseEmbeddedSeasonsCastAndHonestUnavailability() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/shows/169?embed") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "id": 169,
                          "url": "https://www.tvmaze.com/shows/169/breaking-bad",
                          "name": "Breaking Bad",
                          "language": "English",
                          "genres": ["Drama", "Crime", "Thriller"],
                          "premiered": "2008-01-20",
                          "averageRuntime": 60,
                          "rating": {"average": 9.2},
                          "network": {"name": "AMC", "country": {"name": "United States"}},
                          "image": {"medium": "https://img.tvmaze.com/m.jpg", "original": "https://img.tvmaze.com/o.jpg"},
                          "summary": "<p>Chemistry teacher turns to crime.</p>",
                          "_embedded": {
                            "cast": [
                              {"person": {"name": "Bryan Cranston"}, "character": {"name": "Walter White"}}
                            ],
                            "seasons": [
                              {"number": 1, "name": "Season 1", "episodeOrder": 7},
                              {"number": 2, "name": "", "episodeOrder": 13}
                            ],
                            "episodes": [
                              {"id": 12192, "season": 1, "number": 1, "name": "Pilot", "runtime": 60, "airdate": "2008-01-20", "summary": "<p>Pilot episode</p>"}
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/shows/169/episodes") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {"id": 12192, "season": 1, "number": 1, "name": "Pilot", "runtime": 60, "airdate": "2008-01-20"},
                          {"id": 12193, "season": 1, "number": 2, "name": "Cat's in the Bag...", "runtime": 48, "airdate": "2008-01-27"},
                          {"id": 12200, "season": 2, "number": 1, "name": "Seven Thirty-Seven", "runtime": 47, "airdate": "2009-03-08"}
                        ]
                        """.trimIndent()
                    )
                    path == "/shows/169" -> MockResponse().setResponseCode(200).setBody(
                        """{"id": 169, "name": "Breaking Bad", "url": "https://www.tvmaze.com/shows/169/breaking-bad"}"""
                    )
                    path.startsWith("/shows?page=0") -> MockResponse().setResponseCode(200).setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val details = provider.getDetails("169").getOrThrow()
        assertEquals(MediaKind.TV_SERIES, details.summary.kind)
        assertEquals(DataSourceBadge.LIVE_TVMAZE, details.summary.sourceBadge)
        assertEquals("Breaking Bad", details.summary.title)
        assertEquals("AMC", details.networkOrStudio)
        assertEquals(2, details.seasons.size)
        assertEquals("Season 2", details.seasons[1].title)
        assertEquals("Bryan Cranston", details.cast.first().name)

        val s1Episodes = provider.getEpisodes("169", 1).getOrThrow()
        assertEquals(2, s1Episodes.size)
        assertEquals("Cat's in the Bag...", s1Episodes[1].title)

        val resolution = provider.resolvePlaybackSources("169", 1, 2).getOrThrow()
        assertTrue(resolution is PlaybackResolution.Unavailable)
        val unavailable = resolution as PlaybackResolution.Unavailable
        assertTrue(unavailable.title.contains("Breaking Bad (S1E2)"))
        assertEquals("https://www.tvmaze.com/shows/169/breaking-bad", unavailable.externalReferenceUrl)
    }

    @Test
    fun searchAndSuggestions_returnSeriesHits() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {
                    "score": 0.9,
                    "show": {
                      "id": 82,
                      "name": "Game of Thrones",
                      "genres": ["Drama", "Adventure", "Fantasy"],
                      "premiered": "2011-04-17",
                      "rating": {"average": 8.9}
                    }
                  }
                ]
                """.trimIndent()
            )
        )

        val suggestions = provider.getSuggestions("Game", 5).getOrThrow()
        assertEquals(1, suggestions.size)
        assertEquals("82", suggestions.first().id)
        assertEquals(MediaKind.TV_SERIES, suggestions.first().kind)
    }

    @Test
    fun homeFeed_mergesWebStreamingAndBroadcastSchedules() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/shows?page=0") -> MockResponse().setResponseCode(200).setBody("[]")
                    path.startsWith("/schedule/web") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "id": 9001,
                            "_embedded": {
                              "show": {
                                "id": 5001,
                                "name": "Severance",
                                "genres": ["Drama", "Science-Fiction"],
                                "premiered": "2022-02-18",
                                "rating": {"average": 8.7}
                              }
                            }
                          }
                        ]
                        """.trimIndent()
                    )
                    path.startsWith("/schedule") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "id": 9002,
                            "show": {
                              "id": 5002,
                              "name": "Abbott Elementary",
                              "genres": ["Comedy"],
                              "premiered": "2021-12-07",
                              "rating": {"average": 8.2}
                            }
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val feed = provider.getHomeFeed().getOrThrow()
        val trending = feed.sections.firstOrNull { it.id == "tvmaze_trending_tonight" }
        assertTrue(trending != null)
        val titles = trending!!.items.map { it.title }
        assertTrue("Severance" in titles)
        assertTrue("Abbott Elementary" in titles)
    }
}
