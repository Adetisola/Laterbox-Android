package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SeasonSummary
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
    val activeTitleId: String = "",
    val availableDubs: List<AudioDubInfo> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeLabel: String? = null,
    val isEnqueuingDownload: Boolean = false,
    val downloadStatusMessage: String? = null,
    val errorMessage: String? = null
) {
    val nextEpisodeTarget: Pair<Int, Int>?
        get() {
            val s = seasonNumber ?: return null
            val e = episodeNumber ?: return null
            if (seasons.isEmpty()) return null
            val ordered = seasons.sortedBy { it.seasonNumber }
            val currentIdx = ordered.indexOfFirst { it.seasonNumber == s }
            if (currentIdx < 0) return null
            val currentSeason = ordered[currentIdx]
            if (currentSeason.episodeCount > 0 && e < currentSeason.episodeCount) {
                return s to (e + 1)
            }
            val nextSeason = ordered.getOrNull(currentIdx + 1)
            return if (nextSeason != null && (nextSeason.episodeCount <= 0 || nextSeason.episodeCount >= 1)) {
                nextSeason.seasonNumber to 1
            } else {
                null
            }
        }
}

class MoviePlayerViewModel(
    private val moviesRepository: MoviesRepository,
    private val moviesDownloadBridge: MoviesDownloadBridge? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviePlayerUiState())
    val uiState: StateFlow<MoviePlayerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadedKey: String? = null
    private var activeProviderId: String = ""

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
        activeProviderId = providerId

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                MoviePlayerUiState(
                    isLoading = true,
                    activeTitleId = titleId,
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
                                    activeTitleId = titleId,
                                    availableDubs = details?.audioDubs.orEmpty(),
                                    seasons = details?.seasons.orEmpty(),
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
                                    activeTitleId = titleId,
                                    availableDubs = details?.audioDubs.orEmpty(),
                                    seasons = details?.seasons.orEmpty(),
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
                            activeTitleId = titleId,
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

    fun selectAudioDub(dub: AudioDubInfo, currentPositionMs: Long) {
        val currentState = _uiState.value
        val targetId = dub.referenceId?.trim().orEmpty()
        if (targetId.isBlank() || targetId == currentState.activeTitleId) return
        val providerId = currentState.summary?.providerId ?: activeProviderId
        if (providerId.isBlank()) return

        val seasonNumber = currentState.seasonNumber
        val episodeNumber = currentState.episodeNumber
        val preservedPosMs = currentPositionMs.coerceAtLeast(0L)
        val preferredHeight = currentState.selectedSource?.qualityHeight ?: 720
        val previousDubs = currentState.availableDubs
        val previousSeasons = currentState.seasons
        val previousSubCode = currentState.selectedSubtitleTrack?.languageCode

        loadedKey = "$providerId:$targetId:${seasonNumber ?: 0}:${episodeNumber ?: 0}"
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null
                )
            }
            val dubDetails = moviesRepository.getDetails(providerId, targetId, forceRefresh = false).getOrNull()
            moviesRepository.resolvePlayback(providerId, targetId, seasonNumber, episodeNumber).fold(
                onSuccess = { resolution ->
                    when (resolution) {
                        is PlaybackResolution.Available -> {
                            val matchedSource = resolution.sources.firstOrNull { it.qualityHeight == preferredHeight }
                                ?: resolution.sources.firstOrNull { it.qualityHeight <= 720 }
                                ?: resolution.sources.firstOrNull()
                            val matchedSub = previousSubCode?.let { code ->
                                resolution.subtitles.firstOrNull { it.languageCode.equals(code, ignoreCase = true) }
                            } ?: currentState.selectedSubtitleTrack?.let { null } ?: resolution.subtitles.firstOrNull {
                                it.languageCode.equals("en", ignoreCase = true)
                            } ?: resolution.subtitles.firstOrNull()
                            val summary = dubDetails?.summary ?: currentState.summary?.copy(id = targetId)
                                ?: MovieTitleSummary(
                                    id = targetId,
                                    providerId = providerId,
                                    kind = if (seasonNumber != null) MediaKind.TV_SERIES else MediaKind.MOVIE,
                                    title = resolution.title,
                                    sourceBadge = resolution.sourceBadge,
                                    isPlayableHint = true
                                )
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    resolution = resolution,
                                    selectedSource = matchedSource,
                                    selectedSubtitleTrack = matchedSub,
                                    startPositionMs = preservedPosMs,
                                    summary = summary,
                                    activeTitleId = targetId,
                                    availableDubs = dubDetails?.audioDubs?.takeIf { d -> d.isNotEmpty() } ?: previousDubs,
                                    seasons = dubDetails?.seasons?.takeIf { s -> s.isNotEmpty() } ?: previousSeasons,
                                    errorMessage = null
                                )
                            }
                        }
                        is PlaybackResolution.Unavailable -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    resolution = resolution,
                                    summary = dubDetails?.summary ?: it.summary,
                                    activeTitleId = targetId,
                                    availableDubs = dubDetails?.audioDubs?.takeIf { d -> d.isNotEmpty() } ?: previousDubs,
                                    seasons = dubDetails?.seasons?.takeIf { s -> s.isNotEmpty() } ?: previousSeasons,
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
                                ?: "Unable to switch audio dub stream."
                        )
                    }
                }
            )
        }
    }

    fun playNextEpisode() {
        val currentState = _uiState.value
        val (nextSeason, nextEpisode) = currentState.nextEpisodeTarget ?: return
        val providerId = currentState.summary?.providerId ?: activeProviderId
        val titleId = currentState.activeTitleId.ifBlank { currentState.summary?.id.orEmpty() }
        if (providerId.isBlank() || titleId.isBlank()) return
        load(
            providerId = providerId,
            titleId = titleId,
            seasonNumber = nextSeason,
            episodeNumber = nextEpisode,
            forceReload = true
        )
    }

    fun updatePlaybackPosition(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        if (clamped <= 0L) return
        _uiState.update { it.copy(startPositionMs = clamped) }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        val state = _uiState.value
        val clamped = positionMs.coerceAtLeast(0L)
        if (clamped > 0L) {
            _uiState.update { it.copy(startPositionMs = clamped) }
        }
        val summary = state.summary ?: return
        if (clamped < 1_000L && durationMs <= 0L) return
        viewModelScope.launch {
            moviesRepository.saveWatchProgress(
                item = summary,
                positionMs = clamped,
                durationMs = durationMs,
                seasonNumber = state.seasonNumber,
                episodeNumber = state.episodeNumber,
                episodeLabel = state.episodeLabel
            )
        }
    }

    fun downloadSource(source: PlaybackSource) {
        val bridge = moviesDownloadBridge ?: return
        val state = _uiState.value
        val summary = state.summary ?: return
        if (state.isEnqueuingDownload) return

        viewModelScope.launch {
            _uiState.update { it.copy(isEnqueuingDownload = true, downloadStatusMessage = null) }
            bridge.enqueueAuthorisedDownload(
                summary = summary,
                source = source,
                episodeLabel = state.episodeLabel
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isEnqueuingDownload = false,
                            downloadStatusMessage = "Queued ${source.qualityLabel} download in Laterbox Downloads."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isEnqueuingDownload = false,
                            downloadStatusMessage = error.message ?: "Could not enqueue download."
                        )
                    }
                }
            )
        }
    }

    fun clearDownloadStatus() {
        _uiState.update { it.copy(downloadStatusMessage = null) }
    }
}
