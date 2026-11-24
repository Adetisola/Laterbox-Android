package com.laterbox.app.movies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.data.provider.MovieBoxApiException
import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackMarkerWindow
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.network.CdnFailureDomainResolver
import com.laterbox.app.network.RetryAfterParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
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
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val ambientModeEnabled: Boolean = false
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
    val episodeArtworkUrl: String? = null,
    val nextEpisodePreview: NextEpisodePreview? = null,
    val relatedTitles: List<MovieTitleSummary> = emptyList(),
    val playbackPreferences: PlayerPlaybackPreferences = PlayerPlaybackPreferences(),
    val preferredAudioLanguage: String? = null,
    val isPlayingOffline: Boolean = false,
    val introWindow: PlaybackMarkerWindow? = null,
    val recapWindow: PlaybackMarkerWindow? = null,
    val isEnqueuingDownload: Boolean = false,
    val downloadStatusMessage: String? = null,
    val errorMessage: String? = null,
    val failedSourceUrls: Set<String> = emptySet(),
    val hasAttemptedStreamRefresh: Boolean = false
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
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val clockMs: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviePlayerUiState())
    val uiState: StateFlow<MoviePlayerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadedKey: String? = null
    private var activeProviderId: String = ""
    private val rateLimitedDomainsUntilMs = ConcurrentHashMap<String, Long>()
    private val failedSourceAuthFingerprints = ConcurrentHashMap<String, String>()

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
                        subtitleStyle = prefs.toSubtitleStyleConfig(),
                        ambientModeEnabled = prefs.moviesAmbientModeEnabled
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

    fun setAmbientModeEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(playbackPreferences = it.playbackPreferences.copy(ambientModeEnabled = enabled))
        }
        userPreferencesRepository?.let { repo ->
            viewModelScope.launch {
                repo.setMoviesAmbientModeEnabled(enabled)
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
        val activeStreamTarget = com.laterbox.app.movies.playback.LaterboxPlaybackBridge.activeTarget
            as? com.laterbox.app.movies.playback.PlaybackSessionTarget.MovieStream
        val matchesActiveBridgeSession = activeStreamTarget != null &&
            com.laterbox.app.movies.playback.LaterboxPlaybackBridge.activePlayer != null &&
            activeStreamTarget.providerId == providerId &&
            activeStreamTarget.titleId == titleId &&
            activeStreamTarget.seasonNumber == seasonNumber &&
            activeStreamTarget.episodeNumber == episodeNumber
        if (!forceReload && matchesActiveBridgeSession) {
            val cached = com.laterbox.app.movies.playback.LaterboxPlaybackBridge
                .getCachedPlayerUiState<MoviePlayerUiState>(key)
            if (cached != null && cached.resolution is PlaybackResolution.Available) {
                loadedKey = key
                activeProviderId = providerId
                _uiState.value = cached.copy(playbackPreferences = _uiState.value.playbackPreferences)
                return
            }
        }
        if (!forceReload && loadedKey == key && _uiState.value.resolution != null) return
        loadedKey = key
        activeProviderId = providerId
        rateLimitedDomainsUntilMs.clear()
        failedSourceAuthFingerprints.clear()

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
                    subtitleStyle = prefs.toSubtitleStyleConfig(),
                    ambientModeEnabled = prefs.moviesAmbientModeEnabled
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

                            val currentSeasonEpisodes = if (seasonNumber != null && episodeNumber != null) {
                                moviesRepository.getEpisodes(
                                    providerId = summary.providerId,
                                    seriesId = summary.id,
                                    seasonNumber = seasonNumber
                                ).getOrNull().orEmpty()
                            } else {
                                emptyList()
                            }
                            val currentEpArtworkUrl = if (episodeNumber != null) {
                                currentSeasonEpisodes
                                    .firstOrNull { it.episodeNumber == episodeNumber }
                                    ?.stillUrl
                                    ?.takeIf { it.isNotBlank() }
                            } else {
                                null
                            }

                            val nextTarget = computeNextEpisodeTarget(
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                                seasons = details?.seasons.orEmpty()
                            )
                            val nextPreview = if (nextTarget != null) {
                                val nextSeasonEpisodes = if (nextTarget.first == seasonNumber && currentSeasonEpisodes.isNotEmpty()) {
                                    currentSeasonEpisodes
                                } else {
                                    moviesRepository.getEpisodes(
                                        providerId = summary.providerId,
                                        seriesId = summary.id,
                                        seasonNumber = nextTarget.first
                                    ).getOrNull().orEmpty()
                                }
                                val nextEpItem = nextSeasonEpisodes.firstOrNull { it.episodeNumber == nextTarget.second }
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
                                    episodeArtworkUrl = currentEpArtworkUrl,
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
                    val backendMsg = when {
                        error is MovieBoxApiException && error.httpCode == 429 -> {
                            val waitHint = error.retryAfterSeconds?.coerceIn(1L, 60L)
                            if (waitHint != null) {
                                "Catalogue service is temporarily busy. Please try again in ${waitHint}s."
                            } else {
                                "Catalogue service is temporarily busy. Please try again shortly."
                            }
                        }
                        else -> error.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Unable to resolve playback stream."
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeTitleId = effectiveTitleId,
                            errorMessage = backendMsg
                        )
                    }
                }
            )
        }
    }

    fun selectSource(source: PlaybackSource, currentPositionMs: Long) {
        val cleanUrl = source.url.trim()
        if (cleanUrl.isNotEmpty()) {
            failedSourceAuthFingerprints.remove(cleanUrl)
        }
        _uiState.update {
            it.copy(
                selectedSource = source,
                isPlayingOffline = source.isOfflineLocal,
                startPositionMs = currentPositionMs.coerceAtLeast(0L),
                failedSourceUrls = it.failedSourceUrls - cleanUrl
            )
        }
    }

    fun handlePlaybackSourceFailure(
        failedSourceUrl: String,
        currentPositionMs: Long,
        httpStatusCode: Int?,
        retryAfterSeconds: Long? = null,
        failingHost: String? = null
    ): Boolean {
        val current = _uiState.value
        val available = current.resolution as? PlaybackResolution.Available ?: return false
        val cleanUrl = failedSourceUrl.trim()
        if (cleanUrl.isNotEmpty()) {
            val failedSrc = current.selectedSource?.takeIf { it.url.trim() == cleanUrl }
                ?: available.sources.firstOrNull { it.url.trim() == cleanUrl }
            failedSrc?.let { src ->
                sourceAuthFingerprint(src)?.let { fp -> failedSourceAuthFingerprints[cleanUrl] = fp }
            }
        }
        val resolvedDomain = CdnFailureDomainResolver.resolve(failingHost)
            ?: CdnFailureDomainResolver.resolve(cleanUrl)
        val nowMs = clockMs()
        if (httpStatusCode == 429 && resolvedDomain != null) {
            val cooldownSec = (retryAfterSeconds ?: 2L).coerceIn(1L, RetryAfterParser.MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS)
            rateLimitedDomainsUntilMs[resolvedDomain] = nowMs + cooldownSec * 1_000L
        }

        val updatedFailed = if (cleanUrl.isNotBlank()) current.failedSourceUrls + cleanUrl else current.failedSourceUrls
        val fallback = pickNextFallbackSource(
            sources = available.sources,
            failedUrls = updatedFailed,
            preferredQualityHeight = current.playbackPreferences.defaultQualityHeight,
            cooledHosts = activeCooledDomains(nowMs)
        )
        if (fallback != null) {
            _uiState.update {
                it.copy(
                    selectedSource = fallback,
                    isPlayingOffline = fallback.isOfflineLocal,
                    startPositionMs = currentPositionMs.coerceAtLeast(0L),
                    failedSourceUrls = updatedFailed
                )
            }
            return true
        }
        val canRefreshStreams = !current.isPlayingOffline &&
            !current.hasAttemptedStreamRefresh &&
            httpStatusCode in setOf(401, 403, 404, 410, 429) &&
            activeProviderId.isNotBlank() &&
            current.activeTitleId.isNotBlank()
        _uiState.update {
            it.copy(
                failedSourceUrls = updatedFailed,
                hasAttemptedStreamRefresh = it.hasAttemptedStreamRefresh || canRefreshStreams
            )
        }
        if (canRefreshStreams) {
            val providerId = current.summary?.providerId ?: activeProviderId
            val targetId = current.summary?.id ?: current.activeTitleId
            val seasonNum = current.seasonNumber
            val epNum = current.episodeNumber
            val preservedPosMs = currentPositionMs.coerceAtLeast(0L)
            val delayBeforeRefreshMs = if (httpStatusCode == 429) {
                (retryAfterSeconds ?: 1L).coerceIn(0L, RetryAfterParser.MAX_INLINE_MEDIA3_RETRY_DELAY_SECONDS) * 1_000L
            } else {
                0L
            }
            loadJob?.cancel()
            loadJob = viewModelScope.launch {
                if (delayBeforeRefreshMs > 0L) {
                    delay(delayBeforeRefreshMs)
                }
                (moviesRepository as? com.laterbox.app.movies.data.repository.MoviesRepositoryImpl)
                    ?.invalidatePlaybackCache(providerId, targetId, seasonNum, epNum)
                val refreshResult = moviesRepository.resolvePlayback(providerId, targetId, seasonNum, epNum)
                val refreshed = refreshResult.getOrNull() as? PlaybackResolution.Available
                val postRefreshNowMs = clockMs()
                val cooledAfterRefresh = activeCooledDomains(postRefreshNowMs)
                val refreshedFailedUrls = if (refreshed == null) {
                    updatedFailed
                } else {
                    updatedFailed.filterNot { failedUrl ->
                        val candidate = refreshed.sources.firstOrNull { it.url.trim() == failedUrl } ?: return@filterNot false
                        val prevFp = failedSourceAuthFingerprints[failedUrl] ?: return@filterNot false
                        val nextFp = sourceAuthFingerprint(candidate) ?: return@filterNot false
                        prevFp != nextFp
                    }.toSet()
                }
                val freshFallback = refreshed?.let { res ->
                    pickNextFallbackSource(
                        sources = res.sources,
                        failedUrls = refreshedFailedUrls,
                        preferredQualityHeight = _uiState.value.playbackPreferences.defaultQualityHeight,
                        cooledHosts = cooledAfterRefresh
                    )
                }
                if (refreshed != null && freshFallback != null) {
                    failedSourceAuthFingerprints.remove(freshFallback.url.trim())
                    _uiState.update {
                        it.copy(
                            resolution = refreshed,
                            selectedSource = freshFallback,
                            isPlayingOffline = freshFallback.isOfflineLocal,
                            startPositionMs = preservedPosMs,
                            failedSourceUrls = refreshedFailedUrls,
                            errorMessage = null
                        )
                    }
                } else {
                    val refreshErr = refreshResult.exceptionOrNull()
                    val remainingCooldownSec = resolvedDomain?.let { dom ->
                        rateLimitedDomainsUntilMs[dom]?.let { untilMs ->
                            val remMs = untilMs - postRefreshNowMs
                            if (remMs > 0L) ((remMs + 999L) / 1_000L).coerceIn(1L, RetryAfterParser.MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS) else null
                        }
                    }
                    val waitHintSec = retryAfterSeconds?.coerceIn(1L, RetryAfterParser.MAX_INTERACTIVE_PLAYBACK_DELAY_SECONDS)
                        ?: remainingCooldownSec
                    val exhaustionMessage = when {
                        refreshErr is MovieBoxApiException && refreshErr.httpCode == 429 ->
                            refreshErr.message ?: "Streaming service is temporarily busy. Please try again shortly."
                        httpStatusCode == 429 && waitHintSec != null ->
                            "Streaming server is temporarily busy (rate-limited). Please try again in ${waitHintSec}s."
                        httpStatusCode == 429 ->
                            "Streaming server is temporarily busy (rate-limited). Please try again shortly."
                        else ->
                            "Playback access expired or needs verification. Try again or switch quality."
                    }
                    _uiState.update {
                        it.copy(
                            startPositionMs = preservedPosMs,
                            errorMessage = exhaustionMessage
                        )
                    }
                }
            }
            return true
        }
        return false
    }

    private fun activeCooledDomains(nowMs: Long = clockMs()): Set<String> {
        val expired = rateLimitedDomainsUntilMs.entries.filter { it.value <= nowMs }.map { it.key }
        expired.forEach { rateLimitedDomainsUntilMs.remove(it) }
        return rateLimitedDomainsUntilMs.keys.toSet()
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
                                    errorMessage = null,
                                    failedSourceUrls = emptySet(),
                                    hasAttemptedStreamRefresh = false
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
                seasonNumber = state.seasonNumber,
                episodeNumber = state.episodeNumber,
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

        fun pickNextFallbackSource(
            sources: List<PlaybackSource>,
            failedUrls: Set<String>,
            preferredQualityHeight: Int,
            cooledHosts: Set<String> = emptySet()
        ): PlaybackSource? {
            val remaining = sources.filter { it.url.isNotBlank() && it.url.trim() !in failedUrls }
            if (remaining.isEmpty()) return null
            if (cooledHosts.isNotEmpty()) {
                val cooledDomains = cooledHosts
                    .mapNotNull { CdnFailureDomainResolver.resolve(it) ?: it.trim().lowercase().takeIf { raw -> raw.isNotEmpty() } }
                    .toSet()
                val uncooled = remaining.filter { src ->
                    val domain = CdnFailureDomainResolver.resolve(src.url)
                    domain == null || domain !in cooledDomains
                }
                if (uncooled.isNotEmpty()) {
                    return pickPreferredSource(uncooled, preferredQualityHeight) ?: uncooled.firstOrNull()
                }
                return null
            }
            return pickPreferredSource(remaining, preferredQualityHeight) ?: remaining.firstOrNull()
        }

        private fun sourceAuthFingerprint(source: PlaybackSource): String? {
            val authEntries = source.headers.entries
                .filter { (key, value) ->
                    value.isNotBlank() && (
                        com.laterbox.app.network.MediaHeaderScope.isStreamScopedAuthHeader(key) ||
                            key.equals("Authorization", ignoreCase = true)
                        )
                }
                .sortedBy { it.key.lowercase(java.util.Locale.ROOT) }
            if (authEntries.isEmpty()) return null
            return authEntries.joinToString("\n") { "${it.key.lowercase(java.util.Locale.ROOT)}=${it.value.trim()}" }
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
