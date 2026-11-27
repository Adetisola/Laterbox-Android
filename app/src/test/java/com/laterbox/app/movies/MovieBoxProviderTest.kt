package com.laterbox.app.movies

import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SectionStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString()
        provider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { baseUrl }
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
        assertEquals("https://cdn.example/banner-avatar.jpg", banner.backdropUrl)
        assertEquals("https://cdn.example/poster-avatar.jpg", banner.posterUrl)
        assertEquals(DataSourceBadge.LIVE_MOVIEBOX, banner.sourceBadge)

        assertEquals(1, feed.sections.size)
        assertEquals("Trending Now", feed.sections.first().title)
        assertEquals(SectionStyle.RAIL, feed.sections.first().style)
        assertEquals(MediaKind.TV_SERIES, feed.sections.first().items.first().kind)
        assertEquals("https://cdn.example/bb.jpg", feed.sections.first().items.first().posterUrl)
        assertEquals(null, feed.sections.first().items.first().backdropUrl)
        assertEquals(listOf("All", "Action", "Sci-Fi", "Drama"), feed.genres)
        assertFalse(feed.containsSampleFixtures)
    }

    @Test
    fun catalogueMoviesAndSeries_mapSortPaginationWithoutLegacyApiKeyHeader() = runTest {
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
        assertTrue(paths.first().contains("/movies?page=1&per_page=2&sort=Rating&genre=Action"))
        assertNull(apiKeys.first())

        val seriesPage = provider.getCatalogue(
            kind = MediaKind.TV_SERIES,
            page = 2,
            pageSize = 2,
            sort = CatalogueSort.NEWEST
        ).getOrThrow()
        assertEquals(2, seriesPage.items.size)
        assertFalse(seriesPage.hasMore)
        assertEquals(MediaKind.TV_SERIES, seriesPage.items.first().kind)
        assertTrue(paths[1].contains("/tv-series?page=2&per_page=2&sort=Latest"))
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
        assertTrue(err is com.laterbox.app.movies.data.provider.MovieBoxApiException)
        val apiErr = err as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(502, apiErr.httpCode)
        assertFalse(apiErr.isAuthFailure)
        val msg = err.message.orEmpty()
        assertTrue(msg.contains("temporarily unavailable", ignoreCase = true))
        assertFalse(msg.contains("MovieBox", ignoreCase = true))
        assertFalse(msg.contains("bootstrap", ignoreCase = true))
        assertFalse(msg.contains("token", ignoreCase = true))

        val unconfigured = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { "   " }
        )
        val configErr = unconfigured.getHomeFeed().exceptionOrNull()
        assertTrue(configErr?.message.orEmpty().contains("not configured"))
    }

    @Test
    fun httpErrorCodes_distinguishUpstreamFailuresFromAppCheckAndSanitizeCopy() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/home") == true -> MockResponse()
                        .setResponseCode(503)
                        .setBody("""{"detail":"Upstream x-user bootstrap error"}""")
                    request.path?.startsWith("/detail/") == true -> MockResponse()
                        .setResponseCode(403)
                        .setBody("""{"detail":"Invalid Firebase App Check token"}""")
                    else -> MockResponse().setResponseCode(429)
                }
            }
        }

        val upstreamErr = provider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(503, upstreamErr.httpCode)
        assertFalse(upstreamErr.isAuthFailure)
        val upstreamMsg = upstreamErr.message.orEmpty()
        assertTrue(upstreamMsg.contains("temporarily unavailable", ignoreCase = true))
        assertFalse(upstreamMsg.contains("x-user", ignoreCase = true))
        assertFalse(upstreamMsg.contains("bootstrap", ignoreCase = true))
        assertFalse(upstreamMsg.contains("upstream", ignoreCase = true))

        val authProvider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
            retryDelayMs = 0L,
            appCheckTokenProvider = { "valid.appcheck.jwt" }
        )
        val authErr = authProvider.getDetails("123").exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(403, authErr.httpCode)
        assertTrue(authErr.isAuthFailure)
        val authMsg = authErr.message.orEmpty()
        assertTrue(authMsg.contains("verified", ignoreCase = true))
        assertTrue(authMsg.contains("update the app", ignoreCase = true))
        assertFalse(authMsg.contains("App Check", ignoreCase = true))
        assertFalse(authMsg.contains("token", ignoreCase = true))

        val rateErr = provider.search("dune", page = 1).exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(429, rateErr.httpCode)
        assertFalse(rateErr.isAuthFailure)
        assertTrue(rateErr.message.orEmpty().contains("Too many requests", ignoreCase = true))
    }

    @Test
    fun homeFeed_retainsShortTvExcludesPromoBannersAndPreservesCachedTrailerIntoDetails() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "code": 0,
                          "message": "ok",
                          "data": {
                            "genres": ["Action", "Fantasy", "Drama"],
                            "operatingList": [
                              {
                                "type": "BANNER",
                                "title": "Top Banner",
                                "banner": {
                                  "items": [
                                    {
                                      "title": "External Promo",
                                      "url": "https://promo.example/offer",
                                      "subjectId": "0",
                                      "image": {"url": "https://cdn.example/promo.jpg"}
                                    }
                                  ]
                                }
                              },
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
        assertTrue(feed.featuredBanners.none { it.title == "External Promo" })
        assertEquals(2, feed.sections.size)
        assertEquals(SectionStyle.COLLECTION, feed.sections[0].style)
        assertEquals("Percy Jackson", feed.sections[0].items.first().title)
        assertEquals("Hot Short TV", feed.sections[1].title)
        assertEquals("The Holy Covenant Prophet", feed.sections[1].items.first().title)
        assertNull(feed.activeProviderNotice)
        assertEquals(listOf("All", "Action", "Sci-Fi & Fantasy", "Drama"), feed.genres)

        // /detail omits trailer, so MovieBoxProvider restores the cached trailerUrl from /home
        val details = provider.getDetails("2845168888013275776").getOrThrow()
        assertEquals("https://cdn.example/percy-trailer.mp4", details.summary.trailerUrl)
    }

    @Test
    fun catalogue_passesVerifiedCountryYearAndAppliesWesternSortQuirk() = runTest {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add(request.path.orEmpty())
                return MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "page": 1,
                      "per_page": 20,
                      "total": 1,
                      "items": [
                        {"title": "Stagecoach", "subject_id": "555", "detail_path": "stagecoach", "subject_type": 1, "genre": "Western", "country": "United States", "year": "1939"}
                      ]
                    }
                    """.trimIndent()
                )
            }
        }

        val page = provider.getCatalogue(
            kind = MediaKind.MOVIE,
            page = 1,
            pageSize = 20,
            genre = "Western",
            sort = CatalogueSort.POPULAR,
            country = "United States",
            year = "2024"
        ).getOrThrow()
        assertEquals(1, page.items.size)
        assertEquals("United States", page.items.first().country)
        assertTrue(paths[0].contains("sort=ForYou"))
        assertTrue(paths[0].contains("genre=Western"))
        assertTrue(paths[0].contains("country=United%20States"))
        assertTrue(paths[0].contains("year=2024"))

        provider.getCatalogue(
            kind = MediaKind.TV_SERIES,
            page = 1,
            pageSize = 20,
            genre = "Fantasy",
            sort = CatalogueSort.POPULAR,
            country = "South Korea"
        ).getOrThrow()
        assertTrue(paths[1].contains("sort=Hottest"))
        assertTrue(paths[1].contains("genre=Sci-Fi%20%26%20Fantasy"))
        assertTrue(paths[1].contains("country=Korea"))
    }

    @Test
    fun authFailures401And403_doNotRetryAndSurfaceAuthException() = runTest {
        var requestCount = 0
        val fastProvider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
            retryDelayMs = 0L
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCount++
                return MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"detail":"Unauthorized"}""")
            }
        }

        val err = fastProvider.getHomeFeed().exceptionOrNull()
        assertEquals(1, requestCount)
        assertTrue(err is com.laterbox.app.movies.data.provider.MovieBoxApiException)
        assertTrue((err as com.laterbox.app.movies.data.provider.MovieBoxApiException).isAuthFailure)
        val msg = err.message.orEmpty()
        assertTrue(msg.contains("verified", ignoreCase = true))
        assertFalse(msg.contains("App Check", ignoreCase = true))
        assertFalse(msg.contains("Firebase", ignoreCase = true))
    }

    @Test
    fun transient502RetriesOnceAndEmptyBodySurfacesActionableError() = runTest {
        var attempt = 0
        val fastProvider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
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

    @Test
    fun appCheckToken_attachesHeaderAndNeverSendsLegacyApiKey() = runTest {
        val capturedHeaders = mutableListOf<Pair<String?, String?>>()
        var shouldThrow = false
        val appCheckOnlyProvider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
            retryDelayMs = 0L,
            appCheckTokenProvider = { forceRefresh ->
                assertFalse(forceRefresh)
                if (shouldThrow) error("App Check offline") else "appcheck.jwt.1"
            }
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                capturedHeaders.add(
                    request.getHeader("X-Firebase-AppCheck") to request.getHeader("X-API-Key")
                )
                return MockResponse().setResponseCode(200).setBody("""{"data":{"operatingList":[]}}""")
            }
        }

        appCheckOnlyProvider.getHomeFeed().getOrThrow()
        shouldThrow = true
        appCheckOnlyProvider.getHomeFeed().getOrThrow()

        assertEquals(2, capturedHeaders.size)
        assertEquals("appcheck.jwt.1" to null, capturedHeaders[0])
        assertEquals(null to null, capturedHeaders[1])
    }

    @Test
    fun appCheckToken_refreshesOnceOn401AndSurfacesFailureIfStillRejected() = runTest {
        val forceFlags = mutableListOf<Boolean>()
        val sentTokens = mutableListOf<String?>()
        var failSecondAttemptToo = false
        val appCheckProvider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
            retryDelayMs = 0L,
            appCheckTokenProvider = { forceRefresh ->
                forceFlags.add(forceRefresh)
                if (forceRefresh) "fresh.appcheck.jwt" else "stale.appcheck.jwt"
            }
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val token = request.getHeader("X-Firebase-AppCheck")
                sentTokens.add(token)
                return if (!failSecondAttemptToo && token == "fresh.appcheck.jwt") {
                    MockResponse().setResponseCode(200).setBody("""{"data":{"operatingList":[]}}""")
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"detail":"Expired App Check token"}""")
                }
            }
        }

        appCheckProvider.getHomeFeed().getOrThrow()
        assertEquals(listOf(false, true), forceFlags)
        assertEquals(listOf("stale.appcheck.jwt", "fresh.appcheck.jwt"), sentTokens)

        forceFlags.clear()
        sentTokens.clear()
        failSecondAttemptToo = true
        val err = appCheckProvider.getHomeFeed().exceptionOrNull()
        assertEquals(listOf(false, true), forceFlags)
        assertEquals(listOf("stale.appcheck.jwt", "fresh.appcheck.jwt"), sentTokens)
        assertTrue(err is com.laterbox.app.movies.data.provider.MovieBoxApiException)
        assertTrue((err as com.laterbox.app.movies.data.provider.MovieBoxApiException).isAuthFailure)
        val msg = err.message.orEmpty()
        assertTrue(msg.contains("verified", ignoreCase = true))
        assertFalse(msg.contains("App Check", ignoreCase = true))
    }

    @Test
    fun detailsAndStaff_parseStarsStillsRelatedAllEpAndPersonFilmography() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/detail/777" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "777",
                          "title": "Dune: Part Two",
                          "subject_type": 1,
                          "type": "Movie",
                          "description": "Paul Atreides unites with the Fremen.",
                          "release_date": "2024-03-01",
                          "duration": 9960,
                          "imdb_rating": "8.6",
                          "imdb_id": "tt15239678",
                          "cover": "https://cdn.example/dune-poster.jpg",
                          "stills": {"url": "https://cdn.example/dune-still.jpg", "width": 1280, "height": 720},
                          "stars": [
                            {"staff_id": "101", "staff_type": 2, "name": "Denis Villeneuve", "character": null, "avatar_url": "https://cdn.example/denis.jpg"},
                            {"staff_id": "202", "staff_type": 1, "name": "Timothee Chalamet", "character": "Paul Atreides", "avatar_url": "https://cdn.example/tim.jpg"}
                          ],
                          "seasons": [],
                          "related": [
                            {
                              "subject_id": "888",
                              "title": "Dune",
                              "subject_type": 1,
                              "type": "Movie",
                              "cover": "https://cdn.example/dune1-poster.jpg",
                              "stills": {"url": "https://cdn.example/dune1-still.jpg"},
                              "imdb_rating": "8.0",
                              "release_date": "2021-10-22",
                              "genre": "Sci-Fi",
                              "country": "United States",
                              "detail_path": "dune-2021"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    path == "/episodes/909" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "909",
                          "seasons": [
                            {"season": 1, "episode_count": 3, "all_ep": [1, 3, 5]}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/staff/202/titles") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "staff_id": "202",
                          "page": 1,
                          "per_page": 24,
                          "total": 2,
                          "items": [
                            {"subject_id": "777", "title": "Dune: Part Two", "subject_type": 1, "type": "Movie", "cover": "https://cdn.example/dune-poster.jpg"},
                            {"subject_id": "909", "title": "Wonka", "subject_type": 1, "type": "Movie", "cover": "https://cdn.example/wonka-poster.jpg"}
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val details = provider.getDetails("777").getOrThrow()
        assertEquals("https://cdn.example/dune-poster.jpg", details.summary.posterUrl)
        assertEquals("https://cdn.example/dune-still.jpg", details.summary.backdropUrl)
        assertEquals("tt15239678", details.summary.imdbId)
        assertEquals("Denis Villeneuve", details.directorOrCreator)
        assertEquals("101", details.directorPersonId)
        assertEquals("2024-03-01", details.summary.releaseDate)
        assertEquals(2, details.cast.size)
        assertEquals("202", details.cast[1].personId)
        assertEquals("moviebox", details.cast[1].providerId)
        assertEquals("Paul Atreides", details.cast[1].role)
        assertEquals(1, details.relatedTitles.size)
        assertEquals("888", details.relatedTitles.first().id)
        assertEquals("https://cdn.example/dune1-still.jpg", details.relatedTitles.first().backdropUrl)

        val episodes = provider.getEpisodes("909", 1).getOrThrow()
        assertEquals(listOf(1, 3, 5), episodes.map { it.episodeNumber })

        val filmography = provider.getPersonFilmography("202", "Timothee Chalamet").getOrThrow()
        assertEquals("202", filmography.personId)
        assertEquals(2, filmography.credits.size)
        assertEquals("Wonka", filmography.credits[1].title)
    }

    @Test
    fun streamMarkers_parseIntroAndRecapWhenPresentAndStayNullWhenOmitted() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/stream/with_markers/captions") ||
                        path.startsWith("/api/stream/no_markers/captions") ->
                        MockResponse().setResponseCode(200).setBody("""{"captions": []}""")
                    path.startsWith("/api/stream/with_markers") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "with_markers",
                          "title": "Test Show",
                          "intro_start_ms": 15000,
                          "intro_end_ms": 95000,
                          "recap_start_ms": 2000,
                          "recap_end_ms": 12000,
                          "streams": [
                            {"source": "CDN", "format": "MP4", "quality": "1080p", "quality_value": 1080, "url": "https://cdn.example/show.mp4"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/api/stream/no_markers") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "no_markers",
                          "title": "Plain Film",
                          "intro_start_ms": null,
                          "intro_end_ms": null,
                          "streams": [
                            {"source": "CDN", "format": "MP4", "quality": "720p", "quality_value": 720, "url": "https://cdn.example/film.mp4"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/movies") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "page": 1,
                          "per_page": 20,
                          "total": 1,
                          "items": [
                            {
                              "subject_id": "1001",
                              "subject_type": 1,
                              "title": "Long Epic",
                              "release_date": "2024-05-10",
                              "duration": 9300,
                              "language": "English",
                              "country": "United States",
                              "genre": "Drama"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val withMarkers = provider.resolvePlaybackSources("with_markers", 1, 1).getOrThrow()
            as PlaybackResolution.Available
        assertEquals(15_000L, withMarkers.introWindow?.startMs)
        assertEquals(95_000L, withMarkers.introWindow?.endMs)
        assertTrue(withMarkers.introWindow?.contains(45_000L) == true)
        assertEquals(2_000L, withMarkers.recapWindow?.startMs)
        assertEquals(12_000L, withMarkers.recapWindow?.endMs)

        val withoutMarkers = provider.resolvePlaybackSources("no_markers", null, null).getOrThrow()
            as PlaybackResolution.Available
        assertNull(withoutMarkers.introWindow)
        assertNull(withoutMarkers.recapWindow)

        val catalogue = provider.getCatalogue(MediaKind.MOVIE).getOrThrow()
        assertEquals(155, catalogue.items.first().durationMinutes)
        assertEquals("English", catalogue.items.first().language)
        assertEquals("2024-05-10", catalogue.items.first().releaseDate)
    }

    @Test
    fun searchSuggestions_handlesNullSubjectIdKeywordsAndDeduplicatesSafely() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "suggestions": [
                        {"subject_id": null, "title": "100% Wolf", "slug": ""},
                        {"subject_id": null, "title": "100% wolf"},
                        {"subject_id": "555", "title": "Dune", "slug": "dune"},
                        {"subject_id": "555", "title": "Dune Duplicate", "slug": "dune"}
                      ]
                    }
                    """.trimIndent()
                )
            }
        }

        val suggestions = provider.getSuggestions("100% + wolf").getOrThrow()
        assertEquals(2, suggestions.size)
        assertEquals("100% Wolf", suggestions[0].title)
        assertEquals("", suggestions[0].id)
        assertFalse(suggestions[0].hasNavigableId)
        assertEquals("555", suggestions[1].id)
        assertTrue(suggestions[1].hasNavigableId)
    }

    @Test
    fun catalogue_retainsShortTvSubjectType7AndVerifiedAnimeKidsGenres() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "page": 1,
                      "per_page": 20,
                      "total": 3,
                      "items": [
                        {
                          "subject_id": "st7",
                          "subject_type": 7,
                          "title": "Short Drama",
                          "genre": "Anime"
                        },
                        {
                          "subject_id": "st7",
                          "subject_type": 7,
                          "title": "Short Drama Duplicate",
                          "genre": "Anime"
                        },
                        {
                          "subject_id": "mv_kids",
                          "subject_type": 1,
                          "title": "Kids Adventure",
                          "genre": "Kids"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            }
        }

        val page = provider.getCatalogue(kind = MediaKind.TV_SERIES, genre = "Anime").getOrThrow()
        assertEquals(2, page.items.size)
        assertEquals("st7", page.items[0].id)
        assertEquals(MediaKind.SHORT_DRAMA, page.items[0].kind)
        assertEquals(7, page.items[0].subjectType)
        assertEquals(MediaKind.MOVIE, page.items[1].kind)
        assertEquals(1, page.items[1].subjectType)
        assertTrue(MovieBoxProvider.VERIFIED_GENRES.containsAll(listOf("Anime", "Kids", "Sci-Fi & Fantasy", "Action & Adventure")))
    }

    @Test
    fun shortDrama_subjectType7ClassifiedDistinctlyAcrossHomeSearchCatalogueAndDetails() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "data": {
                            "operatingList": [
                              {
                                "type": "SUBJECTS_MOVIE",
                                "opId": "hot_short_tv",
                                "title": "Trending Short Drama",
                                "subjects": [
                                  {"subjectId": "sd1", "subjectType": 7, "title": "CEO Secret Marriage", "genre": "Romance", "imdbRatingValue": "8.4", "releaseDate": "2026-01-10"},
                                  {"subjectId": "sd2", "subjectType": 7, "title": "Billionaire Return", "genre": "Drama", "imdbRatingValue": "7.8", "releaseDate": "2025-11-01"}
                                ]
                              },
                              {
                                "type": "SUBJECTS_MOVIE",
                                "opId": "pop_series",
                                "title": "Popular Series",
                                "subjects": [
                                  {"subjectId": "tv1", "subjectType": 2, "title": "Classic Drama", "genre": "Drama"}
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/search") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "query": "ceo",
                          "page": 1,
                          "results": [
                            {"subject_id": "m1", "subject_type": 1, "title": "CEO Movie"},
                            {"subject_id": "tv1", "subject_type": 2, "title": "CEO Series"},
                            {"subject_id": "sd1", "subject_type": 7, "title": "CEO Short Drama"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path == "/detail/sd1" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "sd1",
                          "subject_type": 7,
                          "title": "CEO Secret Marriage",
                          "seasons": [{"season": 1, "episode_count": 40}]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val home = provider.getHomeFeed().getOrThrow()
        assertEquals(2, home.sections.size)
        assertEquals("Trending Short Drama", home.sections[0].title)
        assertEquals(MediaKind.SHORT_DRAMA, home.sections[0].items[0].kind)
        assertEquals(7, home.sections[0].items[0].subjectType)
        assertEquals(MediaKind.TV_SERIES, home.sections[1].items[0].kind)

        val allSearch = provider.search("ceo", kind = null).getOrThrow()
        assertEquals(listOf(MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.SHORT_DRAMA), allSearch.items.map { it.kind })

        val shortSearch = provider.search("ceo", kind = MediaKind.SHORT_DRAMA).getOrThrow()
        assertEquals(listOf("sd1"), shortSearch.items.map { it.id })
        assertEquals(MediaKind.SHORT_DRAMA, shortSearch.items[0].kind)

        val shortCatalogue = provider.getCatalogue(kind = MediaKind.SHORT_DRAMA, sort = CatalogueSort.TOP_RATED).getOrThrow()
        assertEquals(listOf("sd1", "sd2"), shortCatalogue.items.map { it.id })

        val detail = provider.getDetails("sd1").getOrThrow()
        assertEquals(MediaKind.SHORT_DRAMA, detail.summary.kind)
        assertEquals(1, detail.seasons.size)
        assertEquals(40, detail.seasons[0].episodeCount)
    }

    @Test
    fun suggestions_keywordItemsDoNotDefaultToMovieAndSupportOptionalNavigableFields() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.startsWith("/search/suggest")) {
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "suggestions": [
                            {"title": "ceo bride", "subject_id": null, "slug": null},
                            {"title": "CEO Return", "subject_id": "sd99", "slug": "ceo-return", "subject_type": 7, "year": "2026", "cover": "https://cdn.example/sd99.jpg"}
                          ]
                        }
                        """.trimIndent()
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }

        val suggestions = provider.getSuggestions("ceo", limit = 8).getOrThrow()
        assertEquals(2, suggestions.size)

        val keywordItem = suggestions[0]
        assertFalse(keywordItem.hasNavigableId)
        assertNull(keywordItem.kind)
        assertEquals("ceo bride", keywordItem.title)

        val navigableItem = suggestions[1]
        assertTrue(navigableItem.hasNavigableId)
        assertEquals(MediaKind.SHORT_DRAMA, navigableItem.kind)
        assertEquals(2026, navigableItem.releaseYear)
        assertEquals("https://cdn.example/sd99.jpg", navigableItem.posterUrl)
    }

    @Test
    fun search_kindFilterScansMultipleUpstreamPagesToAvoidShortPages() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("page=1") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "results": [
                            {"subject_id": "sd1", "subject_type": 7, "title": "Short 1"},
                            {"subject_id": "sd2", "subject_type": 7, "title": "Short 2"},
                            {"subject_id": "tv1", "subject_type": 2, "title": "Series 1"}
                          ]
                        }
                        """.trimIndent()
                    )
                    path.contains("page=2") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "results": [
                            {"subject_id": "tv2", "subject_type": 2, "title": "Series 2"},
                            {"subject_id": "tv3", "subject_type": 2, "title": "Series 3"},
                            {"subject_id": "m1", "subject_type": 1, "title": "Movie 1"}
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(200).setBody("""{"results": []}""")
                }
            }
        }

        val page = provider.search("alpha", kind = MediaKind.TV_SERIES, page = 1, pageSize = 3).getOrThrow()
        assertEquals(listOf("tv1", "tv2", "tv3"), page.items.map { it.id })
        assertTrue(page.items.all { it.kind == MediaKind.TV_SERIES })
        assertTrue(page.hasMore)
    }

    @Test
    fun discovery_parsesBlurHashAndAppointmentComingSoonSection() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "data": {
                            "operatingList": [
                              {
                                "type": "APPOINTMENT_LIST",
                                "opId": "coming_soon",
                                "title": "Coming Soon",
                                "subjects": [
                                  {
                                    "subjectId": "cs1",
                                    "subjectType": 1,
                                    "title": "A Talent for Murder",
                                    "cover": {
                                      "url": "https://cdn.example/cs1.jpg",
                                      "blurHash": "ePIFua-;Rht7M|M{M{oIaeWC.TMxe-t7aykDRkV?t6WBMxM{M{RiV["
                                    }
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path == "/detail/cs1" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "cs1",
                          "subject_type": 1,
                          "title": "A Talent for Murder",
                          "cover": "https://cdn.example/cs1.jpg",
                          "cover_blur_hash": "ePIFua-;Rht7M|M{M{oIaeWC.TMxe-t7aykDRkV?t6WBMxM{M{RiV["
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/movies") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "page": 1,
                          "per_page": 20,
                          "total": 1,
                          "items": [
                            {
                              "subject_id": "an1",
                              "subject_type": 1,
                              "title": "Igor",
                              "genre": "Animation, Comedy",
                              "cover": "https://cdn.example/igor.jpg",
                              "cover_blur_hash": "eRBp-UkFM{t7MdRzfiNGWAa#DgjXnhR-x]M}adxaogoyt6V[o~t6RQ"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val home = provider.getHomeFeed().getOrThrow()
        assertEquals(1, home.sections.size)
        assertEquals("Coming Soon", home.sections[0].title)
        assertEquals(SectionStyle.COLLECTION, home.sections[0].style)
        assertEquals(
            "ePIFua-;Rht7M|M{M{oIaeWC.TMxe-t7aykDRkV?t6WBMxM{M{RiV[",
            home.sections[0].items[0].posterBlurHash
        )

        val detail = provider.getDetails("cs1").getOrThrow()
        assertEquals(
            "ePIFua-;Rht7M|M{M{oIaeWC.TMxe-t7aykDRkV?t6WBMxM{M{RiV[",
            detail.summary.posterBlurHash
        )

        val anim = provider.getCatalogue(kind = MediaKind.MOVIE, genre = "Animation").getOrThrow()
        assertEquals(1, anim.items.size)
        assertEquals(
            "eRBp-UkFM{t7MdRzfiNGWAa#DgjXnhR-x]M}adxaogoyt6V[o~t6RQ",
            anim.items[0].posterBlurHash
        )
    }

    @Test
    fun `failureCategory classifies local auth missing, backend auth rejected, bootstrap and upstream 502, 429, timeout, and parse errors`() = kotlinx.coroutines.test.runTest {
        val base = server.url("/").toString().trimEnd('/')
        val fastClient = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(1))
            .readTimeout(java.time.Duration.ofMillis(350))
            .callTimeout(java.time.Duration.ofSeconds(2))
            .build()

        var nextResponse: () -> MockResponse = { MockResponse().setResponseCode(404) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = nextResponse()
        }

        // 1. Missing local auth header -> LOCAL_AUTH_UNAVAILABLE
        nextResponse = { MockResponse().setResponseCode(401).setBody("""{"detail":"Unauthorized"}""") }
        val noAuthProvider = MovieBoxProvider(
            okHttpClient = fastClient,
            baseUrlProvider = { base },
            retryDelayMs = 1L,
            authHeaderProvider = { null },
            authDiagnosticProvider = { "appcheck_timeout_or_null" }
        )
        val localAuthErr = noAuthProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.LOCAL_AUTH_UNAVAILABLE, localAuthErr.failureCategory)
        assertTrue(localAuthErr.isAuthFailure)

        // 2. Valid header still rejected 401/403 -> BACKEND_AUTH_REJECTED
        nextResponse = { MockResponse().setResponseCode(403).setBody("""{"detail":"Forbidden"}""") }
        val rejectedProvider = MovieBoxProvider(
            okHttpClient = fastClient,
            baseUrlProvider = { base },
            retryDelayMs = 1L,
            authHeaderProvider = { "X-Firebase-Auth" to "guest-token" },
            authDiagnosticProvider = { "guest_fallback" }
        )
        val rejectedErr = rejectedProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.BACKEND_AUTH_REJECTED, rejectedErr.failureCategory)
        assertTrue(rejectedErr.isAuthFailure)

        // 3. Upstream bootstrap 502 vs generic 503 upstream failure
        nextResponse = { MockResponse().setResponseCode(502).setBody("""{"detail":"Upstream MovieBox bootstrap failed"}""") }
        val bootstrapErr = rejectedProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.UPSTREAM_BOOTSTRAP_FAILED, bootstrapErr.failureCategory)

        nextResponse = { MockResponse().setResponseCode(503).setBody("""{"detail":"Upstream gateway busy"}""") }
        val upstreamErr = rejectedProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.UPSTREAM_REQUEST_FAILED, upstreamErr.failureCategory)

        // 4. Rate limited 429 & upgrade required 426
        nextResponse = { MockResponse().setResponseCode(429).setBody("""{"detail":"Too many requests"}""") }
        val rateErr = rejectedProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.RATE_LIMITED, rateErr.failureCategory)

        nextResponse = { MockResponse().setResponseCode(426).setBody("""{"detail":"Update required"}""") }
        val upgradeErr = rejectedProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.UPGRADE_REQUIRED, upgradeErr.failureCategory)

        // 5. Empty 200 body -> PARSE_ERROR
        nextResponse = { MockResponse().setResponseCode(200).setBody("") }
        val parseErr = rejectedProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.PARSE_ERROR, parseErr.failureCategory)

        // 6. Slow/unresponsive server -> TIMEOUT
        nextResponse = { MockResponse().setResponseCode(200).setBody("{}").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS) }
        val timeoutErr = rejectedProvider.getHomeFeed().exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(com.laterbox.app.movies.data.provider.MovieBoxFailureCategory.TIMEOUT, timeoutErr.failureCategory)
    }

    @Test
    fun `401 response triggers forced auth refresh and retries both appCheck and guest headers`() = kotlinx.coroutines.test.runTest {
        val base = server.url("/").toString().trimEnd('/')
        val validHomeJson = """{"data":{"operatingList":[]}}"""

        for ((headerName, firstToken, refreshedToken) in listOf(
            Triple("X-Firebase-AppCheck", "stale-appcheck", "fresh-appcheck"),
            Triple("X-Firebase-Auth", "stale-guest", "fresh-guest"),
        )) {
            var refreshCount = 0
            val seenTokens = mutableListOf<String?>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val tok = request.getHeader(headerName)
                    seenTokens += tok
                    return if (tok == firstToken) {
                        MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                    } else {
                        MockResponse().setResponseCode(200).setBody(validHomeJson)
                    }
                }
            }
            val provider = MovieBoxProvider(
                okHttpClient = OkHttpClient(),
                baseUrlProvider = { base },
                authHeaderProvider = { forceRefresh ->
                    if (forceRefresh) {
                        refreshCount++
                        headerName to refreshedToken
                    } else {
                        headerName to firstToken
                    }
                }
            )
            val result = provider.getHomeFeed()
            assertTrue("Expected retry success for $headerName, err=${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(1, refreshCount)
            assertEquals(listOf(firstToken, refreshedToken), seenTokens)
        }
    }

    @Test
    fun parseFormattedDuration_durationSec_snakeCaseBlurHash_yearFallback_and_searchPreserved() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/detail/8906") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "8906",
                          "title": "Avatar Test",
                          "subject_type": 1,
                          "duration": "2h 42m",
                          "duration_sec": 9720,
                          "year": "2009",
                          "cover": {"url": "https://cdn.example/avatar.jpg", "blur_hash": "U123Cover"},
                          "stills": {"url": "https://cdn.example/avatar_stills.jpg", "blur_hash": "U456Stills"},
                          "trailer": {
                            "url": "https://macdn.aoneroom.com/media/avatar-sd.mp4",
                            "headers": {"Referer": "https://moviebox.ph/"}
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/movies") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "items": [
                            {
                              "subject_id": "555",
                              "title": "Flat Movie",
                              "subject_type": 1,
                              "duration": "1h 35m",
                              "year": "2022",
                              "cover": {"url": "https://cdn.example/flat.jpg", "blur_hash": "UFlatBlur"}
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/home") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "data": {
                            "operatingList": [
                              {
                                "type": "SUBJECTS_MOVIE",
                                "title": "Trending",
                                "subjects": [
                                  {
                                    "subjectId": "777",
                                    "title": "Subject Year Fallback",
                                    "subjectType": 1,
                                    "year": "2024",
                                    "cover": {"url": "https://cdn.example/sub.jpg", "blur_hash": "USubBlur"},
                                    "duration": "45m"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/search/suggest") -> MockResponse().setResponseCode(200).setBody(
                        """{"suggestions":[{"title":"Avatar","slug":"avatar","subject_id":"8906"}]}"""
                    )
                    path.startsWith("/search") -> MockResponse().setResponseCode(200).setBody(
                        """{"results":[{"subject_id":"8906","title":"Avatar","type":"Movie"}]}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val provider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') }
        )

        val detail = provider.getDetails("8906").getOrThrow()
        assertEquals(162, detail.summary.durationMinutes)
        assertEquals(2009, detail.summary.releaseYear)
        assertEquals("U123Cover", detail.summary.posterBlurHash)
        assertEquals("U456Stills", detail.summary.backdropBlurHash)
        assertEquals("https://macdn.aoneroom.com/media/avatar-sd.mp4", detail.summary.trailerUrl)
        assertEquals("https://moviebox.ph/", detail.summary.trailerHeaders["Referer"])

        val catalogue = provider.getCatalogue(kind = MediaKind.MOVIE, page = 1).getOrThrow()
        assertEquals(1, catalogue.items.size)
        assertEquals(95, catalogue.items[0].durationMinutes)
        assertEquals(2022, catalogue.items[0].releaseYear)
        assertEquals("UFlatBlur", catalogue.items[0].posterBlurHash)

        val home = provider.getHomeFeed().getOrThrow()
        val homeItem = home.sections.first().items.first()
        assertEquals(2024, homeItem.releaseYear)
        assertEquals(45, homeItem.durationMinutes)
        assertEquals("USubBlur", homeItem.posterBlurHash)

        val suggestions = provider.getSuggestions("av").getOrThrow()
        assertEquals(listOf("Avatar"), suggestions.map { it.title })
        val searchResults = provider.search("avatar").getOrThrow()
        assertEquals(listOf("8906"), searchResults.items.map { it.id })
    }

    @Test
    fun resolvePlaybackSources_coalescesConcurrentCalls_cachesBriefly_invalidates_andParsesRetryAfter() = runTest {
        val streamCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var return429 = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/captions") ->
                        MockResponse().setResponseCode(200).setBody("""{"captions":[]}""")
                    path.startsWith("/api/stream/9001") -> {
                        val callNum = streamCalls.incrementAndGet()
                        if (return429) {
                            MockResponse()
                                .setResponseCode(429)
                                .addHeader("Retry-After", "21")
                                .setBody("""{"detail":"Rate limited"}""")
                        } else {
                            Thread.sleep(60L)
                            MockResponse().setResponseCode(200).setBody(
                                """{"streams":[{"url":"https://bcdnxw.hakunaymatata.com/v_${callNum}.mp4?sign=s1","quality":"720p","quality_value":720,"format":"MP4"}]}"""
                            )
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val provider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            retryDelayMs = 0L
        )

        val concurrent = kotlinx.coroutines.coroutineScope {
            (1..4).map {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    provider.resolvePlaybackSources("9001", 1, 2).getOrThrow()
                }
            }.awaitAll()
        }
        assertEquals(1, streamCalls.get())
        assertTrue(concurrent.all { it is PlaybackResolution.Available })

        // Immediate follow-up hits short 45s cache
        val cached = provider.resolvePlaybackSources("9001", 1, 2).getOrThrow()
        assertEquals(1, streamCalls.get())
        assertEquals(
            "https://bcdnxw.hakunaymatata.com/v_1.mp4?sign=s1",
            (cached as com.laterbox.app.movies.domain.model.PlaybackResolution.Available).sources.first().url
        )

        // After invalidation + 429 response, surfaces MovieBoxApiException with retryAfterSeconds
        provider.invalidatePlaybackCache("9001", 1, 2)
        return429 = true
        val failed = provider.resolvePlaybackSources("9001", 1, 2)
        assertTrue(failed.isFailure)
        val err = failed.exceptionOrNull() as com.laterbox.app.movies.data.provider.MovieBoxApiException
        assertEquals(429, err.httpCode)
        assertEquals(21L, err.retryAfterSeconds)
        assertEquals(2, streamCalls.get())
    }

    @Test
    fun `resolvePlaybackSources parses fallback_dub passes dub_subject_id to captions and parses opensubtitles source`() = runTest {
        var captionsPathSeen: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/stream/1512813957701091280/captions") -> {
                        captionsPathSeen = path
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "subject_id": "1512813957701091280",
                              "captions": [
                                {
                                  "id": "opensubtitles_222",
                                  "language": "English",
                                  "code": "en",
                                  "format": "srt",
                                  "source": "opensubtitles",
                                  "url": "https://www.opensubtitles.com/download/verified-sub.srt"
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/api/stream/1512813957701091280") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "1512813957701091280",
                          "count": 1,
                          "fallback_dub": "Hindi",
                          "fallback_dub_subject_id": "1560762024479573272",
                          "streams": [
                            {"source": "Web CDN", "format": "MP4", "quality": "1080p", "quality_value": 1080, "url": "https://bcdnxw.hakunaymatata.com/hindi.mp4"}
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val provider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            retryDelayMs = 0L
        )
        val res = provider.resolvePlaybackSources("1512813957701091280", null, null).getOrThrow()
        val available = res as PlaybackResolution.Available
        assertEquals("Hindi", available.fallbackDubLabel)
        assertEquals("1560762024479573272", available.fallbackDubTitleId)
        assertTrue(captionsPathSeen?.contains("dub_subject_id=1560762024479573272") == true)
        assertEquals(1, available.subtitles.size)
        assertEquals("srt", available.subtitles.first().format)
        assertEquals("opensubtitles", available.subtitles.first().source)
        assertEquals("en", available.subtitles.first().languageCode)
    }
}
