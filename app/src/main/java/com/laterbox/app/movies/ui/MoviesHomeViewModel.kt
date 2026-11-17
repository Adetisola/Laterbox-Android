package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.BuildConfig
import com.laterbox.app.data.preferences.MovieBoxEndpointConfig
import com.laterbox.app.data.preferences.MoviesProviderMode
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
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
    val watchlist: List<MovieTitleSummary> = emptyList(),
    val continueWatching: List<WatchHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val providerMode: MoviesProviderMode = MoviesProviderMode.MOVIEBOX,
    val movieBoxApiBaseUrl: String = "",
    val effectiveMovieBoxBaseUrl: String = BuildConfig.MOVIEBOX_API_BASE_URL,
    val movieBoxApiKey: String = "",
    val useSampleFixturesOnly: Boolean = false,
    val tmdbApiKeyConfigured: Boolean = false,
    val currentTmdbApiKey: String = "",
    val tmdbPromptDismissed: Boolean = false,
    // Inline Search, Suggestions & Recent Searches
    val searchQuery: String = "",
    val activeSearchQuery: String = "",
    val isSearchFocused: Boolean = false,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    // Inline Filter & Sort state
    val selectedKind: MediaKind? = null,
    val selectedGenre: String = "All",
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
            selectedSort != CatalogueSort.POPULAR ||
            activeSearchQuery.isNotBlank()

    val showSearchDropdown: Boolean
        get() = isSearchFocused && (searchQuery.trim().length >= 2 || recentSearches.isNotEmpty())
}

private data class MoviesConfigSnapshot(
    val providerMode: MoviesProviderMode,
    val movieBoxBaseUrl: String,
    val movieBoxApiKey: String,
    val tmdbApiKey: String
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
            userPreferencesRepository.userPreferencesFlow
                .map { prefs ->
                    MoviesConfigSnapshot(
                        providerMode = prefs.moviesProviderMode,
                        movieBoxBaseUrl = prefs.movieBoxApiBaseUrl,
                        movieBoxApiKey = prefs.movieBoxApiKey,
                        tmdbApiKey = prefs.tmdbApiKey
                    )
                }
                .distinctUntilChanged()
                .collect { config ->
                    val changed = lastConfigKey != null && lastConfigKey != config
                    lastConfigKey = config
                    val effectiveBase = config.movieBoxBaseUrl.ifBlank { BuildConfig.MOVIEBOX_API_BASE_URL }
                    _uiState.update {
                        it.copy(
                            providerMode = config.providerMode,
                            movieBoxApiBaseUrl = config.movieBoxBaseUrl,
                            effectiveMovieBoxBaseUrl = effectiveBase,
                            movieBoxApiKey = config.movieBoxApiKey,
                            useSampleFixturesOnly = config.providerMode == MoviesProviderMode.SAMPLE_FIXTURES,
                            tmdbApiKeyConfigured = config.tmdbApiKey.isNotBlank(),
                            currentTmdbApiKey = config.tmdbApiKey
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
            val result = moviesRepository.getHomeFeed(forceRefresh = forceRefresh)
            result.fold(
                onSuccess = { feed ->
                    _uiState.update {
                        it.copy(
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
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                isSearchFocused = true
            )
        }
        suggestJob?.cancel()
        val trimmed = newQuery.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList()) }
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
            moviesRepository.getSuggestions(trimmed, limit = 8).onSuccess { list ->
                _uiState.update { it.copy(suggestions = list) }
            }
        }
    }

    fun setSearchFocused(focused: Boolean) {
        _uiState.update { it.copy(isSearchFocused = focused) }
    }

    fun submitInlineSearch(overrideQuery: String? = null) {
        val q = (overrideQuery ?: _uiState.value.searchQuery).trim()
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = q,
                activeSearchQuery = q,
                isSearchFocused = false,
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
        _uiState.update { it.copy(selectedKind = kind, isSearchFocused = false) }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectGenre(genre: String) {
        if (_uiState.value.selectedGenre == genre) return
        _uiState.update { it.copy(selectedGenre = genre, isSearchFocused = false) }
        if (_uiState.value.isFilteredMode) {
            loadFiltered(page = 1, append = false)
        } else {
            _uiState.update { it.copy(filteredItems = emptyList(), filteredError = null) }
        }
    }

    fun selectSort(sort: CatalogueSort) {
        if (_uiState.value.selectedSort == sort) return
        _uiState.update { it.copy(selectedSort = sort, isSearchFocused = false) }
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
                selectedKind = null,
                selectedGenre = "All",
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
                    sort = state.selectedSort
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

    fun setProviderMode(mode: MoviesProviderMode) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesProviderMode(mode)
        }
    }

    fun setMovieBoxConfig(baseUrl: String, apiKey: String) {
        if (!MovieBoxEndpointConfig.isValidBaseUrl(baseUrl, BuildConfig.DEBUG)) return
        viewModelScope.launch {
            userPreferencesRepository.setMovieBoxConfig(baseUrl, apiKey)
        }
    }

    fun setSampleFixturesOnly(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMoviesUseSampleFixturesOnly(enabled)
        }
    }

    fun setTmdbApiKey(apiKey: String) {
        viewModelScope.launch {
            userPreferencesRepository.setTmdbApiKey(apiKey)
        }
    }

    fun dismissTmdbPrompt() {
        _uiState.update { it.copy(tmdbPromptDismissed = true) }
    }
}
