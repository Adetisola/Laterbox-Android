package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.BuildConfig
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.PersonFilmography
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.ProviderLinkRecord
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
    val isLoadingEpisodeSizes: Boolean = false,
    val episodesError: String? = null,
    val playbackResolution: PlaybackResolution? = null,
    val isCheckingPlayback: Boolean = false,
    val playbackError: String? = null,
    val isInWatchlist: Boolean = false,
    val savedPositionMs: Long = 0L,
    val isEnqueuingDownload: Boolean = false,
    val downloadStatusMessage: String? = null,
    val providerLinks: List<ProviderLinkRecord> = emptyList(),
    val activePersonMember: CastMember? = null,
    val selectedPerson: PersonFilmography? = null,
    val isLoadingPerson: Boolean = false,
    val personError: String? = null,
    val errorMessage: String? = null
)

class MovieDetailViewModel(
    private val moviesRepository: MoviesRepository,
    private val moviesDownloadBridge: MoviesDownloadBridge? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var watchlistJob: Job? = null
    private var episodesJob: Job? = null
    private var episodeSizesJob: Job? = null
    private var playbackJob: Job? = null
    private var personJob: Job? = null
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
        playbackJob?.cancel()
        episodesJob?.cancel()
        episodeSizesJob?.cancel()
        personJob?.cancel()
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
                    val isSeries = details.summary.kind == MediaKind.TV_SERIES
                    val firstSeason = if (isSeries) {
                        details.seasons.firstOrNull()?.seasonNumber ?: 1
                    } else null
                    val defaultEp = if (isSeries) 1 else null
                    val savedPos = moviesRepository.getSavedPositionMs(
                        details.summary.providerId,
                        details.summary.id,
                        firstSeason,
                        defaultEp
                    )
                    val links = if (BuildConfig.DEBUG) {
                        moviesRepository.getProviderLinks(details.summary.providerId, details.summary.id)
                    } else {
                        emptyList()
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            details = details,
                            selectedSeasonNumber = firstSeason,
                            savedPositionMs = savedPos,
                            providerLinks = links,
                            errorMessage = null
                        )
                    }
                    if (isSeries && firstSeason != null) {
                        loadEpisodes(details.summary.providerId, details.summary.id, firstSeason)
                    }
                    checkPlayback(details.summary.providerId, details.summary.id, firstSeason, defaultEp)
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

    fun unlinkProviderLink(providerId: String, titleId: String) {
        val summary = _uiState.value.details?.summary ?: return
        viewModelScope.launch {
            moviesRepository.unlinkProviderTitle(providerId, titleId)
            val refreshedLinks = moviesRepository.getProviderLinks(summary.providerId, summary.id)
            _uiState.update { it.copy(providerLinks = refreshedLinks) }
        }
    }

    fun openCastMember(member: CastMember) {
        val summary = _uiState.value.details?.summary
        val providerId = member.providerId ?: summary?.providerId
        val personId = member.personId?.takeIf { it.isNotBlank() }

        personJob?.cancel()
        if (providerId.isNullOrBlank() || personId == null) {
            _uiState.update {
                it.copy(
                    activePersonMember = member,
                    selectedPerson = PersonFilmography(
                        personId = "",
                        providerId = providerId.orEmpty(),
                        name = member.name,
                        imageUrl = member.imageUrl,
                        credits = emptyList()
                    ),
                    isLoadingPerson = false,
                    personError = null
                )
            }
            return
        }

        personJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activePersonMember = member,
                    selectedPerson = null,
                    isLoadingPerson = true,
                    personError = null
                )
            }
            moviesRepository.getPersonFilmography(
                providerId = providerId,
                personId = personId,
                personName = member.name,
                personImageUrl = member.imageUrl
            ).fold(
                onSuccess = { filmography ->
                    _uiState.update {
                        it.copy(
                            isLoadingPerson = false,
                            selectedPerson = filmography,
                            personError = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingPerson = false,
                            personError = err.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Unable to load filmography."
                        )
                    }
                }
            )
        }
    }

    fun dismissPersonSheet() {
        personJob?.cancel()
        _uiState.update {
            it.copy(
                activePersonMember = null,
                selectedPerson = null,
                isLoadingPerson = false,
                personError = null
            )
        }
    }

    fun selectSeason(seasonNumber: Int) {
        val (rawProviderId, rawTitleId) = currentKey ?: return
        val providerId = _uiState.value.details?.summary?.providerId ?: rawProviderId
        val titleId = _uiState.value.details?.summary?.id ?: rawTitleId
        if (_uiState.value.selectedSeasonNumber == seasonNumber) return
        _uiState.update { it.copy(selectedSeasonNumber = seasonNumber) }
        loadEpisodes(providerId, titleId, seasonNumber)
        if (_uiState.value.details?.summary?.kind == MediaKind.TV_SERIES) {
            viewModelScope.launch {
                val savedPos = moviesRepository.getSavedPositionMs(providerId, titleId, seasonNumber, 1)
                _uiState.update { it.copy(savedPositionMs = savedPos) }
            }
            checkPlayback(providerId, titleId, seasonNumber, 1)
        }
    }

    fun retryPlaybackCheck() {
        val (rawProviderId, rawTitleId) = currentKey ?: return
        val providerId = _uiState.value.details?.summary?.providerId ?: rawProviderId
        val titleId = _uiState.value.details?.summary?.id ?: rawTitleId
        val isSeries = _uiState.value.details?.summary?.kind == MediaKind.TV_SERIES
        val season = if (isSeries) _uiState.value.selectedSeasonNumber ?: 1 else null
        val firstEp = if (isSeries) _uiState.value.episodes.firstOrNull()?.episodeNumber ?: 1 else null
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
        episodeSizesJob?.cancel()
        episodesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }
            moviesRepository.getEpisodes(providerId, seriesId, seasonNumber).fold(
                onSuccess = { episodes ->
                    val currentRes = _uiState.value.playbackResolution as? PlaybackResolution.Available
                    val ep1Sizes = extractProgressiveSizesMap(currentRes?.sources.orEmpty())
                    val seededEpisodes = if (ep1Sizes.isNotEmpty()) {
                        episodes.map { ep ->
                            if (ep.seasonNumber == seasonNumber && ep.episodeNumber == 1 && ep.sizesMbByQuality.isEmpty()) {
                                ep.copy(sizesMbByQuality = ep1Sizes)
                            } else {
                                ep
                            }
                        }
                    } else {
                        episodes
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodes = false,
                            episodes = seededEpisodes,
                            episodesError = null
                        )
                    }
                    if (seededEpisodes.isNotEmpty()) {
                        loadSeasonEpisodeSizes(
                            providerId = providerId,
                            seriesId = seriesId,
                            seasonNumber = seasonNumber,
                            episodeNumbers = seededEpisodes.map { it.episodeNumber }
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodes = false,
                            episodes = emptyList(),
                            episodesError = err.message ?: "Unable to load episodes."
                        )
                    }
                }
            )
        }
    }

    private fun loadSeasonEpisodeSizes(
        providerId: String,
        seriesId: String,
        seasonNumber: Int,
        episodeNumbers: List<Int>
    ) {
        episodeSizesJob?.cancel()
        episodeSizesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEpisodeSizes = true) }
            val sizesByEpisode = moviesRepository.getSeasonEpisodeSizes(
                providerId = providerId,
                seriesId = seriesId,
                seasonNumber = seasonNumber,
                episodeNumbers = episodeNumbers
            ).getOrDefault(emptyMap())
            _uiState.update { current ->
                if (current.selectedSeasonNumber != seasonNumber) {
                    current.copy(isLoadingEpisodeSizes = false)
                } else {
                    val updatedEpisodes = if (sizesByEpisode.isNotEmpty()) {
                        current.episodes.map { ep ->
                            val resolvedSizes = sizesByEpisode[ep.episodeNumber]
                            if (!resolvedSizes.isNullOrEmpty()) {
                                ep.copy(sizesMbByQuality = ep.sizesMbByQuality + resolvedSizes)
                            } else {
                                ep
                            }
                        }
                    } else {
                        current.episodes
                    }
                    current.copy(
                        isLoadingEpisodeSizes = false,
                        episodes = updatedEpisodes
                    )
                }
            }
        }
    }

    private fun extractProgressiveSizesMap(sources: List<PlaybackSource>): Map<Int, Double> =
        sources
            .filter {
                it.isAuthorised &&
                    !it.format.equals("DASH", ignoreCase = true) &&
                    !it.format.equals("HLS", ignoreCase = true) &&
                    it.qualityHeight > 0 &&
                    (it.sizeMb ?: 0.0) > 0.0
            }
            .associate { it.qualityHeight to (it.sizeMb ?: 0.0) }

    fun downloadSource(source: PlaybackSource, episodeNumber: Int? = null) {
        val bridge = moviesDownloadBridge ?: return
        val state = _uiState.value
        val summary = state.details?.summary ?: return
        if (state.isEnqueuingDownload) return

        val seasonNum = if (summary.kind == MediaKind.TV_SERIES) state.selectedSeasonNumber ?: 1 else null
        val epNum = if (summary.kind == MediaKind.TV_SERIES) {
            episodeNumber ?: state.episodes.firstOrNull()?.episodeNumber ?: 1
        } else null
        val episodeLabel = if (seasonNum != null && epNum != null) "S${seasonNum} · E${epNum}" else null

        viewModelScope.launch {
            _uiState.update { it.copy(isEnqueuingDownload = true, downloadStatusMessage = null) }
            bridge.enqueueAuthorisedDownload(
                summary = summary,
                source = source,
                episodeLabel = episodeLabel,
                seasonNumber = seasonNum,
                episodeNumber = epNum
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isEnqueuingDownload = false,
                            downloadStatusMessage = "Queued ${source.qualityLabel} download."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isEnqueuingDownload = false,
                            downloadStatusMessage = error.message ?: "Could not start download."
                        )
                    }
                }
            )
        }
    }

    fun downloadEpisodesBatch(
        seasonNumber: Int,
        episodeNumbers: List<Int>,
        preferredQualityHeight: Int? = null
    ) {
        val bridge = moviesDownloadBridge ?: return
        val state = _uiState.value
        val summary = state.details?.summary ?: return
        val cleanEpisodes = episodeNumbers.filter { it > 0 }.distinct().sorted()
        if (cleanEpisodes.isEmpty() || state.isEnqueuingDownload) return

        viewModelScope.launch {
            _uiState.update { it.copy(isEnqueuingDownload = true, downloadStatusMessage = null) }
            var queuedCount = 0
            var lowerFallbackCount = 0
            var skippedCount = 0
            var lastError: String? = null

            for (epNum in cleanEpisodes) {
                val res = moviesRepository.resolvePlayback(summary.providerId, summary.id, seasonNumber, epNum)
                val available = res.getOrNull() as? PlaybackResolution.Available
                val progressiveSources = available?.sources?.filter { src ->
                    src.isAuthorised &&
                        !src.format.equals("DASH", ignoreCase = true) &&
                        !src.format.equals("HLS", ignoreCase = true) &&
                        !src.url.contains(".mpd", ignoreCase = true) &&
                        !src.url.contains(".m3u8", ignoreCase = true)
                }.orEmpty()

                val chosenSource = pickPreferredBatchSource(progressiveSources, preferredQualityHeight)
                if (chosenSource == null) {
                    skippedCount++
                    lastError = if (preferredQualityHeight != null && progressiveSources.any { it.qualityHeight > preferredQualityHeight }) {
                        "Episode $epNum is not available in ${preferredQualityHeight}p or lower."
                    } else {
                        res.exceptionOrNull()?.message
                            ?: (res.getOrNull() as? PlaybackResolution.Unavailable)?.reason
                    }
                    continue
                }

                if (preferredQualityHeight != null && chosenSource.qualityHeight in 1 until preferredQualityHeight) {
                    lowerFallbackCount++
                }

                val enqueueResult = bridge.enqueueAuthorisedDownload(
                    summary = summary,
                    source = chosenSource,
                    episodeLabel = "S${seasonNumber} · E${epNum}",
                    seasonNumber = seasonNumber,
                    episodeNumber = epNum
                )
                if (enqueueResult.isSuccess) {
                    queuedCount++
                } else {
                    skippedCount++
                    lastError = enqueueResult.exceptionOrNull()?.message
                }
            }

            val message = when {
                queuedCount > 0 && skippedCount == 0 && lowerFallbackCount == 0 ->
                    "Queued $queuedCount episode${if (queuedCount == 1) "" else "s"} from Season $seasonNumber."
                queuedCount > 0 && skippedCount == 0 ->
                    "Queued $queuedCount episode${if (queuedCount == 1) "" else "s"} from Season $seasonNumber ($lowerFallbackCount at lower available quality)."
                queuedCount > 0 ->
                    "Queued $queuedCount episode${if (queuedCount == 1) "" else "s"} ($skippedCount unavailable at ${preferredQualityHeight?.let { "${it}p" } ?: "selected quality"})."
                else ->
                    lastError ?: "No downloadable video streams found for the selected episodes."
            }

            _uiState.update {
                it.copy(
                    isEnqueuingDownload = false,
                    downloadStatusMessage = message
                )
            }
        }
    }

    internal fun pickPreferredBatchSource(
        progressiveSources: List<PlaybackSource>,
        preferredQualityHeight: Int?
    ): PlaybackSource? {
        if (progressiveSources.isEmpty()) return null
        val target = preferredQualityHeight?.takeIf { it > 0 }
            ?: return progressiveSources.maxByOrNull { it.qualityHeight }
        return progressiveSources.firstOrNull { it.qualityHeight == target }
            ?: progressiveSources.filter { it.qualityHeight in 1..target }.maxByOrNull { it.qualityHeight }
    }

    fun clearDownloadStatus() {
        _uiState.update { it.copy(downloadStatusMessage = null) }
    }

    private fun checkPlayback(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            _uiState.update { it.copy(isCheckingPlayback = true, playbackError = null) }
            moviesRepository.resolvePlayback(providerId, titleId, seasonNumber, episodeNumber).fold(
                onSuccess = { resolution ->
                    val available = resolution as? PlaybackResolution.Available
                    val epSizes = if (seasonNumber != null && episodeNumber != null && available != null) {
                        extractProgressiveSizesMap(available.sources)
                    } else {
                        emptyMap()
                    }
                    _uiState.update { current ->
                        val updatedEpisodes = if (epSizes.isNotEmpty() && seasonNumber != null && episodeNumber != null) {
                            current.episodes.map { ep ->
                                if (ep.seasonNumber == seasonNumber && ep.episodeNumber == episodeNumber) {
                                    ep.copy(sizesMbByQuality = ep.sizesMbByQuality + epSizes)
                                } else {
                                    ep
                                }
                            }
                        } else {
                            current.episodes
                        }
                        current.copy(
                            isCheckingPlayback = false,
                            playbackResolution = resolution,
                            playbackError = null,
                            episodes = updatedEpisodes
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isCheckingPlayback = false,
                            playbackError = error.message?.takeIf { m -> m.isNotBlank() }
                                ?: "Unable to check video availability."
                        )
                    }
                }
            )
        }
    }
}
