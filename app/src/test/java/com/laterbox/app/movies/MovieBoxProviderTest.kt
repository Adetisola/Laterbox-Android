package com.laterbox.app.movies

import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SectionStyle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MovieBoxProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: MovieBoxProvider
    private var apiKey: String = ""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString()
        provider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { baseUrl },
            apiKeyProvider = { apiKey }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun homeFeed_parsesBannerSectionsFiltersSkipsEmptyAndCachesDetailPath() = runTest {
        val recordedRequests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return when {
                    request.path == "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "operatingList": [
                              {
                                "type": "BANNER",
                                "title": "Top Banner",
                                "banner": {
                                  "items": [
                                    {
                                      "title": "Avatar",
                                      "image": {"url": "https://cdn.example/banner-avatar.jpg"},
                                      "subject": {
                                        "subjectId": "8906247916759695608",
                                        "subjectType": 1,
                                        "title": "Avatar",
                                        "description": "Pandora sci-fi epic",
                                        "releaseDate": "2009-12-18",
                                        "duration": 9720,
                                        "genre": "Sci-Fi, Action",
                                        "cover": {"url": "https://cdn.example/poster-avatar.jpg"},
                                        "countryName": "United States",
                                        "imdbRatingValue": "7.9",
                                        "hasResource": true,
                                        "detailPath": "avatar-2009",
                                        "trailer": {"videoAddress": {"url": "https://cdn.example/avatar-trailer.mp4"}}
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "type": "FILTER",
                                "title": "Categories",
                                "filters": [
                                  {"title": "Action"},
                                  {"title": "Sci-Fi"},
                                  {"title": "Drama"}
                                ]
                              },
                              {
                                "type": "SUBJECTS_MOVIE",
                                "opId": "trending_now",
                                "title": "Trending Now",
                                "subjects": [
                                  {
                                    "subjectId": "6207982430134357800",
                                    "subjectType": 2,
                                    "title": "Breaking Bad",
                                    "releaseDate": "2008-01-20",
                                    "duration": 2880,
                                    "genre": "Crime, Drama",
                                    "imdbRatingValue": "9.5",
                                    "cover": {"url": "https://cdn.example/bb.jpg"},
                                    "detailPath": "breaking-bad"
                                  }
                                ]
                              },
                              {
                                "type": "SPORT_LIVE",
                                "title": "Empty Live Section",
                                "subjects": []
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val feed = provider.getHomeFeed().getOrThrow()
        assertEquals(1, feed.featuredBanners.size)
        val banner = feed.featuredBanners.first()
        assertEquals("8906247916759695608", banner.id)
        assertEquals(MediaKind.MOVIE, banner.kind)
        assertEquals(162, banner.durationMinutes)
        assertEquals("https://cdn.example/avatar-trailer.mp4", banner.trailerUrl)
        assertEquals("avatar-2009", banner.detailPath)
        assertEquals(DataSourceBadge.LIVE_MOVIEBOX, banner.sourceBadge)

        assertEquals(1, feed.sections.size)
        assertEquals("Trending Now", feed.sections.first().title)
        assertEquals(SectionStyle.RAIL, feed.sections.first().style)
        assertEquals(MediaKind.TV_SERIES, feed.sections.first().items.first().kind)
        assertEquals(listOf("All", "Action", "Sci-Fi", "Drama"), feed.genres)
        assertFalse(feed.containsSampleFixtures)
    }

    @Test
    fun catalogueMoviesAndSeries_mapSortPaginationAndApiKeyHeader() = runTest {
        apiKey = "secret-test-key"
        val paths = mutableListOf<String>()
        val apiKeys = mutableListOf<String?>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add(request.path.orEmpty())
                apiKeys.add(request.getHeader("X-API-Key"))
                return when {
                    request.path?.startsWith("/movies") == true -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "page": 1,
                          "per_page": 2,
                          "total": 10,
                          "items": [
                            {"title": "Film One", "subject_id": "111", "detail_path": "film-one", "cover": "https://cdn.example/1.jpg", "rating": "8.1", "year": "2024"},
                            {"title": "Film Two", "subject_id": "222", "detail_path": "film-two", "cover": "https://cdn.example/2.jpg", "rating": "7.5", "year": "2023"}
                          ]
                        }
                        """.trimIndent()
                    )
                    request.path?.startsWith("/tv-series") == true -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "page": 2,
                          "per_page": 2,
                          "total": 4,
                          "items": [
                            {"title": "Series One", "subject_id": "333", "detail_path": "series-one", "cover": "https://cdn.example/3.jpg", "rating": "9.0", "year": "2022"},
                            {"title": "Series Two", "subject_id": "444", "detail_path": "series-two", "cover": null, "rating": null, "year": "2021"}
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Passing a genre should NOT wipe out /movies items (since /movies doesn't emit per-item genres)
        val moviesPage = provider.getCatalogue(
            kind = MediaKind.MOVIE,
            page = 1,
            pageSize = 2,
            genre = "Action",
            sort = CatalogueSort.TOP_RATED
        ).getOrThrow()
        assertEquals(2, moviesPage.items.size)
        assertTrue(moviesPage.hasMore)
        assertTrue(paths.first().contains("/movies?page=1&per_page=2&sort=RANKING"))
        assertEquals("secret-test-key", apiKeys.first())

        val seriesPage = provider.getCatalogue(
            kind = MediaKind.TV_SERIES,
            page = 2,
            pageSize = 2,
            sort = CatalogueSort.NEWEST
        ).getOrThrow()
        assertEquals(2, seriesPage.items.size)
        assertFalse(seriesPage.hasMore)
        assertEquals(MediaKind.TV_SERIES, seriesPage.items.first().kind)
        assertTrue(paths[1].contains("/tv-series?page=2&per_page=2&sort=LATEST"))
    }

    @Test
    fun searchAndSuggest_normalizeNoneSubjectIdAndFilterKind() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/search/suggest") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "suggestions": [
                            {"title": "Avatar", "slug": "avatar-2009", "subject_id": "8906247916759695608"},
                            {"title": "Avatar Keyword", "slug": null, "subject_id": "None"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/search") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "query": "Avatar",
                          "page": 1,
                          "results": [
                            {
                              "subject_id": "8906247916759695608",
                              "title": "Avatar",
                              "type": "Movie",
                              "subject_type": 1,
                              "release_date": "2009-12-18",
                              "imdb_rating": "7.9",
                              "genre": "Action, Sci-Fi",
                              "cover": "https://cdn.example/avatar.jpg",
                              "detail_path": "avatar-2009",
                              "language": "English"
                            },
                            {
                              "subject_id": "999",
                              "title": "Avatar Animated Series",
                              "type": "Series",
                              "subject_type": 2,
                              "release_date": "2005-02-21",
                              "imdb_rating": "9.3",
                              "genre": "Animation",
                              "cover": "https://cdn.example/atla.jpg",
                              "detail_path": "atla",
                              "language": "English"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val suggestions = provider.getSuggestions("Avatar", 5).getOrThrow()
        assertEquals(2, suggestions.size)
        assertEquals("8906247916759695608", suggestions[0].id)
        assertEquals("", suggestions[1].id)

        val movieResults = provider.search("Avatar", kind = MediaKind.MOVIE).getOrThrow()
        assertEquals(1, movieResults.items.size)
        assertEquals("8906247916759695608", movieResults.items.first().id)

        val seriesResults = provider.search("Avatar", kind = MediaKind.TV_SERIES).getOrThrow()
        assertEquals(1, seriesResults.items.size)
        assertEquals("999", seriesResults.items.first().id)
    }

    @Test
    fun detailsAndEpisodes_parseMetadataDubsAndSynthesizeEpisodeNumbers() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/detail/6207982430134357800" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "6207982430134357800",
                          "title": "Breaking Bad",
                          "subject_type": 2,
                          "type": "Series",
                          "description": "High school chemistry teacher turned manufacturer.",
                          "release_date": "2008-01-20",
                          "duration": 2700,
                          "imdb_rating": "9.5",
                          "genre": "Crime, Drama, Thriller",
                          "country": "United States",
                          "language": "English",
                          "cover": "https://cdn.example/bb.jpg",
                          "detail_path": "breaking-bad-series",
                          "dubs": [
                            {"subject_id": "6207", "lang_name": "English", "language": "en"},
                            {"subject_id": "6208", "lang_name": "French dub", "language": "fr"}
                          ]
                        }
                        """.trimIndent()
                    )
                    "/episodes/6207982430134357800" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "6207982430134357800",
                          "seasons": [
                            {"season": 1, "episode_count": 7, "resolutions": []},
                            {"season": 2, "episode_count": 13, "resolutions": []}
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val details = provider.getDetails("6207982430134357800").getOrThrow()
        assertEquals("Breaking Bad", details.summary.title)
        assertEquals(MediaKind.TV_SERIES, details.summary.kind)
        assertEquals(45, details.summary.durationMinutes)
        assertEquals("United States", details.summary.country)
        assertEquals("breaking-bad-series", details.summary.detailPath)
        assertEquals(2, details.audioDubs.size)
        assertEquals(2, details.seasons.size)
        assertEquals(7, details.seasons[0].episodeCount)
        assertEquals(13, details.seasons[1].episodeCount)

        val episodesS2 = provider.getEpisodes("6207982430134357800", seasonNumber = 2).getOrThrow()
        assertEquals(13, episodesS2.size)
        assertEquals(1, episodesS2.first().episodeNumber)
        assertEquals(13, episodesS2.last().episodeNumber)
        assertEquals(2, episodesS2.first().seasonNumber)
    }

    @Test
    fun streamAndCaptions_passDetailPathMergeDashCookiesAndParseSubtitles() = runTest {
        val streamRequestPaths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/detail/8906247916759695608" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "8906247916759695608",
                          "title": "Avatar",
                          "subject_type": 1,
                          "type": "Movie",
                          "detail_path": "avatar-detail-slug"
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/api/stream/8906247916759695608/captions") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "8906247916759695608",
                          "season": 0,
                          "episode": 0,
                          "count": 2,
                          "captions": [
                            {"id": "10", "language": "English", "code": "en", "url": "https://cdn.example/en.srt"},
                            {"id": "11", "language": "Spanish", "code": "es", "url": "https://cdn.example/es.vtt"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/api/stream/8906247916759695608") -> {
                        streamRequestPaths.add(path)
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "subject_id": "8906247916759695608",
                              "season": 0,
                              "episode": 0,
                              "count": 2,
                              "streams": [
                                {
                                  "source": "Web DASH",
                                  "format": "DASH",
                                  "quality": "1080p (Adaptive)",
                                  "quality_value": 1080,
                                  "resolution_tag": "1080p",
                                  "url": "https://cdn.example/index.mpd",
                                  "size_mb": 0,
                                  "codec": "hevc/h264",
                                  "cookie": "Edge-Cache-Cookie=abc123",
                                  "headers": {"User-Agent": "Mozilla/5.0", "Referer": "https://cdn.example/"}
                                },
                                {
                                  "source": "Mobile Native CDN",
                                  "format": "MP4",
                                  "quality": "1080p",
                                  "quality_value": 1080,
                                  "resolution_tag": "1080p",
                                  "url": "https://cdn.example/movie-1080p.mp4",
                                  "size_mb": 1450.5,
                                  "codec": "h264",
                                  "duration_sec": 9720,
                                  "headers": {"User-Agent": "Dalvik/2.1"}
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/api/stream/empty_id") -> MockResponse().setResponseCode(200).setBody(
                        """{"subject_id": "empty_id", "season": 1, "episode": 2, "count": 0, "streams": []}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // First load detail so detail_path & title get cached
        provider.getDetails("8906247916759695608").getOrThrow()

        val resolution = provider.resolvePlaybackSources("8906247916759695608", null, null).getOrThrow()
        assertTrue(resolution is PlaybackResolution.Available)
        val available = resolution as PlaybackResolution.Available
        assertEquals("Avatar", available.title)
        assertNull(available.subtitleHeading)
        assertEquals(2, available.sources.size)
        // Progressive MP4 sorted ahead of DASH when both are 1080p
        assertEquals("MP4", available.sources[0].format)
        assertEquals("DASH", available.sources[1].format)
        assertEquals("Edge-Cache-Cookie=abc123", available.sources[1].headers["Cookie"])
        assertEquals(2, available.subtitles.size)
        assertEquals("srt", available.subtitles[0].format)
        assertEquals("vtt", available.subtitles[1].format)
        assertTrue(streamRequestPaths.single().contains("se=0&ep=0&detail_path=avatar-detail-slug"))

        val unavailable = provider.resolvePlaybackSources("empty_id", 1, 2).getOrThrow()
        assertTrue(unavailable is PlaybackResolution.Unavailable)
    }

    @Test
    fun httpErrorAndBlankBaseUrl_surfaceActionableErrors() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(502)
                    .setBody("""{"detail":"Failed to bootstrap MovieBox token"}""")
            }
        }

        val err = provider.getHomeFeed().exceptionOrNull()
        assertEquals("Failed to bootstrap MovieBox token", err?.message)

        val unconfigured = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { "   " }
        )
        val configErr = unconfigured.getHomeFeed().exceptionOrNull()
        assertTrue(configErr?.message.orEmpty().contains("not configured"))
    }

    @Test
    fun homeFeed_parsesShortTvAppointmentListAndPreservesCachedTrailerIntoDetails() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "operatingList": [
                              {
                                "type": "APPOINTMENT_LIST",
                                "opId": "coming_soon",
                                "title": "Coming Soon",
                                "subjects": [
                                  {
                                    "subjectId": "2845168888013275776",
                                    "subjectType": 2,
                                    "title": "Percy Jackson",
                                    "releaseDate": "2023-12-19",
                                    "genre": "Action,Adventure",
                                    "cover": {"url": "https://cdn.example/percy.jpg"},
                                    "detailPath": "percy-jackson",
                                    "trailer": {
                                      "videoAddress": {
                                        "url": "https://cdn.example/percy-trailer.mp4"
                                      }
                                    }
                                  }
                                ]
                              },
                              {
                                "type": "SUBJECTS_MOVIE",
                                "opId": "hot_short_tv",
                                "title": "Hot Short TV",
                                "subjects": [
                                  {
                                    "subjectId": "8616684100902086880",
                                    "subjectType": 7,
                                    "title": "The Holy Covenant Prophet",
                                    "releaseDate": "2026-10-19",
                                    "genre": "Modern Drama",
                                    "cover": {"url": "https://cdn.example/short.jpg"},
                                    "detailPath": "short-tv-prophet"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    "/detail/2845168888013275776" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "2845168888013275776",
                          "title": "Percy Jackson",
                          "subject_type": 2,
                          "type": "Series",
                          "description": "Demigod adventure.",
                          "release_date": "2023-12-19",
                          "cover": "https://cdn.example/percy.jpg",
                          "detail_path": "percy-jackson"
                        }
                        """.trimIndent()
                    )
                    "/episodes/2845168888013275776" -> MockResponse().setResponseCode(200).setBody(
                        """{"subject_id": "2845168888013275776", "seasons": [{"season": 1, "episode_count": 8}]}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val feed = provider.getHomeFeed().getOrThrow()
        assertEquals(2, feed.sections.size)
        assertEquals(SectionStyle.COLLECTION, feed.sections[0].style)
        assertEquals(MediaKind.TV_SERIES, feed.sections[1].items.first().kind)

        // /detail omits trailer, so MovieBoxProvider restores the cached trailerUrl from /home
        val details = provider.getDetails("2845168888013275776").getOrThrow()
        assertEquals("https://cdn.example/percy-trailer.mp4", details.summary.trailerUrl)
    }

    @Test
    fun authFailures401And403_doNotRetryAndSurfaceAuthException() = runTest {
        var requestCount = 0
        val fastProvider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
            apiKeyProvider = { "wrong-key" },
            retryDelayMs = 0L
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCount++
                return MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"detail":"Invalid API key"}""")
            }
        }

        val err = fastProvider.getHomeFeed().exceptionOrNull()
        assertEquals(1, requestCount)
        assertTrue(err is com.laterbox.app.movies.data.provider.MovieBoxApiException)
        assertTrue((err as com.laterbox.app.movies.data.provider.MovieBoxApiException).isAuthFailure)
        assertTrue(err.message.orEmpty().contains("authentication", ignoreCase = true))
    }

    @Test
    fun transient502RetriesOnceAndEmptyBodySurfacesActionableError() = runTest {
        var attempt = 0
        val fastProvider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
            apiKeyProvider = { "" },
            retryDelayMs = 0L
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/home" -> {
                        attempt++
                        if (attempt == 1) {
                            MockResponse().setResponseCode(502).setBody("""{"detail":"Bad gateway"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"data":{"operatingList":[]}}""")
                        }
                    }
                    request.path?.startsWith("/movies") == true -> MockResponse().setResponseCode(200).setBody("")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val feed = fastProvider.getHomeFeed().getOrThrow()
        assertEquals(2, attempt)
        assertTrue(feed.featuredBanners.isEmpty())

        val emptyErr = fastProvider.getCatalogue(kind = MediaKind.MOVIE).exceptionOrNull()
        assertTrue(emptyErr?.message.orEmpty().contains("empty response", ignoreCase = true))
    }

    @Test
    fun endpointConfig_defaultsToVercelHttpsAndValidatesReleaseUrls() {
        assertEquals(
            "https://laterbox-moviebox-api.vercel.app/",
            com.laterbox.app.data.preferences.MovieBoxEndpointConfig.DEFAULT_PRODUCTION_BASE_URL
        )
        assertTrue(
            com.laterbox.app.data.preferences.MovieBoxEndpointConfig.isValidBaseUrl(
                "https://laterbox-moviebox-api.vercel.app",
                allowDebugCleartextLoopback = false
            )
        )
        assertFalse(
            com.laterbox.app.data.preferences.MovieBoxEndpointConfig.isValidBaseUrl(
                "http://10.0.2.2:8000/",
                allowDebugCleartextLoopback = false
            )
        )
        assertTrue(
            com.laterbox.app.data.preferences.MovieBoxEndpointConfig.isValidBaseUrl(
                "http://10.0.2.2:8000/",
                allowDebugCleartextLoopback = true
            )
        )
    }
}
