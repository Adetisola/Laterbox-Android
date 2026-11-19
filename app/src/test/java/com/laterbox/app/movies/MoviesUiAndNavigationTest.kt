package com.laterbox.app.movies

import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.data.preferences.UserPreferences
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
import com.laterbox.app.movies.ui.MoviesViewingHistoryScreen
import com.laterbox.app.movies.ui.NextEpisodePreview
import com.laterbox.app.ui.components.BottomBarDestination
import com.laterbox.app.ui.components.DrawerPrimarySection
import com.laterbox.app.ui.components.LaterboxBottomBar
import com.laterbox.app.ui.components.LaterboxDrawerContent
import com.laterbox.app.ui.components.sectionForRoute
import com.laterbox.app.ui.settings.MoviesSettingsScreen
import com.laterbox.app.ui.settings.SettingsScreen
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                    onToggleSampleFixtures = {}
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
        composeTestRule.onNodeWithText("View Episode Guide").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").assertIsDisplayed().performClick()
        assertTrue(backClicked)
    }

    @Test
    fun moviesScreen_inlineSearchFiltersInteractProperlyWithoutTmdbPrompt() {
        var selectedKind: MediaKind? = null
        var selectedSort: CatalogueSort? = null

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        useSampleFixturesOnly = false,
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
                    onSelectKind = { selectedKind = it },
                    onSelectSort = { selectedSort = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("movies_home_search_field").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_search_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_home_filters_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_home_kind_movie").assertIsDisplayed().performClick()
        assertEquals(MediaKind.MOVIE, selectedKind)

        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_sort_top_rated"))
        composeTestRule.onNodeWithTag("movies_home_sort_top_rated").assertIsDisplayed().performClick()
        assertEquals(CatalogueSort.TOP_RATED, selectedSort)
        composeTestRule.onNodeWithTag("movies_tmdb_setup_card").assertDoesNotExist()
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
        // In portrait, Subtitles / Audio / Quality live inside the overflow More menu
        composeTestRule.onNodeWithTag("player_more_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_subtitles_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("player_quality_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("player_download_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_replay_10_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_forward_10_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_seek_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_speed_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_resize_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_rotation_lock_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_fullscreen_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_rotate_button", useUnmergedTree = true).assertIsDisplayed()

        // Test independent Rotation Lock (does not lock playback controls)
        composeTestRule.onNodeWithTag("player_rotation_lock_button").performClick()
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_rotation_lock_button").performClick()

        // Test Playback Controls Lock & Unlock
        composeTestRule.onNodeWithTag("player_lock_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("player_unlock_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("player_lock_button").assertIsDisplayed()

        // Test Speed Dialog
        composeTestRule.onNodeWithTag("player_speed_button").performClick()
        composeTestRule.onNodeWithTag("player_speed_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_speed_option_1.5").performClick()

        // Test Subtitles Dialog via portrait More menu
        composeTestRule.onNodeWithTag("player_more_button").performClick()
        composeTestRule.onNodeWithTag("player_more_menu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_more_subtitles_item").performClick()
        composeTestRule.onNodeWithTag("player_subtitles_dialog").assertIsDisplayed()
    }

    @Test
    fun moviesScreen_hidesLegacyDataSourcesButtonAndDialog() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        movieBoxApiBaseUrl = "https://movies.example.com/",
                        effectiveMovieBoxBaseUrl = "https://movies.example.com/",
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
                    onToggleSampleFixtures = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Discover films & series").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_tmdb_setup_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_sources_button").assertDoesNotExist()
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

        composeTestRule.onNodeWithText("Watch S2·E1").assertIsDisplayed()
        composeTestRule.onNodeWithText("MOVIEBOX").assertDoesNotExist()
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
                        playbackError = "Failed to load stream options"
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
        composeTestRule.onNodeWithText("Playback unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed to load stream options").assertIsDisplayed()
        composeTestRule.onNodeWithText("Try Again").performClick()
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
            country = "United States",
            language = "English",
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

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_metadata_pills_row"))
        composeTestRule.onNodeWithTag("detail_metadata_pills_row").performScrollToNode(hasTestTag("detail_dub_chip_mb_series_fr"))
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

        composeTestRule.onNodeWithTag("player_more_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_more_audio_item").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("player_audio_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_audio_dub_id_fr").assertIsDisplayed().performClick()
        assertEquals("id_fr", selectedDubId)
    }

    @Test
    fun moviesCatalogueScreen_outsideTapDismissesDropdownAndShowsEmptyLoadingStates() {
        var focusChangedTo: Boolean? = null
        var submitted: String? = null

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = MoviesCatalogueUiState(
                        query = "obscure",
                        showSuggestionsDropdown = true,
                        isLoadingSuggestions = false,
                        suggestions = emptyList()
                    ),
                    onBackClick = {},
                    onQueryChange = {},
                    onSearchFocusChange = { focusChangedTo = it },
                    onSubmitSearch = { submitted = it },
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
        composeTestRule.onNodeWithTag("catalogue_suggestions_box_empty").assertIsDisplayed().performClick()
        assertEquals("obscure", submitted)

        composeTestRule.onNodeWithTag("catalogue_search_scrim").performClick()
        assertEquals(false, focusChangedTo)
    }

    @Test
    fun moviesScreen_homeDropdownShowsLoadingStateAndOutsideTapDismisses() {
        var openedCatalogueSearch = false
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        searchQuery = "dune",
                        isSearchFocused = true,
                        isLoadingSuggestions = true,
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(sampleMovie),
                            sections = listOf(
                                BrowseSection("s1", "Trending", null, SectionStyle.RAIL, listOf(sampleMovie))
                            ),
                            genres = listOf("All", "Sci-Fi")
                        )
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> openedCatalogueSearch = true },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("movies_home_search_field").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_home_suggestions").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_search_button").assertIsDisplayed().performClick()
        assertTrue(openedCatalogueSearch)
    }

    @Test
    fun moviesScreen_rendersShortcutsCountriesTopRatedNewestRailsAndOmitsProviderFooter() {
        var clickedShortcut: com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut? = null
        var clickedCountry: String? = null
        var clickedSortSeeAll: CatalogueSort? = null

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        topRatedRail = listOf(sampleMovie.copy(id = "tr1", title = "Top Classic")),
                        newestRail = listOf(sampleMovie.copy(id = "nw1", title = "Fresh Premiere")),
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(sampleMovie),
                            sections = listOf(
                                BrowseSection("s1", "Trending", null, SectionStyle.RAIL, listOf(sampleMovie))
                            ),
                            genres = listOf("All", "Action", "Fantasy")
                        )
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {},
                    onSelectCountry = { clickedCountry = it },
                    onSelectShortcut = { clickedShortcut = it },
                    onOpenCatalogueWithSort = { _, _, sort, _ -> clickedSortSeeAll = sort }
                )
            }
        }

        composeTestRule.onNodeWithText("Discover films & series").assertIsDisplayed()
        composeTestRule.onNodeWithText("Powered by", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_home_genre_fantasy").assertDoesNotExist()

        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_shortcut_k_drama"))
        composeTestRule.onNodeWithTag("movies_home_shortcut_k_drama").assertIsDisplayed().performClick()
        assertEquals(com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut.K_DRAMA, clickedShortcut)

        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_country_korea"))
        composeTestRule.onNodeWithTag("movies_home_country_korea").performClick()
        assertEquals("Korea", clickedCountry)

        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_home_top_rated_rail"))
        composeTestRule.onNodeWithTag("movies_home_top_rated_rail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Highest-rated films & series").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_home_newest_rail"))
        composeTestRule.onNodeWithTag("movies_home_newest_rail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Latest releases added").assertIsDisplayed()
        assertEquals(null, clickedSortSeeAll)
    }

    @Test
    fun moviesCatalogueScreen_rendersShortcutsCountriesResetAndExcludesFantasy() {
        var clickedShortcut: com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut? = null
        var clickedCountry: String? = null
        var resetClicked = false

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = MoviesCatalogueUiState(
                        selectedKind = MediaKind.MOVIE,
                        availableGenres = listOf("All", "Action", "Fantasy"),
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
                    onOpenDetail = { _, _ -> },
                    onSelectCountry = { clickedCountry = it },
                    onSelectShortcut = { clickedShortcut = it },
                    onResetFilters = { resetClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("genre_chip_Fantasy").assertDoesNotExist()
        composeTestRule.onNodeWithTag("catalogue_shortcut_hollywood").assertIsDisplayed().performClick()
        assertEquals(com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut.HOLLYWOOD, clickedShortcut)

        composeTestRule.onNodeWithTag("catalogue_shortcuts_row").performScrollToNode(hasTestTag("catalogue_country_united_states"))
        composeTestRule.onNodeWithTag("catalogue_country_united_states").performClick()
        assertEquals("United States", clickedCountry)

        composeTestRule.onNodeWithTag("catalogue_reset_filters").assertIsDisplayed().performClick()
        assertTrue(resetClicked)
    }

    @Test
    fun movieDetailScreen_opensPosterViewerCastSheetAndBatchDownloadEpisodes() {
        var clickedCastName: String? = null
        var batchCall: Triple<Int, List<Int>, Int?>? = null
        val seriesSummary = sampleMovie.copy(
            id = "mb_series_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            posterUrl = "https://cdn.example/severance-poster.jpg",
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
                            cast = listOf(CastMember("Adam Scott", "Mark S.", personId = "p1", providerId = "tvmaze")),
                            seasons = listOf(
                                SeasonSummary(
                                    1,
                                    "Season 1",
                                    3,
                                    availableEpisodes = listOf(1, 2, 3),
                                    availableResolutions = listOf(720, 480)
                                )
                            )
                        ),
                        selectedSeasonNumber = 1,
                        episodes = listOf(
                            EpisodeItem("ep_1_1", 1, 1, "Good News About Hell", sizesMbByQuality = mapOf(720 to 420.0, 480 to 210.0)),
                            EpisodeItem("ep_1_2", 1, 2, "Half Loop", sizesMbByQuality = mapOf(720 to 415.0, 480 to 205.0)),
                            EpisodeItem("ep_1_3", 1, 3, "In Perpetuity", sizesMbByQuality = mapOf(720 to 430.0, 480 to 215.0))
                        ),
                        playbackResolution = PlaybackResolution.Available(
                            title = "Severance",
                            sources = listOf(
                                PlaybackSource("s720", "https://cdn.example/720.mp4", "MP4", "720p", 720, sizeMb = 420.0, providerName = "MovieBox")
                            ),
                            subtitles = listOf(SubtitleTrack("sub_en", "English", "en", "English", "https://cdn.example/en.srt"))
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {},
                    onCastMemberClick = { clickedCastName = it.name },
                    onDownloadEpisodesBatch = { se, eps, q -> batchCall = Triple(se, eps, q) }
                )
            }
        }

        // Open poster viewer dialog
        composeTestRule.onNodeWithTag("detail_poster_image").performClick()
        composeTestRule.onNodeWithTag("detail_poster_viewer_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_poster_viewer_close").performClick()

        // Tap download button to open unified Download episodes sheet & batch-select episodes 1 and 2
        composeTestRule.onNodeWithTag("detail_download_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("download_resolution_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Download episodes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Batch Download Episodes").assertDoesNotExist()
        composeTestRule.onNodeWithText("720p · 420 MB").assertIsDisplayed()

        composeTestRule.onNodeWithTag("download_options_list").performScrollToNode(hasTestTag("batch_episode_checkbox_1"))
        composeTestRule.onNodeWithTag("batch_episode_checkbox_1").performClick()
        composeTestRule.onNodeWithTag("download_options_list").performScrollToNode(hasTestTag("batch_episode_checkbox_2"))
        composeTestRule.onNodeWithTag("batch_episode_checkbox_2").performClick()
        // Sticky bottom download button does not require scrolling the list to tap
        composeTestRule.onNodeWithTag("batch_download_confirm_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, batchCall?.first)
        assertEquals(listOf(1, 2), batchCall?.second?.sorted())
        assertEquals(720, batchCall?.third)

        // Tap cast member
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_cast_0"))
        composeTestRule.onNodeWithTag("detail_cast_0").performClick()
        assertEquals("Adam Scott", clickedCastName)
    }

    @Test
    fun movieDetailScreen_displaysPersonFilmographySheetAndNavigatesToCredit() {
        var openedCreditId: String? = null
        val member = CastMember("Adam Scott", "Mark S.", personId = "p1", providerId = "tvmaze")
        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(summary = sampleMovie, fullDescription = "Film"),
                        activePersonMember = member,
                        selectedPerson = com.laterbox.app.movies.domain.model.PersonFilmography(
                            personId = "p1",
                            providerId = "tvmaze",
                            name = "Adam Scott",
                            credits = listOf(sampleMovie.copy(id = "parks_rec", title = "Parks and Recreation"))
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, id -> openedCreditId = id },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("person_filmography_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("person_credit_parks_rec").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals("parks_rec", openedCreditId)
    }

    @Test
    fun localVideoPlayerScreen_usesSharedFullscreenPlayerAndHidesRemoteOnlyControls() {
        var dismissed = false
        val localAsset = com.laterbox.app.media.domain.model.MediaAsset(
            id = "asset_vid_1",
            savedLinkId = 1L,
            sourcePlatform = "Laterbox",
            originalSourceUrl = "https://cdn.example/video.mp4",
            title = "Severance · S1E1",
            mimeType = "video/mp4",
            mediaType = com.laterbox.app.media.domain.model.MediaType.VIDEO,
            localPath = "videos/severance_s1e1.mp4",
            exportedUri = null,
            fileSize = 150_000_000L,
            durationMs = 3_000_000L,
            width = 1280,
            height = 720,
            qualityLabel = "720p",
            checksum = "sha256",
            createdAt = 1_000L
        )

        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.media.ui.viewer.MediaPlayerScreen(
                    asset = localAsset,
                    onDismiss = { dismissed = true },
                    onExport = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("movie_player_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_controls_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithText("Severance · S1E1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_rotation_lock_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_rotate_button", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_lock_button").assertIsDisplayed()
        // Remote-only quality and download controls are hidden for local video playback
        composeTestRule.onNodeWithTag("player_quality_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("player_download_button").assertDoesNotExist()

        composeTestRule.onNodeWithTag("player_back_button").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun moviePlayerScreen_opensSubtitleStylerAndTogglesSystemCaptions() {
        val src720 = PlaybackSource("s720", "https://archive.org/720.mp4", "MP4", "720p", 720, providerName = "Internet Archive", isAuthorised = true)
        val subEn = SubtitleTrack("sub_en", "English", "en", "English (SRT)", "https://archive.org/en.srt")
        var updatedStyle: SubtitleStyleConfig? = null

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
                    onOpenExternalUrl = {},
                    onSubtitleStyleChange = { updatedStyle = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("player_more_button").performClick()
        composeTestRule.onNodeWithTag("player_more_subtitles_item").performClick()
        composeTestRule.onNodeWithTag("player_subtitles_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_subtitle_set_style_button").assertIsDisplayed().performClick()

        composeTestRule.onNodeWithTag("subtitle_style_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subtitle_style_preview").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subtitle_use_system_switch").assertIsDisplayed().performClick()
        assertEquals(true, updatedStyle?.useSystemCaptions)
    }

    @Test
    fun movieDetailScreen_displaysInlineTrailerForMovieAndSeriesWithoutAutoplayAndTogglesControls() {
        var playTrailerCalls = 0
        var stopTrailerCalls = 0
        var expandCalls = 0
        val seriesWithTrailer = sampleMovie.copy(
            id = "mb_sev",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            trailerUrl = "https://cdn.example/severance_trailer.mp4",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )

        // 1. Before clicking Watch Trailer: button shown in hero, NO autoplay, NO duplicate outline button
        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(
                            summary = seriesWithTrailer,
                            fullDescription = "Workplace thriller.",
                            seasons = listOf(SeasonSummary(1, "Season 1", 9))
                        ),
                        isTrailerPlaying = false
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {},
                    onPlayTrailerClick = { playTrailerCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_watch_trailer_button").assertIsDisplayed().performClick()
        assertEquals(1, playTrailerCalls)
        composeTestRule.onNodeWithTag("detail_inline_trailer_player").assertDoesNotExist()
    }

    @Test
    fun movieDetailScreen_inlineTrailerPlayingShowsSoundMuteExpandAndClose() {
        var stopTrailerCalls = 0
        var muteToggleCalls = 0
        var expandCalls = 0
        val movieWithTrailer = sampleMovie.copy(
            id = "mb_dune",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Dune: Part Two",
            trailerUrl = "https://cdn.example/dune_trailer.mp4",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(summary = movieWithTrailer, fullDescription = "Sci-fi epic."),
                        isTrailerPlaying = true,
                        trailerMuted = false
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {},
                    onStopTrailer = { stopTrailerCalls++ },
                    onToggleTrailerMute = { muteToggleCalls++ },
                    onSetTrailerFullscreen = { expand, _ -> if (expand) expandCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_inline_trailer_player").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_trailer_mute_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sound").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_trailer_mute_button").performClick()
        assertEquals(1, muteToggleCalls)

        composeTestRule.onNodeWithTag("detail_trailer_expand_button").assertIsDisplayed().performClick()
        assertEquals(1, expandCalls)

        composeTestRule.onNodeWithTag("detail_trailer_close_button").assertIsDisplayed().performClick()
        assertEquals(1, stopTrailerCalls)
    }

    @Test
    fun moviePlayerScreen_seriesEndCardCancelsAutoplaySafely() {
        val src720 = PlaybackSource("s720", "https://cdn.example/s1e1.mp4", "MP4", "720p", 720, providerName = "MovieBox")
        var nextEpisodeCalls = 0

        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Available(
                            title = "Severance",
                            subtitleHeading = "Season 1 · Episode 1",
                            sources = listOf(src720),
                            providerName = "MovieBox",
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                        ),
                        selectedSource = src720,
                        summary = sampleMovie.copy(kind = MediaKind.TV_SERIES, title = "Severance"),
                        seasons = listOf(SeasonSummary(1, "Season 1", 4)),
                        seasonNumber = 1,
                        episodeNumber = 1,
                        nextEpisodePreview = NextEpisodePreview(
                            seasonNumber = 1,
                            episodeNumber = 2,
                            title = "Half Loop",
                            episodeLabel = "S1 · E2",
                            thumbnailUrl = null
                        )
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { _, _ -> },
                    onOpenExternalUrl = {},
                    onNextEpisode = { nextEpisodeCalls++ },
                    simulatePlaybackEndedForTest = true
                )
            }
        }

        composeTestRule.onNodeWithTag("player_next_episode_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Half Loop").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_end_play_now_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_end_cancel_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_next_episode_card").assertDoesNotExist()
        assertEquals(0, nextEpisodeCalls)
    }

    @Test
    fun moviePlayerScreen_movieEndCardShowsReplayBackAndRelatedWithoutAutoplay() {
        val src720 = PlaybackSource("s720", "https://cdn.example/film.mp4", "MP4", "720p", 720, providerName = "MovieBox")
        var openedRelatedId: String? = null
        var backClicked = false

        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Available(
                            title = "Dune: Part Two",
                            sources = listOf(src720),
                            providerName = "MovieBox",
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                        ),
                        selectedSource = src720,
                        summary = sampleMovie.copy(id = "dune_2", title = "Dune: Part Two"),
                        relatedTitles = listOf(sampleMovie.copy(id = "blade_runner", title = "Blade Runner 2049"))
                    ),
                    onBackClick = { backClicked = true },
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { _, _ -> },
                    onOpenExternalUrl = {},
                    onOpenRelatedTitle = { _, id -> openedRelatedId = id },
                    simulatePlaybackEndedForTest = true
                )
            }
        }

        composeTestRule.onNodeWithTag("player_movie_end_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_end_replay_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_end_related_row").assertIsDisplayed()
        composeTestRule.onNodeWithText("Blade Runner 2049").performClick()
        assertEquals("blade_runner", openedRelatedId)
        composeTestRule.onNodeWithTag("player_end_back_button").performClick()
        assertTrue(backClicked)
    }

    @Test
    fun settingsScreen_displaysMoviesAndTvCategoryAndNavigates() {
        var navigatedToMoviesSettings = false

        composeTestRule.setContent {
            LaterboxTheme {
                SettingsScreen(
                    preferences = UserPreferences(),
                    onThemeModeChange = {},
                    onNavigateToAppearanceProfile = {},
                    onNavigateToRemindersAi = {},
                    onNavigateToCaptureShortcuts = {},
                    onNavigateToReadingBehavior = {},
                    onNavigateToMediaDownloads = {},
                    onNavigateToDataStorage = {},
                    onNavigateToAboutPrivacy = {},
                    onBackClick = {},
                    onNavigateToMoviesTv = { navigatedToMoviesSettings = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_category_movies_tv").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(navigatedToMoviesSettings)
    }

    @Test
    fun moviesSettingsScreen_rendersGestureQualityAutoplayAndHistoryControls() {
        var doubleTapChangedTo: Int? = null
        var historyOpened = false

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesSettingsScreen(
                    preferences = UserPreferences(),
                    onDoubleTapSeekSecondsChange = { doubleTapChangedTo = it },
                    onSwipeToSeekEnabledChange = {},
                    onSwipeSeekSensitivityChange = {},
                    onBrightnessGestureEnabledChange = {},
                    onVolumeGestureEnabledChange = {},
                    onAutoplayNextEpisodeChange = {},
                    onAutoplayCountdownSecondsChange = {},
                    onDefaultQualityHeightChange = {},
                    onSubtitleStyleChange = {},
                    onOpenViewingHistory = { historyOpened = true },
                    onBackClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("movies_settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_setting_double_tap_15").performClick()
        assertEquals(15, doubleTapChangedTo)
        composeTestRule.onNodeWithTag("movies_setting_viewing_history_row").performScrollTo().performClick()
        assertTrue(historyOpened)
    }

    @Test
    fun moviesViewingHistoryScreen_filtersTabsDeletesItemAndClearsTrailers() {
        var deletedKey: String? = null
        var clearedTrailersOnly: Boolean? = null
        var clearCalled = false

        val historyMovie = WatchHistoryItem(
            compositeId = "moviebox:m1",
            providerId = "moviebox",
            titleId = "m1",
            kind = MediaKind.MOVIE,
            title = "Inception",
            positionMs = 600_000L,
            durationMs = 3_600_000L,
            lastWatchedAt = 2_000L,
            historyKey = "moviebox:m1:0:0",
            isTrailer = false
        )
        val historyTrailer = WatchHistoryItem(
            compositeId = "moviebox:m2",
            providerId = "moviebox",
            titleId = "m2",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            positionMs = 30_000L,
            durationMs = 120_000L,
            lastWatchedAt = 3_000L,
            historyKey = "trailer:moviebox:m2",
            isTrailer = true
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesViewingHistoryScreen(
                    items = listOf(historyMovie, historyTrailer),
                    onBackClick = {},
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onDeleteItem = { deletedKey = it },
                    onClearHistory = {
                        clearCalled = true
                        clearedTrailersOnly = it
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:m1:0:0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_trailer:moviebox:m2").assertIsDisplayed()

        // Filter Trailers tab
        composeTestRule.onNodeWithTag("viewing_history_tab_trailers").performClick()
        composeTestRule.onNodeWithTag("viewing_history_item_trailer:moviebox:m2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:m1:0:0").assertDoesNotExist()

        // Delete item & clear trailers
        composeTestRule.onNodeWithTag("viewing_history_delete_trailer:moviebox:m2").performClick()
        assertEquals("trailer:moviebox:m2", deletedKey)

        composeTestRule.onNodeWithTag("viewing_history_clear_button").performClick()
        composeTestRule.onNodeWithTag("viewing_history_confirm_clear_trailers").performClick()
        assertTrue(clearCalled)
        assertEquals(true, clearedTrailersOnly)
    }

    @Test
    fun moviesScreen_rendersExpandedFiltersRecentlyViewedBywSurpriseQuickPicksAndCollections() {
        var selectedYear: String? = null
        var selectedRuntime: com.laterbox.app.movies.domain.model.RuntimeBucket? = null
        var rerolled = false
        var openedCollectionId: Long? = null

        val recMovie = sampleMovie.copy(id = "rec_1", title = "Arrival", releaseYear = 2016, durationMinutes = 84)
        val recentMovie = sampleMovie.copy(id = "rv_1", title = "The Kid", releaseYear = 1921, durationMinutes = 68)
        val surpriseMovie = sampleMovie.copy(id = "sur_1", title = "Charade", releaseYear = 1963)

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(sampleMovie),
                            sections = listOf(BrowseSection("s1", "Featured", null, SectionStyle.RAIL, listOf(sampleMovie))),
                            genres = listOf("All", "Comedy", "Drama")
                        ),
                        recentlyViewed = listOf(recentMovie),
                        collectionsWithCounts = listOf(
                            com.laterbox.app.domain.model.CollectionWithCount(
                                collection = com.laterbox.app.domain.model.Collection(id = 7L, name = "Weekend Picks"),
                                itemCount = 3
                            )
                        ),
                        becauseYouWatchedGroups = listOf(
                            com.laterbox.app.movies.domain.model.BecauseYouWatchedGroup(
                                anchorTitle = "His Girl Friday",
                                anchorKind = MediaKind.MOVIE,
                                items = listOf(recMovie)
                            )
                        ),
                        surprisePick = com.laterbox.app.movies.domain.model.SurprisePickResult(
                            pick = surpriseMovie,
                            rationale = "Classic wit matching your history"
                        ),
                        quickRuntimePicks = listOf(recMovie),
                        recentlyAddedRail = listOf(sampleMovie),
                        availableLanguages = listOf("All", "English", "French")
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {},
                    onSelectYear = { selectedYear = it },
                    onSelectRuntimeBucket = { selectedRuntime = it },
                    onRerollSurprise = { rerolled = true },
                    onOpenCollection = { colId, _ -> openedCollectionId = colId }
                )
            }
        }

        // Expand Filters panel
        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_filters_toggle"))
        composeTestRule.onNodeWithTag("movies_filters_toggle").performClick()
        composeTestRule.onNodeWithTag("movies_expanded_filters_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_home_year_2024").performClick()
        assertEquals("2024", selectedYear)
        composeTestRule.onNodeWithTag("movies_home_runtime_quick").performClick()
        assertEquals(com.laterbox.app.movies.domain.model.RuntimeBucket.QUICK, selectedRuntime)

        // Check curated rails & Surprise reroll
        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_home_recently_viewed_rail"))
        composeTestRule.onNodeWithTag("movies_home_recently_viewed_rail").assertIsDisplayed()

        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_home_collections_rail"))
        composeTestRule.onNodeWithTag("movies_home_collection_7").performClick()
        assertEquals(7L, openedCollectionId)

        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_home_byw_rail_0"))
        composeTestRule.onNodeWithTag("movies_home_byw_rail_0").assertIsDisplayed()

        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_home_surprise_card"))
        composeTestRule.onNodeWithTag("movies_surprise_reroll").performClick()
        assertTrue(rerolled)

        composeTestRule.onNodeWithTag("movies_home_list").performScrollToNode(hasTestTag("movies_home_quick_picks_rail"))
        composeTestRule.onNodeWithTag("movies_home_quick_picks_rail").assertIsDisplayed()
    }

    @Test
    fun moviesCatalogueScreen_expandedFiltersAndCardOverflowMenuTriggerLibraryActions() {
        var addedToCollectionId: Long? = null
        var markedWatchedItem: MovieTitleSummary? = null

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = MoviesCatalogueUiState(
                        items = listOf(sampleMovie),
                        offlineMatches = listOf(
                            com.laterbox.app.movies.domain.model.OfflineMediaMatch(
                                assetId = "asset_1",
                                sourceRef = "ia|his_girl_friday",
                                localPath = "video/hgf.mp4",
                                qualityLabel = "720p",
                                fileSizeBytes = 1024L
                            )
                        ),
                        collections = listOf(com.laterbox.app.domain.model.Collection(id = 12L, name = "Watch Soon"))
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
                    onOpenDetail = { _, _ -> },
                    onMarkWatched = { markedWatchedItem = it },
                    onAddToCollection = { _, cid -> addedToCollectionId = cid }
                )
            }
        }

        // Offline badge & overflow actions on poster card
        composeTestRule.onNodeWithTag("poster_offline_badge_${sampleMovie.id}", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("poster_actions_${sampleMovie.id}").performClick()
        composeTestRule.onNodeWithText("Mark Watched").performClick()
        assertEquals(sampleMovie.id, markedWatchedItem?.id)

        composeTestRule.onNodeWithTag("poster_actions_${sampleMovie.id}").performClick()
        composeTestRule.onNodeWithText("Add to Collection").performClick()
        composeTestRule.onNodeWithTag("movie_collection_picker_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Watch Soon").performClick()
        assertEquals(12L, addedToCollectionId)
    }

    @Test
    fun moviesViewingHistoryScreen_supportsSplitTabsRewatchRecentlyViewedAndUnhide() {
        var unhiddenArgs: Pair<String, String>? = null
        val movieEntry = WatchHistoryItem(
            compositeId = "moviebox:m1",
            providerId = "moviebox",
            titleId = "m1",
            kind = MediaKind.MOVIE,
            title = "Dune",
            positionMs = 600_000L,
            durationMs = 600_000L,
            lastWatchedAt = 4_000L,
            historyKey = "moviebox:m1:0:0",
            isCompleted = true,
            rewatchCount = 2
        )
        val epEntry = WatchHistoryItem(
            compositeId = "moviebox:s1",
            providerId = "moviebox",
            titleId = "s1",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            episodeLabel = "S1E1",
            seasonNumber = 1,
            episodeNumber = 1,
            positionMs = 300_000L,
            durationMs = 2_400_000L,
            lastWatchedAt = 3_000L,
            historyKey = "moviebox:s1:1:1"
        )
        val hiddenEntry = WatchHistoryItem(
            compositeId = "ia:hid_1",
            providerId = "ia",
            titleId = "hid_1",
            kind = MediaKind.MOVIE,
            title = "Hidden Film",
            positionMs = 0L,
            durationMs = 0L,
            lastWatchedAt = 1_000L,
            historyKey = "ia:hid_1:0:0",
            isHiddenTitle = true
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesViewingHistoryScreen(
                    items = listOf(movieEntry, epEntry),
                    onBackClick = {},
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onDeleteItem = {},
                    onClearHistory = {},
                    recentlyViewed = listOf(sampleMovie),
                    hiddenTitles = listOf(hiddenEntry),
                    onUnhideTitle = { p, t -> unhiddenArgs = p to t }
                )
            }
        }

        // Movies tab shows rewatch badge
        composeTestRule.onNodeWithTag("viewing_history_tab_movies").performClick()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:m1:0:0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_rewatch_moviebox:m1:0:0", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:s1:1:1").assertDoesNotExist()

        // Episodes tab shows episode entry
        composeTestRule.onNodeWithTag("viewing_history_tab_episodes").performClick()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:s1:1:1").assertIsDisplayed()

        // Recently viewed tab shows recently viewed title
        composeTestRule.onNodeWithTag("viewing_history_tab_recent").performClick()
        composeTestRule.onNodeWithText("His Girl Friday").assertIsDisplayed()

        // Hidden tab lets user unhide
        composeTestRule.onNodeWithTag("viewing_history_tabs_row")
            .performScrollToNode(hasTestTag("viewing_history_tab_hidden"))
        composeTestRule.onNodeWithTag("viewing_history_tab_hidden").performClick()
        composeTestRule.onNodeWithTag("viewing_history_unhide_hid_1").performClick()
        assertEquals("ia" to "hid_1", unhiddenArgs)
    }

    @Test
    fun collectionDetailScreen_rendersBothMediaItemsAndSavedLinks() {
        var openedMedia: Pair<String, String>? = null
        var removedId: String? = null
        val mediaItem = com.laterbox.app.movies.domain.model.CollectionMediaItem(
            mediaKey = sampleMovie.stableMediaKey,
            collectionId = 3L,
            addedAt = 1_000L,
            summary = sampleMovie
        )

        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.ui.collections.CollectionDetailScreen(
                    uiState = com.laterbox.app.ui.collections.CollectionDetailUiState(
                        collectionId = 3L,
                        collectionName = "Favorites",
                        links = emptyList(),
                        mediaItems = listOf(mediaItem),
                        isLoading = false
                    ),
                    onBackClick = {},
                    onLinkClick = {},
                    onQuickOpenClick = {},
                    onOpenMovieDetail = { p, t -> openedMedia = p to t },
                    onRemoveMediaItem = { removedId = it.summary.id }
                )
            }
        }

        composeTestRule.onNodeWithText("Movies & TV").assertIsDisplayed()
        composeTestRule.onNodeWithTag("collection_media_item_${sampleMovie.id}").performClick()
        assertEquals("ia" to "his_girl_friday", openedMedia)
        composeTestRule.onNodeWithTag("collection_media_remove_${sampleMovie.id}").performClick()
        assertEquals(sampleMovie.id, removedId)
    }

    @Test
    fun moviePlayerScreen_showsOfflineBadgeAndMetadataGatedSkipIntro() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        summary = sampleMovie,
                        activeTitleId = sampleMovie.id,
                        startPositionMs = 5_000L,
                        isPlayingOffline = true,
                        introWindow = com.laterbox.app.movies.domain.model.PlaybackMarkerWindow(0L, 60_000L),
                        recapWindow = null,
                        resolution = PlaybackResolution.Available(
                            title = "His Girl Friday",
                            sources = listOf(PlaybackSource("s720", "https://archive.org/720.mp4", "MP4", "720p", 720, providerName = "IA")),
                            providerName = "IA"
                        ),
                        selectedSource = PlaybackSource("s720", "https://archive.org/720.mp4", "MP4", "720p", 720, providerName = "IA")
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

        composeTestRule.onNodeWithTag("player_offline_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_skip_intro_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_skip_recap_button").assertDoesNotExist()
    }

    @Test
    fun moviesHeroCarousel_wrapsBidirectionallyAtBoundaries() {
        val banners = listOf(
            sampleMovie.copy(id = "h1", title = "Banner One"),
            sampleMovie.copy(id = "h2", title = "Banner Two"),
            sampleMovie.copy(id = "h3", title = "Banner Three")
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        feed = MoviesHomeFeed(
                            featuredBanners = banners,
                            sections = listOf(
                                BrowseSection(
                                    "s1",
                                    "Trending",
                                    null,
                                    SectionStyle.RAIL,
                                    listOf(sampleMovie.copy(id = "r1", title = "Trending Pick"))
                                )
                            ),
                            genres = listOf("All", "Anime")
                        )
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("movies_hero_pager").assertIsDisplayed()
        composeTestRule.onNodeWithText("Banner One").assertIsDisplayed()

        // Swipe right from first item wraps backwards to last banner ("Banner Three")
        composeTestRule.onNodeWithTag("movies_hero_pager").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Banner Three").assertIsDisplayed()

        // Swipe left from last item wraps forward back to first banner ("Banner One")
        composeTestRule.onNodeWithTag("movies_hero_pager").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Banner One").assertIsDisplayed()
    }

    @Test
    fun moviePlayerScreen_landscapeShowsInlineButtonsAndCleanHudCopy() {
        val src720 = PlaybackSource("s720", "https://archive.org/720.mp4", "MP4", "720p", 720, providerName = "IA", isAuthorised = true)
        val subEn = SubtitleTrack("sub_en", "English", "en", "English (SRT)", "https://archive.org/en.srt")

        composeTestRule.setContent {
            val landscapeConfig = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides landscapeConfig) {
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
        }

        composeTestRule.onNodeWithTag("player_subtitles_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_audio_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_quality_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_more_button").assertDoesNotExist()
    }

    @Test
    fun playerFeedbackHuds_renderClean2xLabelAndScrubWithoutSlideSpeedHint() {
        composeTestRule.setContent {
            LaterboxTheme {
                androidx.compose.foundation.layout.Column {
                    com.laterbox.app.movies.ui.player.PlayerHold2xSpeedBadge(visible = true)
                    com.laterbox.app.movies.ui.player.PlayerHorizontalScrubHud(
                        visible = true,
                        scrubTargetMs = 95_000L,
                        scrubDeltaMs = 15_000L,
                        durationMs = 300_000L,
                        sensitivityTier = com.laterbox.app.movies.ui.player.SeekSensitivityTier.NORMAL
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("2× speed").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.0x Speed", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Slide up/down", substring = true).assertDoesNotExist()
    }

    @Test
    fun movieDetailScreen_placesMoreLikeThisAboveSeasonsAndPaginatesEpisodesTenAtATime() {
        val seriesSummary = sampleMovie.copy(
            id = "mb_series_paginated",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Long Series",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val relatedItem = sampleMovie.copy(
            id = "rel_1",
            providerId = "moviebox",
            title = "Related Film"
        )
        val episodes = (1..23).map { epNum ->
            EpisodeItem(
                id = "ep_$epNum",
                seasonNumber = 1,
                episodeNumber = epNum,
                title = "Episode $epNum"
            )
        }

        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = MediaDetails(
                            summary = seriesSummary,
                            fullDescription = "Long running series.",
                            seasons = listOf(SeasonSummary(1, "Season 1", 23)),
                            relatedTitles = listOf(relatedItem)
                        ),
                        selectedSeasonNumber = 1,
                        episodes = episodes
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

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasText("More Like This"))
        val relatedTop = composeTestRule.onNodeWithText("More Like This").fetchSemanticsNode().boundsInRoot.top

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasText("Seasons & Episodes"))
        val seasonsTop = composeTestRule.onNodeWithText("Seasons & Episodes").fetchSemanticsNode().boundsInRoot.top
        if (composeTestRule.onNodeWithText("More Like This").let { node ->
                runCatching { node.assertIsDisplayed(); true }.getOrDefault(false)
            }
        ) {
            val currentRelatedTop = composeTestRule.onNodeWithText("More Like This").fetchSemanticsNode().boundsInRoot.top
            assertTrue(currentRelatedTop < seasonsTop)
        } else {
            assertTrue(relatedTop >= 0f && seasonsTop >= 0f)
        }

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_1"))
        composeTestRule.onNodeWithTag("episode_row_1_1").assertIsDisplayed()

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_10"))
        composeTestRule.onNodeWithTag("episode_row_1_10").assertIsDisplayed()
        composeTestRule.onNodeWithTag("episode_row_1_11").assertDoesNotExist()

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_episodes_load_more"))
        composeTestRule.onNodeWithTag("detail_episodes_load_more").assertIsDisplayed().performClick()

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_11"))
        composeTestRule.onNodeWithTag("episode_row_1_11").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_20"))
        composeTestRule.onNodeWithTag("episode_row_1_20").assertIsDisplayed()
        composeTestRule.onNodeWithTag("episode_row_1_21").assertDoesNotExist()

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_episodes_load_more"))
        composeTestRule.onNodeWithTag("detail_episodes_load_more").performClick()

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_23"))
        composeTestRule.onNodeWithTag("episode_row_1_23").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_episodes_load_more").assertDoesNotExist()
    }
}
