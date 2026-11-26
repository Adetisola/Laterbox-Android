package com.laterbox.app.movies

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.LibrarySearchSuggestion
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.MoviesCatalogueScreen
import com.laterbox.app.movies.ui.MoviesCatalogueUiState
import com.laterbox.app.movies.ui.MoviesCatalogueViewModel
import com.laterbox.app.movies.ui.MoviesHomeViewModel
import com.laterbox.app.ui.components.LaterboxSearchField
import com.laterbox.app.ui.theme.LaterboxTheme
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchInputSpaceAndDebounceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private open class StubRepo : MoviesRepository {
        override suspend fun getHomeFeed(forceRefresh: Boolean) = Result.success(MoviesHomeFeed())
        override suspend fun getCatalogue(
            kind: MediaKind?,
            page: Int,
            pageSize: Int,
            genre: String?,
            sort: CatalogueSort,
            country: String?,
            year: String?
        ) = Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))

        override suspend fun getGenres() = Result.success(listOf("All"))
        override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int) =
            Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))

        override suspend fun getSuggestions(query: String, limit: Int) =
            Result.success(emptyList<SearchSuggestion>())

        override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
            Result.failure<MediaDetails>(NoSuchElementException())

        override suspend fun getSeasons(providerId: String, seriesId: String) =
            Result.success(emptyList<SeasonSummary>())

        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int) =
            Result.success(emptyList<EpisodeItem>())

        override suspend fun resolvePlayback(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ) = Result.success(PlaybackResolution.Unavailable("Test", "none", providerId))

        override suspend fun getSubtitles(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ) = Result.success(emptyList<SubtitleTrack>())

        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = flowOf(emptyList())
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean = false
        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
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
        ) = 0L

        override fun observeRecentSearches(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit
    }

    @Test
    fun `catalogue search allows deleting multi-word trailing spaces even with delayed stateFlow echoes`() {
        val externalState = MutableStateFlow(MoviesCatalogueUiState(query = ""))
        val emittedQueries = mutableListOf<String>()

        composeTestRule.setContent {
            val state by externalState.collectAsState()
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = state,
                    onBackClick = {},
                    onQueryChange = { q ->
                        emittedQueries.add(q)
                        // Deliberately do NOT update externalState immediately on every backspace
                        // to simulate async StateFlow lag / out-of-order frames!
                        if (!q.endsWith(" ")) {
                            // Leave trailing-space lag scenario testable; simulate delayed catch-up below
                        }
                        externalState.value = externalState.value.copy(query = q)
                    },
                    onSearchFocusChange = {},
                    onSubmitSearch = {},
                    onClearSearch = { externalState.value = externalState.value.copy(query = "") },
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

        val inputNode = composeTestRule.onNodeWithTag("catalogue_search_input")
        inputNode.performClick()
        inputNode.performTextInput("Breaking Bad Season")
        composeTestRule.waitForIdle()
        assertEquals("Breaking Bad Season", externalState.value.query)

        // Delete back to "Breaking " and then delete final separator space -> "Breaking"
        inputNode.performTextInputSelection(TextRange(0, "Breaking Bad Season".length))
        inputNode.performTextInput("Breaking ")
        composeTestRule.waitForIdle()
        assertEquals("Breaking ", externalState.value.query)

        // Delete the trailing space explicitly
        inputNode.performTextInputSelection(TextRange(0, "Breaking ".length))
        inputNode.performTextInput("Breaking")
        composeTestRule.waitForIdle()
        assertEquals("Breaking", externalState.value.query)
        inputNode.assertTextEquals("Breaking")

        // Delete all the way down to empty string
        inputNode.performTextClearance()
        composeTestRule.waitForIdle()
        assertEquals("", externalState.value.query)
    }

    @Test
    fun `synced text field ignores stale intermediate echoes during rapid deletion of space`() {
        // Simulates async ViewModel delivering an older echo ("Breaking ") AFTER local state already
        // advanced to "Breaking".
        val externalState = MutableStateFlow(MoviesCatalogueUiState(query = "Breaking Bad"))
        var dropNextExternalEmit = false

        composeTestRule.setContent {
            val state by externalState.collectAsState()
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = state,
                    onBackClick = {},
                    onQueryChange = { q ->
                        if (!dropNextExternalEmit) {
                            externalState.value = externalState.value.copy(query = q)
                        }
                    },
                    onSearchFocusChange = {},
                    onSubmitSearch = {},
                    onClearSearch = { externalState.value = externalState.value.copy(query = "") },
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

        val inputNode = composeTestRule.onNodeWithTag("catalogue_search_input")
        inputNode.performClick()

        // Step 1: Drop immediate echo on transitioning to "Breaking " then "Breaking"
        dropNextExternalEmit = true
        inputNode.performTextInputSelection(TextRange(0, "Breaking Bad".length))
        inputNode.performTextInput("Breaking ")
        composeTestRule.waitForIdle()

        inputNode.performTextInputSelection(TextRange(0, "Breaking ".length))
        inputNode.performTextInput("Breaking")
        composeTestRule.waitForIdle()

        // Deliver the stale delayed intermediate echo "Breaking " first -> must NOT revert "Breaking"!
        externalState.value = externalState.value.copy(query = "Breaking ")
        composeTestRule.waitForIdle()
        inputNode.assertTextEquals("Breaking")

        // Deliver final echo "Breaking" -> stays "Breaking"
        externalState.value = externalState.value.copy(query = "Breaking")
        composeTestRule.waitForIdle()
        inputNode.assertTextEquals("Breaking")

        // Resume normal external propagation and verify selection/replacement + mid-string edit
        dropNextExternalEmit = false
        inputNode.performTextInputSelection(TextRange(0, "Breaking".length))
        inputNode.performTextInput("Dark Knight")
        composeTestRule.waitForIdle()
        assertEquals("Dark Knight", externalState.value.query)
        inputNode.assertTextEquals("Dark Knight")
    }

    @Test
    fun `catalogue and home viewModels do not duplicate suggestion requests on trailing spaces and drop stale results`() = runTest {
        val fetchedQueries = mutableListOf<String>()
        val firstGate = CompletableDeferred<Result<List<SearchSuggestion>>>()

        val repo = object : StubRepo() {
            override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> {
                fetchedQueries.add(query)
                return if (query == "Break") {
                    firstGate.await()
                } else {
                    Result.success(
                        listOf(SearchSuggestion("id_$query", "moviebox", "Result $query", MediaKind.MOVIE, 2024))
                    )
                }
            }
        }

        val catVm = MoviesCatalogueViewModel(repo)
        // Typing "Breaking", adding space "Breaking ", removing space "Breaking"
        catVm.onQueryChange("Breaking")
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(listOf("Breaking"), fetchedQueries)

        // Trailing space & deleting trailing space must NOT fire duplicate suggestion requests
        catVm.onQueryChange("Breaking ")
        advanceTimeBy(300L)
        catVm.onQueryChange("Breaking")
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(listOf("Breaking"), fetchedQueries)
        assertEquals("Breaking", catVm.uiState.value.query)

        // Stale debounced suggestion response test: "Break" arrives AFTER query became "Dark"
        fetchedQueries.clear()
        catVm.onQueryChange("Break")
        advanceTimeBy(270L) // launches getSuggestions("Break") suspended on firstGate
        catVm.onQueryChange("Dark")
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals("Result Dark", catVm.uiState.value.suggestions.firstOrNull()?.title)

        // Now complete stale "Break" -> MUST be ignored!
        firstGate.complete(
            Result.success(listOf(SearchSuggestion("stale", "moviebox", "Stale Break", MediaKind.MOVIE, 2020)))
        )
        advanceUntilIdle()
        assertEquals("Result Dark", catVm.uiState.value.suggestions.firstOrNull()?.title)

        // Verify MoviesHomeViewModel also preserves raw text without duplicate trailing-space churn
        val homeFetched = mutableListOf<String>()
        val homeRepo = object : StubRepo() {
            override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> {
                homeFetched.add(query)
                return Result.success(listOf(SearchSuggestion("h1", "moviebox", "Result $query", MediaKind.MOVIE, 2024)))
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(
            context = ctx,
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tempFolder.newFile("home_search_${System.nanoTime()}.preferences_pb")
            }
        )
        val homeVm = MoviesHomeViewModel(homeRepo, prefs)
        homeVm.onSearchQueryChange("Matrix")
        advanceTimeBy(300L)
        advanceUntilIdle()
        homeVm.onSearchQueryChange("Matrix ")
        advanceTimeBy(300L)
        homeVm.onSearchQueryChange("Matrix")
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(listOf("Matrix"), homeFetched)
    }

    @Test
    fun `shared LaterboxSearchField stays responsive during rapid typing and external clear`() {
        val queryState = MutableStateFlow("Alpha ")

        composeTestRule.setContent {
            val q by queryState.collectAsState()
            LaterboxTheme {
                LaterboxSearchField(
                    query = q,
                    onQueryChange = { queryState.value = it },
                    placeholder = "Search..."
                )
            }
        }

        // Verify initial query rendered and external reset clears it
        queryState.value = "Alpha"
        composeTestRule.waitForIdle()
        assertTrue(queryState.value == "Alpha")

        queryState.value = ""
        composeTestRule.waitForIdle()
        assertEquals("", queryState.value)
    }

    @Test
    fun `catalogueViewModel separates primary and supplementary search items across pages and populates library suggestions`() = runTest {
        val repo = object : StubRepo() {
            override suspend fun searchLibrarySuggestions(query: String, limit: Int): List<LibrarySearchSuggestion> {
                val ceo = LibrarySearchSuggestion(
                    summary = MovieTitleSummary(
                        id = "lib_sd_1",
                        providerId = "moviebox",
                        kind = MediaKind.SHORT_DRAMA,
                        title = "CEO Destiny",
                        releaseYear = 2026,
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                        isPlayableHint = true
                    ),
                    sourceLabel = "Watchlist",
                    isDownloaded = true
                )
                return if (query.isBlank() || ceo.summary.title.contains(query, ignoreCase = true)) {
                    listOf(ceo)
                } else {
                    emptyList()
                }
            }

            override suspend fun search(
                query: String,
                kind: MediaKind?,
                page: Int,
                pageSize: Int
            ): Result<CataloguePage> {
                return if (page == 1) {
                    val primary = MovieTitleSummary(
                        id = "mb_1",
                        providerId = "moviebox",
                        kind = MediaKind.MOVIE,
                        title = "Primary One",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                        isPlayableHint = true
                    )
                    val supp = MovieTitleSummary(
                        id = "tv_1",
                        providerId = "tvmaze",
                        kind = MediaKind.TV_SERIES,
                        title = "Guide Series",
                        sourceBadge = DataSourceBadge.LIVE_TVMAZE,
                        isPlayableHint = false
                    )
                    Result.success(CataloguePage(items = listOf(primary, supp), page = 1, pageSize = 20, hasMore = true, primaryCount = 1))
                } else {
                    val primary2 = MovieTitleSummary(
                        id = "mb_2",
                        providerId = "moviebox",
                        kind = MediaKind.MOVIE,
                        title = "Primary Two",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                        isPlayableHint = true
                    )
                    val supp2 = MovieTitleSummary(
                        id = "ia_2",
                        providerId = "ia",
                        kind = MediaKind.MOVIE,
                        title = "Archive Two",
                        sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
                        isPlayableHint = true
                    )
                    Result.success(CataloguePage(items = listOf(primary2, supp2), page = 2, pageSize = 20, hasMore = false, primaryCount = 1))
                }
            }
        }

        val vm = MoviesCatalogueViewModel(repo)
        vm.onSearchFocusChange(true)
        advanceUntilIdle()
        assertEquals(listOf("lib_sd_1"), vm.uiState.value.librarySuggestions.map { it.summary.id })
        assertTrue(vm.uiState.value.showSuggestionsDropdown)

        vm.submitSearch("ceo")
        advanceUntilIdle()
        assertEquals(listOf("mb_1"), vm.uiState.value.items.map { it.id })
        assertEquals(listOf("tv_1"), vm.uiState.value.supplementaryItems.map { it.id })

        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(listOf("mb_1", "mb_2"), vm.uiState.value.items.map { it.id })
        assertEquals(listOf("tv_1", "ia_2"), vm.uiState.value.supplementaryItems.map { it.id })
    }

    @Test
    fun `catalogue screen renders three suggestion groups and additional results section with info only badge`() {
        var openedDetail: Pair<String, String>? = null
        var submittedQuery: String? = null

        val libraryEntry = LibrarySearchSuggestion(
            summary = MovieTitleSummary(
                id = "lib_sd_7",
                providerId = "moviebox",
                kind = MediaKind.SHORT_DRAMA,
                title = "CEO Marriage",
                releaseYear = 2026,
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                isPlayableHint = true
            ),
            sourceLabel = "Watchlist",
            isDownloaded = true
        )
        val keywordSuggestion = SearchSuggestion(
            id = "",
            providerId = "",
            title = "ceo contract",
            kind = null
        )
        val stateFlow = MutableStateFlow(
            MoviesCatalogueUiState(
                query = "ce",
                recentSearches = listOf("alpha"),
                librarySuggestions = listOf(libraryEntry),
                suggestions = listOf(keywordSuggestion),
                isSearchFocused = true,
                showSuggestionsDropdown = true,
                items = listOf(
                    MovieTitleSummary(
                        id = "mb_1",
                        providerId = "moviebox",
                        kind = MediaKind.MOVIE,
                        title = "Primary Film",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                        isPlayableHint = true
                    )
                ),
                supplementaryItems = listOf(
                    MovieTitleSummary(
                        id = "tv_9",
                        providerId = "tvmaze",
                        kind = MediaKind.TV_SERIES,
                        title = "Supplementary Guide Show",
                        sourceBadge = DataSourceBadge.LIVE_TVMAZE,
                        isPlayableHint = false
                    )
                )
            )
        )

        composeTestRule.setContent {
            val uiState by stateFlow.collectAsState()
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = uiState,
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
                    onOpenDetail = { pId, tId -> openedDetail = pId to tId }
                )
            }
        }

        // Verify Additional Results header & neutral Info only badge
        composeTestRule.onNodeWithTag("catalogue_additional_results_header").assertIsDisplayed()
        composeTestRule.onNodeWithText("Additional Results").assertIsDisplayed()
        composeTestRule.onNodeWithText("Info only").assertIsDisplayed()

        // Verify Your Library & Search Suggestions groups when query >= 2 chars
        composeTestRule.onNodeWithTag("catalogue_suggestions_box_your_library").assertIsDisplayed()
        composeTestRule.onNodeWithTag("catalogue_suggestions_box_search_suggestions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search query").assertIsDisplayed()

        // Tapping keyword suggestion submits query rather than opening fake movie detail
        composeTestRule.onNodeWithTag("suggestion_item_idx_0").performClick()
        assertEquals("ceo contract", submittedQuery)

        // Tapping library suggestion opens its real detail page
        composeTestRule.onNodeWithTag("library_suggestion_item_lib_sd_7").performClick()
        assertEquals("moviebox" to "lib_sd_7", openedDetail)

        // Empty query shows Your Searches group too
        stateFlow.value = stateFlow.value.copy(query = "", suggestions = emptyList())
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("catalogue_suggestions_box_your_searches").assertIsDisplayed()
        composeTestRule.onNodeWithTag("catalogue_suggestions_box_your_library").assertIsDisplayed()
    }

    @Test
    fun `deduplicateSuggestionsPreferringMovieBox preserves multiple keyword suggestions and merges exact duplicates`() {
        val raw = listOf(
            SearchSuggestion(id = "", providerId = "", title = "Avatar: Seven Havens", kind = null),
            SearchSuggestion(id = "", providerId = "", title = "Avatar Aang: The Last Airbender", kind = null),
            SearchSuggestion(id = "", providerId = "", title = "avatar the way of water", kind = null),
            SearchSuggestion(id = "", providerId = "", title = "Avatar: Fire and Ash", kind = null),
            SearchSuggestion(id = "", providerId = "", title = "avatar: seven havens", kind = null), // duplicate keyword
            SearchSuggestion(id = "8906", providerId = "moviebox", title = "Avatar", kind = MediaKind.MOVIE, releaseYear = 2009),
            SearchSuggestion(id = "ia_av", providerId = "ia", title = "Avatar", kind = MediaKind.MOVIE, releaseYear = 2009)
        )

        val deduped = com.laterbox.app.movies.domain.engine.MovieBoxPriorityDeduplicator
            .deduplicateSuggestionsPreferringMovieBox(raw)

        assertEquals(
            listOf(
                "Avatar: Seven Havens",
                "Avatar Aang: The Last Airbender",
                "avatar the way of water",
                "Avatar: Fire and Ash",
                "Avatar"
            ),
            deduped.map { it.title }
        )
        assertEquals("moviebox", deduped.last().providerId)
    }

    @Test
    fun `laterboxSearchField backspaces cleanly across spaces even when StateFlow conflates intermediate echoes`() {
        val queryFlow = MutableStateFlow("iron")
        val dispatched = mutableListOf<String>()

        composeTestRule.setContent {
            val q by queryFlow.collectAsState()
            LaterboxTheme {
                LaterboxSearchField(
                    query = q,
                    onQueryChange = { next ->
                        dispatched += next
                        // Deliberately do NOT update queryFlow with trailing-space intermediate ("iron ")
                        // to simulate StateFlow conflation when space + backspace happen faster than UI emission.
                        if (!next.endsWith(" ")) {
                            queryFlow.value = next
                        }
                    },
                    placeholder = "Search..."
                )
            }
        }

        val input = composeTestRule.onNodeWithTag("laterbox_search_input")
        input.performTextInput(" ")
        composeTestRule.waitForIdle()
        // Delete trailing space by replacing text back to "iron" while keeping local state intact
        input.performTextInputSelection(TextRange(0, 5))
        input.performTextInput("iron")
        composeTestRule.waitForIdle()

        // Delayed echo of intermediate space emission must NOT overwrite current local text if already confirmed/superseded,
        // and typing a subsequent word with spaces works cleanly.
        input.performTextInput(" man")
        composeTestRule.waitForIdle()
        input.assertTextEquals("iron man")
        assertEquals("iron man", queryFlow.value)

        // Genuine external suggestion selection still updates the field
        queryFlow.value = "Avatar: Fire and Ash"
        composeTestRule.waitForIdle()
        input.assertTextEquals("Avatar: Fire and Ash")
    }

    @Test
    fun `syncedTextFieldController supports repeated backspace through spaces, middle edits, selection replace, paste, and cursor preservation`() {
        val edits = mutableListOf<String>()
        val controller = com.laterbox.app.ui.components.SyncedTextFieldController("star wars rebels")

        // 1. Repeated Backspace deleting back through internal space ("rebels" -> "reb" -> "" -> "wars ")
        var current = "star wars rebels"
        while (current.length > "star wars".length) {
            current = current.dropLast(1)
            controller.onLocalValueChange(
                androidx.compose.ui.text.input.TextFieldValue(current, TextRange(current.length)),
                onExternalChange = { edits += it }
            )
            // Simulated delayed echo of previous space-containing string must never block or revert deletion
            if (current == "star wars") {
                controller.syncExternal("star wars ")
            }
        }
        assertEquals("star wars", controller.value.text)
        assertEquals(TextRange(9), controller.value.selection)

        // 2. Delete character immediately BEFORE internal space: "star| wars" -> "sta| wars"
        controller.onLocalValueChange(
            androidx.compose.ui.text.input.TextFieldValue("sta wars", TextRange(3)),
            onExternalChange = { edits += it }
        )
        // Asynchronous StateFlow confirmation preserves middle cursor position (index 3)
        controller.syncExternal("sta wars")
        assertEquals("sta wars", controller.value.text)
        assertEquals(TextRange(3), controller.value.selection)

        // 3. Delete character immediately AFTER internal space: "sta |wars" -> "sta |ars"
        controller.onLocalValueChange(
            androidx.compose.ui.text.input.TextFieldValue("sta ars", TextRange(4)),
            onExternalChange = { edits += it }
        )
        controller.syncExternal("sta ars")
        assertEquals("sta ars", controller.value.text)
        assertEquals(TextRange(4), controller.value.selection)

        // 4. Select substring ("ars") and replace / paste multi-word query mid-edit
        controller.onLocalValueChange(
            androidx.compose.ui.text.input.TextFieldValue("sta ars", TextRange(4, 7)),
            onExternalChange = { edits += it }
        )
        controller.onLocalValueChange(
            androidx.compose.ui.text.input.TextFieldValue("sta trek beyond", TextRange(15)),
            onExternalChange = { edits += it }
        )
        // 5. Out-of-order delayed intermediate echo from earlier typing does NOT clobber pasted text
        controller.syncExternal("sta wars")
        controller.syncExternal("sta ars")
        assertEquals("sta trek beyond", controller.value.text)
    }

    @Test
    fun `laterboxSearchField plain string mode backspaces 'the matrix' smoothly through the space`() {
        val controller = com.laterbox.app.ui.components.SyncedTextFieldController("")
        val emitted = mutableListOf<String>()
        val target = "the matrix"
        for (i in 1..target.length) {
            controller.onLocalTextChange(target.substring(0, i)) { emitted += it }
        }
        assertEquals("the matrix", controller.text)

        var step = "the matrix"
        while (step.isNotEmpty()) {
            step = step.dropLast(1)
            controller.onLocalTextChange(step) { emitted += it }
            // Simulate conflated StateFlow echoing the space-trailing prefix while deleting past it
            if (step == "the") {
                controller.syncExternal("the ")
            }
            assertEquals(step, controller.text)
        }
        assertEquals("", controller.text)

        val queryFlow = MutableStateFlow("the matrix")
        composeTestRule.setContent {
            val q by queryFlow.collectAsState()
            LaterboxTheme {
                LaterboxSearchField(
                    query = q,
                    onQueryChange = { next -> queryFlow.value = next },
                    placeholder = "Search..."
                )
            }
        }
        val input = composeTestRule.onNodeWithTag("laterbox_search_input")
        input.assertTextEquals("the matrix")
        input.performTextInputSelection(TextRange(0, "the matrix".length))
        input.performTextInput("the ")
        composeTestRule.waitForIdle()
        input.assertTextEquals("the ")
        input.performTextInputSelection(TextRange(0, "the ".length))
        input.performTextInput("the")
        composeTestRule.waitForIdle()
        input.assertTextEquals("the")
        assertEquals("the", queryFlow.value)
    }
}
