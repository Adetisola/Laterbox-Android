package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.BuildConfig
import com.laterbox.app.data.preferences.MovieBoxEndpointConfig
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.CollectionWithCount
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.domain.engine.MoviesDiscoveryEngine
import com.laterbox.app.movies.domain.model.BecauseYouWatchedGroup
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.movies.domain.model.RuntimeBucket
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SurprisePickResult
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoviesHomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val feed: MoviesHomeFeed? = null,
    val topRatedRail: List<MovieTitleSummary> = emptyList(),
    val newestRail: List<MovieTitleSummary> = emptyList(),
    val watchlist: List<MovieTitleSummary> = emptyList(),
    val continueWatching: List<WatchHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val movieBoxApiBaseUrl: String = "",
    val effectiveMovieBoxBaseUrl: String = BuildConfig.MOVIEBOX_API_BASE_URL,
    val useSampleFixturesOnly: Boolean = false,
    // Inline Search, Suggestions & Recent Searches
    val searchQuery: String = "",
    val activeSearchQuery: String = "",
    val isSearchFocused: Boolean = false,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val suggestionsError: String? = null,
    val recentSearches: List<String> = emptyList(),
    // Inline Filter & Sort state
    val selectedKind: MediaKind? = null,
    val selectedGenre: String = "All",
    val selectedCountry: String = "All",
    val activeShortcut: MoviesDiscoveryShortcut? = null,
    val availableCountries: List<String> = listOf("All") + MovieBoxProvider.VERIFIED_COUNTRIES,
    val selectedSort: CatalogueSort = CatalogueSort.POPULAR,
    val filteredItems: List<MovieTitleSummary> = emptyList(),
    val filteredPage: Int = 1,
    val filteredHasMore: Boolean = false,
    val isLoadingFiltered: Boolean = false,
    val isLoadingMoreFiltered: Boolean = false,
    val filteredError: String? = null,
    // Personal Library & Discovery Engine
    val recentlyViewed: List<MovieTitleSummary> = emptyList(),
    val collectionsWithCounts: List<CollectionWithCount> = emptyList(),
    val becauseYouWatchedGroups: List<BecauseYouWatchedGroup> = emptyList(),
    val quickRuntimePicks: List<MovieTitleSummary> = emptyList(),
    val recentlyAddedRail: List<MovieTitleSummary> = emptyList(),
    val surprisePick: SurprisePickResult? = null,
    val offlineMatches: List<OfflineMediaMatch> = emptyList(),
    val hiddenMediaKeys: Set<String> = emptySet(),
    val availableLanguages: List<String> = listOf("All"),
    val selectedLanguage: String = "All",
    val selectedYear: String = "All",
    val selectedRuntimeBucket: RuntimeBucket = RuntimeBucket.ALL,
    val actionFeedbackMessage: String? = null
) {
    val collections: List<Collection>
        get() = collectionsWithCounts.map { it.collection }

    val isFilteredMode: Boolean
        get() = selectedKind != null ||
            selectedGenre != "All" ||
            selectedCountry != "All" ||
            selectedLanguage != "All" ||
            selectedYear != "All" ||
            selectedRuntimeBucket != RuntimeBucket.ALL ||
            activeShortcut != null ||
            selectedSort != CatalogueSort.POPULAR ||
            activeSearchQuery.isNotBlank()

    val showSearchDropdown: Boolean
        get() = isSearchFocused && (
            searchQuery.trim().length >= 2 ||
                isLoadingSuggestions ||
                suggestionsError != null ||
                recentSearches.isNotEmpty()
            )
}

private data class MoviesConfigSnapshot(
    val useSampleFixturesOnly: Boolean,
    val movieBoxBaseUrl: String
)

class MoviesHomeViewModel(
    private val moviesRepository: MoviesRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val collectionRepository: CollectionRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesHomeUiState())
    val uiState: StateFlow<MoviesHomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var suggestJob: Job? = null
    private var suggestRequestSeq: Long = 0L
    private var lastConfigKey: MoviesConfigSnapshot? = null
    private var latestNonTrailerHistory: List<WatchHistoryItem> = emptyList()
    private var rawFilteredItems: List<MovieTitleSummary> = emptyList()
    private var rawWatchlist: List<MovieTitleSummary> = emptyList()
    private var rawRecentlyViewed: List<MovieTitleSummary> = emptyList()
    private var surpriseSeed: Long = 101L
    private var surprisePreferredKind: MediaKind? = null

    init {
        viewModelScope.launch {
            moviesRepository.observeWatchlist().collect { list ->
                rawWatchlist = list
                _uiState.update { current ->
                    val hidden = current.hiddenMediaKeys
                    applyDiscovery(
                        current.copy(watchlist = list.filterNot { m -> m.stableMediaKey in hidden })
                    )
                }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeContinueWatching(12).collect { history ->
                _uiState.update { it.copy(continueWatching = history) }
            }
        }
        viewModelScope.launch {
            combine(
                moviesRepository.observeMovieWatchHistory(30),
                moviesRepository.observeEpisodeWatchHistory(30)
            ) { movies, episodes ->
                (movies + episodes).sortedByDescending { it.lastWatchedAt }
            }.collect { combined ->
                latestNonTrailerHistory = combined
                recomputeDiscovery()
            }
        }
        viewModelScope.launch {
            moviesRepository.observeRecentlyViewed(20).collect { list ->
                rawRecentlyViewed = list
                _uiState.update { current ->
                    val hidden = current.hiddenMediaKeys
                    applyDiscovery(
                        current.copy(recentlyViewed = list.filterNot { m -> m.stableMediaKey in hidden })
                    )
                }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeOfflineAvailability().collect { matches ->
                _uiState.update { it.copy(offlineMatches = matches) }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeHiddenHistory(100).collect { hiddenList ->
                val keys = hiddenList.map { it.stableMediaKey }.toSet()
                _uiState.update { current ->
                    val filtered = MoviesDiscoveryEngine.filterTitles(
                        items = rawFilteredItems,
                        kind = current.selectedKind,
                        genre = current.selectedGenre,
                        language = current.selectedLanguage,
                        country = current.selectedCountry,
                        year = current.selectedYear,
                        runtimeBucket = current.selectedRuntimeBucket,
                        hiddenMediaKeys = keys
                    )
                    applyDiscovery(
                        current.copy(
                            hiddenMediaKeys = keys,
                            watchlist = rawWatchlist.filterNot { it.stableMediaKey in keys },
                            recentlyViewed = rawRecentlyViewed.filterNot { it.stableMediaKey in keys },
                            filteredItems = filtered
                        )
                    )
                }
            }
        }
        if (collectionRepository != null) {
            viewModelScope.launch {
                collectionRepository.collectionsWithCounts.collect { cols ->
                    _uiState.update { it.copy(collectionsWithCounts = cols) }
                }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeRecentSearches(8).collect { recent ->
                _uiState.update { it.copy(recentSearches = recent) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.migrateLegacyMoviesPreferences()
            userPreferencesRepository.userPreferencesFlow
                .map { prefs ->
                    MoviesConfigSnapshot(
                        useSampleFixturesOnly = BuildConfig.DEBUG && prefs.moviesUseSampleFixturesOnly,
                        movieBoxBaseUrl = prefs.movieBoxApiBaseUrl
                    )
                }
                .distinctUntilChanged()
                .collect { config ->
                    val changed = lastConfigKey != null && lastConfigKey != config
                    lastConfigKey = config
                    val effectiveBase = config.movieBoxBaseUrl.ifBlank { BuildConfig.MOVIEBOX_API_BASE_URL }
                    _uiState.update {
                        it.copy(
                            movieBoxApiBaseUrl = config.movieBoxBaseUrl,
                            effectiveMovieBoxBaseUrl = effectiveBase,
                            useSampleFixturesOnly = config.useSampleFixturesOnly,
                            isRefreshing = if (changed && it.feed != null) true else it.isRefreshing
                        )
                    }
                    if (changed || _uiState.value.feed == null) {
                        loadHome(forceRefresh = changed)
                        if (changed && _uiState.value.isFilteredMode) {
                            loadFiltered(page = 1, append = false)
                        }
                    }
                }
        }
    }

    fun refresh() {
        loadHome(forceRefresh = true)
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        }
    }

    fun loadHome(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val hasExisting = _uiState.value.feed != null
            _uiState.update {
                it.copy(
                    isLoading = !hasExisting,
                    isRefreshing = hasExisting && forceRefresh,
                    errorMessage = null
                )
            }
            val (result, topRated, newest) = coroutineScope {
                val homeDeferred = async { moviesRepository.getHomeFeed(forceRefresh = forceRefresh) }
                val topRatedDeferred = async {
                    moviesRepository.getCatalogue(
                        kind = null,
                        page = 1,
                        pageSize = 12,
                        sort = CatalogueSort.TOP_RATED
                    ).getOrNull()?.items.orEmpty()
                }
                val newestDeferred = async {
                    moviesRepository.getCatalogue(
                        kind = null,
                        page = 1,
                        pageSize = 12,
                        sort = CatalogueSort.NEWEST
                    ).getOrNull()?.items.orEmpty()
                }
                Triple(homeDeferred.await(), topRatedDeferred.await(), newestDeferred.await())
            }
            result.fold(
                onSuccess = { feed ->
                    _uiState.update {
                        applyDiscovery(
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                feed = feed,
                                topRatedRail = topRated.distinctBy { m -> m.compositeId }.ifEmpty { it.topRatedRail },
                                newestRail = newest.distinctBy { m -> m.compositeId }.ifEmpty { it.newestRail },
                                errorMessage = null
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Could not load Movies & TV discovery feed."
                        )
                    }
                }
            )
        }
    }

    private fun normalizeSearchQuery(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    fun onSearchQueryChange(newQuery: String) {
        val normalized = normalizeSearchQuery(newQuery)
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                isSearchFocused = true,
                isLoadingSuggestions = normalized.length >= 2,
                suggestionsError = null
            )
        }
        val reqSeq = ++suggestRequestSeq
        suggestJob?.cancel()
        if (normalized.length < 2) {
            _uiState.update {
                it.copy(
                    suggestions = emptyList(),
                    isLoadingSuggestions = false,
                    suggestionsError = null
                )
            }
            if (normalized.isEmpty() && _uiState.value.activeSearchQuery.isNotEmpty()) {
                _uiState.update { it.copy(activeSearchQuery = "") }
                if (_uiState.value.isFilteredMode) {
                    loadFiltered(page = 1, append = false)
                }
            }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(260L)
            if (reqSeq != suggestRequestSeq || normalizeSearchQuery(_uiState.value.searchQuery) != normalized) {
                return@launch
            }
            moviesRepository.getSuggestions(normalized, limit = 8).fold(
                onSuccess = { list ->
                    if (reqSeq != suggestRequestSeq || normalizeSearchQuery(_uiState.value.searchQuery) != normalized) {
                        return@fold
                    }
                    _uiState.update {
                        it.copy(
                            suggestions = list,
                            isLoadingSuggestions = false,
                            suggestionsError = null
                        )
                    }
                },
                onFailure = { err ->
                    if (reqSeq != suggestRequestSeq || normalizeSearchQuery(_uiState.value.searchQuery) != normalized) {
                        return@fold
                    }
                    _uiState.update {
                        it.copy(
                            suggestions = emptyList(),
                            isLoadingSuggestions = false,
                            suggestionsError = err.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Couldn't load suggestions"
                        )
                    }
                }
            )
        }
    }

    fun setSearchFocused(focused: Boolean) {
        if (!focused) {
            ++suggestRequestSeq
            suggestJob?.cancel()
        }
        _uiState.update {
            it.copy(
                isSearchFocused = focused,
                isLoadingSuggestions = if (focused) it.isLoadingSuggestions else false
            )
        }
    }

    fun submitInlineSearch(overrideQuery: String? = null) {
        val q = normalizeSearchQuery(overrideQuery ?: _uiState.value.searchQuery)
        ++suggestRequestSeq
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = q,
                activeSearchQuery = q,
                isSearchFocused = false,
                isLoadingSuggestions = false,
                suggestionsError = null,
                suggestions = emptyList()
            )
        }
        if (q.isNotBlank()) {
            viewModelScope.launch {
                moviesRepository.recordSearchQuery(q)
            }
        }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        }
    }

    fun clearInlineSearch() {
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = "",
                activeSearchQuery = "",
                suggestions = emptyList(),
                isLoadingSuggestions = false,
                suggestionsError = null,
                isSearchFocused = false
            )
        }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            moviesRepository.clearSearchHistory()
        }
    }

    fun selectKind(kind: MediaKind?) {
        if (_uiState.value.selectedKind == kind) return
        suggestJob?.cancel()
        _uiState.update { current ->
            val matchedShortcut = matchingShortcut(kind, current.selectedGenre, current.selectedCountry)
            current.copy(
                selectedKind = kind,
                activeShortcut = matchedShortcut,
                isSearchFocused = false,
                isLoadingSuggestions = false
            )
        }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectGenre(genre: String) {
        if (_uiState.value.selectedGenre == genre) return
        suggestJob?.cancel()
        _uiState.update { current ->
            val matchedShortcut = matchingShortcut(current.selectedKind, genre, current.selectedCountry)
            current.copy(
                selectedGenre = genre,
                activeShortcut = matchedShortcut,
                isSearchFocused = false,
                isLoadingSuggestions = false
            )
        }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectCountry(country: String) {
        if (_uiState.value.selectedCountry == country) return
        suggestJob?.cancel()
        _uiState.update { current ->
            val matchedShortcut = matchingShortcut(current.selectedKind, current.selectedGenre, country)
            current.copy(
                selectedCountry = country,
                activeShortcut = matchedShortcut,
                isSearchFocused = false,
                isLoadingSuggestions = false
            )
        }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectLanguage(language: String) {
        if (_uiState.value.selectedLanguage == language) return
        suggestJob?.cancel()
        _uiState.update { it.copy(selectedLanguage = language, isSearchFocused = false, isLoadingSuggestions = false) }
        if (_uiState.value.isFilteredMode) {
            if (rawFilteredItems.isEmpty()) {
                loadFiltered(page = 1, append = false)
            } else {
                applyClientFiltersToFilteredItems()
            }
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectYear(year: String) {
        if (_uiState.value.selectedYear == year) return
        suggestJob?.cancel()
        _uiState.update { it.copy(selectedYear = year, isSearchFocused = false, isLoadingSuggestions = false) }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectRuntimeBucket(bucket: RuntimeBucket) {
        if (_uiState.value.selectedRuntimeBucket == bucket) return
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                selectedRuntimeBucket = bucket,
                isSearchFocused = false,
                isLoadingSuggestions = false
            )
        }
        if (_uiState.value.isFilteredMode) {
            if (rawFilteredItems.isEmpty()) {
                loadFiltered(page = 1, append = false)
            } else {
                applyClientFiltersToFilteredItems()
            }
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectShortcut(shortcut: MoviesDiscoveryShortcut) {
        suggestJob?.cancel()
        val isTogglingOff = _uiState.value.activeShortcut == shortcut
        _uiState.update {
            if (isTogglingOff) {
                it.copy(
                    activeShortcut = null,
                    selectedKind = null,
                    selectedGenre = "All",
                    selectedCountry = "All",
                    isSearchFocused = false,
                    isLoadingSuggestions = false
                )
            } else {
                it.copy(
                    activeShortcut = shortcut,
                    selectedKind = shortcut.kind,
                    selectedGenre = shortcut.genre ?: "All",
                    selectedCountry = shortcut.country ?: "All",
                    isSearchFocused = false,
                    isLoadingSuggestions = false
                )
            }
        }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectSort(sort: CatalogueSort) {
        if (_uiState.value.selectedSort == sort) return
        suggestJob?.cancel()
        _uiState.update { it.copy(selectedSort = sort, isSearchFocused = false, isLoadingSuggestions = false) }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            rawFilteredItems = emptyList()
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun resetFilters() {
        suggestJob?.cancel()
        filterJob?.cancel()
        rawFilteredItems = emptyList()
        _uiState.update {
            it.copy(
                searchQuery = "",
                activeSearchQuery = "",
                isSearchFocused = false,
                suggestions = emptyList(),
                isLoadingSuggestions = false,
                suggestionsError = null,
                selectedKind = null,
                selectedGenre = "All",
                selectedCountry = "All",
                selectedLanguage = "All",
                selectedYear = "All",
                selectedRuntimeBucket = RuntimeBucket.ALL,
                activeShortcut = null,
                selectedSort = CatalogueSort.POPULAR,
                filteredItems = emptyList(),
                filteredPage = 1,
                filteredHasMore = false,
                isLoadingFiltered = false,
                isLoadingMoreFiltered = false,
                filteredError = null
            )
        }
    }

    fun rerollSurpriseMe(preferredKind: MediaKind? = surprisePreferredKind) {
        surprisePreferredKind = preferredKind
        surpriseSeed = surpriseSeed * 6364136223846793005L + 1442695040888963407L + System.nanoTime()
        recomputeDiscovery()
    }

    fun markWatched(item: MovieTitleSummary) {
        viewModelScope.launch {
            moviesRepository.markWatched(item = item, durationMs = (item.durationMinutes ?: 90) * 60_000L)
            _uiState.update { it.copy(actionFeedbackMessage = "Marked ${item.title} as watched") }
        }
    }

    fun hideTitle(item: MovieTitleSummary) {
        viewModelScope.launch {
            moviesRepository.setTitleHidden(item.providerId, item.id, true)
            _uiState.update { it.copy(actionFeedbackMessage = "Hidden ${item.title}") }
        }
    }

    fun restartFromBeginning(entry: WatchHistoryItem) {
        viewModelScope.launch {
            moviesRepository.restartFromBeginning(
                providerId = entry.providerId,
                titleId = entry.titleId,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber
            )
            _uiState.update { it.copy(actionFeedbackMessage = "Reset progress for ${entry.title}") }
        }
    }

    fun removeFromContinueWatching(entry: WatchHistoryItem) {
        viewModelScope.launch {
            moviesRepository.removeFromContinueWatching(entry.providerId, entry.titleId)
            _uiState.update { it.copy(actionFeedbackMessage = "Removed ${entry.title} from Continue Watching") }
        }
    }

    fun removeRecentlyViewed(item: MovieTitleSummary) {
        viewModelScope.launch {
            moviesRepository.removeRecentlyViewed(item.providerId, item.id)
            _uiState.update { it.copy(actionFeedbackMessage = "Removed ${item.title} from Viewed") }
        }
    }

    fun markHistoryEntryWatched(entry: WatchHistoryItem) {
        viewModelScope.launch {
            val summary = MovieTitleSummary(
                id = entry.titleId,
                providerId = entry.providerId,
                kind = entry.kind,
                title = entry.title,
                posterUrl = entry.posterUrl,
                backdropUrl = entry.backdropUrl,
                canonicalId = entry.canonicalId
            )
            moviesRepository.markWatched(
                item = summary,
                durationMs = entry.durationMs.coerceAtLeast(1_000L),
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
                episodeLabel = entry.episodeLabel
            )
            _uiState.update { it.copy(actionFeedbackMessage = "Marked ${entry.title} as watched") }
        }
    }

    fun addToCollection(item: MovieTitleSummary, collectionId: Long) {
        viewModelScope.launch {
            moviesRepository.addMediaToCollection(item, collectionId)
            val colName = _uiState.value.collections.firstOrNull { it.id == collectionId }?.name ?: "collection"
            _uiState.update { it.copy(actionFeedbackMessage = "Added ${item.title} to $colName") }
        }
    }

    fun createAndAddToCollection(item: MovieTitleSummary, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || collectionRepository == null) return
        viewModelScope.launch {
            val id = collectionRepository.createCollection(clean)
            moviesRepository.addMediaToCollection(item, id)
            _uiState.update { it.copy(actionFeedbackMessage = "Added ${item.title} to $clean") }
        }
    }

    fun clearActionFeedback() {
        _uiState.update { it.copy(actionFeedbackMessage = null) }
    }

    private fun applyDiscovery(state: MoviesHomeUiState): MoviesHomeUiState {
        val hidden = state.hiddenMediaKeys
        val candidatePool = (
            state.feed?.featuredBanners.orEmpty() +
                state.feed?.sections.orEmpty().flatMap { it.items } +
                state.topRatedRail +
                state.newestRail
            ).distinctBy { it.stableMediaKey }

        val byw = MoviesDiscoveryEngine.buildBecauseYouWatchedGroups(
            watchHistory = latestNonTrailerHistory,
            watchlist = rawWatchlist,
            candidatePool = candidatePool,
            hiddenMediaKeys = hidden
        )
        val quick = MoviesDiscoveryEngine.selectQuickRuntimePicks(
            items = candidatePool,
            hiddenMediaKeys = hidden
        )
        val recentAdded = MoviesDiscoveryEngine.selectRecentlyAdded(
            items = candidatePool,
            hiddenMediaKeys = hidden
        )
        val languages = MoviesDiscoveryEngine.extractAvailableLanguages(candidatePool)
        val surprise = MoviesDiscoveryEngine.pickSurpriseMe(
            watchHistory = latestNonTrailerHistory,
            recentlyViewed = rawRecentlyViewed,
            hiddenMediaKeys = hidden,
            watchlist = rawWatchlist,
            candidatePool = candidatePool,
            seed = surpriseSeed,
            preferredKind = surprisePreferredKind
        )

        return state.copy(
            becauseYouWatchedGroups = byw,
            quickRuntimePicks = quick,
            recentlyAddedRail = recentAdded,
            availableLanguages = languages,
            surprisePick = surprise
        )
    }

    private fun recomputeDiscovery() {
        _uiState.update { current -> applyDiscovery(current) }
    }

    private fun applyClientFiltersToFilteredItems() {
        val state = _uiState.value
        val filtered = MoviesDiscoveryEngine.filterTitles(
            items = rawFilteredItems,
            kind = state.selectedKind,
            genre = state.selectedGenre,
            language = state.selectedLanguage,
            country = state.selectedCountry,
            year = state.selectedYear,
            runtimeBucket = state.selectedRuntimeBucket,
            hiddenMediaKeys = state.hiddenMediaKeys
        )
        _uiState.update { it.copy(filteredItems = filtered) }
    }

    private fun matchingShortcut(
        kind: MediaKind?,
        genre: String,
        country: String
    ): MoviesDiscoveryShortcut? = MoviesDiscoveryShortcut.entries.firstOrNull { shortcut ->
        shortcut.kind == kind &&
            (shortcut.genre ?: "All").equals(genre, ignoreCase = true) &&
            (shortcut.country ?: "All").equals(country, ignoreCase = true)
    }

    fun loadMoreFiltered() {
        val current = _uiState.value
        if (!current.isFilteredMode || current.isLoadingFiltered || current.isLoadingMoreFiltered || !current.filteredHasMore) {
            return
        }
        loadFiltered(page = current.filteredPage + 1, append = true)
    }

    private fun loadFiltered(page: Int, append: Boolean) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            _uiState.update {
                if (append) {
                    it.copy(isLoadingMoreFiltered = true, filteredError = null)
                } else {
                    it.copy(isLoadingFiltered = true, isLoadingMoreFiltered = false, filteredError = null)
                }
            }
            val state = _uiState.value
            val query = state.activeSearchQuery.trim()
            val exactYearParam = state.selectedYear.takeIf { Regex("\\d{4}").matches(it) }
            val result = if (query.isNotBlank()) {
                moviesRepository.search(query = query, kind = state.selectedKind, page = page, pageSize = 20)
            } else {
                moviesRepository.getCatalogue(
                    kind = state.selectedKind,
                    page = page,
                    pageSize = 20,
                    genre = state.selectedGenre.takeUnless { it == "All" },
                    sort = state.selectedSort,
                    country = state.selectedCountry.takeUnless { it == "All" },
                    year = exactYearParam
                )
            }

            result.fold(
                onSuccess = { pageResult ->
                    val mergedRaw = if (append) {
                        (rawFilteredItems + pageResult.items).distinctBy { it.compositeId }
                    } else {
                        pageResult.items.distinctBy { it.compositeId }
                    }
                    rawFilteredItems = mergedRaw
                    val latest = _uiState.value
                    val filtered = MoviesDiscoveryEngine.filterTitles(
                        items = mergedRaw,
                        kind = latest.selectedKind,
                        genre = latest.selectedGenre,
                        language = latest.selectedLanguage,
                        country = latest.selectedCountry,
                        year = latest.selectedYear,
                        runtimeBucket = latest.selectedRuntimeBucket,
                        hiddenMediaKeys = latest.hiddenMediaKeys
                    )
                    _uiState.update { current ->
                        current.copy(
                            filteredItems = filtered,
                            filteredPage = page,
                            filteredHasMore = pageResult.hasMore,
                            isLoadingFiltered = false,
                            isLoadingMoreFiltered = false,
                            filteredError = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingFiltered = false,
                            isLoadingMoreFiltered = false,
                            filteredError = error.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Unable to load matching titles."
                        )
                    }
                }
            )
        }
    }

    fun toggleWatchlist(item: MovieTitleSummary) {
        viewModelScope.launch {
            moviesRepository.toggleWatchlist(item)
        }
    }

    fun setMovieBoxBaseUrl(baseUrl: String) {
        if (!MovieBoxEndpointConfig.isValidBaseUrl(baseUrl, BuildConfig.DEBUG)) return
        viewModelScope.launch {
            userPreferencesRepository.setMovieBoxApiBaseUrl(baseUrl)
        }
    }

    fun setSampleFixturesOnly(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            userPreferencesRepository.setMoviesUseSampleFixturesOnly(enabled)
        }
    }
}
