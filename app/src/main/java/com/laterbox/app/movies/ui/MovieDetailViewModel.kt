package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MovieDetailUiState(
    val isLoading: Boolean = true,
    val details: MediaDetails? = null,
    val selectedSeasonNumber: Int? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    val episodesError: String? = null,
    val playbackResolution: PlaybackResolution? = null,
    val isCheckingPlayback: Boolean = false,
    val isInWatchlist: Boolean = false,
    val savedPositionMs: Long = 0L,
    val errorMessage: String? = null
)

class MovieDetailViewModel(
    private val moviesRepository: MoviesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var watchlistJob: Job? = null
    private var episodesJob: Job? = null
    private var currentKey: Pair<String, String>? = null

    fun load(providerId: String, titleId: String, forceRefresh: Boolean = false) {
        val key = providerId to titleId
        if (!forceRefresh && currentKey == key && _uiState.value.details != null) return
        currentKey = key

        watchlistJob?.cancel()
        watchlistJob = viewModelScope.launch {
            moviesRepository.observeIsInWatchlist("$providerId:$titleId").collect { inWatchlist ->
                _uiState.update { it.copy(isInWatchlist = inWatchlist) }
            }
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                MovieDetailUiState(
                    isLoading = true,
                    isInWatchlist = _uiState.value.isInWatchlist
                )
            }
            val result = moviesRepository.getDetails(providerId, titleId, forceRefresh)
            result.fold(
                onSuccess = { details ->
                    val savedPos = moviesRepository.getSavedPositionMs(providerId, titleId, null, null)
                    val firstSeason = details.seasons.firstOrNull()?.seasonNumber
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            details = details,
                            selectedSeasonNumber = firstSeason,
                            savedPositionMs = savedPos,
                            errorMessage = null
                        )
                    }
                    if (details.summary.kind == MediaKind.TV_SERIES && firstSeason != null) {
                        loadEpisodes(providerId, titleId, firstSeason)
                    }
                    checkPlayback(providerId, titleId, firstSeason, if (firstSeason != null) 1 else null)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Unable to load title details."
                        )
                    }
                }
            )
        }
    }

    fun selectSeason(seasonNumber: Int) {
        val (providerId, titleId) = currentKey ?: return
        if (_uiState.value.selectedSeasonNumber == seasonNumber) return
        _uiState.update { it.copy(selectedSeasonNumber = seasonNumber) }
        loadEpisodes(providerId, titleId, seasonNumber)
    }

    fun retryPlaybackCheck() {
        val (providerId, titleId) = currentKey ?: return
        val season = _uiState.value.selectedSeasonNumber
        val firstEp = _uiState.value.episodes.firstOrNull()?.episodeNumber
        checkPlayback(providerId, titleId, season, firstEp)
    }

    fun toggleWatchlist() {
        val summary = _uiState.value.details?.summary ?: return
        viewModelScope.launch {
            moviesRepository.toggleWatchlist(summary)
        }
    }

    private fun loadEpisodes(providerId: String, seriesId: String, seasonNumber: Int) {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }
            moviesRepository.getEpisodes(providerId, seriesId, seasonNumber).fold(
                onSuccess = { episodes ->
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodes = false,
                            episodes = episodes,
                            episodesError = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodes = false,
                            episodes = emptyList(),
                            episodesError = err.message ?: "Unable to load season episodes."
                        )
                    }
                }
            )
        }
    }

    private fun checkPlayback(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingPlayback = true) }
            moviesRepository.resolvePlayback(providerId, titleId, seasonNumber, episodeNumber).fold(
                onSuccess = { resolution ->
                    _uiState.update { it.copy(isCheckingPlayback = false, playbackResolution = resolution) }
                },
                onFailure = {
                    _uiState.update { it.copy(isCheckingPlayback = false) }
                }
            )
        }
    }
}
