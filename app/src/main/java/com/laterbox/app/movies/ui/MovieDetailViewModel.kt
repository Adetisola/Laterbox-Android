package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.BuildConfig
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.engine.MoviesDiscoveryEngine
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.movies.domain.model.PersonFilmography
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.ProviderLinkRecord
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SeriesPreferences
import com.laterbox.app.movies.domain.model.SeriesProgressSummary
import com.laterbox.app.movies.domain.model.ViewingReminderItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.reminders.ReminderScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MovieDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshingDetails: Boolean = false,
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
    val isTrailerPlaying: Boolean = false,
    val trailerMuted: Boolean = false,
    val trailerPositionMs: Long = 0L,
    val isTrailerFullscreen: Boolean = false,
    val trailerError: String? = null,
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val playbackPreferences: PlayerPlaybackPreferences = PlayerPlaybackPreferences(),
    val seriesProgress: SeriesProgressSummary? = null,
    val nextUnwatchedTarget: Pair<Int, Int>? = null,
    val isTitleCompleted: Boolean = false,
    val collections: List<Collection> = emptyList(),
    val memberCollectionIds: Set<Long> = emptySet(),
    val offlineMatches: List<OfflineMediaMatch> = emptyList(),
    val viewingReminder: ViewingReminderItem? = null,
    val seriesPreferences: SeriesPreferences? = null,
    val hiddenMediaKeys: Set<String> = emptySet(),
    val rankedRelatedTitles: List<MovieTitleSummary> = emptyList(),
    val actionFeedbackMessage: String? = null,
    val actionErrorMessage: String? = null,
    val errorMessage: String? = null
)

class MovieDetailViewModel(
    private val moviesRepository: MoviesRepository,
    private val moviesDownloadBridge: MoviesDownloadBridge? = null,
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val collectionRepository: CollectionRepository? = null,
    private val reminderScheduler: ReminderScheduler? = null,
    private val computeDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main.immediate
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var watchlistJob: Job? = null
    private var libraryObserversJob: Job? = null
    private var episodesJob: Job? = null
    private var episodeSizesJob: Job? = null
    private var playbackJob: Job? = null
    private var personJob: Job? = null
    private var currentKey: Pair<String, String>? = null
    private var loadRequestSeq: Long = 0L

    init {
        userPreferencesRepository?.let { repo ->
            viewModelScope.launch {
                repo.userPreferencesFlow.collect { prefs ->
                    val style = prefs.toSubtitleStyleConfig()
                    val mappedPrefs = PlayerPlaybackPreferences(
                        doubleTapSeekSeconds = prefs.moviesDoubleTapSeekSeconds.coerceIn(5, 60),
                        swipeToSeekEnabled = prefs.moviesSwipeToSeekEnabled,
                        swipeSeekSensitivity = prefs.moviesSwipeSeekSensitivity,
                        brightnessGestureEnabled = prefs.moviesBrightnessGestureEnabled,
                        volumeGestureEnabled = prefs.moviesVolumeGestureEnabled,
                        autoplayNextEpisode = prefs.moviesAutoplayNextEpisode,
                        autoplayCountdownSeconds = prefs.moviesAutoplayCountdownSeconds.coerceIn(3, 30),
                        defaultQualityHeight = prefs.moviesDefaultQualityHeight,
                        subtitleStyle = style
                    )
                    _uiState.update {
                        it.copy(
                            trailerMuted = prefs.moviesTrailerMuted,
                            subtitleStyle = style,
                            playbackPreferences = mappedPrefs
                        )
                    }
                }
            }
        }
        collectionRepository?.let { repo ->
            viewModelScope.launch {
                repo.collections.collect { cols ->
                    _uiState.update { it.copy(collections = cols) }
                }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeOfflineAvailability().collect { matches ->
                _uiState.update { it.copy(offlineMatches = matches) }
            }
        }
        viewModelScope.launch {
            moviesRepository.observeHiddenHistory(100).collect { hidden ->
                val keys = hidden.map { it.stableMediaKey }.toSet()
                _uiState.update { current ->
                    val reranked = current.details?.let { details ->
                        MoviesDiscoveryEngine.rankMoreLikeThis(
                            anchor = details.summary,
                            relatedTitles = details.relatedTitles,
                            candidatePool = emptyList(),
                            hiddenMediaKeys = keys
                        )
                    } ?: current.rankedRelatedTitles
                    current.copy(hiddenMediaKeys = keys, rankedRelatedTitles = reranked)
                }
            }
        }
    }

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

        libraryObserversJob?.cancel()
        libraryObserversJob = viewModelScope.launch {
            launch {
                moviesRepository.observeSeriesProgress(providerId, titleId).collect { progress ->
                    val currentDetails = _uiState.value.details
                    val isEpisodic = currentDetails?.summary?.kind?.isEpisodic == true
                    val epMap = _uiState.value.selectedSeasonNumber?.let { sn ->
                        if (_uiState.value.episodes.isNotEmpty()) mapOf(sn to _uiState.value.episodes) else emptyMap()
                    } ?: emptyMap()
                    val effectiveSeasons = when {
                        currentDetails == null || !isEpisodic -> emptyList()
                        currentDetails.seasons.isNotEmpty() -> currentDetails.seasons
                        _uiState.value.episodes.isNotEmpty() -> {
                            _uiState.value.episodes.groupBy { it.seasonNumber }.map { (sn, eps) ->
                                SeasonSummary(
                                    seasonNumber = sn,
                                    episodeCount = eps.size,
                                    availableEpisodes = eps.map { it.episodeNumber }
                                )
                            }
                        }
                        else -> emptyList()
                    }
                    val nextTarget = if (isEpisodic && effectiveSeasons.isNotEmpty()) {
                        moviesRepository.computeNextUnwatchedEpisode(
                            currentDetails!!.summary.providerId,
                            currentDetails.summary.id,
                            effectiveSeasons,
                            epMap
                        )
                    } else null
                    val seriesCompleted = isEpisodic &&
                        effectiveSeasons.isNotEmpty() &&
                        progress.totalWatchedEpisodes > 0 &&
                        nextTarget == null
                    _uiState.update { current ->
                        current.copy(
                            seriesProgress = progress,
                            nextUnwatchedTarget = if (isEpisodic) nextTarget else current.nextUnwatchedTarget,
                            isTitleCompleted = if (isEpisodic) seriesCompleted else current.isTitleCompleted
                        )
                    }
                }
            }
            launch {
                moviesRepository.observeMovieWatchHistory(100).collect { history ->
                    val currentDetails = _uiState.value.details ?: return@collect
                    if (currentDetails.summary.kind == MediaKind.MOVIE) {
                        val mKey = currentDetails.summary.stableMediaKey
                        val completed = history.any {
                            (it.stableMediaKey == mKey || it.compositeId == currentDetails.summary.compositeId) && it.isCompleted
                        }
                        _uiState.update { it.copy(isTitleCompleted = completed) }
                    }
                }
            }
            launch {
                moviesRepository.observeCollectionIdsForMedia(providerId, titleId).collect { ids ->
                    _uiState.update { it.copy(memberCollectionIds = ids.toSet()) }
                }
            }
            launch {
                moviesRepository.observeViewingReminderForTitle(providerId, titleId).collect { rem ->
                    _uiState.update { it.copy(viewingReminder = rem) }
                }
            }
            launch {
                moviesRepository.observeSeriesPreferences(providerId, titleId).collect { prefs ->
                    _uiState.update { it.copy(seriesPreferences = prefs) }
                }
            }
        }

        loadJob?.cancel()
        playbackJob?.cancel()
        episodesJob?.cancel()
        episodeSizesJob?.cancel()
        personJob?.cancel()
        val requestSeq = ++loadRequestSeq
        val cachedSummary = moviesRepository.getCachedSummary(providerId, titleId)
        loadJob = viewModelScope.launch {
            val prev = _uiState.value
            val cachedIsSeries = cachedSummary?.kind == MediaKind.TV_SERIES
            val prefillSeason = if (cachedIsSeries) 1 else null
            val prefillEp = if (cachedIsSeries) 1 else null
            var startedEpisodesSeason: Int? = null
            var startedPlaybackTarget: Pair<Int?, Int?>? = null

            if (cachedSummary != null) {
                _uiState.update {
                    MovieDetailUiState(
                        isLoading = false,
                        isRefreshingDetails = true,
                        details = MediaDetails(summary = cachedSummary),
                        selectedSeasonNumber = prefillSeason,
                        isInWatchlist = prev.isInWatchlist,
                        trailerMuted = prev.trailerMuted,
                        subtitleStyle = prev.subtitleStyle,
                        playbackPreferences = prev.playbackPreferences,
                        collections = prev.collections,
                        offlineMatches = prev.offlineMatches,
                        hiddenMediaKeys = prev.hiddenMediaKeys
                    )
                }
                if (cachedIsSeries && prefillSeason != null) {
                    startedEpisodesSeason = prefillSeason
                    loadEpisodes(cachedSummary.providerId, cachedSummary.id, prefillSeason)
                }
                startedPlaybackTarget = prefillSeason to prefillEp
                checkPlayback(cachedSummary.providerId, cachedSummary.id, prefillSeason, prefillEp)
            } else {
                _uiState.update {
                    MovieDetailUiState(
                        isLoading = true,
                        isRefreshingDetails = true,
                        isInWatchlist = prev.isInWatchlist,
                        trailerMuted = prev.trailerMuted,
                        subtitleStyle = prev.subtitleStyle,
                        playbackPreferences = prev.playbackPreferences,
                        collections = prev.collections,
                        offlineMatches = prev.offlineMatches,
                        hiddenMediaKeys = prev.hiddenMediaKeys
                    )
                }
            }

            val result = moviesRepository.getDetails(providerId, titleId, forceRefresh)
            if (loadRequestSeq != requestSeq) return@launch
            result.fold(
                onSuccess = { details ->
                    val isSeries = details.summary.kind.isEpisodic
                    val defaultSeason = if (isSeries) {
                        details.seasons.firstOrNull()?.seasonNumber ?: 1
                    } else null

                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            isRefreshingDetails = false,
                            details = details,
                            selectedSeasonNumber = current.selectedSeasonNumber ?: defaultSeason,
                            errorMessage = null
                        )
                    }

                    if (details.summary.kind == MediaKind.MOVIE &&
                        (details.summary.durationMinutes == null || details.summary.durationMinutes <= 0)
                    ) {
                        launch {
                            val resolved = moviesRepository.enrichMissingMovieDurations(
                                items = listOf(details.summary),
                                maxItems = 1
                            )
                            val enrichedMinutes = resolved[details.summary.compositeId]
                            if (enrichedMinutes != null &&
                                enrichedMinutes > 0 &&
                                loadRequestSeq == requestSeq
                            ) {
                                _uiState.update { current ->
                                    val curDetails = current.details ?: return@update current
                                    if (curDetails.summary.compositeId != details.summary.compositeId ||
                                        curDetails.summary.kind != MediaKind.MOVIE
                                    ) {
                                        return@update current
                                    }
                                    current.copy(
                                        details = curDetails.copy(
                                            summary = curDetails.summary.copy(durationMinutes = enrichedMinutes)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Priority 4: Rank related titles off Main
                    launch {
                        val rankedRelated = kotlinx.coroutines.withContext(computeDispatcher) {
                            MoviesDiscoveryEngine.rankMoreLikeThis(
                                anchor = details.summary,
                                relatedTitles = details.relatedTitles,
                                candidatePool = emptyList(),
                                hiddenMediaKeys = _uiState.value.hiddenMediaKeys
                            )
                        }
                        if (loadRequestSeq == requestSeq) {
                            _uiState.update { it.copy(rankedRelatedTitles = rankedRelated) }
                        }
                    }

                    // Priority 3: Supporting persistence/links concurrently
                    launch {
                        runCatching { moviesRepository.recordRecentlyViewed(details.summary) }
                    }
                    if (BuildConfig.DEBUG) {
                        launch {
                            val links = runCatching {
                                moviesRepository.getProviderLinks(details.summary.providerId, details.summary.id)
                            }.getOrDefault(emptyList())
                            if (loadRequestSeq == requestSeq) {
                                _uiState.update { it.copy(providerLinks = links) }
                            }
                        }
                    }

                    // Priority 2: Seasons/episodes & playback resolution (+ resume target)
                    launch {
                        val nextUnwatched = if (isSeries) {
                            runCatching {
                                moviesRepository.computeNextUnwatchedEpisode(
                                    providerId = details.summary.providerId,
                                    titleId = details.summary.id,
                                    seasons = details.seasons
                                )
                            }.getOrNull()
                        } else null

                        if (loadRequestSeq != requestSeq) return@launch
                        val targetSeason = if (isSeries) {
                            nextUnwatched?.first ?: defaultSeason
                        } else null
                        val targetEp = if (isSeries) {
                            nextUnwatched?.second ?: 1
                        } else null

                        _uiState.update { current ->
                            current.copy(
                                selectedSeasonNumber = targetSeason,
                                nextUnwatchedTarget = nextUnwatched
                            )
                        }

                        if (isSeries && targetSeason != null && startedEpisodesSeason != targetSeason) {
                            startedEpisodesSeason = targetSeason
                            loadEpisodes(details.summary.providerId, details.summary.id, targetSeason)
                        }
                        val desiredPlaybackTarget = targetSeason to targetEp
                        if (startedPlaybackTarget != desiredPlaybackTarget) {
                            startedPlaybackTarget = desiredPlaybackTarget
                            checkPlayback(details.summary.providerId, details.summary.id, targetSeason, targetEp)
                        }

                        val savedPos = runCatching {
                            moviesRepository.getSavedPositionMs(
                                details.summary.providerId,
                                details.summary.id,
                                targetSeason,
                                targetEp
                            )
                        }.getOrDefault(0L)
                        if (loadRequestSeq == requestSeq) {
                            _uiState.update { it.copy(savedPositionMs = savedPos) }
                        }
                    }
                },
                onFailure = { error ->
                    val errText = error.message?.takeIf { m -> m.isNotBlank() }
                        ?: "Unable to load title details."
                    _uiState.update { current ->
                        if (current.details != null) {
                            current.copy(
                                isLoading = false,
                                isRefreshingDetails = false,
                                actionErrorMessage = errText
                            )
                        } else {
                            current.copy(
                                isLoading = false,
                                isRefreshingDetails = false,
                                errorMessage = errText
                            )
                        }
                    }
                }
            )
        }
    }

    fun clearActionFeedback() {
        _uiState.update { it.copy(actionFeedbackMessage = null) }
    }

    fun markTitleWatched(isRewatch: Boolean = false) {
        val state = _uiState.value
        val details = state.details ?: return
        val summary = details.summary
        viewModelScope.launch {
            if (summary.kind.isEpisodic) {
                val epMap = state.selectedSeasonNumber?.let { sn ->
                    if (state.episodes.isNotEmpty()) mapOf(sn to state.episodes) else emptyMap()
                } ?: emptyMap()
                val effectiveSeasons = when {
                    details.seasons.isNotEmpty() -> details.seasons
                    state.episodes.isNotEmpty() -> state.episodes.groupBy { it.seasonNumber }.map { (sn, eps) ->
                        SeasonSummary(
                            seasonNumber = sn,
                            episodeCount = eps.size,
                            availableEpisodes = eps.map { it.episodeNumber }
                        )
                    }
                    else -> emptyList()
                }
                val markedCount = moviesRepository.markSeriesWatched(
                    item = summary,
                    seasons = effectiveSeasons,
                    episodesBySeason = epMap,
                    isRewatch = isRewatch
                )
                if (markedCount <= 0) {
                    _uiState.update {
                        it.copy(actionFeedbackMessage = "No episode list available to mark watched")
                    }
                    return@launch
                }
                val nextTarget = moviesRepository.computeNextUnwatchedEpisode(
                    summary.providerId,
                    summary.id,
                    effectiveSeasons,
                    epMap
                )
                _uiState.update {
                    it.copy(
                        isTitleCompleted = nextTarget == null,
                        nextUnwatchedTarget = nextTarget,
                        savedPositionMs = 0L,
                        actionFeedbackMessage = if (isRewatch) "Logged series rewatch" else "Marked series as watched"
                    )
                }
            } else {
                moviesRepository.markWatched(
                    item = summary,
                    durationMs = (summary.durationMinutes ?: 90) * 60_000L,
                    isRewatch = isRewatch
                )
                _uiState.update {
                    it.copy(
                        isTitleCompleted = true,
                        savedPositionMs = 0L,
                        actionFeedbackMessage = if (isRewatch) "Logged rewatch" else "Marked as watched"
                    )
                }
            }
        }
    }

    fun markTitleUnwatched() {
        val state = _uiState.value
        val details = state.details ?: return
        val summary = details.summary
        viewModelScope.launch {
            moviesRepository.markUnwatched(summary.providerId, summary.id)
            val epMap = state.selectedSeasonNumber?.let { sn ->
                if (state.episodes.isNotEmpty()) mapOf(sn to state.episodes) else emptyMap()
            } ?: emptyMap()
            val effectiveSeasons = when {
                !summary.kind.isEpisodic -> emptyList()
                details.seasons.isNotEmpty() -> details.seasons
                state.episodes.isNotEmpty() -> state.episodes.groupBy { it.seasonNumber }.map { (sn, eps) ->
                    SeasonSummary(
                        seasonNumber = sn,
                        episodeCount = eps.size,
                        availableEpisodes = eps.map { it.episodeNumber }
                    )
                }
                else -> emptyList()
            }
            val nextTarget = if (summary.kind.isEpisodic && effectiveSeasons.isNotEmpty()) {
                moviesRepository.computeNextUnwatchedEpisode(summary.providerId, summary.id, effectiveSeasons, epMap)
            } else null
            _uiState.update {
                it.copy(
                    isTitleCompleted = false,
                    nextUnwatchedTarget = nextTarget,
                    savedPositionMs = 0L,
                    actionFeedbackMessage = "Marked as unwatched"
                )
            }
        }
    }

    fun restartTitleFromBeginning() {
        val state = _uiState.value
        val summary = state.details?.summary ?: return
        viewModelScope.launch {
            val se = if (summary.kind.isEpisodic) state.selectedSeasonNumber ?: 1 else null
            val ep = if (summary.kind.isEpisodic) state.episodes.firstOrNull()?.episodeNumber ?: 1 else null
            moviesRepository.restartFromBeginning(summary.providerId, summary.id, se, ep)
            _uiState.update {
                it.copy(
                    savedPositionMs = 0L,
                    actionFeedbackMessage = "Progress reset to beginning"
                )
            }
        }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    fun removeFromContinueWatching() {
        val summary = _uiState.value.details?.summary ?: return
        val prevFeedback = _uiState.value.actionFeedbackMessage
        _uiState.update {
            it.copy(
                actionFeedbackMessage = "Removed from Continue Watching",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            runCatching {
                moviesRepository.removeFromContinueWatching(summary.providerId, summary.id)
            }.onFailure {
                _uiState.update {
                    it.copy(
                        actionFeedbackMessage = prevFeedback,
                        actionErrorMessage = "Couldn't remove ${summary.title} from Continue Watching."
                    )
                }
            }
        }
    }

    fun setTitleHidden(hidden: Boolean) {
        val summary = _uiState.value.details?.summary ?: return
        val prevHidden = _uiState.value.hiddenMediaKeys
        val updatedHidden = if (hidden) {
            prevHidden + listOf(summary.stableMediaKey, summary.compositeId)
        } else {
            prevHidden - setOf(summary.stableMediaKey, summary.compositeId)
        }
        _uiState.update {
            it.copy(
                hiddenMediaKeys = updatedHidden,
                actionFeedbackMessage = if (hidden) "Title hidden from discovery" else "Title unhidden",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            runCatching {
                moviesRepository.setTitleHidden(summary, hidden)
            }.onFailure {
                _uiState.update {
                    it.copy(
                        hiddenMediaKeys = prevHidden,
                        actionFeedbackMessage = null,
                        actionErrorMessage = if (hidden) {
                            "Couldn't hide ${summary.title}. Please try again."
                        } else {
                            "Couldn't unhide ${summary.title}. Please try again."
                        }
                    )
                }
            }
        }
    }

    fun toggleEpisodeWatched(episode: EpisodeItem) {
        val state = _uiState.value
        val details = state.details ?: return
        val summary = details.summary
        val currentlyCompleted = state.seriesProgress?.episodeStates
            ?.get(episode.seasonNumber to episode.episodeNumber)?.isCompleted == true
        viewModelScope.launch {
            if (currentlyCompleted) {
                moviesRepository.markUnwatched(
                    providerId = summary.providerId,
                    titleId = summary.id,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber
                )
            } else {
                moviesRepository.markWatched(
                    item = summary,
                    durationMs = (episode.runtimeMinutes ?: 45) * 60_000L,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeLabel = "S${episode.seasonNumber} · E${episode.episodeNumber}"
                )
            }
            val epMap = state.selectedSeasonNumber?.let { sn ->
                if (state.episodes.isNotEmpty()) mapOf(sn to state.episodes) else emptyMap()
            } ?: emptyMap()
            val effectiveSeasons = when {
                details.seasons.isNotEmpty() -> details.seasons
                state.episodes.isNotEmpty() -> state.episodes.groupBy { it.seasonNumber }.map { (sn, eps) ->
                    SeasonSummary(
                        seasonNumber = sn,
                        episodeCount = eps.size,
                        availableEpisodes = eps.map { it.episodeNumber }
                    )
                }
                else -> emptyList()
            }
            val nextTarget = if (effectiveSeasons.isNotEmpty()) {
                moviesRepository.computeNextUnwatchedEpisode(
                    summary.providerId,
                    summary.id,
                    effectiveSeasons,
                    epMap
                )
            } else null
            _uiState.update {
                it.copy(
                    nextUnwatchedTarget = nextTarget,
                    isTitleCompleted = effectiveSeasons.isNotEmpty() && nextTarget == null && !currentlyCompleted
                )
            }
        }
    }

    fun toggleCollectionMembership(collectionId: Long) {
        val summary = _uiState.value.details?.summary ?: return
        val isMember = collectionId in _uiState.value.memberCollectionIds
        viewModelScope.launch {
            if (isMember) {
                moviesRepository.removeMediaFromCollection(summary, collectionId)
                _uiState.update { it.copy(actionFeedbackMessage = "Removed from collection") }
            } else {
                moviesRepository.addMediaToCollection(summary, collectionId)
                _uiState.update { it.copy(actionFeedbackMessage = "Added to collection") }
            }
        }
    }

    fun createAndAddToCollection(name: String) {
        val summary = _uiState.value.details?.summary ?: return
        val repo = collectionRepository ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val colId = repo.createCollection(clean)
            moviesRepository.addMediaToCollection(summary, colId)
            _uiState.update { it.copy(actionFeedbackMessage = "Added to \"$clean\"") }
        }
    }

    fun scheduleViewingReminder(triggerAtMillis: Long, note: String? = null) {
        val state = _uiState.value
        val summary = state.details?.summary ?: return
        val cleanNote = note?.trim()?.takeIf { it.isNotBlank() }
        val target = if (summary.kind.isEpisodic) state.nextUnwatchedTarget else null
        viewModelScope.launch {
            moviesRepository.scheduleViewingReminder(
                item = summary,
                triggerAtMillis = triggerAtMillis,
                note = cleanNote,
                seasonNumber = target?.first,
                episodeNumber = target?.second
            )
            reminderScheduler?.scheduleMovieViewingReminder(
                mediaKey = summary.stableMediaKey,
                providerId = summary.providerId,
                titleId = summary.id,
                title = summary.title,
                triggerAtMillis = triggerAtMillis,
                note = cleanNote
            )
            _uiState.update { it.copy(actionFeedbackMessage = "Viewing reminder scheduled") }
        }
    }

    fun dismissViewingReminder() {
        val summary = _uiState.value.details?.summary ?: return
        viewModelScope.launch {
            moviesRepository.dismissViewingReminder(summary.providerId, summary.id)
            reminderScheduler?.cancelMovieViewingReminder(summary.stableMediaKey)
            _uiState.update { it.copy(actionFeedbackMessage = "Viewing reminder cancelled") }
        }
    }

    fun toggleMuteSeriesReminders() {
        val summary = _uiState.value.details?.summary ?: return
        val newMuted = !(_uiState.value.seriesPreferences?.remindersMuted ?: false)
        viewModelScope.launch {
            moviesRepository.setSeriesRemindersMuted(summary.providerId, summary.id, newMuted)
            _uiState.update {
                it.copy(
                    actionFeedbackMessage = if (newMuted) {
                        "Muted alerts for ${summary.title}"
                    } else {
                        "Unmuted alerts for ${summary.title}"
                    }
                )
            }
        }
    }

    fun onPlayTrailerClick() {
        val summary = _uiState.value.details?.summary ?: return
        val url = summary.trailerUrl?.trim().orEmpty()
        if (!(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
            return
        }
        _uiState.update {
            it.copy(
                isTrailerPlaying = true,
                trailerError = null
            )
        }
        viewModelScope.launch {
            moviesRepository.recordTrailerWatch(
                item = summary,
                positionMs = _uiState.value.trailerPositionMs,
                durationMs = 0L
            )
        }
    }

    fun onStopTrailer() {
        _uiState.update {
            it.copy(
                isTrailerPlaying = false,
                isTrailerFullscreen = false
            )
        }
    }

    fun onToggleTrailerMute() {
        val newMuted = !_uiState.value.trailerMuted
        _uiState.update { it.copy(trailerMuted = newMuted) }
        userPreferencesRepository?.let { repo ->
            viewModelScope.launch {
                repo.setMoviesTrailerMuted(newMuted)
            }
        }
    }

    fun onSetTrailerFullscreen(fullscreen: Boolean, currentPositionMs: Long = _uiState.value.trailerPositionMs) {
        _uiState.update {
            it.copy(
                isTrailerFullscreen = fullscreen,
                trailerPositionMs = currentPositionMs.coerceAtLeast(0L)
            )
        }
    }

    fun onUpdateTrailerPosition(positionMs: Long) {
        _uiState.update { it.copy(trailerPositionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun onRecordTrailerProgress(positionMs: Long, durationMs: Long) {
        val clampedPos = positionMs.coerceAtLeast(0L)
        _uiState.update { it.copy(trailerPositionMs = clampedPos) }
        val summary = _uiState.value.details?.summary ?: return
        viewModelScope.launch {
            moviesRepository.recordTrailerWatch(
                item = summary,
                positionMs = clampedPos,
                durationMs = durationMs.coerceAtLeast(0L)
            )
        }
    }

    fun onTrailerPlaybackError(message: String?) {
        _uiState.update {
            it.copy(
                isTrailerPlaying = false,
                isTrailerFullscreen = false,
                trailerError = message?.takeIf { m -> m.isNotBlank() } ?: "Trailer unavailable."
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
        val wasInWatchlist = _uiState.value.isInWatchlist
        _uiState.update {
            it.copy(
                isInWatchlist = !wasInWatchlist,
                actionFeedbackMessage = if (wasInWatchlist) "Removed from Watchlist" else "Added to Watchlist",
                actionErrorMessage = null
            )
        }
        viewModelScope.launch {
            runCatching { moviesRepository.toggleWatchlist(summary) }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isInWatchlist = wasInWatchlist,
                            actionFeedbackMessage = null,
                            actionErrorMessage = "Couldn't update Watchlist for ${summary.title}."
                        )
                    }
                }
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
                    val seasons = _uiState.value.details?.seasons.orEmpty()
                    val refinedNext = if (seasons.isNotEmpty()) {
                        moviesRepository.computeNextUnwatchedEpisode(
                            providerId = providerId,
                            titleId = seriesId,
                            seasons = seasons,
                            episodesBySeason = mapOf(seasonNumber to seededEpisodes)
                        )
                    } else null
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodes = false,
                            episodes = seededEpisodes,
                            nextUnwatchedTarget = refinedNext ?: it.nextUnwatchedTarget,
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

        val seasonNum = if (summary.kind.isEpisodic) state.selectedSeasonNumber ?: 1 else null
        val epNum = if (summary.kind.isEpisodic) {
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
