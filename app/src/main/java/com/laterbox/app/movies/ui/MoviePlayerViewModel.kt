package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoviePlayerUiState(
    val isLoading: Boolean = true,
    val resolution: PlaybackResolution? = null,
    val selectedSource: PlaybackSource? = null,
    val selectedSubtitleTrack: SubtitleTrack? = null,
    val startPositionMs: Long = 0L,
    val summary: MovieTitleSummary? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeLabel: String? = null,
    val errorMessage: String? = null
)

class MoviePlayerViewModel(
    private val moviesRepository: MoviesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviePlayerUiState())
    val uiState: StateFlow<MoviePlayerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadedKey: String? = null

    fun load(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        forceReload: Boolean = false
    ) {
        val key = "$providerId:$titleId:${seasonNumber ?: 0}:${episodeNumber ?: 0}"
        if (!forceReload && loadedKey == key && _uiState.value.resolution != null) return
        loadedKey = key

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                MoviePlayerUiState(
                    isLoading = true,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber
                )
            }

            val details = moviesRepository.getDetails(providerId, titleId, forceRefresh = false).getOrNull()
            val result = moviesRepository.resolvePlayback(providerId, titleId, seasonNumber, episodeNumber)
            result.fold(
                onSuccess = { resolution ->
                    when (resolution) {
                        is PlaybackResolution.Available -> {
                            val defaultSource = resolution.sources.firstOrNull { it.qualityHeight <= 720 }
                                ?: resolution.sources.firstOrNull()
                            val defaultSub = resolution.subtitles.firstOrNull {
                                it.languageCode.equals("en", ignoreCase = true)
                            } ?: resolution.subtitles.firstOrNull()
                            val summary = details?.summary ?: MovieTitleSummary(
                                id = titleId,
                                providerId = providerId,
                                kind = if (seasonNumber != null) MediaKind.TV_SERIES else MediaKind.MOVIE,
                                title = resolution.title,
                                sourceBadge = resolution.sourceBadge,
                                isPlayableHint = true
                            )
                            val epLabel = if (seasonNumber != null && episodeNumber != null) {
                                "S${seasonNumber} · E${episodeNumber}"
                            } else null

                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    resolution = resolution,
                                    selectedSource = defaultSource,
                                    selectedSubtitleTrack = defaultSub,
                                    startPositionMs = resolution.startPositionMs,
                                    summary = summary,
                                    episodeLabel = epLabel,
                                    errorMessage = null
                                )
                            }
                        }
                        is PlaybackResolution.Unavailable -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    resolution = resolution,
                                    summary = details?.summary,
                                    errorMessage = null
                                )
                            }
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Unable to resolve playback stream."
                        )
                    }
                }
            )
        }
    }

    fun selectSource(source: PlaybackSource, currentPositionMs: Long) {
        _uiState.update {
            it.copy(
                selectedSource = source,
                startPositionMs = currentPositionMs.coerceAtLeast(0L)
            )
        }
    }

    fun selectSubtitle(track: SubtitleTrack?, currentPositionMs: Long) {
        _uiState.update {
            it.copy(
                selectedSubtitleTrack = track,
                startPositionMs = currentPositionMs.coerceAtLeast(0L)
            )
        }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        val state = _uiState.value
        val summary = state.summary ?: return
        if (positionMs < 1_000L && durationMs <= 0L) return
        viewModelScope.launch {
            moviesRepository.saveWatchProgress(
                item = summary,
                positionMs = positionMs,
                durationMs = durationMs,
                seasonNumber = state.seasonNumber,
                episodeNumber = state.episodeNumber,
                episodeLabel = state.episodeLabel
            )
        }
    }
}
