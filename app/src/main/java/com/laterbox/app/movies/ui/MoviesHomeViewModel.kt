package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
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
    val useSampleFixturesOnly: Boolean = false,
    val tmdbApiKeyConfigured: Boolean = false,
    val currentTmdbApiKey: String = ""
)

class MoviesHomeViewModel(
    private val moviesRepository: MoviesRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesHomeUiState())
    val uiState: StateFlow<MoviesHomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var lastConfigKey: Pair<Boolean, String>? = null

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
            userPreferencesRepository.userPreferencesFlow
                .map { it.moviesUseSampleFixturesOnly to it.tmdbApiKey }
                .distinctUntilChanged()
                .collect { config ->
                    val changed = lastConfigKey != null && lastConfigKey != config
                    lastConfigKey = config
                    _uiState.update {
                        it.copy(
                            useSampleFixturesOnly = config.first,
                            tmdbApiKeyConfigured = config.second.isNotBlank(),
                            currentTmdbApiKey = config.second
                        )
                    }
                    if (changed || _uiState.value.feed == null) {
                        loadHome(forceRefresh = changed)
                    }
                }
        }
    }

    fun refresh() {
        loadHome(forceRefresh = true)
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

    fun toggleWatchlist(item: MovieTitleSummary) {
        viewModelScope.launch {
            moviesRepository.toggleWatchlist(item)
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
}
