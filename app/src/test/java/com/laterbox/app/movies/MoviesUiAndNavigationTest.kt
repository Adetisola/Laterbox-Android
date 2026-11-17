package com.laterbox.app.movies

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.laterbox.app.data.preferences.MoviesProviderMode
import com.laterbox.app.movies.data.cache.MoviesMediaCache
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.ui.MovieDetailScreen
import com.laterbox.app.movies.ui.MovieDetailUiState
import com.laterbox.app.movies.ui.MoviePlayerScreen
import com.laterbox.app.movies.ui.MoviePlayerUiState
import com.laterbox.app.movies.ui.MoviesCatalogueScreen
import com.laterbox.app.movies.ui.MoviesCatalogueUiState
import com.laterbox.app.movies.ui.MoviesHomeUiState
import com.laterbox.app.movies.ui.MoviesScreen
import com.laterbox.app.ui.components.BottomBarDestination
import com.laterbox.app.ui.components.DrawerPrimarySection
import com.laterbox.app.ui.components.LaterboxBottomBar
import com.laterbox.app.ui.components.LaterboxDrawerContent
import com.laterbox.app.ui.components.sectionForRoute
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesUiAndNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @After
    fun tearDown() {
        MoviesMediaCache.releaseForTesting()
    }

    private val sampleMovie = MovieTitleSummary(
        id = "his_girl_friday",
        providerId = "ia",
        kind = MediaKind.MOVIE,
        title = "His Girl Friday",
        releaseYear = 1940,
        rating = 8.8,
        genres = listOf("Comedy"),
        sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
        isPlayableHint = true
    )

    @Test
    fun bottomBar_displaysAllFiveDestinationsIncludingMovies() {
        var selected: BottomBarDestination? = null
        composeTestRule.setContent {
            LaterboxTheme {
                LaterboxBottomBar(
                    currentDestination = BottomBarDestination.INBOX,
                    onDestinationSelected = { selected = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("bottom_bar_inbox").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_bar_collections").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_bar_movies").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("bottom_bar_downloads").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_bar_archive").assertIsDisplayed()

        assertEquals(BottomBarDestination.MOVIES, selected)
    }

    @Test
    fun drawerContent_showsMoviesSectionAndRoutesCorrectly() {
        var navigatedToMovies = false
        composeTestRule.setContent {
            LaterboxTheme {
                LaterboxDrawerContent(
                    currentSection = DrawerPrimarySection.MOVIES,
                    activeConversationId = null,
                    recentConversations = emptyList(),
                    unreadNotificationCount = 0,
                    userName = "Timi",
                    onNavigateInbox = {},
                    onNavigateCollections = {},
                    onNavigateMovies = { navigatedToMovies = true },
                    onNavigateDownloads = {},
                    onNavigateArchive = {},
                    onNavigateNotifications = {},
                    onNavigateSettings = {},
                    onNewChatClick = {},
                    onConversationClick = {},
                    onDeleteConversation = {},
                    modifier = Modifier.width(304.dp)
                )
            }
        }

        composeTestRule.onNodeWithText("Movies & TV").assertIsDisplayed().performClick()
        assertTrue(navigatedToMovies)
        assertEquals(DrawerPrimarySection.MOVIES, sectionForRoute("movies"))
        assertEquals(DrawerPrimarySection.MOVIES, sectionForRoute("movies/detail/ia/his_girl_friday"))
    }

    @Test
    fun moviesScreen_rendersLoadingErrorAndPopulatedHeroContinueWatchingRails() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(sampleMovie),
                            sections = listOf(
                                BrowseSection("s1", "Playable Classic Cinema", "Public domain", SectionStyle.RAIL, listOf(sampleMovie))
                            ),
                            genres = listOf("All", "Comedy"),
                            activeProviderNotice = "Development Sample Mode",
                            containsSampleFixtures = true
                        ),
                        continueWatching = listOf(
                            WatchHistoryItem(
                                compositeId = "ia:his_girl_friday",
                                providerId = "ia",
                                titleId = "his_girl_friday",
                                kind = MediaKind.MOVIE,
                                title = "His Girl Friday",
                                positionMs = 300_000L,
                                durationMs = 5_500_000L,
                                lastWatchedAt = 1000L
                            )
                        )
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {},
                    onSaveTmdbApiKey = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("movies_notice_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_hero_pager").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasText("Continue Watching"))
        composeTestRule.onNodeWithText("Continue Watching").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasText("Playable Classic Cinema"))
        composeTestRule.onNodeWithText("Playable Classic Cinema").assertIsDisplayed()
    }

    @Test
    fun moviesCatalogueScreen_rendersSuggestionsFiltersAndGrid() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = MoviesCatalogueUiState(
                        query = "His",
                        showSuggestionsDropdown = true,
                        suggestions = listOf(SearchSuggestion("his_girl_friday", "ia", "His Girl Friday", MediaKind.MOVIE, 1940)),
                        items = listOf(sampleMovie)
                    ),
                    onBackClick = {},
                    onQueryChange = {},
                    onSearchFocusChange = {},
                    onSubmitSearch = {},
                    onClearSearch = {},
                    onSelectKind = {},
                    onSelectGenre = {},
                    onSelectSort = {},
                    onLoadNextPage = {},
                    onRetry = {},
                    onClearSearchHistory = {},
                    onOpenDetail = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("catalogue_suggestions_box").assertIsDisplayed()
        composeTestRule.onNodeWithTag("suggestion_item_his_girl_friday").assertIsDisplayed()
        composeTestRule.onNodeWithTag("filter_kind_movie").assertIsDisplayed()
        composeTestRule.onNodeWithTag("catalogue_grid").assertIsDisplayed()
    }

    @Test
    fun movieDetailScreen_showsWatchButtonForPlayableMovie_andSeasonsUnavailableForTvMazeSeries() {
        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(
                            summary = sampleMovie.copy(kind = MediaKind.TV_SERIES, providerId = "tvmaze", title = "Breaking Bad", isPlayableHint = false, sourceBadge = DataSourceBadge.LIVE_TVMAZE),
                            fullDescription = "Chemistry teacher series.",
                            cast = listOf(CastMember("Bryan Cranston", "Walter White")),
                            seasons = listOf(SeasonSummary(1, "Season 1", 7), SeasonSummary(2, "Season 2", 13))
                        ),
                        selectedSeasonNumber = 1,
                        episodes = listOf(EpisodeItem("ep1", 1, 1, "Pilot", "Opening episode", 58, "2008-01-20")),
                        playbackResolution = PlaybackResolution.Unavailable(
                            title = "Breaking Bad",
                            reason = "Metadata guide only.",
                            providerName = "TVMaze Series Guide",
                            externalReferenceUrl = "https://www.tvmaze.com/shows/169"
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_playback_unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasText("Seasons & Episodes"))
        composeTestRule.onNodeWithTag("season_chip_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_1"))
        composeTestRule.onNodeWithTag("episode_row_1_1").assertIsDisplayed()
    }

    @Test
    fun movieDetailScreen_showsWatchNowCtaWhenPlaybackAvailable() {
        var playClicked = false
        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(summary = sampleMovie, fullDescription = "Screwball classic."),
                        playbackResolution = PlaybackResolution.Available(
                            title = "His Girl Friday",
                            sources = listOf(PlaybackSource("s1", "https://archive.org/hgf.mp4", "MP4", "720p", 720, providerName = "IA"))
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> playClicked = true },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_watch_button").assertIsDisplayed().performClick()
        assertTrue(playClicked)
    }

    @Test
    fun moviePlayerScreen_rendersUnavailableOverlayClearly() {
        var backClicked = false
        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Unavailable(
                            title = "TV Series",
                            reason = "Authorised stream unavailable for this title.",
                            providerName = "TVMaze",
                            externalReferenceUrl = "https://www.tvmaze.com/shows/1"
                        )
                    ),
                    onBackClick = { backClicked = true },
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("player_unavailable_state").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Official Guide").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertIsDisplayed().performClick()
        assertTrue(backClicked)
    }

    @Test
    fun moviesScreen_inlineSearchFiltersAndTmdbPromptInteractProperly() {
        var selectedKind: MediaKind? = null
        var selectedSort: CatalogueSort? = null
        var tmdbDismissed = false

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        providerMode = MoviesProviderMode.OPEN_ARCHIVE_AND_TVMAZE,
                        tmdbApiKeyConfigured = false,
                        useSampleFixturesOnly = false,
                        tmdbPromptDismissed = false,
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(sampleMovie),
                            sections = listOf(
                                BrowseSection("s1", "Playable Classic Cinema", "Public domain", SectionStyle.RAIL, listOf(sampleMovie))
                            ),
                            genres = listOf("All", "Comedy", "Drama")
                        )
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {},
                    onSaveTmdbApiKey = {},
                    onSelectKind = { selectedKind = it },
                    onSelectSort = { selectedSort = it },
                    onDismissTmdbPrompt = { tmdbDismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("movies_home_search_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_home_kind_movie").assertIsDisplayed().performClick()
        assertEquals(MediaKind.MOVIE, selectedKind)

        composeTestRule.onNodeWithTag("movies_home_sort_top_rated").assertIsDisplayed().performClick()
        assertEquals(CatalogueSort.TOP_RATED, selectedSort)

        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_tmdb_setup_card"))
        composeTestRule.onNodeWithTag("movies_tmdb_setup_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_tmdb_dismiss_button").performClick()
        assertTrue(tmdbDismissed)
    }

    @Test
    fun movieDetailScreen_opensDownloadResolutionDialogAndSelectsQuality() {
        var downloadedSource: PlaybackSource? = null
        val src720 = PlaybackSource("s720", "https://archive.org/720.mp4", "MP4", "720p", 720, sizeMb = 520.0, providerName = "Internet Archive", isAuthorised = true)
        val src480 = PlaybackSource("s480", "https://archive.org/480.mp4", "MP4", "480p", 480, sizeMb = 280.0, providerName = "Internet Archive", isAuthorised = true)

        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(summary = sampleMovie, fullDescription = "Screwball classic."),
                        playbackResolution = PlaybackResolution.Available(
                            title = "His Girl Friday",
                            sources = listOf(src720, src480)
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {},
                    onDownloadSource = { downloadedSource = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_download_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("download_resolution_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("download_quality_option_s480").assertIsDisplayed().performClick()
        assertEquals("s480", downloadedSource?.id)
    }

    @Test
    fun moviePlayerScreen_displaysFullCinemaControlsAndSubtitlesLockSpeedDialogs() {
        val src720 = PlaybackSource("s720", "https://archive.org/720.mp4", "MP4", "720p", 720, providerName = "Internet Archive", isAuthorised = true)
        val subEn = SubtitleTrack("sub_en", "English", "en", "English (SRT)", "https://archive.org/en.srt")

        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Available(
                            title = "His Girl Friday",
                            sources = listOf(src720),
                            subtitles = listOf(subEn)
                        ),
                        selectedSource = src720,
                        selectedSubtitleTrack = subEn
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("player_controls_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_back_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_subtitles_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_quality_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_download_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_replay_10_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_forward_10_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_seek_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_speed_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_resize_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_fullscreen_button").assertIsDisplayed()

        // Test Lock & Unlock
        composeTestRule.onNodeWithTag("player_lock_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("player_unlock_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("player_lock_button").assertIsDisplayed()

        // Test Speed Dialog
        composeTestRule.onNodeWithTag("player_speed_button").performClick()
        composeTestRule.onNodeWithTag("player_speed_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_speed_option_1.5").performClick()

        // Test Subtitles Dialog
        composeTestRule.onNodeWithTag("player_subtitles_button").performClick()
        composeTestRule.onNodeWithTag("player_subtitles_dialog").assertIsDisplayed()
    }

    @Test
    fun moviesScreen_movieBoxModeHidesTmdbPromptAndOpensServerConfigDialog() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        providerMode = MoviesProviderMode.MOVIEBOX,
                        movieBoxApiBaseUrl = "https://movies.example.com/",
                        effectiveMovieBoxBaseUrl = "https://movies.example.com/",
                        tmdbApiKeyConfigured = false,
                        tmdbPromptDismissed = false,
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(
                                sampleMovie.copy(
                                    id = "mb1",
                                    providerId = "moviebox",
                                    sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                                )
                            ),
                            sections = listOf(
                                BrowseSection("s1", "Trending", null, SectionStyle.RAIL, listOf(sampleMovie))
                            ),
                            genres = listOf("All", "Action")
                        )
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {},
                    onSaveTmdbApiKey = {}
                )
            }
        }

        composeTestRule.onNodeWithText("MovieBox catalogue & streaming").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_tmdb_setup_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_sources_button").performClick()
        composeTestRule.onNodeWithTag("provider_mode_moviebox").assertIsDisplayed()
        composeTestRule.onNodeWithTag("moviebox_base_url_input").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun moviesCatalogueScreen_emptySuggestionIdTriggersSubmitSearchInsteadOfDetail() {
        var submittedQuery: String? = null
        var openedDetailId: String? = null

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = MoviesCatalogueUiState(
                        query = "incep",
                        showSuggestionsDropdown = true,
                        suggestions = listOf(
                            SearchSuggestion(
                                id = "",
                                providerId = "moviebox",
                                title = "Inception",
                                kind = MediaKind.MOVIE,
                                releaseYear = null
                            )
                        )
                    ),
                    onBackClick = {},
                    onQueryChange = {},
                    onSearchFocusChange = {},
                    onSubmitSearch = { submittedQuery = it },
                    onClearSearch = {},
                    onSelectKind = {},
                    onSelectGenre = {},
                    onSelectSort = {},
                    onLoadNextPage = {},
                    onRetry = {},
                    onClearSearchHistory = {},
                    onOpenDetail = { _, id -> openedDetailId = id }
                )
            }
        }

        composeTestRule.onNodeWithTag("suggestion_item_idx_0").assertIsDisplayed().performClick()
        assertEquals("Inception", submittedQuery)
        assertEquals(null, openedDetailId)
    }

    @Test
    fun movieDetailScreen_watchNowForTvSeriesPassesSelectedSeasonAndEpisode() {
        var clickedSeason: Int? = null
        var clickedEpisode: Int? = null
        val seriesSummary = sampleMovie.copy(
            id = "mb_series_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(
                            summary = seriesSummary,
                            fullDescription = "Workplace thriller.",
                            seasons = listOf(SeasonSummary(1, "Season 1", 9), SeasonSummary(2, "Season 2", 10))
                        ),
                        selectedSeasonNumber = 2,
                        episodes = listOf(EpisodeItem("ep_2_1", 2, 1, "Episode 1")),
                        playbackResolution = PlaybackResolution.Available(
                            title = "Severance",
                            sources = listOf(
                                PlaybackSource("s1080", "https://cdn.example/s2e1.mp4", "MP4", "1080p", 1080, providerName = "MovieBox")
                            ),
                            providerName = "MovieBox",
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { se, ep ->
                        clickedSeason = se
                        clickedEpisode = ep
                    },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Watch S2·E1 (1080p)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_watch_button").performClick()
        assertEquals(2, clickedSeason)
        assertEquals(1, clickedEpisode)
    }

    @Test
    fun movieDetailScreen_displaysRetryablePlaybackErrorWhenStreamCheckFails() {
        var retried = false
        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(summary = sampleMovie, fullDescription = "Classic."),
                        playbackResolution = null,
                        playbackError = "Failed to bootstrap MovieBox token"
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {},
                    onRetryPlaybackCheck = { retried = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_playback_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed to bootstrap MovieBox token").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry Stream Check").performClick()
        assertTrue(retried)
    }

    @Test
    fun movieDetailScreen_rendersInteractiveAudioDubChipsAndEpisodesErrorRetry() {
        var openedRelatedId: String? = null
        var retriedSeason: Int? = null
        val seriesSummary = sampleMovie.copy(
            id = "mb_series_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Avatar",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(
                            summary = seriesSummary,
                            fullDescription = "Pandora epic.",
                            audioDubs = listOf(
                                com.laterbox.app.movies.domain.model.AudioDubInfo(language = "en", label = "English", referenceId = "mb_series_1"),
                                com.laterbox.app.movies.domain.model.AudioDubInfo(language = "fr", label = "French dub", referenceId = "mb_series_fr")
                            ),
                            seasons = listOf(SeasonSummary(1, "Season 1", 8))
                        ),
                        selectedSeasonNumber = 1,
                        episodesError = "Failed to load episodes",
                        playbackResolution = PlaybackResolution.Available(
                            title = "Avatar",
                            sources = listOf(PlaybackSource("s1", "https://cdn.example/1.mp4", "MP4", "720p", 720, providerName = "MovieBox"))
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = { retriedSeason = it },
                    onOpenRelated = { _, id -> openedRelatedId = id },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_dub_chip_mb_series_fr"))
        composeTestRule.onNodeWithTag("detail_dub_chip_mb_series_fr").assertIsDisplayed().performClick()
        assertEquals("mb_series_fr", openedRelatedId)

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_episodes_error"))
        composeTestRule.onNodeWithTag("detail_episodes_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed to load episodes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retriedSeason)
    }

    @Test
    fun moviePlayerScreen_opensAudioDialogSwitchesDubAndTriggersNextEpisode() {
        val src720 = PlaybackSource("s720", "https://cdn.example/s1e1.mp4", "MP4", "720p", 720, providerName = "MovieBox")
        val dubs = listOf(
            com.laterbox.app.movies.domain.model.AudioDubInfo(language = "en", label = "English", referenceId = "id_en"),
            com.laterbox.app.movies.domain.model.AudioDubInfo(language = "fr", label = "French dub", referenceId = "id_fr")
        )
        var selectedDubId: String? = null
        var nextEpisodeClicked = false

        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Available(
                            title = "Severance",
                            subtitleHeading = "S1 · E1",
                            sources = listOf(src720)
                        ),
                        selectedSource = src720,
                        activeTitleId = "id_en",
                        availableDubs = dubs,
                        seasons = listOf(SeasonSummary(1, "Season 1", 9)),
                        seasonNumber = 1,
                        episodeNumber = 1
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { _, _ -> },
                    onOpenExternalUrl = {},
                    onSelectAudioDub = { dub, _ -> selectedDubId = dub.referenceId },
                    onNextEpisode = { nextEpisodeClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("player_next_episode_button").assertIsDisplayed().performClick()
        assertTrue(nextEpisodeClicked)

        composeTestRule.onNodeWithTag("player_audio_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("player_audio_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_audio_dub_id_fr").assertIsDisplayed().performClick()
        assertEquals("id_fr", selectedDubId)
    }
}
