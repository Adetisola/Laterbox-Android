package com.laterbox.app.movies.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.movies.data.cache.MoviesMediaCache
import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackMarkerWindow
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SubtitleTrack
import android.util.Log
import com.laterbox.app.movies.playback.LaterboxPlaybackBridge
import com.laterbox.app.movies.playback.PlaybackSessionService
import com.laterbox.app.movies.ui.player.LaterboxVideoSurface
import com.laterbox.app.movies.ui.player.PlayerAudioBoostController
import com.laterbox.app.movies.ui.player.PlayerBrightnessHelper
import com.laterbox.app.movies.ui.player.PlayerBrightnessHud
import com.laterbox.app.movies.ui.player.PlayerGesturesMath
import com.laterbox.app.movies.ui.player.PlayerHold2xSpeedBadge
import com.laterbox.app.movies.ui.player.PlayerHorizontalScrubHud
import com.laterbox.app.movies.ui.player.PlayerSeekFeedbackBadge
import com.laterbox.app.movies.ui.player.PlayerTapGestureTracker
import com.laterbox.app.movies.ui.player.PlayerTapOutcome
import com.laterbox.app.movies.ui.player.PlayerVolumeBoostHud
import com.laterbox.app.movies.ui.player.SeekSensitivityTier
import com.laterbox.app.movies.ui.player.SubtitleStyleDialog
import com.laterbox.app.movies.ui.player.SubtitleStyleHelper
import com.laterbox.app.movies.ui.player.playerDragGestures
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(
    uiState: MoviePlayerUiState,
    onBackClick: () -> Unit,
    onRetry: () -> Unit,
    onSelectSource: (source: PlaybackSource, currentPosMs: Long) -> Unit,
    onSelectSubtitle: (track: SubtitleTrack?, currentPosMs: Long) -> Unit,
    onSaveProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onDownloadSource: (PlaybackSource) -> Unit = {},
    onDismissDownloadStatus: () -> Unit = {},
    onSelectAudioDub: (dub: AudioDubInfo, currentPosMs: Long) -> Unit = { _, _ -> },
    onSelectInStreamAudio: (language: String?, label: String?) -> Unit = { _, _ -> },
    onNextEpisode: () -> Unit = {},
    onSubtitleStyleChange: (SubtitleStyleConfig) -> Unit = {},
    onOpenRelatedTitle: (providerId: String, titleId: String) -> Unit = { _, _ -> },
    onSaveProgressForEpisode: ((positionMs: Long, durationMs: Long, expectedEpisodeKey: String) -> Unit)? = null,
    introSkipEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    @androidx.annotation.VisibleForTesting simulatePlaybackEndedForTest: Boolean = false
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("movie_player_screen")
    ) {
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = colors.accentPrimary)
                        Text(
                            text = "Loading video...",
                            style = typography.bodySecondary,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            uiState.errorMessage != null -> {
                PlayerMessageOverlay(
                    title = "Playback Error",
                    message = uiState.errorMessage,
                    primaryLabel = "Retry",
                    onPrimaryClick = onRetry,
                    secondaryLabel = "Back",
                    onSecondaryClick = onBackClick
                )
            }
            uiState.resolution is PlaybackResolution.Unavailable -> {
                val unavailable = uiState.resolution
                val extUrl = unavailable.externalReferenceUrl
                PlayerMessageOverlay(
                    title = unavailable.title,
                    message = unavailable.reason,
                    primaryLabel = if (!extUrl.isNullOrBlank()) "View Episode Guide" else "Back",
                    onPrimaryClick = {
                        if (!extUrl.isNullOrBlank()) onOpenExternalUrl(extUrl) else onBackClick()
                    },
                    secondaryLabel = if (!extUrl.isNullOrBlank()) "Back" else null,
                    onSecondaryClick = onBackClick,
                    modifier = Modifier.testTag("player_unavailable_state")
                )
            }
            uiState.resolution is PlaybackResolution.Available && uiState.selectedSource != null -> {
                val currentEpisodeKey = uiState.episodeKey
                ActiveVideoPlayerContent(
                    resolution = uiState.resolution,
                    selectedSource = uiState.selectedSource,
                    selectedSubtitle = uiState.selectedSubtitleTrack,
                    startPositionMs = uiState.startPositionMs,
                    activeTitleId = uiState.activeTitleId,
                    availableDubs = uiState.availableDubs,
                    nextEpisodeTarget = uiState.nextEpisodeTarget,
                    nextEpisodePreview = uiState.nextEpisodePreview,
                    relatedTitles = uiState.relatedTitles,
                    playbackPreferences = uiState.playbackPreferences,
                    preferredAudioLanguage = uiState.preferredAudioLanguage,
                    isPlayingOffline = uiState.isPlayingOffline || uiState.selectedSource.isOfflineLocal,
                    introWindow = uiState.introWindow,
                    recapWindow = uiState.recapWindow,
                    seasonNumber = uiState.seasonNumber,
                    episodeNumber = uiState.episodeNumber,
                    isEnqueuingDownload = uiState.isEnqueuingDownload,
                    downloadStatusMessage = uiState.downloadStatusMessage,
                    isLocalFile = false,
                    simulatePlaybackEndedForTest = simulatePlaybackEndedForTest,
                    onBackClick = onBackClick,
                    onSelectSource = onSelectSource,
                    onSelectSubtitle = onSelectSubtitle,
                    onSelectAudioDub = onSelectAudioDub,
                    onSelectInStreamAudio = onSelectInStreamAudio,
                    onNextEpisode = onNextEpisode,
                    onSaveProgressForEpisode = onSaveProgressForEpisode ?: { pos, dur, expectedKey ->
                        if (expectedKey == currentEpisodeKey) {
                            onSaveProgress(pos, dur)
                        }
                    },
                    onOpenExternalUrl = onOpenExternalUrl,
                    onDownloadSource = onDownloadSource,
                    onDismissDownloadStatus = onDismissDownloadStatus,
                    onSubtitleStyleChange = onSubtitleStyleChange,
                    onOpenRelatedTitle = onOpenRelatedTitle,
                    introSkipEnabled = introSkipEnabled
                )
            }
        }

        // Top close button in loading state so user is never trapped
        if (uiState.isLoading) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(12.dp)
                    .align(Alignment.TopStart)
                    .testTag("player_back_button")
            ) {
                Icon(
                    painter = painterResource(LaterboxIcons.ArrowLeft),
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun LocalVideoPlayerScreen(
    asset: com.laterbox.app.media.domain.model.MediaAsset,
    onBackClick: () -> Unit,
    playbackPreferences: PlayerPlaybackPreferences = PlayerPlaybackPreferences(),
    onSubtitleStyleChange: (SubtitleStyleConfig) -> Unit = {},
    offlineSubtitles: List<SubtitleTrack>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedFile = remember(asset.localPath) {
        val direct = java.io.File(asset.localPath)
        if (direct.isAbsolute && direct.exists()) {
            direct
        } else {
            java.io.File(context.filesDir, "media/${asset.localPath}")
        }
    }
    val playbackUriString = remember(asset.id, asset.localPath, asset.exportedUri, asset.originalSourceUrl) {
        when {
            resolvedFile.exists() -> Uri.fromFile(resolvedFile).toString()
            !asset.exportedUri.isNullOrBlank() -> asset.exportedUri
            asset.localPath.startsWith("content://") ||
                asset.localPath.startsWith("file://") ||
                asset.localPath.startsWith("http") -> asset.localPath
            else -> Uri.fromFile(resolvedFile).toString()
        }
    }
    val displayTitle = remember(asset.id, asset.title, asset.localPath) {
        asset.title?.takeIf { it.isNotBlank() }
            ?: resolvedFile.name.takeIf { it.isNotBlank() }
            ?: "Saved Video"
    }
    val resolvedSubtitles = remember(asset.id, offlineSubtitles) {
        offlineSubtitles
            ?: com.laterbox.app.movies.data.download.SubtitleDownloadManager(context.applicationContext)
                .getOfflineSubtitleTracks(asset.id)
    }
    var selectedSubtitle by remember(asset.id, resolvedSubtitles) {
        mutableStateOf(
            resolvedSubtitles.firstOrNull { it.languageCode.equals("en", ignoreCase = true) }
                ?: resolvedSubtitles.firstOrNull()
        )
    }
    val localSource = remember(asset.id, playbackUriString) {
        PlaybackSource(
            id = "local_${asset.id}",
            url = playbackUriString,
            format = asset.mimeType.substringAfterLast('/', "MP4").uppercase(Locale.US),
            qualityLabel = "Offline",
            qualityHeight = asset.height ?: 720,
            providerName = "Local",
            isAuthorised = false
        )
    }
    val localResolution = remember(asset.id, displayTitle, localSource, resolvedSubtitles) {
        PlaybackResolution.Available(
            title = displayTitle,
            sources = listOf(localSource),
            subtitles = resolvedSubtitles
        )
    }
    val appContainer = remember(context) {
        (context.applicationContext as? com.laterbox.app.LaterboxApplication)?.container
    }
    var savedPositionMs by rememberSaveable(asset.id) { mutableLongStateOf(0L) }
    var hasLoadedSavedProgress by remember(asset.id) { mutableStateOf(appContainer == null) }
    val movieRefParts = remember(asset.sourceRef) {
        com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.parseMediaParts(asset.sourceRef)
    }
    val progressKey = remember(asset.id, asset.sourceRef) {
        com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.forAsset(asset)
    }
    val playerScope = rememberCoroutineScope()

    LaunchedEffect(asset.id, progressKey) {
        if (appContainer == null) return@LaunchedEffect
        val progressDao = appContainer.database.downloadPlaybackProgressDao()
        val moviesRepo = appContainer.moviesRepository
        val loadedPos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dlEntry = progressDao.getByKey(progressKey)
            val dlValidPos = if (dlEntry != null &&
                !(dlEntry.durationMs > 0L && dlEntry.positionMs.toFloat() / dlEntry.durationMs.toFloat() >= 0.96f)
            ) {
                dlEntry.positionMs
            } else {
                0L
            }
            val moviePos = if (movieRefParts != null) {
                runCatching {
                    moviesRepo.getSavedPositionMs(
                        providerId = movieRefParts.providerId,
                        titleId = movieRefParts.titleId,
                        seasonNumber = movieRefParts.seasonNumber,
                        episodeNumber = movieRefParts.episodeNumber
                    )
                }.getOrDefault(0L)
            } else {
                0L
            }
            maxOf(dlValidPos, moviePos)
        }
        if (savedPositionMs == 0L && loadedPos > 0L) {
            savedPositionMs = loadedPos
        }
        hasLoadedSavedProgress = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("movie_player_screen")
    ) {
        ActiveVideoPlayerContent(
            resolution = localResolution,
            selectedSource = localSource,
            selectedSubtitle = selectedSubtitle,
            startPositionMs = savedPositionMs,
            activeTitleId = movieRefParts?.titleId ?: asset.id,
            availableDubs = emptyList(),
            nextEpisodeTarget = null,
            nextEpisodePreview = null,
            relatedTitles = emptyList(),
            playbackPreferences = playbackPreferences,
            preferredAudioLanguage = null,
            isPlayingOffline = true,
            introWindow = null,
            recapWindow = null,
            seasonNumber = movieRefParts?.seasonNumber,
            episodeNumber = movieRefParts?.episodeNumber,
            isEnqueuingDownload = false,
            downloadStatusMessage = null,
            isLocalFile = true,
            onBackClick = onBackClick,
            onSelectSource = { _, _ -> },
            onSelectSubtitle = { track, posMs ->
                savedPositionMs = posMs.coerceAtLeast(0L)
                selectedSubtitle = track
            },
            onSelectAudioDub = { _, _ -> },
            onSelectInStreamAudio = { _, _ -> },
            onNextEpisode = {},
            onSaveProgressForEpisode = { pos, dur, _ ->
                if (hasLoadedSavedProgress && (pos > 0L || dur > 0L)) {
                    val cleanPos = pos.coerceAtLeast(0L)
                    val cleanDur = dur.coerceAtLeast(0L)
                    if (cleanPos > 0L) {
                        savedPositionMs = cleanPos
                    }
                    val progressDao = appContainer?.database?.downloadPlaybackProgressDao()
                    val moviesRepo = appContainer?.moviesRepository
                    playerScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        progressDao?.upsert(
                            com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressEntity(
                                progressKey = progressKey,
                                assetId = asset.id,
                                sourceRef = asset.sourceRef,
                                positionMs = cleanPos,
                                durationMs = cleanDur,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        if (movieRefParts != null && moviesRepo != null && cleanPos > 0L) {
                            val summary = moviesRepo.getCachedSummary(movieRefParts.providerId, movieRefParts.titleId)
                                ?: MovieTitleSummary(
                                    id = movieRefParts.titleId,
                                    providerId = movieRefParts.providerId,
                                    kind = if (movieRefParts.seasonNumber != null || movieRefParts.episodeNumber != null) {
                                        MediaKind.TV_SERIES
                                    } else {
                                        MediaKind.MOVIE
                                    },
                                    title = displayTitle,
                                    posterUrl = asset.artworkUrl,
                                    sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                                    isPlayableHint = true
                                )
                            runCatching {
                                moviesRepo.saveWatchProgress(
                                    item = summary,
                                    positionMs = cleanPos,
                                    durationMs = cleanDur,
                                    seasonNumber = movieRefParts.seasonNumber,
                                    episodeNumber = movieRefParts.episodeNumber,
                                    episodeLabel = if (movieRefParts.seasonNumber != null && movieRefParts.episodeNumber != null) {
                                        "S${movieRefParts.seasonNumber} · E${movieRefParts.episodeNumber}"
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }
            },
                onOpenExternalUrl = {},
                onDownloadSource = {},
                onDismissDownloadStatus = {},
                onSubtitleStyleChange = onSubtitleStyleChange,
                onOpenRelatedTitle = { _, _ -> }
        )
    }
}

private data class PlayerAudioTrackOption(
    val group: TrackGroup,
    val trackIndex: Int,
    val label: String,
    val languageCode: String?,
    val isSelected: Boolean
)

@OptIn(UnstableApi::class)
@Composable
private fun ActiveVideoPlayerContent(
    resolution: PlaybackResolution.Available,
    selectedSource: PlaybackSource,
    selectedSubtitle: SubtitleTrack?,
    startPositionMs: Long,
    activeTitleId: String,
    availableDubs: List<AudioDubInfo>,
    nextEpisodeTarget: Pair<Int, Int>?,
    nextEpisodePreview: NextEpisodePreview?,
    relatedTitles: List<MovieTitleSummary>,
    playbackPreferences: PlayerPlaybackPreferences,
    preferredAudioLanguage: String?,
    isPlayingOffline: Boolean,
    introWindow: PlaybackMarkerWindow?,
    recapWindow: PlaybackMarkerWindow?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    isEnqueuingDownload: Boolean,
    downloadStatusMessage: String?,
    isLocalFile: Boolean = false,
    simulatePlaybackEndedForTest: Boolean = false,
    onBackClick: () -> Unit,
    onSelectSource: (PlaybackSource, Long) -> Unit,
    onSelectSubtitle: (SubtitleTrack?, Long) -> Unit,
    onSelectAudioDub: (AudioDubInfo, Long) -> Unit,
    onSelectInStreamAudio: (String?, String?) -> Unit,
    onNextEpisode: () -> Unit,
    onSaveProgressForEpisode: (Long, Long, String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onDownloadSource: (PlaybackSource) -> Unit,
    onDismissDownloadStatus: () -> Unit,
    onSubtitleStyleChange: (SubtitleStyleConfig) -> Unit,
    onOpenRelatedTitle: (String, String) -> Unit,
    introSkipEnabled: Boolean = true
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val viewConfiguration = LocalViewConfiguration.current
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showControls by remember { mutableStateOf(true) }
    var isLocked by rememberSaveable { mutableStateOf(false) }
    var forcedLandscape by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val effectiveIsLandscape = forcedLandscape ?: isLandscape
    var selectedSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }
    var isHolding2x by remember { mutableStateOf(false) }
    var ignoreNextTap by remember { mutableStateOf(false) }
    var resizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val playbackEpisodeKey = "${activeTitleId}:${seasonNumber ?: 0}:${episodeNumber ?: 0}"

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    val clockState = remember(playbackEpisodeKey) {
        PlayerClockState(initialPositionMs = startPositionMs.coerceAtLeast(0L))
    }
    val tapTracker = remember(playbackEpisodeKey) { PlayerTapGestureTracker() }
    var isSeeking by remember { mutableStateOf(false) }
    var seekFeedbackText by remember { mutableStateOf<String?>(null) }

    var inStreamAudioTracks by remember { mutableStateOf<List<PlayerAudioTrackOption>>(emptyList()) }
    var hasManualAudioOverride by remember { mutableStateOf(false) }
    var hasAppliedPreferredAudio by remember(activeTitleId, seasonNumber, episodeNumber, selectedSource.id) {
        mutableStateOf(false)
    }

    var showQualityDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var playerErrorText by remember { mutableStateOf<String?>(null) }

    val latestSaveEpisodeProgress by rememberUpdatedState(onSaveProgressForEpisode)
    val latestSelectedSpeed by rememberUpdatedState(selectedSpeed)
    val latestIsLocked by rememberUpdatedState(isLocked)
    val latestShowControls by rememberUpdatedState(showControls)
    val latestStartPositionMs by rememberUpdatedState(startPositionMs)
    val latestOnNextEpisode by rememberUpdatedState(onNextEpisode)

    val brightnessHelper = remember(activity) { PlayerBrightnessHelper(activity) }
    var brightnessFraction by remember { mutableFloatStateOf(brightnessHelper.currentNormalizedBrightness) }
    var showBrightnessHud by remember { mutableStateOf(false) }
    var isBrightnessDragging by remember { mutableStateOf(false) }

    val audioBoostController = remember(context) { PlayerAudioBoostController(context) }
    var currentVolumePercent by remember { mutableIntStateOf(audioBoostController.currentVolumePercent) }
    var volumeFloatPercent by remember { mutableFloatStateOf(audioBoostController.currentVolumePercent.toFloat()) }
    var showVolumeHud by remember { mutableStateOf(false) }
    var isVolumeDragging by remember { mutableStateOf(false) }

    val baseSensitivityTier = remember(playbackPreferences.swipeSeekSensitivity) {
        SeekSensitivityTier.fromPreference(playbackPreferences.swipeSeekSensitivity)
    }
    var isHorizontalScrubbing by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableLongStateOf(0L) }
    var scrubDeltaMs by remember { mutableLongStateOf(0L) }
    var scrubSensitivityTier by remember(baseSensitivityTier) { mutableStateOf(baseSensitivityTier) }

    var localSubtitleStyle by remember(playbackPreferences.subtitleStyle) {
        mutableStateOf(playbackPreferences.subtitleStyle)
    }
    var showSubtitleStyleDialog by remember { mutableStateOf(false) }

    var isPlaybackEnded by remember(playbackEpisodeKey, simulatePlaybackEndedForTest) {
        mutableStateOf(simulatePlaybackEndedForTest)
    }
    var nextEpisodeCancelled by remember(playbackEpisodeKey) { mutableStateOf(false) }
    var endCardDismissed by remember(playbackEpisodeKey) { mutableStateOf(false) }
    var countdownStarted by remember(playbackEpisodeKey) { mutableStateOf(false) }
    var countdownSecondsLeft by remember(playbackEpisodeKey, playbackPreferences.autoplayCountdownSeconds) {
        mutableIntStateOf(playbackPreferences.autoplayCountdownSeconds)
    }
    var lastLoadedEpisodeKey by remember { mutableStateOf<String?>(null) }

    val isAdaptiveSource = remember(selectedSource.id, selectedSource.format, selectedSource.url) {
        selectedSource.format.equals("DASH", ignoreCase = true) ||
            selectedSource.format.equals("HLS", ignoreCase = true) ||
            selectedSource.url.contains(".mpd", ignoreCase = true) ||
            selectedSource.url.contains(".m3u8", ignoreCase = true)
    }

    val appContext = remember(context) { context.applicationContext }
    var attachedPlayerView by remember { mutableStateOf<PlayerView?>(null) }

    val exoPlayer = remember(playbackEpisodeKey, isLocalFile) {
        val audioAttributes = PlaybackSessionService.buildConfiguredAudioAttributes()
        val seekIncrementMs = (playbackPreferences.doubleTapSeekSeconds.coerceIn(5, 30)) * 1000L
        ExoPlayer.Builder(appContext)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(seekIncrementMs)
            .setSeekForwardIncrementMs(seekIncrementMs)
            .build()
    }

    DisposableEffect(selectedSource.url, isLocalFile) {
        if (!isLocalFile) {
            MoviesMediaCache.markStreamActive(selectedSource.url)
        }
        onDispose {
            if (!isLocalFile) {
                MoviesMediaCache.markStreamInactive(selectedSource.url)
            }
        }
    }

    BackHandler(enabled = isLocked) {
        showControls = true
    }

    BackHandler(enabled = !isLocked) {
        when {
            showMoreMenu -> showMoreMenu = false
            showQualityDialog -> showQualityDialog = false
            showSubtitleDialog -> showSubtitleDialog = false
            showAudioDialog -> showAudioDialog = false
            showSpeedDialog -> showSpeedDialog = false
            showDownloadDialog -> showDownloadDialog = false
            showSubtitleStyleDialog -> showSubtitleStyleDialog = false
            else -> {
                val pos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
                val dur = runCatching { exoPlayer.duration.coerceAtLeast(0L) }.getOrDefault(0L)
                latestSaveEpisodeProgress(pos, dur, playbackEpisodeKey)
                attachedPlayerView?.player = null
                LaterboxPlaybackBridge.stopAndDetach(appContext)
                onBackClick()
            }
        }
    }

    LaunchedEffect(effectiveIsLandscape, isLocked) {
        showMoreMenu = false
        if (isLocked) {
            isHolding2x = false
            ignoreNextTap = false
        }
    }

    DisposableEffect(brightnessHelper) {
        onDispose {
            brightnessHelper.restoreOriginalBrightness()
        }
    }

    // Genuine full-screen mode by default + manual orientation toggle handling
    LaunchedEffect(forcedLandscape, effectiveIsLandscape, activity) {
        val act = activity ?: return@LaunchedEffect
        val window = act.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        act.requestedOrientation = when {
            forcedLandscape == true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            forcedLandscape == false -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.let { act ->
                if (!act.isChangingConfigurations) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    LaunchedEffect(exoPlayer, playbackEpisodeKey, selectedSource.id, selectedSource.url, selectedSource.headers, isAdaptiveSource, selectedSubtitle?.id) {
        val isEpisodeTransition = lastLoadedEpisodeKey != playbackEpisodeKey
        lastLoadedEpisodeKey = playbackEpisodeKey
        playerErrorText = null
        isBuffering = true
        isPlaybackEnded = false
        hasManualAudioOverride = false
        try {
            val metadata = MediaMetadata.Builder()
                .setTitle(resolution.title)
                .setDisplayTitle(resolution.title)
                .setSubtitle(resolution.subtitleHeading)
                .setIsPlayable(true)
                .build()
            val itemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(selectedSource.url))
                .setMediaMetadata(metadata)

            val streamMimeType = when {
                selectedSource.format.equals("DASH", ignoreCase = true) ||
                    selectedSource.url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                selectedSource.format.equals("HLS", ignoreCase = true) ||
                    selectedSource.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                else -> null
            }
            if (streamMimeType != null) {
                itemBuilder.setMimeType(streamMimeType)
            }

            if (selectedSubtitle != null) {
                val mimeType = if (selectedSubtitle.format.equals("vtt", ignoreCase = true)) {
                    MimeTypes.TEXT_VTT
                } else {
                    MimeTypes.APPLICATION_SUBRIP
                }
                val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(selectedSubtitle.url))
                    .setMimeType(mimeType)
                    .setLanguage(selectedSubtitle.languageCode)
                    .setLabel(selectedSubtitle.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                itemBuilder.setSubtitleConfigurations(listOf(subConfig))
            }

            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitle == null)
                .build()

            val mediaItem = itemBuilder.build()
            if (isLocalFile) {
                exoPlayer.setMediaItem(mediaItem)
            } else {
                val mediaSource = MoviesMediaCache
                    .createMediaSourceFactory(
                        context = appContext,
                        headers = selectedSource.headers,
                        useDiskCache = !isAdaptiveSource
                    )
                    .createMediaSource(mediaItem)
                exoPlayer.setMediaSource(mediaSource)
            }
            val resumeAtMs = if (isEpisodeTransition) {
                latestStartPositionMs.coerceAtLeast(0L)
            } else {
                maxOf(latestStartPositionMs, clockState.positionMs).coerceAtLeast(0L)
            }
            clockState.positionMs = resumeAtMs
            if (isEpisodeTransition) {
                clockState.durationMs = 0L
            }
            if (resumeAtMs > 0L) {
                exoPlayer.seekTo(resumeAtMs)
            }
            exoPlayer.setPlaybackSpeed(latestSelectedSpeed)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            LaterboxPlaybackBridge.updateMetadata(
                title = resolution.title,
                subtitle = resolution.subtitleHeading
            )
        } catch (err: kotlinx.coroutines.CancellationException) {
            throw err
        } catch (err: Throwable) {
            Log.e("MoviePlayerScreen", "Failed preparing stream playback", err)
            isBuffering = false
            playerErrorText = err.message?.takeIf { it.isNotBlank() }
                ?: "Unable to prepare playback stream."
        }
    }

    LaunchedEffect(exoPlayer, playbackEpisodeKey, startPositionMs) {
        if (startPositionMs > 0L && exoPlayer.currentPosition < 2_000L && clockState.positionMs < startPositionMs) {
            exoPlayer.seekTo(startPositionMs)
            clockState.positionMs = startPositionMs
        }
    }

    DisposableEffect(exoPlayer, audioBoostController, playbackEpisodeKey) {
        val snapshotEpisodeKey = playbackEpisodeKey
        var hasSavedEpisodeStart = false
        audioBoostController.bindToPlayer(exoPlayer)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                isPlaybackEnded = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY && !hasSavedEpisodeStart) {
                    val readyDur = exoPlayer.duration.coerceAtLeast(0L)
                    if (readyDur > 0L) {
                        hasSavedEpisodeStart = true
                        latestSaveEpisodeProgress(
                            exoPlayer.currentPosition.coerceAtLeast(0L),
                            readyDur,
                            snapshotEpisodeKey
                        )
                    }
                }
                if (playbackState == Player.STATE_ENDED) {
                    audioBoostController.resetBoost(exoPlayer)
                    currentVolumePercent = audioBoostController.currentVolumePercent
                    volumeFloatPercent = currentVolumePercent.toFloat()
                    latestSaveEpisodeProgress(
                        exoPlayer.currentPosition.coerceAtLeast(0L),
                        exoPlayer.duration.coerceAtLeast(0L),
                        snapshotEpisodeKey
                    )
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                inStreamAudioTracks = extractAudioTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playerErrorText = formatPlayerErrorMessage(error)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        LaterboxPlaybackBridge.attachPlayer(
            context = appContext,
            player = exoPlayer,
            title = resolution.title,
            subtitle = resolution.subtitleHeading
        )
        onDispose {
            val pos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
            val dur = runCatching { exoPlayer.duration.coerceAtLeast(0L) }.getOrDefault(0L)
            if (pos > 0L) {
                clockState.positionMs = pos
            }
            latestSaveEpisodeProgress(pos, dur, snapshotEpisodeKey)
            audioBoostController.release()
            exoPlayer.removeListener(listener)
            // Detach PlayerView BEFORE releasing MediaSession and ExoPlayer
            attachedPlayerView?.player = null
            attachedPlayerView = null
            LaterboxPlaybackBridge.releasePlayer(appContext, exoPlayer)
        }
    }

    LaunchedEffect(inStreamAudioTracks, preferredAudioLanguage, hasManualAudioOverride, hasAppliedPreferredAudio) {
        val pref = preferredAudioLanguage?.trim().takeIf { !it.isNullOrEmpty() } ?: return@LaunchedEffect
        if (hasAppliedPreferredAudio || hasManualAudioOverride || inStreamAudioTracks.size <= 1) return@LaunchedEffect
        val match = inStreamAudioTracks.firstOrNull { opt ->
            opt.languageCode?.equals(pref, ignoreCase = true) == true ||
                opt.label.contains(pref, ignoreCase = true)
        }
        hasAppliedPreferredAudio = true
        if (match != null && !match.isSelected) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(TrackSelectionOverride(match.group, match.trackIndex))
                .build()
        }
    }

    // Periodic progress tracker & playback position updater (writes only to isolated clockState)
    LaunchedEffect(exoPlayer, playbackEpisodeKey, clockState) {
        val snapshotEpisodeKey = playbackEpisodeKey
        var tick = 0
        while (true) {
            val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.coerceAtLeast(0L)
            val stateNow = exoPlayer.playbackState
            isBuffering = stateNow == Player.STATE_BUFFERING
            isPlaying = exoPlayer.isPlaying
            isPlaybackEnded = stateNow == Player.STATE_ENDED
            clockState.durationMs = dur
            if (!isSeeking && !isHorizontalScrubbing) {
                clockState.positionMs = cur
            }
            tick++
            if (tick % 12 == 0 && exoPlayer.isPlaying) {
                latestSaveEpisodeProgress(cur, dur, snapshotEpisodeKey)
            }
            delay(400L)
        }
    }

    // Auto-hide controls after 3.5s during active non-buffering playback
    LaunchedEffect(
        showControls,
        isPlaying,
        isBuffering,
        isLocked,
        showQualityDialog,
        showSubtitleDialog,
        showSubtitleStyleDialog,
        showAudioDialog,
        showSpeedDialog,
        showDownloadDialog,
        showMoreMenu,
        isSeeking,
        isHorizontalScrubbing,
        playerErrorText
    ) {
        if (showControls && isPlaying && !isBuffering && !isLocked && playerErrorText == null &&
            !showQualityDialog && !showSubtitleDialog && !showSubtitleStyleDialog && !showAudioDialog &&
            !showSpeedDialog && !showDownloadDialog && !showMoreMenu && !isSeeking && !isHorizontalScrubbing
        ) {
            delay(3_500L)
            showControls = false
        }
    }

    // Auto-clear brief double-tap seek indicator
    LaunchedEffect(seekFeedbackText) {
        if (seekFeedbackText != null) {
            delay(750L)
            seekFeedbackText = null
        }
    }

    LaunchedEffect(showBrightnessHud, brightnessFraction, isBrightnessDragging) {
        if (showBrightnessHud && !isBrightnessDragging) {
            delay(1_200L)
            showBrightnessHud = false
        }
    }

    LaunchedEffect(showVolumeHud, currentVolumePercent, isVolumeDragging) {
        if (showVolumeHud && !isVolumeDragging) {
            delay(1_400L)
            showVolumeHud = false
        }
    }

    val effectivePlaybackEnded = isPlaybackEnded || simulatePlaybackEndedForTest
    val hasNextEpisode = !isLocalFile && (nextEpisodePreview != null || nextEpisodeTarget != null)
    val nearEndWindow by remember(clockState) {
        derivedStateOf {
            clockState.durationMs > 25_000L && (clockState.durationMs - clockState.positionMs) in 1L..10_000L
        }
    }
    val showNextEpisodeCard = hasNextEpisode && !nextEpisodeCancelled && (nearEndWindow || effectivePlaybackEnded) && playerErrorText == null
    val showMovieEndCard = !isLocalFile && !hasNextEpisode && effectivePlaybackEnded && !endCardDismissed && playerErrorText == null

    LaunchedEffect(
        showNextEpisodeCard,
        nextEpisodeCancelled,
        playbackPreferences.autoplayNextEpisode,
        playbackPreferences.autoplayCountdownSeconds,
        playbackEpisodeKey
    ) {
        val snapshotEpisodeKey = playbackEpisodeKey
        if (showNextEpisodeCard && playbackPreferences.autoplayNextEpisode && !nextEpisodeCancelled && !countdownStarted) {
            countdownStarted = true
            var remaining = playbackPreferences.autoplayCountdownSeconds.coerceIn(3, 30)
            countdownSecondsLeft = remaining
            while (remaining > 0 && !nextEpisodeCancelled) {
                delay(1_000L)
                remaining--
                countdownSecondsLeft = remaining
            }
            if (!nextEpisodeCancelled && remaining <= 0) {
                latestSaveEpisodeProgress(
                    exoPlayer.currentPosition.coerceAtLeast(0L),
                    exoPlayer.duration.coerceAtLeast(0L),
                    snapshotEpisodeKey
                )
                latestOnNextEpisode()
            }
        }
    }

    val seekJumpMs = (playbackPreferences.doubleTapSeekSeconds.coerceIn(5, 60) * 1000L)
    val seekJumpSeconds = playbackPreferences.doubleTapSeekSeconds.coerceIn(5, 60)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .playerDragGestures(
                enabled = !isLocked && playerErrorText == null,
                swipeToSeekEnabled = playbackPreferences.swipeToSeekEnabled,
                brightnessGestureEnabled = playbackPreferences.brightnessGestureEnabled,
                volumeGestureEnabled = playbackPreferences.volumeGestureEnabled,
                baseSensitivity = baseSensitivityTier,
                currentPositionProvider = { exoPlayer.currentPosition.coerceAtLeast(0L) },
                durationProvider = { exoPlayer.duration.coerceAtLeast(0L) },
                onHorizontalScrubStart = { startPos ->
                    isHorizontalScrubbing = true
                    scrubTargetMs = startPos
                    scrubDeltaMs = 0L
                    scrubSensitivityTier = baseSensitivityTier
                },
                onHorizontalScrubUpdate = { targetMs, deltaMs, sensitivity ->
                    scrubTargetMs = targetMs
                    scrubDeltaMs = deltaMs
                    scrubSensitivityTier = sensitivity
                },
                onHorizontalScrubEnd = { finalTargetMs ->
                    exoPlayer.seekTo(finalTargetMs)
                    clockState.positionMs = finalTargetMs
                    isHorizontalScrubbing = false
                    latestSaveEpisodeProgress(
                        finalTargetMs,
                        exoPlayer.duration.coerceAtLeast(0L),
                        playbackEpisodeKey
                    )
                },
                onHorizontalScrubCancel = {
                    isHorizontalScrubbing = false
                },
                onBrightnessDelta = { deltaFraction ->
                    isBrightnessDragging = true
                    showBrightnessHud = true
                    val updated = PlayerGesturesMath.applyBrightnessDelta(brightnessFraction, deltaFraction)
                    brightnessFraction = updated
                    brightnessHelper.setBrightness(updated)
                },
                onBrightnessGestureEnd = {
                    isBrightnessDragging = false
                },
                onVolumeDelta = { deltaPercent ->
                    isVolumeDragging = true
                    showVolumeHud = true
                    val maxAllowed = if (audioBoostController.isBoostSupported) {
                        PlayerAudioBoostController.MAX_PERCENT
                    } else {
                        PlayerAudioBoostController.NORMAL_MAX_PERCENT
                    }
                    val updatedFloat = PlayerGesturesMath.applyVolumeDelta(volumeFloatPercent, deltaPercent, maxAllowed)
                    volumeFloatPercent = updatedFloat
                    val applied = audioBoostController.applyVolumePercent(updatedFloat.roundToInt(), exoPlayer)
                    currentVolumePercent = applied
                },
                onVolumeGestureEnd = {
                    isVolumeDragging = false
                }
            )
            .pointerInput(playbackEpisodeKey, exoPlayer) {
                // Tap-and-Hold 2x Speed gesture respecting child control consumption on Main pass
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    ignoreNextTap = false
                    val downPos = down.position
                    val slop = viewConfiguration.touchSlop
                    var activatedHold = false
                    var releasedOrMovedOut = false

                    try {
                        val deadline = System.currentTimeMillis() + 320L
                        while (!releasedOrMovedOut && !activatedHold) {
                            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                            val event = withTimeoutOrNull(remaining) {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                            if (event == null) {
                                if (!latestIsLocked && !isSeeking && !isBrightnessDragging && !isVolumeDragging && !isHorizontalScrubbing) {
                                    activatedHold = true
                                    isHolding2x = true
                                    ignoreNextTap = true
                                    tapTracker.reset()
                                    exoPlayer.setPlaybackSpeed(2.0f)
                                } else {
                                    break
                                }
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null ||
                                    !change.pressed ||
                                    change.isConsumed ||
                                    abs(change.position.x - downPos.x) > slop ||
                                    abs(change.position.y - downPos.y) > slop
                                ) {
                                    releasedOrMovedOut = true
                                }
                            }
                        }

                        while (activatedHold) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                break
                            }
                        }
                    } finally {
                        if (activatedHold) {
                            isHolding2x = false
                            exoPlayer.setPlaybackSpeed(latestSelectedSpeed)
                        }
                    }
                }
            }
            .pointerInput(playbackEpisodeKey, exoPlayer, seekJumpMs, seekJumpSeconds) {
                // Zero-delay single-tap controls toggle + non-blocking consecutive left/right seek accumulation
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downPos = down.position
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.count { it.pressed } > 1) {
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed ||
                            abs(change.position.x - downPos.x) > slop ||
                            abs(change.position.y - downPos.y) > slop
                        ) {
                            break
                        }
                        if (!change.pressed) {
                            if (ignoreNextTap) {
                                ignoreNextTap = false
                            } else {
                                when (
                                    val outcome = tapTracker.onTap(
                                        touchX = change.position.x,
                                        widthPx = size.width.toFloat(),
                                        controlsVisible = latestShowControls,
                                        isLocked = latestIsLocked,
                                        stepSeconds = seekJumpSeconds,
                                        uptimeMs = change.uptimeMillis
                                    )
                                ) {
                                    is PlayerTapOutcome.SetControlsVisible -> {
                                        showControls = outcome.visible
                                    }
                                    is PlayerTapOutcome.SeekDelta -> {
                                        val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
                                        val dur = exoPlayer.duration.coerceAtLeast(0L)
                                        val stepMs = outcome.stepSeconds * 1000L
                                        val target = if (outcome.direction < 0) {
                                            (cur - stepMs).coerceAtLeast(0L)
                                        } else if (dur > 0L) {
                                            (cur + stepMs).coerceAtMost(dur)
                                        } else {
                                            cur + stepMs
                                        }
                                        exoPlayer.seekTo(target)
                                        clockState.positionMs = target
                                        seekFeedbackText = if (outcome.direction < 0) {
                                            "-${outcome.accumulatedSeconds}s"
                                        } else {
                                            "+${outcome.accumulatedSeconds}s"
                                        }
                                        showControls = outcome.keepControlsVisible
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            }
    ) {
        LaterboxVideoSurface(
            player = exoPlayer,
            resizeMode = resizeMode,
            keepScreenOn = isPlaying || isBuffering,
            useTextureView = false,
            subtitleStyle = localSubtitleStyle,
            onPlayerViewAttached = { attachedPlayerView = it },
            modifier = Modifier.fillMaxSize()
        )

        // Cinema Controls Overlay
        AnimatedVisibility(
            visible = showControls && playerErrorText == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Black.copy(alpha = 0.20f),
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
                    .displayCutoutPadding()
                    .testTag("player_controls_overlay")
            ) {
                if (isLocked) {
                    // Minimal Locked Controls HUD
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .isolatePlayerControlTouch()
                            .clickable { isLocked = false }
                            .testTag("player_unlock_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Unlock),
                                contentDescription = "Unlock player controls",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Screen Locked · Tap to Unlock",
                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                        }
                    }
                } else {
                    // Top Control Bar (responsive: inline controls in landscape, More overflow menu in portrait)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .align(Alignment.TopCenter)
                            .isolatePlayerControlTouch(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                latestSaveEpisodeProgress(
                                    exoPlayer.currentPosition,
                                    exoPlayer.duration.coerceAtLeast(0L),
                                    playbackEpisodeKey
                                )
                                forcedLandscape = null
                                onBackClick()
                            },
                            modifier = Modifier.testTag("player_back_button")
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.ArrowLeft),
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = resolution.title,
                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            resolution.subtitleHeading?.let { sub ->
                                Text(
                                    text = sub,
                                    style = typography.caption,
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (isPlayingOffline || isLocalFile) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1B5E20).copy(alpha = 0.85f),
                                border = BorderStroke(0.75.dp, Color(0xFF81C784).copy(alpha = 0.7f)),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .testTag("player_offline_badge")
                            ) {
                                Text(
                                    text = "OFFLINE",
                                    style = typography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }

                        val canShowSubtitles = !isLocalFile || resolution.subtitles.isNotEmpty()
                        val canShowAudio = !isLocalFile || inStreamAudioTracks.size > 1 || availableDubs.isNotEmpty()
                        val canShowQuality = !isLocalFile
                        val activeAudioBadge = inStreamAudioTracks.firstOrNull { it.isSelected }?.languageCode?.uppercase(Locale.US)
                            ?: availableDubs.firstOrNull { it.referenceId == activeTitleId }
                                ?.language?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)

                        if (effectiveIsLandscape) {
                            // Subtitles (CC) Control
                            if (canShowSubtitles) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selectedSubtitle != null) colors.accentPrimary else Color.White.copy(alpha = 0.16f),
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSubtitleDialog = true }
                                        .testTag("player_subtitles_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(LaterboxIcons.Subtitles),
                                            contentDescription = "Subtitles",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = selectedSubtitle?.languageCode?.uppercase(Locale.US) ?: "CC",
                                            style = typography.caption.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            // Audio Track / Dub Selector
                            if (canShowAudio) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (hasManualAudioOverride || availableDubs.size > 1 || inStreamAudioTracks.size > 1) {
                                        colors.accentPrimary.copy(alpha = 0.85f)
                                    } else {
                                        Color.White.copy(alpha = 0.16f)
                                    },
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showAudioDialog = true }
                                        .testTag("player_audio_button")
                                ) {
                                    Text(
                                        text = activeAudioBadge?.let { "Audio · $it" } ?: "Audio",
                                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            // Quality Selector (Remote streams only)
                            if (canShowQuality) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.16f),
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showQualityDialog = true }
                                        .testTag("player_quality_button")
                                ) {
                                    Text(
                                        text = selectedSource.qualityLabel,
                                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        // Download Stream Button (Remote streams only)
                        if (!isLocalFile && resolution.sources.any { it.isAuthorised }) {
                            IconButton(
                                onClick = { showDownloadDialog = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("player_download_button")
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Download),
                                    contentDescription = "Download offline copy",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Lock Controls Button (Playback controls lock, independent of rotation lock)
                        IconButton(
                            onClick = { isLocked = true },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("player_lock_button")
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Lock),
                                contentDescription = "Lock controls",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (!effectiveIsLandscape && (canShowSubtitles || canShowAudio || canShowQuality)) {
                            Box {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("player_more_button")
                                ) {
                                    Icon(
                                        painter = painterResource(LaterboxIcons.MoreVertical),
                                        contentDescription = "More player options",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    modifier = Modifier.testTag("player_more_menu")
                                ) {
                                    if (canShowSubtitles) {
                                        val subSummary = selectedSubtitle?.languageCode?.uppercase(Locale.US) ?: "Off"
                                        DropdownMenuItem(
                                            text = { Text("Subtitles ($subSummary)") },
                                            onClick = {
                                                showMoreMenu = false
                                                showSubtitleDialog = true
                                            },
                                            modifier = Modifier.testTag("player_more_subtitles_item")
                                        )
                                    }
                                    if (canShowAudio) {
                                        val audioSummary = activeAudioBadge?.let { "Audio ($it)" } ?: "Audio"
                                        DropdownMenuItem(
                                            text = { Text(audioSummary) },
                                            onClick = {
                                                showMoreMenu = false
                                                showAudioDialog = true
                                            },
                                            modifier = Modifier.testTag("player_more_audio_item")
                                        )
                                    }
                                    if (canShowQuality) {
                                        DropdownMenuItem(
                                            text = { Text("Quality (${selectedSource.qualityLabel})") },
                                            onClick = {
                                                showMoreMenu = false
                                                showQualityDialog = true
                                            },
                                            modifier = Modifier.testTag("player_more_quality_item")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Center Transport Row (-seek, Play/Pause, +seek)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            modifier = Modifier
                                .size(52.dp)
                                .isolatePlayerControlTouch()
                        ) {
                            IconButton(
                                onClick = {
                                    val target = (exoPlayer.currentPosition - seekJumpMs).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    clockState.positionMs = target
                                    seekFeedbackText = "-${seekJumpSeconds}s"
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("player_replay_10_button")
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Replay10),
                                    contentDescription = "Rewind $seekJumpSeconds seconds",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = colors.accentPrimary,
                            modifier = Modifier
                                .size(66.dp)
                                .isolatePlayerControlTouch()
                        ) {
                            IconButton(
                                onClick = {
                                    val activeOrBuffering = exoPlayer.playWhenReady &&
                                        (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING)
                                    if (activeOrBuffering) {
                                        exoPlayer.pause()
                                        isPlaying = false
                                    } else {
                                        if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                            exoPlayer.seekTo(0L)
                                            isPlaybackEnded = false
                                        }
                                        exoPlayer.play()
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("player_play_pause_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isBuffering && playerErrorText == null && !isHorizontalScrubbing) {
                                        CircularProgressIndicator(
                                            color = colors.onAccentPrimary,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier
                                                .size(52.dp)
                                                .testTag("player_buffering_indicator")
                                        )
                                    }
                                    Icon(
                                        painter = painterResource(
                                            if (isPlaying) LaterboxIcons.Pause else LaterboxIcons.Play
                                        ),
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = colors.onAccentPrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            modifier = Modifier
                                .size(52.dp)
                                .isolatePlayerControlTouch()
                        ) {
                            IconButton(
                                onClick = {
                                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                                    val target = if (dur > 0L) {
                                        (exoPlayer.currentPosition + seekJumpMs).coerceAtMost(dur)
                                    } else {
                                        exoPlayer.currentPosition + seekJumpMs
                                    }
                                    exoPlayer.seekTo(target)
                                    clockState.positionMs = target
                                    seekFeedbackText = "+${seekJumpSeconds}s"
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("player_forward_10_button")
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Forward10),
                                    contentDescription = "Forward $seekJumpSeconds seconds",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }

                    // Bottom Scrubber & Utility Bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .isolatePlayerControlTouch()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PlayerTimelineBar(
                            clockState = clockState,
                            exoPlayer = exoPlayer,
                            isHorizontalScrubbing = isHorizontalScrubbing,
                            scrubTargetMs = scrubTargetMs,
                            isSeeking = isSeeking,
                            onSeekingChange = { seeking -> isSeeking = seeking },
                            onSaveProgress = { posMs, durMs ->
                                latestSaveEpisodeProgress(posMs, durMs, playbackEpisodeKey)
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Speed Selector Pill
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selectedSpeed != 1.0f) colors.accentPrimary else Color.White.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSpeedDialog = true }
                                        .testTag("player_speed_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(LaterboxIcons.Speed),
                                            contentDescription = "Playback speed",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "${selectedSpeed}x",
                                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = Color.White
                                        )
                                    }
                                }

                                // Aspect / Resize Mode Pill
                                val resizeLabel = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                                    else -> "Fit"
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            resizeMode = when (resizeMode) {
                                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            }
                                        }
                                        .testTag("player_resize_button")
                                ) {
                                    Text(
                                        text = resizeLabel,
                                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }

                                if (nextEpisodeTarget == null && !effectiveIsLandscape) {
                                    Text(
                                        text = "Hold video for 2×",
                                        style = typography.caption,
                                        color = Color.White.copy(alpha = 0.58f),
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (nextEpisodeTarget != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = colors.accentPrimary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                latestSaveEpisodeProgress(
                                                    exoPlayer.currentPosition,
                                                    exoPlayer.duration.coerceAtLeast(0L),
                                                    playbackEpisodeKey
                                                )
                                                onNextEpisode()
                                            }
                                            .testTag("player_next_episode_button")
                                    ) {
                                        Text(
                                            text = "Next S${nextEpisodeTarget.first}·E${nextEpisodeTarget.second}",
                                            style = typography.caption.copy(fontWeight = FontWeight.Bold),
                                            color = colors.onAccentPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }

                                // Icon-Only Rotate Button (switches between Landscape and Portrait)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (forcedLandscape != null) colors.accentPrimary else Color.White.copy(alpha = 0.16f),
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            forcedLandscape = !effectiveIsLandscape
                                        }
                                        .testTag("player_fullscreen_button")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.testTag("player_rotate_button")
                                    ) {
                                        Icon(
                                            painter = painterResource(LaterboxIcons.Refresh),
                                            contentDescription = if (effectiveIsLandscape) "Rotate to portrait" else "Rotate to landscape",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Left Brightness Gesture HUD
        PlayerBrightnessHud(
            visible = showBrightnessHud && playerErrorText == null,
            brightnessFraction = brightnessFraction,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 22.dp)
        )

        // Right Volume / Boost Gesture HUD (0–100% normal vs 100–200% boost zone)
        PlayerVolumeBoostHud(
            visible = showVolumeHud && playerErrorText == null,
            volumePercent = currentVolumePercent,
            isBoostSupported = audioBoostController.isBoostSupported,
            fallbackNotice = audioBoostController.lastFallbackReason,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 22.dp)
        )

        // Horizontal Scrubbing HUD with Dynamic Sensitivity
        PlayerScrubHudSlot(
            visible = isHorizontalScrubbing && playerErrorText == null,
            scrubTargetMs = scrubTargetMs,
            scrubDeltaMs = scrubDeltaMs,
            clockState = clockState,
            sensitivityTier = scrubSensitivityTier,
            modifier = Modifier.align(Alignment.Center)
        )

        // Center buffering spinner when controls are hidden or locked (controls-open buffering renders unified inside Play/Pause)
        if (isBuffering && playerErrorText == null && !isHorizontalScrubbing && (!showControls || isLocked)) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.60f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = colors.accentPrimary,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("player_buffering_indicator")
                    )
                }
            }
        }

        // Tap-and-Hold 2× speed indicator pill
        PlayerHold2xSpeedBadge(
            visible = isHolding2x,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 18.dp)
        )

        // Double-Tap Seek Feedback Badge
        PlayerSeekFeedbackBadge(
            visible = seekFeedbackText != null && !isHorizontalScrubbing,
            text = seekFeedbackText.orEmpty(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = if (showControls && !isLocked) 120.dp else 0.dp)
        )

        // Metadata-gated Skip Intro / Skip Recap floating pills (derivedStateOf avoids 400ms recompositions)
        val activeSkipWindow by remember(
            introSkipEnabled,
            introWindow,
            recapWindow,
            isLocked,
            playerErrorText,
            isHorizontalScrubbing,
            showNextEpisodeCard,
            showMovieEndCard,
            clockState
        ) {
            derivedStateOf {
                val pos = clockState.positionMs
                when {
                    !introSkipEnabled || isLocked || playerErrorText != null || isHorizontalScrubbing || showNextEpisodeCard || showMovieEndCard -> null
                    introWindow != null && pos >= introWindow.startMs && pos < introWindow.endMs ->
                        Triple(introWindow, "Skip Intro", "player_skip_intro_button")
                    recapWindow != null && pos >= recapWindow.startMs && pos < recapWindow.endMs ->
                        Triple(recapWindow, "Skip Recap", "player_skip_recap_button")
                    else -> null
                }
            }
        }
        AnimatedVisibility(
            visible = activeSkipWindow != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = if (showControls) 96.dp else 28.dp)
        ) {
            activeSkipWindow?.let { (window, label, tag) ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.84f),
                    border = BorderStroke(1.dp, colors.accentPrimary.copy(alpha = 0.75f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val target = window.endMs.coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            clockState.positionMs = target
                            latestSaveEpisodeProgress(
                                target,
                                exoPlayer.duration.coerceAtLeast(0L),
                                playbackEpisodeKey
                            )
                        }
                        .testTag(tag)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = label,
                            style = typography.uiLabel.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Icon(
                            painter = painterResource(LaterboxIcons.ChevronRight),
                            contentDescription = label,
                            tint = colors.accentPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // TV Series Next-Episode Netflix-Style Preview & Countdown Card
        AnimatedVisibility(
            visible = showNextEpisodeCard,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (effectivePlaybackEnded) Alignment.Center else Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = if (effectivePlaybackEnded) 18.dp else 88.dp)
        ) {
            val previewSeason = nextEpisodePreview?.seasonNumber ?: nextEpisodeTarget?.first ?: 1
            val previewEp = nextEpisodePreview?.episodeNumber ?: nextEpisodeTarget?.second ?: 1
            val previewLabel = nextEpisodePreview?.episodeLabel ?: "S${previewSeason} · E${previewEp}"
            val previewTitle = nextEpisodePreview?.title ?: "Episode $previewEp"
            val previewThumb = nextEpisodePreview?.thumbnailUrl

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surfaceElevated.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, colors.accentPrimary.copy(alpha = 0.45f)),
                modifier = Modifier
                    .width(if (effectiveIsLandscape) 340.dp else 310.dp)
                    .testTag("player_next_episode_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(104.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceSecondary),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!previewThumb.isNullOrBlank()) {
                                AsyncImage(
                                    model = previewThumb,
                                    contentDescription = previewTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = previewLabel,
                                    style = typography.caption.copy(fontWeight = FontWeight.Bold),
                                    color = colors.textSecondary
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = if (playbackPreferences.autoplayNextEpisode) {
                                    "Up Next · Playing in ${countdownSecondsLeft}s"
                                } else {
                                    "Up Next"
                                },
                                style = typography.caption.copy(fontWeight = FontWeight.Bold),
                                color = colors.accentPrimary
                            )
                            Text(
                                text = previewLabel,
                                style = typography.caption,
                                color = colors.textSecondary
                            )
                            Text(
                                text = previewTitle,
                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (playbackPreferences.autoplayNextEpisode) {
                        val totalSecs = playbackPreferences.autoplayCountdownSeconds.coerceIn(3, 30).toFloat()
                        val frac = (1f - (countdownSecondsLeft / totalSecs)).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { frac },
                            color = colors.accentPrimary,
                            trackColor = colors.surfaceSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                nextEpisodeCancelled = true
                                latestSaveEpisodeProgress(
                                    exoPlayer.currentPosition.coerceAtLeast(0L),
                                    exoPlayer.duration.coerceAtLeast(0L),
                                    playbackEpisodeKey
                                )
                                onNextEpisode()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentPrimary,
                                contentColor = colors.onAccentPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("player_end_play_now_button")
                        ) {
                            Text("Play Now", style = typography.caption.copy(fontWeight = FontWeight.Bold))
                        }
                        OutlinedButton(
                            onClick = { nextEpisodeCancelled = true },
                            border = BorderStroke(1.dp, colors.borderSubtle),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("player_end_cancel_button")
                        ) {
                            Text("Cancel", style = typography.caption, color = colors.textPrimary)
                        }
                    }
                }
            }
        }

        // Movie End-of-Playback Card (Replay / Related / Back — never autoplays unrelated movies)
        AnimatedVisibility(
            visible = showMovieEndCard,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.84f))
                    .displayCutoutPadding()
                    .padding(20.dp)
                    .testTag("player_movie_end_card"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surfaceElevated,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Playback Finished",
                            style = typography.caption.copy(fontWeight = FontWeight.Bold),
                            color = colors.accentPrimary
                        )
                        Text(
                            text = resolution.title,
                            style = typography.titleLarge,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    isPlaybackEnded = false
                                    endCardDismissed = false
                                    exoPlayer.seekTo(0L)
                                    clockState.positionMs = 0L
                                    exoPlayer.play()
                                    isPlaying = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentPrimary,
                                    contentColor = colors.onAccentPrimary
                                ),
                                modifier = Modifier.testTag("player_end_replay_button")
                            ) {
                                Text("Replay")
                            }
                            OutlinedButton(
                                onClick = onBackClick,
                                border = BorderStroke(1.dp, colors.borderSubtle),
                                modifier = Modifier.testTag("player_end_back_button")
                            ) {
                                Text("Back", color = colors.textPrimary)
                            }
                        }

                        if (relatedTitles.isNotEmpty()) {
                            Text(
                                text = "Related Titles",
                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textSecondary
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.testTag("player_end_related_row")
                            ) {
                                items(relatedTitles, key = { "end_rel_${it.compositeId}" }) { rel ->
                                    MoviePosterCard(
                                        item = rel,
                                        width = 96.dp,
                                        onClick = { onOpenRelatedTitle(rel.providerId, rel.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Download status toast card inside player
        downloadStatusMessage?.let { statusMsg ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceElevated.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, colors.accentPrimary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 88.dp)
                    .testTag("player_download_status")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = statusMsg,
                        style = typography.metaText,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onDismissDownloadStatus,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("OK", style = typography.caption, color = colors.accentPrimary)
                    }
                }
            }
        }

        if (playerErrorText != null) {
            PlayerMessageOverlay(
                title = "Playback Interrupted",
                message = playerErrorText ?: "Unable to play this video.",
                primaryLabel = "Retry",
                onPrimaryClick = {
                    playerErrorText = null
                    isBuffering = true
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                },
                secondaryLabel = if (resolution.sources.size > 1) "Switch Quality" else "Open Video Link",
                onSecondaryClick = {
                    if (resolution.sources.size > 1) {
                        showQualityDialog = true
                    } else {
                        onOpenExternalUrl(selectedSource.url)
                    }
                },
                tertiaryLabel = "Back",
                onTertiaryClick = onBackClick,
                modifier = Modifier.testTag("player_stream_error_overlay")
            )
        }
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            containerColor = colors.surfaceElevated,
            modifier = Modifier.testTag("player_quality_dialog"),
            title = { Text("Playback Quality", style = typography.sheetTitle, color = colors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    resolution.sources.forEach { source ->
                        val isSelected = source.id == selectedSource.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playerErrorText = null
                                    onSelectSource(source, exoPlayer.currentPosition)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${source.qualityLabel} · ${source.format}",
                                    style = typography.uiLabel,
                                    color = colors.textPrimary
                                )
                                source.sizeMb?.let { mb ->
                                    Text(
                                        text = "${mb} MB",
                                        style = typography.caption,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("Close", color = colors.accentPrimary)
                }
            }
        )
    }

    if (showAudioDialog) {
        AlertDialog(
            onDismissRequest = { showAudioDialog = false },
            containerColor = colors.surfaceElevated,
            modifier = Modifier.testTag("player_audio_dialog"),
            title = { Text("Audio Track & Dubs", style = typography.sheetTitle, color = colors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (inStreamAudioTracks.isNotEmpty()) {
                        Text(
                            text = "Stream Audio Tracks",
                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    hasManualAudioOverride = false
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                        .build()
                                    onSelectInStreamAudio(null, null)
                                    showAudioDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !hasManualAudioOverride, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Auto / Default", style = typography.uiLabel, color = colors.textPrimary)
                        }
                        inStreamAudioTracks.forEachIndexed { idx, option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        hasManualAudioOverride = true
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                            .addOverride(TrackSelectionOverride(option.group, option.trackIndex))
                                            .build()
                                        onSelectInStreamAudio(option.languageCode ?: option.label, option.label)
                                        showAudioDialog = false
                                    }
                                    .padding(vertical = 6.dp)
                                    .testTag("player_audio_track_$idx"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = hasManualAudioOverride && option.isSelected,
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(option.label, style = typography.uiLabel, color = colors.textPrimary)
                            }
                        }
                    }

                    if (availableDubs.isNotEmpty()) {
                        if (inStreamAudioTracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = "Language Dubs",
                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textSecondary
                        )
                        availableDubs.forEach { dub ->
                            val hasRef = !dub.referenceId.isNullOrBlank()
                            val isSelectedDub = hasRef && dub.referenceId == activeTitleId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = hasRef) {
                                        showAudioDialog = false
                                        if (!isSelectedDub) {
                                            onSelectAudioDub(dub, exoPlayer.currentPosition)
                                        }
                                    }
                                    .padding(vertical = 6.dp)
                                    .testTag("player_audio_dub_${dub.referenceId ?: dub.language}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSelectedDub, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = dub.label,
                                    style = typography.uiLabel,
                                    color = if (hasRef) colors.textPrimary else colors.textSecondary
                                )
                            }
                        }
                    }

                    if (inStreamAudioTracks.isEmpty() && availableDubs.isEmpty()) {
                        Text(
                            text = "Only a single default audio track is available for this stream.",
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioDialog = false }) {
                    Text("Close", color = colors.accentPrimary)
                }
            }
        )
    }

    if (showSubtitleDialog) {
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            containerColor = colors.surfaceElevated,
            modifier = Modifier.testTag("player_subtitles_dialog"),
            title = { Text("Subtitles", style = typography.sheetTitle, color = colors.textPrimary) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectSubtitle(null, exoPlayer.currentPosition)
                                showSubtitleDialog = false
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedSubtitle == null, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Off", style = typography.uiLabel, color = colors.textPrimary)
                    }
                    if (resolution.subtitles.isEmpty()) {
                        Text(
                            text = "No subtitle tracks were returned for this stream.",
                            style = typography.metaText,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        resolution.subtitles.forEach { track ->
                            val isSelected = track.id == selectedSubtitle?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectSubtitle(track, exoPlayer.currentPosition)
                                        showSubtitleDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSelected, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(track.label, style = typography.uiLabel, color = colors.textPrimary)
                            }
                        }
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showSubtitleDialog = false
                        showSubtitleStyleDialog = true
                    },
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    modifier = Modifier.testTag("player_subtitle_set_style_button")
                ) {
                    Text("Set style", color = colors.textPrimary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("Close", color = colors.accentPrimary)
                }
            }
        )
    }

    if (showSubtitleStyleDialog) {
        SubtitleStyleDialog(
            config = localSubtitleStyle,
            onConfigChange = { updated ->
                localSubtitleStyle = updated
                onSubtitleStyleChange(updated)
            },
            onDismiss = { showSubtitleStyleDialog = false }
        )
    }

    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = colors.surfaceElevated,
            modifier = Modifier.testTag("player_speed_dialog"),
            title = { Text("Playback Speed", style = typography.sheetTitle, color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    speeds.forEach { spd ->
                        val isSelected = abs(spd - selectedSpeed) < 0.01f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSpeed = spd
                                    exoPlayer.setPlaybackSpeed(spd)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 8.dp)
                                .testTag("player_speed_option_$spd"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (spd == 1.0f) "1.0x (Normal)" else "${spd}x",
                                style = typography.uiLabel,
                                color = colors.textPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Close", color = colors.accentPrimary)
                }
            }
        )
    }

    if (showDownloadDialog) {
        DownloadResolutionDialog(
            title = resolution.title,
            sources = resolution.sources,
            isEnqueuing = isEnqueuingDownload,
            onSelectSource = onDownloadSource,
            onDismiss = { showDownloadDialog = false }
        )
    }
}

@Composable
private fun PlayerMessageOverlay(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String?,
    onSecondaryClick: () -> Unit,
    tertiaryLabel: String? = null,
    onTertiaryClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = colors.surfaceElevated,
            border = BorderStroke(1.dp, colors.borderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title, style = typography.titleLarge, color = colors.textPrimary)
                Text(text = message, style = typography.bodySecondary, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPrimaryClick,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                    ) {
                        Text(primaryLabel)
                    }
                    if (secondaryLabel != null) {
                        OutlinedButton(
                            onClick = onSecondaryClick,
                            border = BorderStroke(1.dp, colors.borderSubtle)
                        ) {
                            Text(secondaryLabel, color = colors.textPrimary)
                        }
                    }
                    if (tertiaryLabel != null) {
                        TextButton(onClick = onTertiaryClick) {
                            Text(tertiaryLabel, color = colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun extractAudioTracks(tracks: Tracks): List<PlayerAudioTrackOption> {
    val options = mutableListOf<PlayerAudioTrackOption>()
    tracks.groups.forEachIndexed { groupIdx, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            val lang = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            val readableLang = lang?.let {
                Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).takeIf { name -> name.isNotBlank() }
                    ?: it.uppercase(Locale.US)
            }
            val baseLabel = format.label?.takeIf { it.isNotBlank() }
                ?: readableLang
                ?: "Audio Track ${options.size + 1}"
            val channels = if (format.channelCount > 2) " · ${format.channelCount}ch" else ""
            options += PlayerAudioTrackOption(
                group = group.mediaTrackGroup,
                trackIndex = i,
                label = "$baseLabel$channels",
                languageCode = lang,
                isSelected = group.isTrackSelected(i)
            )
        }
    }
    return options
}

private fun formatPlayerErrorMessage(error: PlaybackException): String {
    var cause: Throwable? = error.cause
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return when (cause.responseCode) {
                401, 403 -> "Playback access expired or needs verification. Try again or switch quality."
                404, 410 -> "This video link expired or is no longer available. Retry or switch quality."
                else -> "Unable to load this video right now. Please try again or switch quality."
            }
        }
        if (cause is HttpDataSource.HttpDataSourceException) {
            return "Connection interrupted while loading this video. Check your internet connection or try another quality."
        }
        cause = cause.cause
    }
    return "Unable to play this video right now. Please try again or switch quality."
}

private fun formatPlaybackClock(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Stable
internal class PlayerClockState(initialPositionMs: Long = 0L) {
    var positionMs by mutableLongStateOf(initialPositionMs.coerceAtLeast(0L))
    var durationMs by mutableLongStateOf(0L)
}

internal fun Modifier.isolatePlayerControlTouch(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) {
                break
            }
        }
    }
}

@Composable
private fun PlayerTimelineBar(
    clockState: PlayerClockState,
    exoPlayer: ExoPlayer,
    isHorizontalScrubbing: Boolean,
    scrubTargetMs: Long,
    isSeeking: Boolean,
    onSeekingChange: (Boolean) -> Unit,
    onSaveProgress: (posMs: Long, durMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var seekSliderPosMs by remember { mutableFloatStateOf(0f) }

    val durationMs = clockState.durationMs
    val effectiveDuration = durationMs.coerceAtLeast(1L)
    val displayPosition = when {
        isHorizontalScrubbing -> scrubTargetMs
        isSeeking -> seekSliderPosMs.toLong()
        else -> clockState.positionMs
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = formatPlaybackClock(displayPosition),
            style = typography.caption.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            modifier = Modifier.testTag("player_clock_position")
        )
        Slider(
            value = if (durationMs > 0L) {
                displayPosition.toFloat().coerceIn(0f, effectiveDuration.toFloat())
            } else {
                0f
            },
            valueRange = 0f..effectiveDuration.toFloat(),
            enabled = durationMs > 0L,
            onValueChange = { value ->
                onSeekingChange(true)
                seekSliderPosMs = value
            },
            onValueChangeFinished = {
                val target = seekSliderPosMs.toLong().coerceIn(0L, effectiveDuration)
                exoPlayer.seekTo(target)
                clockState.positionMs = target
                onSeekingChange(false)
                onSaveProgress(target, durationMs)
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accentPrimary,
                activeTrackColor = colors.accentPrimary,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f)
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("player_seek_slider")
        )
        Text(
            text = formatPlaybackClock(durationMs),
            style = typography.caption.copy(fontWeight = FontWeight.Medium),
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.testTag("player_clock_duration")
        )
    }
}

@Composable
private fun PlayerScrubHudSlot(
    visible: Boolean,
    scrubTargetMs: Long,
    scrubDeltaMs: Long,
    clockState: PlayerClockState,
    sensitivityTier: SeekSensitivityTier,
    modifier: Modifier = Modifier
) {
    val dur = if (visible) clockState.durationMs else 0L
    PlayerHorizontalScrubHud(
        visible = visible,
        scrubTargetMs = scrubTargetMs,
        scrubDeltaMs = scrubDeltaMs,
        durationMs = dur,
        sensitivityTier = sensitivityTier,
        modifier = modifier
    )
}

