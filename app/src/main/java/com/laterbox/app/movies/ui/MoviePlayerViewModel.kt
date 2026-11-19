package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackMarkerWindow
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class PlayerPlaybackPreferences(
    val doubleTapSeekSeconds: Int = 10,
    val swipeToSeekEnabled: Boolean = true,
    val swipeSeekSensitivity: Float = 1.0f,
    val brightnessGestureEnabled: Boolean = true,
    val volumeGestureEnabled: Boolean = true,
    val autoplayNextEpisode: Boolean = true,
    val autoplayCountdownSeconds: Int = 10,
    val defaultQualityHeight: Int = 720,
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig()
)

data class NextEpisodePreview(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val episodeLabel: String,
    val thumbnailUrl: String?
)

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
    val nextEpisodePreview: NextEpisodePreview? = null,
    val relatedTitles: List<MovieTitleSummary> = emptyList(),
    val playbackPreferences: PlayerPlaybackPreferences = PlayerPlaybackPreferences(),
    val preferredAudioLanguage: String? = null,
    val isPlayingOffline: Boolean = false,
    val introWindow: PlaybackMarkerWindow? = null,
    val recapWindow: PlaybackMarkerWindow? = null,
    val isEnqueuingDownload: Boolean = false,
    val downloadStatusMessage: String? = null,
    val errorMessage: String? = null
) {
    val episodeKey: String
        get() = "${activeTitleId}:${seasonNumber ?: 0}:${episodeNumber ?: 0}"

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
    private val moviesDownloadBridge: MoviesDownloadBridge? = null,
    private val userPreferencesRepository: UserPreferencesRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviePlayerUiState())
    val uiState: StateFlow<MoviePlayerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadedKey: String? = null
    private var activeProviderId: String = ""

    init {
        userPreferencesRepository?.let { prefsRepo ->
            viewModelScope.launch {
                prefsRepo.userPreferencesFlow.collect { prefs ->
                    val mapped = PlayerPlaybackPreferences(
                        doubleTapSeekSeconds = prefs.moviesDoubleTapSeekSeconds,
                        swipeToSeekEnabled = prefs.moviesSwipeToSeekEnabled,
                        swipeSeekSensitivity = prefs.moviesSwipeSeekSensitivity,
                        brightnessGestureEnabled = prefs.moviesBrightnessGestureEnabled,
                        volumeGestureEnabled = prefs.moviesVolumeGestureEnabled,
                        autoplayNextEpisode = prefs.moviesAutoplayNextEpisode,
                        autoplayCountdownSeconds = prefs.moviesAutoplayCountdownSeconds,
                        defaultQualityHeight = prefs.moviesDefaultQualityHeight,
                        subtitleStyle = prefs.toSubtitleStyleConfig()
                    )
                    _uiState.update { it.copy(playbackPreferences = mapped) }
                }
            }
        }
    }

    fun updateSubtitleStyle(config: SubtitleStyleConfig) {
        _uiState.update {
            it.copy(playbackPreferences = it.playbackPreferences.copy(subtitleStyle = config))
        }
        userPreferencesRepository?.let { repo ->
            viewModelScope.launch {
                repo.setSubtitleStyleConfig(config)
            }
        }
    }

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
        _uiState.update {
            MoviePlayerUiState(
                isLoading = true,
                activeTitleId = titleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                playbackPreferences = it.playbackPreferences
            )
        }
        loadJob = viewModelScope.launch {
            val currentPrefs = userPreferencesRepository?.userPreferencesFlow?.firstOrNull()?.let { prefs ->
                PlayerPlaybackPreferences(
                    doubleTapSeekSeconds = prefs.moviesDoubleTapSeekSeconds,
                    swipeToSeekEnabled = prefs.moviesSwipeToSeekEnabled,
                    swipeSeekSensitivity = prefs.moviesSwipeSeekSensitivity,
                    brightnessGestureEnabled = prefs.moviesBrightnessGestureEnabled,
                    volumeGestureEnabled = prefs.moviesVolumeGestureEnabled,
                    autoplayNextEpisode = prefs.moviesAutoplayNextEpisode,
                    autoplayCountdownSeconds = prefs.moviesAutoplayCountdownSeconds,
                    defaultQualityHeight = prefs.moviesDefaultQualityHeight,
                    subtitleStyle = prefs.toSubtitleStyleConfig()
                )
            } ?: _uiState.value.playbackPreferences

            _uiState.update {
                it.copy(playbackPreferences = currentPrefs)
            }

            val initialDetails = moviesRepository.getDetails(providerId, titleId, forceRefresh = false).getOrNull()
            val seriesPrefs = moviesRepository.getSeriesPreferences(providerId, titleId)
            val preferredDubRef = seriesPrefs?.preferredAudioLanguage?.takeIf { it.isNotBlank() }?.let { prefLang ->
                initialDetails?.audioDubs?.firstOrNull { dub ->
                    !dub.referenceId.isNullOrBlank() &&
                        dub.referenceId != titleId &&
                        (dub.language.equals(prefLang, ignoreCase = true) ||
                            dub.label.equals(prefLang, ignoreCase = true))
                }?.referenceId
            }
            val effectiveTitleId = preferredDubRef ?: titleId
            val details = if (preferredDubRef != null) {
                moviesRepository.getDetails(providerId, effectiveTitleId, forceRefresh = false).getOrNull()
                    ?: initialDetails
            } else {
                initialDetails
            }
            val result = moviesRepository.resolvePlayback(providerId, effectiveTitleId, seasonNumber, episodeNumber)
            result.fold(
                onSuccess = { resolution ->
                    when (resolution) {
                        is PlaybackResolution.Available -> {
                            val defaultSource = pickPreferredSource(
                                sources = resolution.sources,
                                preferredQualityHeight = currentPrefs.defaultQualityHeight
                            )
                            val defaultSub = when {
                                seriesPrefs?.subtitlesDisabled == true -> null
                                !seriesPrefs?.preferredSubtitleLanguage.isNullOrBlank() -> {
                                    val prefSub = seriesPrefs!!.preferredSubtitleLanguage!!
                                    resolution.subtitles.firstOrNull {
                                        it.languageCode.equals(prefSub, ignoreCase = true) ||
                                            it.language.equals(prefSub, ignoreCase = true)
                                    } ?: resolution.subtitles.firstOrNull {
                                        it.languageCode.equals("en", ignoreCase = true)
                                    } ?: resolution.subtitles.firstOrNull()
                                }
                                else -> resolution.subtitles.firstOrNull {
                                    it.languageCode.equals("en", ignoreCase = true)
                                } ?: resolution.subtitles.firstOrNull()
                            }
                            val summary = details?.summary ?: MovieTitleSummary(
                                id = effectiveTitleId,
                                providerId = providerId,
                                kind = if (seasonNumber != null) MediaKind.TV_SERIES else MediaKind.MOVIE,
                                title = resolution.title,
                                sourceBadge = resolution.sourceBadge,
                                isPlayableHint = true
                            )
                            val epLabel = if (seasonNumber != null && episodeNumber != null) {
                                "S${seasonNumber} · E${episodeNumber}"
                            } else null

                            val nextTarget = computeNextEpisodeTarget(
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                                seasons = details?.seasons.orEmpty()
                            )
                            val nextPreview = if (nextTarget != null) {
                                val nextEpItem = moviesRepository.getEpisodes(
                                    providerId = summary.providerId,
                                    seriesId = summary.id,
                                    seasonNumber = nextTarget.first
                                ).getOrNull()?.firstOrNull { it.episodeNumber == nextTarget.second }
                                NextEpisodePreview(
                                    seasonNumber = nextTarget.first,
                                    episodeNumber = nextTarget.second,
                                    title = nextEpItem?.title?.takeIf { it.isNotBlank() }
                                        ?: "Episode ${nextTarget.second}",
                                    episodeLabel = "S${nextTarget.first} · E${nextTarget.second}",
                                    thumbnailUrl = nextEpItem?.stillUrl
                                        ?: summary.backdropUrl
                                        ?: summary.posterUrl
                                )
                            } else {
                                null
                            }

                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    resolution = resolution,
                                    selectedSource = defaultSource,
                                    selectedSubtitleTrack = defaultSub,
                                    startPositionMs = resolution.startPositionMs,
                                    summary = summary,
                                    activeTitleId = effectiveTitleId,
                                    availableDubs = details?.audioDubs.orEmpty(),
                                    seasons = details?.seasons.orEmpty(),
                                    episodeLabel = epLabel,
                                    nextEpisodePreview = nextPreview,
                                    relatedTitles = details?.relatedTitles.orEmpty().take(8),
                                    preferredAudioLanguage = seriesPrefs?.preferredAudioLanguage,
                                    isPlayingOffline = defaultSource?.isOfflineLocal == true,
                                    introWindow = resolution.introWindow,
                                    recapWindow = resolution.recapWindow,
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
                                    activeTitleId = effectiveTitleId,
                                    availableDubs = details?.audioDubs.orEmpty(),
                                    seasons = details?.seasons.orEmpty(),
                                    relatedTitles = details?.relatedTitles.orEmpty().take(8),
                                    preferredAudioLanguage = seriesPrefs?.preferredAudioLanguage,
                                    isPlayingOffline = false,
                                    introWindow = null,
                                    recapWindow = null,
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
                            activeTitleId = effectiveTitleId,
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
                isPlayingOffline = source.isOfflineLocal,
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
        val state = _uiState.value
        val providerId = state.summary?.providerId ?: activeProviderId
        val titleId = state.summary?.id ?: state.activeTitleId
        if (providerId.isNotBlank() && titleId.isNotBlank()) {
            viewModelScope.launch {
                moviesRepository.saveSeriesSubtitlePreference(
                    providerId = providerId,
                    titleId = titleId,
                    language = track?.languageCode ?: track?.language,
                    label = track?.label,
                    disabled = track == null
                )
            }
        }
    }

    fun saveStreamAudioPreference(language: String?, label: String?) {
        _uiState.update { it.copy(preferredAudioLanguage = language) }
        val state = _uiState.value
        val providerId = state.summary?.providerId ?: activeProviderId
        val titleId = state.summary?.id ?: state.activeTitleId
        if (providerId.isNotBlank() && titleId.isNotBlank()) {
            viewModelScope.launch {
                moviesRepository.saveSeriesAudioPreference(
                    providerId = providerId,
                    titleId = titleId,
                    language = language,
                    label = label
                )
            }
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
        val preferredHeight = currentState.selectedSource?.qualityHeight
            ?: currentState.playbackPreferences.defaultQualityHeight
        val previousDubs = currentState.availableDubs
        val previousSeasons = currentState.seasons
        val previousSubCode = currentState.selectedSubtitleTrack?.languageCode

        loadedKey = "$providerId:$targetId:${seasonNumber ?: 0}:${episodeNumber ?: 0}"
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    preferredAudioLanguage = dub.language,
                    errorMessage = null
                )
            }
            moviesRepository.saveSeriesAudioPreference(
                providerId = providerId,
                titleId = targetId,
                language = dub.language,
                label = dub.label
            )
            val dubDetails = moviesRepository.getDetails(providerId, targetId, forceRefresh = false).getOrNull()
            moviesRepository.resolvePlayback(providerId, targetId, seasonNumber, episodeNumber).fold(
                onSuccess = { resolution ->
                    when (resolution) {
                        is PlaybackResolution.Available -> {
                            val matchedSource = resolution.sources.firstOrNull { it.qualityHeight == preferredHeight }
                                ?: pickPreferredSource(resolution.sources, preferredHeight)
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
                                    isPlayingOffline = matchedSource?.isOfflineLocal == true,
                                    introWindow = resolution.introWindow,
                                    recapWindow = resolution.recapWindow,
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
                                    isPlayingOffline = false,
                                    introWindow = null,
                                    recapWindow = null,
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
        saveProgressInternal(
            positionMs = positionMs,
            durationMs = durationMs,
            expectedEpisodeKey = null
        )
    }

    fun saveProgressForEpisode(
        positionMs: Long,
        durationMs: Long,
        expectedEpisodeKey: String
    ) {
        saveProgressInternal(
            positionMs = positionMs,
            durationMs = durationMs,
            expectedEpisodeKey = expectedEpisodeKey
        )
    }

    private fun saveProgressInternal(
        positionMs: Long,
        durationMs: Long,
        expectedEpisodeKey: String?
    ) {
        val state = _uiState.value
        if (expectedEpisodeKey != null && state.episodeKey != expectedEpisodeKey) return
        val summary = state.summary ?: return
        val seasonNumber = state.seasonNumber
        val episodeNumber = state.episodeNumber
        val episodeLabel = state.episodeLabel
        val clamped = positionMs.coerceAtLeast(0L)
        if (clamped > 0L) {
            _uiState.update { current ->
                if (expectedEpisodeKey != null && current.episodeKey != expectedEpisodeKey) {
                    current
                } else {
                    current.copy(startPositionMs = clamped)
                }
            }
        }
        if (clamped < 1_000L && durationMs <= 0L) return
        viewModelScope.launch {
            moviesRepository.saveWatchProgress(
                item = summary,
                positionMs = clamped,
                durationMs = durationMs,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeLabel = episodeLabel
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

    companion object {
        fun pickPreferredSource(
            sources: List<PlaybackSource>,
            preferredQualityHeight: Int
        ): PlaybackSource? {
            if (sources.isEmpty()) return null
            val target = preferredQualityHeight.coerceIn(240, 2160)
            return sources
                .filter { it.qualityHeight in 1..target }
                .maxByOrNull { it.qualityHeight }
                ?: sources.minByOrNull { abs(it.qualityHeight - target) }
                ?: sources.firstOrNull()
        }

        private fun computeNextEpisodeTarget(
            seasonNumber: Int?,
            episodeNumber: Int?,
            seasons: List<SeasonSummary>
        ): Pair<Int, Int>? {
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
}
