package com.laterbox.app.movies

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.CollectionWithCount
import com.laterbox.app.movies.data.cache.MoviesMediaCache
import com.laterbox.app.movies.domain.model.BecauseYouWatchedGroup
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.model.SurprisePickResult
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.ui.MoviesHomeUiState
import com.laterbox.app.movies.ui.MoviesScreen
import com.laterbox.app.movies.ui.MoviesWatchlistScreen
import com.laterbox.app.ui.components.DrawerPrimarySection
import com.laterbox.app.ui.components.LaterboxDrawerContent
import com.laterbox.app.ui.navigation.LaterboxDestination
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.After
import org.junit.Assert.assertEquals
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
class MoviesLandingNavigationAndFiltersTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @After
    fun tearDown() {
        MoviesMediaCache.releaseForTesting()
    }

    private fun movie(
        id: String,
        title: String,
        kind: MediaKind = MediaKind.MOVIE
    ) = MovieTitleSummary(
        id = id,
        providerId = "ia",
        kind = kind,
        title = title,
        releaseYear = 2024,
        rating = 8.4,
        genres = listOf("Action"),
        sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
        isPlayableHint = true
    )

    @Test
    fun heroCarousel_autoAdvancesAfter6Seconds_supportsSwipe_andHasNoBottomIndicators() {
        val banners = listOf(
            movie("b1", "Hero Alpha"),
            movie("b2", "Hero Beta"),
            movie("b3", "Hero Gamma")
        )
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.movies.ui.MoviesHeroCarousel(
                    banners = banners,
                    watchlistIds = emptySet(),
                    onBannerClick = {},
                    onPlayClick = {},
                    onToggleWatchlist = {}
                )
            }
        }

        composeTestRule.mainClock.advanceTimeBy(100L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("movies_hero_pager").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hero Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movies_hero_indicators").assertDoesNotExist()
        composeTestRule.onNodeWithText("1 / 3").assertDoesNotExist()

        // 6s auto-advance must not be cancelled mid-cycle
        composeTestRule.mainClock.advanceTimeBy(6_800L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Beta").assertIsDisplayed()

        // Swipe still advances further
        composeTestRule.onNodeWithTag("movies_hero_pager").performTouchInput { swipeLeft() }
        composeTestRule.mainClock.advanceTimeBy(600L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hero Gamma").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h1200dp")
    fun quickFilters_enforceFourGroupOrder_toggleKind_andExpandGenresRegionsWithoutTriggeringFilters() {
        var selectedKindState: MediaKind? = null
        var filterCallbackCount = 0

        composeTestRule.setContent {
            LaterboxTheme {
                var kind by remember { mutableStateOf<MediaKind?>(null) }
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        selectedKind = kind,
                        availableCountries = listOf("All", "Korea", "Japan"),
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(movie("h1", "Banner")),
                            sections = listOf(BrowseSection("s1", "Trending", null, SectionStyle.RAIL, listOf(movie("m1", "Title")))),
                            genres = listOf("All", "Drama", "Western", "Biography", "Action", "Anime", "Adventure", "Comedy")
                        )
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {},
                    onSelectKind = { newKind ->
                        filterCallbackCount++
                        kind = newKind
                        selectedKindState = newKind
                    },
                    onSelectSort = { filterCallbackCount++ },
                    onSelectGenre = { filterCallbackCount++ },
                    onSelectShortcut = { filterCallbackCount++ },
                    onSelectCountry = { filterCallbackCount++ }
                )
            }
        }

        // Group 1 toggleable All Movies / TV Series
        composeTestRule.onNodeWithTag("movies_home_kind_all").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_home_kind_movie").assertIsDisplayed().performClick()
        assertEquals(MediaKind.MOVIE, selectedKindState)
        composeTestRule.onNodeWithTag("movies_home_kind_movie").performClick()
        assertEquals(null, selectedKindState)
        filterCallbackCount = 0

        // Order check: Group 1 -> Group 2 -> Group 3 -> Group 4 (all fit across 1280dp initially)
        val movieLeft = composeTestRule.onNodeWithTag("movies_home_kind_movie").getBoundsInRoot().left
        val tvLeft = composeTestRule.onNodeWithTag("movies_home_kind_tv").getBoundsInRoot().left
        val popularLeft = composeTestRule.onNodeWithTag("movies_home_sort_popular").getBoundsInRoot().left
        val newestLeft = composeTestRule.onNodeWithTag("movies_home_sort_newest").getBoundsInRoot().left
        val animeLeft = composeTestRule.onNodeWithTag("movies_home_genre_anime").getBoundsInRoot().left
        val actionLeft = composeTestRule.onNodeWithTag("movies_home_genre_action").getBoundsInRoot().left
        val adventureLeft = composeTestRule.onNodeWithTag("movies_home_genre_adventure").getBoundsInRoot().left
        val hollywoodLeft = composeTestRule.onNodeWithTag("movies_home_shortcut_hollywood").getBoundsInRoot().left
        val westernLeft = composeTestRule.onNodeWithTag("movies_home_shortcut_western").getBoundsInRoot().left

        assertTrue(movieLeft < tvLeft)
        assertTrue(tvLeft < popularLeft)
        assertTrue(popularLeft < newestLeft)
        assertTrue(newestLeft < animeLeft)
        assertTrue(animeLeft < actionLeft && actionLeft < adventureLeft)
        assertTrue(adventureLeft < hollywoodLeft && hollywoodLeft < westernLeft)

        // Group 3: <= 3 visible initially, Western excluded
        composeTestRule.onNodeWithTag("movies_home_genre_biography").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_home_genre_comedy").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_home_genre_western").assertDoesNotExist()

        // Expand Group 3 without triggering any filter callback
        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_genres_expand_toggle"))
        composeTestRule.onNodeWithTag("movies_home_genres_expand_toggle").performClick()
        assertEquals(0, filterCallbackCount)
        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_genre_comedy"))
        composeTestRule.onNodeWithTag("movies_home_genre_biography").assertExists()
        composeTestRule.onNodeWithTag("movies_home_genre_comedy").assertExists()
        composeTestRule.onNodeWithTag("movies_home_genre_western").assertDoesNotExist()

        // Collapse Group 3
        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_genres_expand_toggle"))
        composeTestRule.onNodeWithTag("movies_home_genres_expand_toggle").performClick()
        assertEquals(0, filterCallbackCount)
        composeTestRule.onNodeWithTag("movies_home_genre_comedy").assertDoesNotExist()

        // Group 4: First 3 regional shortcuts visible initially (Hollywood, Bollywood, Western); K-Drama + Korea collapsed
        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_regions_expand_toggle"))
        composeTestRule.onNodeWithTag("movies_home_shortcut_k_drama").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movies_home_country_korea").assertDoesNotExist()

        // Expand Group 4 without triggering filter callback
        composeTestRule.onNodeWithTag("movies_home_regions_expand_toggle").performClick()
        assertEquals(0, filterCallbackCount)
        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_country_korea"))
        composeTestRule.onNodeWithTag("movies_home_shortcut_k_drama").assertExists()
        composeTestRule.onNodeWithTag("movies_home_country_korea").assertExists()

        // Collapse Group 4
        composeTestRule.onNodeWithTag("movies_home_filters_row").performScrollToNode(hasTestTag("movies_home_regions_expand_toggle"))
        composeTestRule.onNodeWithTag("movies_home_regions_expand_toggle").performClick()
        assertEquals(0, filterCallbackCount)
        composeTestRule.onNodeWithTag("movies_home_shortcut_k_drama").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h3600dp")
    fun landingOrder_placesWatchlistAfterContinueWatching_omitsCollectionsStrip_andLinksSeeAll() {
        var watchlistSeeAllClicked = false
        val watchlistItem = movie("w1", "Watchlist Noir")
        val recentItem = movie("rv1", "Recent Classic")
        val bywItem = movie("byw1", "Recommended Noir")
        val surpriseItem = movie("sur1", "Surprise Gem")

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = MoviesHomeUiState(
                        isLoading = false,
                        feed = MoviesHomeFeed(
                            featuredBanners = listOf(movie("h1", "Hero Film")),
                            sections = listOf(BrowseSection("s1", "Extra Rail", null, SectionStyle.RAIL, listOf(movie("e1", "Extra")))),
                            genres = listOf("All", "Action")
                        ),
                        continueWatching = listOf(
                            WatchHistoryItem(
                                compositeId = "ia:cw1",
                                providerId = "ia",
                                titleId = "cw1",
                                kind = MediaKind.MOVIE,
                                title = "Half Watched",
                                positionMs = 120_000L,
                                durationMs = 1_200_000L,
                                lastWatchedAt = 500L
                            )
                        ),
                        watchlist = listOf(watchlistItem),
                        recentlyViewed = listOf(recentItem),
                        collectionsWithCounts = listOf(
                            CollectionWithCount(Collection(id = 1L, name = "Weekend"), itemCount = 2)
                        ),
                        becauseYouWatchedGroups = listOf(
                            BecauseYouWatchedGroup("Hero Film", MediaKind.MOVIE, listOf(bywItem))
                        ),
                        surprisePick = SurprisePickResult(surpriseItem, "Great match"),
                        topRatedRail = listOf(movie("tr1", "Top Gem")),
                        newestRail = listOf(movie("nw1", "Newest Gem"))
                    ),
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {},
                    onOpenWatchlist = { watchlistSeeAllClicked = true }
                )
            }
        }

        // Confirm Collections strip is absent from Movies landing
        composeTestRule.onNodeWithTag("movies_home_collections_rail").assertDoesNotExist()
        composeTestRule.onNodeWithText("Your Collections").assertDoesNotExist()

        // Measure vertical bounds in exact required order inside tall viewport
        val heroTop = composeTestRule.onNodeWithTag("movies_hero_pager").getBoundsInRoot().top
        val continueTop = composeTestRule.onNodeWithText("Continue Watching").getBoundsInRoot().top
        val watchlistTop = composeTestRule.onNodeWithTag("movies_home_watchlist_rail").getBoundsInRoot().top
        val recentTop = composeTestRule.onNodeWithTag("movies_home_recently_viewed_rail").getBoundsInRoot().top
        val bywTop = composeTestRule.onNodeWithTag("movies_home_byw_rail_0").getBoundsInRoot().top
        val surpriseTop = composeTestRule.onNodeWithTag("movies_home_surprise_card").getBoundsInRoot().top
        val topRatedTop = composeTestRule.onNodeWithTag("movies_home_top_rated_rail").getBoundsInRoot().top
        val newestTop = composeTestRule.onNodeWithTag("movies_home_newest_rail").getBoundsInRoot().top

        assertTrue(heroTop < continueTop)
        assertTrue(continueTop < watchlistTop)
        assertTrue(watchlistTop < recentTop)
        assertTrue(recentTop < bywTop)
        assertTrue(bywTop < surpriseTop)
        assertTrue(surpriseTop < topRatedTop)
        assertTrue(topRatedTop < newestTop)

        // Tap Watchlist "See all"
        composeTestRule.onNode(
            hasText("See all") and hasAnyAncestor(hasTestTag("movies_home_watchlist_rail"))
        ).performClick()
        assertTrue(watchlistSeeAllClicked)
    }

    @Test
    fun watchlistScreen_filtersByKind_sharesLiveToggleRemoval_andShowsEmptyCta() {
        var explored = false
        val savedMovie = movie("m_wl", "Saved Film", MediaKind.MOVIE)
        val savedSeries = movie("s_wl", "Saved Show", MediaKind.TV_SERIES)

        composeTestRule.setContent {
            var watchlist by remember { mutableStateOf(listOf(savedMovie, savedSeries)) }
            LaterboxTheme {
                MoviesWatchlistScreen(
                    watchlist = watchlist,
                    onBackClick = {},
                    onExploreClick = { explored = true },
                    onOpenDetail = { _, _ -> },
                    onToggleWatchlist = { target ->
                        watchlist = watchlist.filterNot { it.compositeId == target.compositeId }
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("movies_watchlist_grid").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved Film").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved Show").assertIsDisplayed()

        // Filter to TV Series
        composeTestRule.onNodeWithTag("watchlist_filter_tv").performClick()
        composeTestRule.onNodeWithText("Saved Show").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved Film").assertDoesNotExist()

        // Filter to Movies, remove via overflow menu
        composeTestRule.onNodeWithTag("watchlist_filter_movies").performClick()
        composeTestRule.onNodeWithText("Saved Film").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_card_menu_m_wl").performClick()
        composeTestRule.onNodeWithText("Remove from Watchlist").performClick()

        // Switch back to All, remove remaining item -> empty state CTA appears
        composeTestRule.onNodeWithTag("watchlist_filter_all").performClick()
        composeTestRule.onNodeWithTag("watchlist_card_menu_s_wl").performClick()
        composeTestRule.onNodeWithText("Remove from Watchlist").performClick()

        composeTestRule.onNodeWithTag("movies_watchlist_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watchlist_explore_cta").performClick()
        assertTrue(explored)
    }

    @Test
    fun drawerAccordion_chevronTogglesIndependently_autoExpandsOnMoviesRoutes_andRoutesAllSevenSubItems() {
        var navigatedMovies = 0
        var navigatedSettings = 0
        val routedSubPaths = mutableListOf<String>()

        composeTestRule.setContent {
            var currentSection by remember { mutableStateOf(DrawerPrimarySection.INBOX) }
            var currentRoute by remember { mutableStateOf(LaterboxDestination.Inbox.route) }
            LaterboxTheme {
                LaterboxDrawerContent(
                    currentSection = currentSection,
                    activeConversationId = null,
                    recentConversations = emptyList(),
                    unreadNotificationCount = 0,
                    userName = "Timi",
                    onNavigateInbox = {
                        currentSection = DrawerPrimarySection.INBOX
                        currentRoute = LaterboxDestination.Inbox.route
                    },
                    onNavigateCollections = {},
                    onNavigateMovies = { navigatedMovies++ },
                    onNavigateDownloads = {},
                    onNavigateArchive = {},
                    onNavigateNotifications = {},
                    onNavigateSettings = { navigatedSettings++ },
                    onNewChatClick = {},
                    onConversationClick = {},
                    onDeleteConversation = {},
                    currentRoute = currentRoute,
                    onNavigateMoviesSubRoute = { sub ->
                        routedSubPaths.add(sub)
                        currentSection = DrawerPrimarySection.MOVIES
                        currentRoute = sub
                    },
                    modifier = Modifier.width(304.dp)
                )
            }
        }

        // Starts collapsed when not on Movies route
        composeTestRule.onNodeWithTag("drawer_movies_submenu").assertDoesNotExist()

        // Chevron toggles submenu without triggering Movies or SubRoute navigation
        composeTestRule.onNodeWithTag("drawer_movies_chevron").performClick()
        composeTestRule.onNodeWithTag("drawer_movies_submenu").assertIsDisplayed()
        assertEquals(0, navigatedMovies)
        assertTrue(routedSubPaths.isEmpty())

        // Settings must NOT be inside drawer_movies_submenu
        composeTestRule.onNode(
            hasText("Settings") and hasAnyAncestor(hasTestTag("drawer_movies_submenu"))
        ).assertDoesNotExist()

        // Main row navigates to landing without collapsing
        composeTestRule.onNodeWithTag("drawer_movies_row").performClick()
        assertEquals(1, navigatedMovies)

        // All 7 sub-destinations route cleanly (scroll each into view before clicking)
        val subTags = listOf(
            "drawer_movies_sub_search",
            "drawer_movies_sub_history",
            "drawer_movies_sub_watchlist",
            "drawer_movies_sub_popular_movies",
            "drawer_movies_sub_popular_series",
            "drawer_movies_sub_newest",
            "drawer_movies_sub_top_rated"
        )
        subTags.forEach { tag ->
            composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
        }

        assertEquals(7, routedSubPaths.size)
        assertTrue(routedSubPaths[0].contains("focusSearch=true"))
        assertEquals(LaterboxDestination.MoviesViewingHistory.route, routedSubPaths[1])
        assertEquals(LaterboxDestination.MoviesWatchlist.route, routedSubPaths[2])
        assertTrue(routedSubPaths[3].contains("kind=movie") && routedSubPaths[3].contains("sort=popular"))
        assertTrue(routedSubPaths[4].contains("kind=tv") && routedSubPaths[4].contains("sort=popular"))
        assertTrue(routedSubPaths[5].contains("sort=newest"))
        assertTrue(routedSubPaths[6].contains("sort=top_rated"))

        // Parent Settings still works once scrolled into view
        composeTestRule.onNodeWithTag("laterbox_drawer_list").performScrollToNode(hasText("Settings"))
        composeTestRule.onNodeWithText("Settings").performClick()
        assertEquals(1, navigatedSettings)
    }

    @Test
    fun drawerAccordion_autoExpandsWhenOpenedOnMoviesSubRoute() {
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
                    onNavigateMovies = {},
                    onNavigateDownloads = {},
                    onNavigateArchive = {},
                    onNavigateNotifications = {},
                    onNavigateSettings = {},
                    onNewChatClick = {},
                    onConversationClick = {},
                    onDeleteConversation = {},
                    currentRoute = LaterboxDestination.MoviesWatchlist.route,
                    modifier = Modifier.width(304.dp)
                )
            }
        }

        composeTestRule.onNodeWithTag("drawer_movies_submenu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_movies_sub_watchlist").assertIsDisplayed()
    }
}
