package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.BuildConfig
import com.laterbox.app.data.preferences.MovieBoxEndpointConfig
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val movieBoxApiKey: String = "",
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
    val filteredError: String? = null
) {
    val isFilteredMode: Boolean
        get() = selectedKind != null ||
            selectedGenre != "All" ||
            selectedCountry != "All" ||
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
    val movieBoxBaseUrl: String,
    val movieBoxApiKey: String
)

class MoviesHomeViewModel(
    private val moviesRepository: MoviesRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesHomeUiState())
    val uiState: StateFlow<MoviesHomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var suggestJob: Job? = null
    private var lastConfigKey: MoviesConfigSnapshot? = null

    init {
        viewModelScope.launch {
            moviesRepository.observeWatchlist().collect { list ->
                _uiState.update { it.copy(watchlist = list) }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeContinueWatching(12).collect { history ->
                _uiState.update { it.copy(continueWatching = history) }
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
                        movieBoxBaseUrl = prefs.movieBoxApiBaseUrl,
                        movieBoxApiKey = prefs.movieBoxApiKey
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
                            movieBoxApiKey = config.movieBoxApiKey,
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
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            feed = feed,
                            topRatedRail = topRated.ifEmpty { it.topRatedRail },
                            newestRail = newest.ifEmpty { it.newestRail },
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
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        val trimmed = newQuery.trim()
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                isSearchFocused = true,
                isLoadingSuggestions = trimmed.length >= 2,
                suggestionsError = null
            )
        }
        suggestJob?.cancel()
        if (trimmed.length < 2) {
            _uiState.update {
                it.copy(
                    suggestions = emptyList(),
                    isLoadingSuggestions = false,
                    suggestionsError = null
                )
            }
            if (trimmed.isEmpty() && _uiState.value.activeSearchQuery.isNotEmpty()) {
                _uiState.update { it.copy(activeSearchQuery = "") }
                if (_uiState.value.isFilteredMode) {
                    loadFiltered(page = 1, append = false)
                }
            }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(260L)
            moviesRepository.getSuggestions(trimmed, limit = 8).fold(
                onSuccess = { list ->
                    _uiState.update {
                        it.copy(
                            suggestions = list,
                            isLoadingSuggestions = false,
                            suggestionsError = null
                        )
                    }
                },
                onFailure = { err ->
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
        val q = (overrideQuery ?: _uiState.value.searchQuery).trim()
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
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun resetFilters() {
        suggestJob?.cancel()
        filterJob?.cancel()
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
            val result = if (query.isNotBlank()) {
                moviesRepository.search(query = query, kind = state.selectedKind, page = page, pageSize = 20)
            } else {
                moviesRepository.getCatalogue(
                    kind = state.selectedKind,
                    page = page,
                    pageSize = 20,
                    genre = state.selectedGenre.takeUnless { it == "All" },
                    sort = state.selectedSort,
                    country = state.selectedCountry.takeUnless { it == "All" }
                )
            }

            result.fold(
                onSuccess = { pageResult ->
                    _uiState.update { current ->
                        val merged = if (append) {
                            (current.filteredItems + pageResult.items).distinctBy { it.compositeId }
                        } else {
                            pageResult.items
                        }
                        current.copy(
                            filteredItems = merged,
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

    fun setMovieBoxConfig(baseUrl: String, apiKey: String) {
        if (!MovieBoxEndpointConfig.isValidBaseUrl(baseUrl, BuildConfig.DEBUG)) return
        viewModelScope.launch {
            userPreferencesRepository.setMovieBoxConfig(baseUrl, apiKey)
        }
    }

    fun setSampleFixturesOnly(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            userPreferencesRepository.setMoviesUseSampleFixturesOnly(enabled)
        }
    }
}
