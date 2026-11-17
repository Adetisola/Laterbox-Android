package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoviesCatalogueUiState(
    val query: String = "",
    val selectedKind: MediaKind? = null,
    val selectedGenre: String = "All",
    val sort: CatalogueSort = CatalogueSort.POPULAR,
    val availableGenres: List<String> = DEFAULT_GENRES,
    val items: List<MovieTitleSummary> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val showSuggestionsDropdown: Boolean = false
) {
    companion object {
        val DEFAULT_GENRES = listOf(
            "All", "Drama", "Comedy", "Film Noir", "Sci-Fi",
            "Horror", "Science-Fiction", "Animation", "Action", "Crime", "Mystery"
        )
    }
}

class MoviesCatalogueViewModel(
    private val moviesRepository: MoviesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesCatalogueUiState())
    val uiState: StateFlow<MoviesCatalogueUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private var suggestJob: Job? = null
    private var lastInitializedKey: String? = null

    init {
        viewModelScope.launch {
            moviesRepository.observeRecentSearches(10).collect { recent ->
                _uiState.update { it.copy(recentSearches = recent) }
            }
        }
        viewModelScope.launch {
            moviesRepository.getGenres().onSuccess { genres ->
                if (genres.isNotEmpty()) {
                    _uiState.update { it.copy(availableGenres = genres) }
                }
            }
        }
    }

    fun initialize(
        sessionKey: String,
        initialKind: MediaKind? = null,
        initialGenre: String? = null,
        initialQuery: String? = null
    ) {
        if (lastInitializedKey == sessionKey && _uiState.value.items.isNotEmpty()) return
        lastInitializedKey = sessionKey
        _uiState.update {
            it.copy(
                selectedKind = initialKind,
                selectedGenre = initialGenre?.takeIf { g -> g.isNotBlank() } ?: "All",
                query = initialQuery.orEmpty(),
                showSuggestionsDropdown = false,
                suggestions = emptyList()
            )
        }
        loadPage(page = 1, append = false)
    }

    fun onQueryChange(newQuery: String) {
        _uiState.update {
            it.copy(
                query = newQuery,
                showSuggestionsDropdown = newQuery.isNotBlank() || it.recentSearches.isNotEmpty()
            )
        }
        suggestJob?.cancel()
        if (newQuery.trim().length < 2) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            if (newQuery.isBlank()) {
                loadPage(page = 1, append = false)
            }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(280L)
            moviesRepository.getSuggestions(newQuery, limit = 8).onSuccess { list ->
                _uiState.update { it.copy(suggestions = list) }
            }
        }
    }

    fun setSuggestionsVisible(visible: Boolean) {
        _uiState.update { it.copy(showSuggestionsDropdown = visible) }
    }

    fun submitSearch(overrideQuery: String? = null) {
        val q = (overrideQuery ?: _uiState.value.query).trim()
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                query = q,
                showSuggestionsDropdown = false,
                suggestions = emptyList()
            )
        }
        if (q.isNotBlank()) {
            viewModelScope.launch {
                moviesRepository.recordSearchQuery(q)
            }
        }
        loadPage(page = 1, append = false)
    }

    fun clearSearch() {
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                suggestions = emptyList(),
                showSuggestionsDropdown = false
            )
        }
        loadPage(page = 1, append = false)
    }

    fun selectKind(kind: MediaKind?) {
        if (_uiState.value.selectedKind == kind) return
        _uiState.update { it.copy(selectedKind = kind) }
        loadPage(page = 1, append = false)
    }

    fun selectGenre(genre: String) {
        if (_uiState.value.selectedGenre == genre) return
        _uiState.update { it.copy(selectedGenre = genre) }
        loadPage(page = 1, append = false)
    }

    fun selectSort(sort: CatalogueSort) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        loadPage(page = 1, append = false)
    }

    fun loadNextPage() {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore) return
        loadPage(page = current.page + 1, append = true)
    }

    fun retry() {
        loadPage(page = 1, append = false)
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            moviesRepository.clearSearchHistory()
        }
    }

    private fun loadPage(page: Int, append: Boolean) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _uiState.update {
                if (append) {
                    it.copy(isLoadingMore = true, errorMessage = null)
                } else {
                    it.copy(isLoading = true, isLoadingMore = false, errorMessage = null)
                }
            }
            val state = _uiState.value
            val query = state.query.trim()
            val result = if (query.isNotBlank()) {
                moviesRepository.search(query = query, kind = state.selectedKind, page = page, pageSize = 20)
            } else {
                moviesRepository.getCatalogue(
                    kind = state.selectedKind,
                    page = page,
                    pageSize = 20,
                    genre = state.selectedGenre.takeUnless { it == "All" },
                    sort = state.sort
                )
            }

            result.fold(
                onSuccess = { pageResult ->
                    _uiState.update { current ->
                        val merged = if (append) {
                            (current.items + pageResult.items).distinctBy { it.compositeId }
                        } else {
                            pageResult.items
                        }
                        current.copy(
                            items = merged,
                            page = page,
                            hasMore = pageResult.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = err.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Unable to load catalogue results."
                        )
                    }
                }
            )
        }
    }
}
