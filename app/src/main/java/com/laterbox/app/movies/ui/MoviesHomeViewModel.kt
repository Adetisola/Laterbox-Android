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
    val actionFeedbackMessage: String? = null,
    val actionErrorMessage: String? = null
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
    private val collectionRepository: CollectionRepository? = null,
    private val computeDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main.immediate
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesHomeUiState())
    val uiState: StateFlow<MoviesHomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var suggestJob: Job? = null
    private var discoveryJob: Job? = null
    private var suggestRequestSeq: Long = 0L
    private var lastConfigKey: MoviesConfigSnapshot? = null
    private var latestNonTrailerHistory: List<WatchHistoryItem> = emptyList()
    private var rawFilteredItems: List<MovieTitleSummary> = emptyList()
    private var rawWatchlist: List<MovieTitleSummary> = emptyList()
    private var rawContinueWatching: List<WatchHistoryItem> = emptyList()
    private var rawRecentlyViewed: List<MovieTitleSummary> = emptyList()
    private var rawHiddenKeys: Set<String> = emptySet()
    private val pendingRemovedContinueWatchingKeys = mutableMapOf<String, Long>()
    private val pendingRemovedRecentlyViewedKeys = mutableMapOf<String, Long>()
    private val pendingHiddenMediaKeys = mutableSetOf<String>()
    private val pendingWatchlistAdded = linkedMapOf<String, MovieTitleSummary>()
    private val pendingWatchlistRemovedKeys = mutableSetOf<String>()
    private val inFlightMutationKeys = mutableSetOf<String>()
    private var surpriseSeed: Long = 101L
    private var surprisePreferredKind: MediaKind? = null

    private fun effectiveHiddenKeys(): Set<String> =
        if (pendingHiddenMediaKeys.isEmpty()) rawHiddenKeys else rawHiddenKeys + pendingHiddenMediaKeys

    private fun effectiveWatchlist(): List<MovieTitleSummary> {
        val hidden = effectiveHiddenKeys()
        val base = rawWatchlist.filterNot {
            it.stableMediaKey in pendingWatchlistRemovedKeys ||
                it.compositeId in pendingWatchlistRemovedKeys ||
                it.stableMediaKey in hidden ||
                it.compositeId in hidden
        }
        if (pendingWatchlistAdded.isEmpty()) return base
        val existingIds = base.mapTo(HashSet()) { it.compositeId }
        val prepended = pendingWatchlistAdded.values.filter {
            it.compositeId !in existingIds &&
                it.stableMediaKey !in hidden &&
                it.compositeId !in hidden
        }
        return prepended + base
    }

    private fun effectiveRecentlyViewed(): List<MovieTitleSummary> {
        val hidden = effectiveHiddenKeys()
        return rawRecentlyViewed.filterNot {
            it.stableMediaKey in pendingRemovedRecentlyViewedKeys ||
                it.compositeId in pendingRemovedRecentlyViewedKeys ||
                it.stableMediaKey in hidden ||
                it.compositeId in hidden
        }
    }

    private fun effectiveContinueWatching(): List<WatchHistoryItem> {
        val hidden = effectiveHiddenKeys()
        return rawContinueWatching.filterNot { entry ->
            val removedAt = pendingRemovedContinueWatchingKeys[entry.stableMediaKey]
                ?: pendingRemovedContinueWatchingKeys[entry.compositeId]
            val isPendingRemoval = removedAt != null && entry.lastWatchedAt <= removedAt
            isPendingRemoval ||
                entry.stableMediaKey in hidden ||
                entry.compositeId in hidden
        }
    }

    init {
        viewModelScope.launch {
            moviesRepository.observeWatchlist().collect { list ->
                rawWatchlist = list
                _uiState.update { current ->
                    current.copy(watchlist = effectiveWatchlist())
                }
                scheduleDiscoveryRecompute()
            }
        }
        viewModelScope.launch {
            moviesRepository.observeContinueWatching(12).collect { history ->
                rawContinueWatching = history
                if (pendingRemovedContinueWatchingKeys.isNotEmpty()) {
                    val byKey = HashMap<String, WatchHistoryItem>(history.size * 2)
                    history.forEach { item ->
                        byKey[item.stableMediaKey] = item
                        byKey[item.compositeId] = item
                    }
                    val iterator = pendingRemovedContinueWatchingKeys.entries.iterator()
                    while (iterator.hasNext()) {
                        val (key, removedAt) = iterator.next()
                        val match = byKey[key]
                        if (match != null && match.lastWatchedAt > removedAt) {
                            iterator.remove()
                        } else if (match == null && !inFlightMutationKeys.contains("cw:$key")) {
                            iterator.remove()
                        }
                    }
                }
                _uiState.update { it.copy(continueWatching = effectiveContinueWatching()) }
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
                scheduleDiscoveryRecompute()
            }
        }
        viewModelScope.launch {
            moviesRepository.observeRecentlyViewed(20).collect { list ->
                rawRecentlyViewed = list
                if (pendingRemovedRecentlyViewedKeys.isNotEmpty()) {
                    val presentKeys = list.flatMapTo(HashSet()) { listOf(it.stableMediaKey, it.compositeId) }
                    val iterator = pendingRemovedRecentlyViewedKeys.keys.iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        if (key !in presentKeys && !inFlightMutationKeys.contains("rv:$key")) {
                            iterator.remove()
                        }
                    }
                }
                _uiState.update { current ->
                    current.copy(recentlyViewed = effectiveRecentlyViewed())
                }
                scheduleDiscoveryRecompute()
            }
        }
        viewModelScope.launch {
            moviesRepository.observeOfflineAvailability().collect { matches ->
                _uiState.update { it.copy(offlineMatches = matches) }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeHiddenHistory(100).collect { hiddenList ->
                val keys = hiddenList.flatMapTo(LinkedHashSet()) {
                    listOfNotNull(it.stableMediaKey, it.compositeId.takeIf { c -> c.isNotBlank() })
                }
                rawHiddenKeys = keys
                if (pendingHiddenMediaKeys.isNotEmpty()) {
                    pendingHiddenMediaKeys.removeAll(keys)
                }
                val effectiveHidden = effectiveHiddenKeys()
                _uiState.update { current ->
                    val filtered = MoviesDiscoveryEngine.filterTitles(
                        items = rawFilteredItems,
                        kind = current.selectedKind,
                        genre = current.selectedGenre,
                        language = current.selectedLanguage,
                        country = current.selectedCountry,
                        year = current.selectedYear,
                        runtimeBucket = current.selectedRuntimeBucket,
                        hiddenMediaKeys = effectiveHidden
                    )
                    current.copy(
                        hiddenMediaKeys = effectiveHidden,
                        watchlist = effectiveWatchlist(),
                        recentlyViewed = effectiveRecentlyViewed(),
                        continueWatching = effectiveContinueWatching(),
                        filteredItems = filtered
                    )
                }
                scheduleDiscoveryRecompute()
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
            // Priority 1: render main home feed as soon as it resolves (cache or live),
            // while Top Rated & Newest discovery rails load concurrently without blocking first render.
            val topRatedDeferred = async {
                moviesRepository.getCatalogue(
                    kind = null,
                    page = 1,
                    pageSize = 12,
                    sort = CatalogueSort.TOP_RATED
                ).getOrNull()?.items.orEmpty().distinctBy { m -> m.compositeId }
            }
            val newestDeferred = async {
                moviesRepository.getCatalogue(
                    kind = null,
                    page = 1,
                    pageSize = 12,
                    sort = CatalogueSort.NEWEST
                ).getOrNull()?.items.orEmpty().distinctBy { m -> m.compositeId }
            }

            val result = moviesRepository.getHomeFeed(forceRefresh = forceRefresh)
            result.fold(
                onSuccess = { feed ->
                    val immediateDiscovery = computeDiscoverySnapshot(_uiState.value.copy(feed = feed))
                    _uiState.update {
                        immediateDiscovery.copy(
                            isLoading = false,
                            isRefreshing = false,
                            feed = feed,
                            errorMessage = null
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

            // Update progressive secondary discovery rails when ready
            val topRated = topRatedDeferred.await()
            if (topRated.isNotEmpty()) {
                _uiState.update { it.copy(topRatedRail = topRated) }
                scheduleDiscoveryRecompute()
            }
            val newest = newestDeferred.await()
            if (newest.isNotEmpty()) {
                _uiState.update { it.copy(newestRail = newest) }
                scheduleDiscoveryRecompute()
            }
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
        val opKey = "hide:${item.compositeId}"
        if (!inFlightMutationKeys.add(opKey)) return
        val keys = listOf(item.stableMediaKey, item.compositeId)
        pendingHiddenMediaKeys.addAll(keys)
        val hiddenNow = effectiveHiddenKeys()
        _uiState.update { current ->
            val filtered = MoviesDiscoveryEngine.filterTitles(
                items = rawFilteredItems,
                kind = current.selectedKind,
                genre = current.selectedGenre,
                language = current.selectedLanguage,
                country = current.selectedCountry,
                year = current.selectedYear,
                runtimeBucket = current.selectedRuntimeBucket,
                hiddenMediaKeys = hiddenNow
            )
            current.copy(
                hiddenMediaKeys = hiddenNow,
                watchlist = effectiveWatchlist(),
                recentlyViewed = effectiveRecentlyViewed(),
                continueWatching = effectiveContinueWatching(),
                filteredItems = filtered,
                actionFeedbackMessage = "Hidden ${item.title}",
                actionErrorMessage = null
            )
        }
        scheduleDiscoveryRecompute()

        viewModelScope.launch {
            try {
                val res = runCatching { moviesRepository.setTitleHidden(item, true) }
                if (res.isFailure) {
                    pendingHiddenMediaKeys.removeAll(keys.toSet())
                    val restoredHidden = effectiveHiddenKeys()
                    _uiState.update { current ->
                        val filtered = MoviesDiscoveryEngine.filterTitles(
                            items = rawFilteredItems,
                            kind = current.selectedKind,
                            genre = current.selectedGenre,
                            language = current.selectedLanguage,
                            country = current.selectedCountry,
                            year = current.selectedYear,
                            runtimeBucket = current.selectedRuntimeBucket,
                            hiddenMediaKeys = restoredHidden
                        )
                        current.copy(
                            hiddenMediaKeys = restoredHidden,
                            watchlist = effectiveWatchlist(),
                            recentlyViewed = effectiveRecentlyViewed(),
                            continueWatching = effectiveContinueWatching(),
                            filteredItems = filtered,
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't hide ${item.title}. Please try again."
                        )
                    }
                    scheduleDiscoveryRecompute()
                } else {
                    if (keys.any { it in rawHiddenKeys }) {
                        pendingHiddenMediaKeys.removeAll(keys.toSet())
                    }
                }
            } finally {
                inFlightMutationKeys.remove(opKey)
            }
        }
    }

    fun restartFromBeginning(entry: WatchHistoryItem) {
        viewModelScope.launch {
            runCatching {
                moviesRepository.restartFromBeginning(
                    providerId = entry.providerId,
                    titleId = entry.titleId,
                    seasonNumber = entry.seasonNumber,
                    episodeNumber = entry.episodeNumber
                )
            }.onSuccess {
                _uiState.update { it.copy(actionFeedbackMessage = "Reset progress for ${entry.title}") }
            }.onFailure {
                _uiState.update { it.copy(actionErrorMessage = "Couldn't reset progress for ${entry.title}.") }
            }
        }
    }

    fun removeFromContinueWatching(entry: WatchHistoryItem) {
        val opKey = "cw:${entry.compositeId}"
        if (!inFlightMutationKeys.add(opKey)) return
        val keys = listOf(entry.stableMediaKey, entry.compositeId)
        val removedAt = entry.lastWatchedAt
        keys.forEach { pendingRemovedContinueWatchingKeys[it] = removedAt }
        _uiState.update {
            it.copy(
                continueWatching = effectiveContinueWatching(),
                actionFeedbackMessage = "Removed ${entry.title} from Continue Watching",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val res = runCatching { moviesRepository.removeFromContinueWatching(entry.providerId, entry.titleId) }
                if (res.isFailure) {
                    keys.forEach { pendingRemovedContinueWatchingKeys.remove(it) }
                    _uiState.update {
                        it.copy(
                            continueWatching = effectiveContinueWatching(),
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't remove ${entry.title} from Continue Watching."
                        )
                    }
                } else {
                    val stillInRaw = rawContinueWatching.any {
                        (it.stableMediaKey in keys || it.compositeId in keys) && it.lastWatchedAt <= removedAt
                    }
                    if (!stillInRaw) {
                        keys.forEach { pendingRemovedContinueWatchingKeys.remove(it) }
                    }
                }
            } finally {
                inFlightMutationKeys.remove(opKey)
            }
        }
    }

    fun removeRecentlyViewed(item: MovieTitleSummary) {
        val opKey = "rv:${item.compositeId}"
        if (!inFlightMutationKeys.add(opKey)) return
        val keys = listOf(item.stableMediaKey, item.compositeId)
        val removedAt = System.currentTimeMillis()
        keys.forEach { pendingRemovedRecentlyViewedKeys[it] = removedAt }
        _uiState.update {
            it.copy(
                recentlyViewed = effectiveRecentlyViewed(),
                actionFeedbackMessage = "Removed ${item.title} from Viewed",
                actionErrorMessage = null
            )
        }
        scheduleDiscoveryRecompute()
        viewModelScope.launch {
            try {
                val res = runCatching { moviesRepository.removeRecentlyViewed(item.providerId, item.id) }
                if (res.isFailure) {
                    keys.forEach { pendingRemovedRecentlyViewedKeys.remove(it) }
                    _uiState.update {
                        it.copy(
                            recentlyViewed = effectiveRecentlyViewed(),
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't remove ${item.title} from Viewed."
                        )
                    }
                    scheduleDiscoveryRecompute()
                } else {
                    val stillInRaw = rawRecentlyViewed.any {
                        it.stableMediaKey in keys || it.compositeId in keys
                    }
                    if (!stillInRaw) {
                        keys.forEach { pendingRemovedRecentlyViewedKeys.remove(it) }
                    }
                }
            } finally {
                inFlightMutationKeys.remove(opKey)
            }
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

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    private fun scheduleDiscoveryRecompute() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val updated = kotlinx.coroutines.withContext(computeDispatcher) {
                computeDiscoverySnapshot(snapshot)
            }
            _uiState.update { current ->
                current.copy(
                    becauseYouWatchedGroups = updated.becauseYouWatchedGroups,
                    quickRuntimePicks = updated.quickRuntimePicks,
                    recentlyAddedRail = updated.recentlyAddedRail,
                    availableLanguages = updated.availableLanguages,
                    surprisePick = updated.surprisePick
                )
            }
        }
    }

    private fun computeDiscoverySnapshot(state: MoviesHomeUiState): MoviesHomeUiState {
        val hidden = state.hiddenMediaKeys
        val watchlistSnapshot = effectiveWatchlist()
        val recentlyViewedSnapshot = effectiveRecentlyViewed()
        val candidatePool = (
            state.feed?.featuredBanners.orEmpty() +
                state.feed?.sections.orEmpty().flatMap { it.items } +
                state.topRatedRail +
                state.newestRail
            ).distinctBy { it.stableMediaKey }

        val byw = MoviesDiscoveryEngine.buildBecauseYouWatchedGroups(
            watchHistory = latestNonTrailerHistory,
            watchlist = watchlistSnapshot,
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
            recentlyViewed = recentlyViewedSnapshot,
            hiddenMediaKeys = hidden,
            watchlist = watchlistSnapshot,
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
        scheduleDiscoveryRecompute()
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
        val opKey = "wl:${item.compositeId}"
        if (!inFlightMutationKeys.add(opKey)) return
        val currentlyInWatchlist = effectiveWatchlist().any {
            it.compositeId == item.compositeId || it.stableMediaKey == item.stableMediaKey
        }
        val keys = listOf(item.stableMediaKey, item.compositeId)
        if (currentlyInWatchlist) {
            pendingWatchlistAdded.remove(item.compositeId)
            pendingWatchlistRemovedKeys.addAll(keys)
        } else {
            pendingWatchlistRemovedKeys.removeAll(keys.toSet())
            pendingWatchlistAdded[item.compositeId] = item
        }
        _uiState.update {
            it.copy(
                watchlist = effectiveWatchlist(),
                actionFeedbackMessage = if (currentlyInWatchlist) {
                    "Removed ${item.title} from Watchlist"
                } else {
                    "Added ${item.title} to Watchlist"
                },
                actionErrorMessage = null
            )
        }
        scheduleDiscoveryRecompute()

        viewModelScope.launch {
            try {
                val res = runCatching { moviesRepository.toggleWatchlist(item) }
                if (res.isFailure) {
                    if (currentlyInWatchlist) {
                        pendingWatchlistRemovedKeys.removeAll(keys.toSet())
                    } else {
                        pendingWatchlistAdded.remove(item.compositeId)
                    }
                    _uiState.update {
                        it.copy(
                            watchlist = effectiveWatchlist(),
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't update Watchlist for ${item.title}."
                        )
                    }
                    scheduleDiscoveryRecompute()
                } else {
                    pendingWatchlistAdded.remove(item.compositeId)
                    pendingWatchlistRemovedKeys.removeAll(keys.toSet())
                }
            } finally {
                inFlightMutationKeys.remove(opKey)
            }
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
