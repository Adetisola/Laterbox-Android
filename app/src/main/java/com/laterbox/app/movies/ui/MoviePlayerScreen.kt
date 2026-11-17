package com.laterbox.app.movies.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.laterbox.app.movies.data.cache.MoviesMediaCache
import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

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
    onNextEpisode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding()
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
                            text = "Preparing authorised playback...",
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
                    primaryLabel = if (!extUrl.isNullOrBlank()) "Open Official Guide" else "Back",
                    onPrimaryClick = {
                        if (!extUrl.isNullOrBlank()) onOpenExternalUrl(extUrl) else onBackClick()
                    },
                    secondaryLabel = if (!extUrl.isNullOrBlank()) "Back" else null,
                    onSecondaryClick = onBackClick,
                    modifier = Modifier.testTag("player_unavailable_state")
                )
            }
            uiState.resolution is PlaybackResolution.Available && uiState.selectedSource != null -> {
                ActiveVideoPlayerContent(
                    resolution = uiState.resolution,
                    selectedSource = uiState.selectedSource,
                    selectedSubtitle = uiState.selectedSubtitleTrack,
                    startPositionMs = uiState.startPositionMs,
                    activeTitleId = uiState.activeTitleId,
                    availableDubs = uiState.availableDubs,
                    nextEpisodeTarget = uiState.nextEpisodeTarget,
                    isEnqueuingDownload = uiState.isEnqueuingDownload,
                    downloadStatusMessage = uiState.downloadStatusMessage,
                    onBackClick = onBackClick,
                    onSelectSource = onSelectSource,
                    onSelectSubtitle = onSelectSubtitle,
                    onSelectAudioDub = onSelectAudioDub,
                    onNextEpisode = onNextEpisode,
                    onSaveProgress = onSaveProgress,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onDownloadSource = onDownloadSource,
                    onDismissDownloadStatus = onDismissDownloadStatus
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
    isEnqueuingDownload: Boolean,
    downloadStatusMessage: String?,
    onBackClick: () -> Unit,
    onSelectSource: (PlaybackSource, Long) -> Unit,
    onSelectSubtitle: (SubtitleTrack?, Long) -> Unit,
    onSelectAudioDub: (AudioDubInfo, Long) -> Unit,
    onNextEpisode: () -> Unit,
    onSaveProgress: (Long, Long) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onDownloadSource: (PlaybackSource) -> Unit,
    onDismissDownloadStatus: () -> Unit
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
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var selectedSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }
    var isHolding2x by remember { mutableStateOf(false) }
    var ignoreNextTap by remember { mutableStateOf(false) }
    var resizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPositionMs by rememberSaveable { mutableLongStateOf(startPositionMs.coerceAtLeast(0L)) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekSliderPosMs by remember { mutableFloatStateOf(0f) }
    var seekFeedbackText by remember { mutableStateOf<String?>(null) }

    var inStreamAudioTracks by remember { mutableStateOf<List<PlayerAudioTrackOption>>(emptyList()) }
    var hasManualAudioOverride by remember { mutableStateOf(false) }

    var showQualityDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var playerErrorText by remember { mutableStateOf<String?>(null) }

    val latestSaveProgress by rememberUpdatedState(onSaveProgress)
    val latestSelectedSpeed by rememberUpdatedState(selectedSpeed)
    val latestIsLocked by rememberUpdatedState(isLocked)
    val latestStartPositionMs by rememberUpdatedState(startPositionMs)

    val isAdaptiveSource = remember(selectedSource.id, selectedSource.format, selectedSource.url) {
        selectedSource.format.equals("DASH", ignoreCase = true) ||
            selectedSource.format.equals("HLS", ignoreCase = true) ||
            selectedSource.url.contains(".mpd", ignoreCase = true) ||
            selectedSource.url.contains(".m3u8", ignoreCase = true)
    }

    val exoPlayer = remember(resolution.title, selectedSource.headers, isAdaptiveSource) {
        val mediaSourceFactory = MoviesMediaCache.createMediaSourceFactory(
            context = context,
            headers = selectedSource.headers,
            useDiskCache = !isAdaptiveSource
        )
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    BackHandler(enabled = isLocked || isFullscreen) {
        if (isLocked) {
            showControls = true
        } else {
            isFullscreen = false
        }
    }

    // Apply orientation & immersive system bars when fullscreen or landscape changes; never reset during config change recreation
    val isImmersiveLandscape = isFullscreen || isLandscape
    LaunchedEffect(isFullscreen, isImmersiveLandscape, activity) {
        val act = activity ?: return@LaunchedEffect
        val window = act.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (isImmersiveLandscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
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

    LaunchedEffect(exoPlayer, selectedSource.id, selectedSubtitle?.id) {
        playerErrorText = null
        isBuffering = true
        hasManualAudioOverride = false
        val itemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(selectedSource.url))

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

        exoPlayer.setMediaItem(itemBuilder.build())
        val resumeAtMs = maxOf(latestStartPositionMs, currentPositionMs).coerceAtLeast(0L)
        if (resumeAtMs > 0L) {
            exoPlayer.seekTo(resumeAtMs)
        }
        exoPlayer.setPlaybackSpeed(latestSelectedSpeed)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
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
        onDispose {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.coerceAtLeast(0L)
            if (pos > 0L) {
                currentPositionMs = pos
            }
            latestSaveProgress(pos, dur)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodic progress tracker & playback position updater
    LaunchedEffect(exoPlayer) {
        var tick = 0
        while (true) {
            val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.coerceAtLeast(0L)
            isPlaying = exoPlayer.isPlaying || (exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_BUFFERING)
            durationMs = dur
            if (!isSeeking) {
                currentPositionMs = cur
            }
            tick++
            if (tick % 12 == 0 && exoPlayer.isPlaying) {
                latestSaveProgress(cur, dur)
            }
            delay(400L)
        }
    }

    // Auto-hide controls after 3.5s during active playback
    LaunchedEffect(
        showControls,
        isPlaying,
        isLocked,
        showQualityDialog,
        showSubtitleDialog,
        showAudioDialog,
        showSpeedDialog,
        showDownloadDialog,
        isSeeking,
        playerErrorText
    ) {
        if (showControls && isPlaying && !isLocked && playerErrorText == null &&
            !showQualityDialog && !showSubtitleDialog && !showAudioDialog &&
            !showSpeedDialog && !showDownloadDialog && !isSeeking
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(exoPlayer) {
                // Tap-and-Hold 2x Speed gesture using a true timeout so stationary holds trigger after 320ms
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val slop = viewConfiguration.touchSlop
                    var activatedHold = false
                    var releasedOrMovedOut = false

                    try {
                        val deadline = System.currentTimeMillis() + 320L
                        while (!releasedOrMovedOut && !activatedHold) {
                            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                            val event = withTimeoutOrNull(remaining) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (event == null) {
                                if (!latestIsLocked) {
                                    activatedHold = true
                                    isHolding2x = true
                                    ignoreNextTap = true
                                    exoPlayer.setPlaybackSpeed(2.0f)
                                } else {
                                    break
                                }
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed ||
                                    abs(change.position.x - downPos.x) > slop ||
                                    abs(change.position.y - downPos.y) > slop
                                ) {
                                    releasedOrMovedOut = true
                                }
                            }
                        }

                        while (activatedHold) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
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
            .pointerInput(exoPlayer) {
                detectTapGestures(
                    onTap = {
                        if (ignoreNextTap) {
                            ignoreNextTap = false
                        } else {
                            showControls = !showControls
                        }
                    },
                    onDoubleTap = { offset ->
                        if (latestIsLocked) {
                            showControls = true
                            return@detectTapGestures
                        }
                        val widthPx = size.width.toFloat().coerceAtLeast(1f)
                        if (offset.x < widthPx / 2f) {
                            val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            currentPositionMs = target
                            seekFeedbackText = "-10s"
                        } else {
                            val dur = exoPlayer.duration.coerceAtLeast(0L)
                            val target = if (dur > 0L) {
                                (exoPlayer.currentPosition + 10_000L).coerceAtMost(dur)
                            } else {
                                exoPlayer.currentPosition + 10_000L
                            }
                            exoPlayer.seekTo(target)
                            currentPositionMs = target
                            seekFeedbackText = "+10s"
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    this.resizeMode = resizeMode
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(true)
                        setApplyEmbeddedFontSizes(true)
                        setStyle(
                            CaptionStyleCompat(
                                AndroidColor.WHITE,
                                AndroidColor.argb(170, 0, 0, 0),
                                AndroidColor.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                AndroidColor.BLACK,
                                null
                            )
                        )
                    }
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
                view.resizeMode = resizeMode
            },
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
                    // Top Control Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                latestSaveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
                                isFullscreen = false
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

                        // Subtitles (CC) Control
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

                        // Audio Track / Dub Selector
                        val activeAudioBadge = inStreamAudioTracks.firstOrNull { it.isSelected }?.languageCode?.uppercase(Locale.US)
                            ?: availableDubs.firstOrNull { it.referenceId == activeTitleId }
                                ?.language?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
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

                        // Quality Selector
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

                        // Download Authorised Stream Button
                        if (resolution.sources.any { it.isAuthorised }) {
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

                        // Lock Controls Button
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
                    }

                    // Center Transport Row (-10s, Play/Pause, +10s)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            modifier = Modifier.size(52.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    currentPositionMs = target
                                    seekFeedbackText = "-10s"
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("player_replay_10_button")
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Replay10),
                                    contentDescription = "Rewind 10 seconds",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = colors.accentPrimary,
                            modifier = Modifier.size(66.dp)
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
                                        }
                                        exoPlayer.play()
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("player_play_pause_button")
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isPlaying) LaterboxIcons.Pause else LaterboxIcons.Play
                                    ),
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = colors.onAccentPrimary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            modifier = Modifier.size(52.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                                    val target = if (dur > 0L) {
                                        (exoPlayer.currentPosition + 10_000L).coerceAtMost(dur)
                                    } else {
                                        exoPlayer.currentPosition + 10_000L
                                    }
                                    exoPlayer.seekTo(target)
                                    currentPositionMs = target
                                    seekFeedbackText = "+10s"
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("player_forward_10_button")
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Forward10),
                                    contentDescription = "Forward 10 seconds",
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
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val effectiveDuration = durationMs.coerceAtLeast(1L)
                        val displayPosition = if (isSeeking) seekSliderPosMs.toLong() else currentPositionMs

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = formatPlaybackClock(displayPosition),
                                style = typography.caption.copy(fontWeight = FontWeight.Medium),
                                color = Color.White
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
                                    isSeeking = true
                                    seekSliderPosMs = value
                                },
                                onValueChangeFinished = {
                                    val target = seekSliderPosMs.toLong().coerceIn(0L, effectiveDuration)
                                    exoPlayer.seekTo(target)
                                    currentPositionMs = target
                                    isSeeking = false
                                    latestSaveProgress(target, durationMs)
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
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }

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

                                if (nextEpisodeTarget == null && !isImmersiveLandscape) {
                                    Text(
                                        text = "Hold video for 2x",
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
                                                latestSaveProgress(
                                                    exoPlayer.currentPosition,
                                                    exoPlayer.duration.coerceAtLeast(0L)
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

                                // Fullscreen / Landscape Rotation Icon Control
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isFullscreen) colors.accentPrimary else Color.White.copy(alpha = 0.16f),
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { isFullscreen = !isFullscreen }
                                        .testTag("player_fullscreen_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(
                                                if (isFullscreen) LaterboxIcons.FullscreenExit else LaterboxIcons.Fullscreen
                                            ),
                                            contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
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

        // Buffering Indicator (rendered above controls scrim so it stays crisp)
        if (isBuffering && playerErrorText == null) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.56f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = colors.accentPrimary,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("player_buffering_indicator")
                    )
                }
            }
        }

        // Tap-and-Hold 2x Speed Indicator Pill (rendered above controls scrim)
        AnimatedVisibility(
            visible = isHolding2x,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.accentPrimary.copy(alpha = 0.94f),
                modifier = Modifier.testTag("player_hold_2x_badge")
            ) {
                Text(
                    text = "2.0x Speed >>",
                    style = typography.uiLabel.copy(fontWeight = FontWeight.Bold),
                    color = colors.onAccentPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // Double-Tap Seek Feedback Badge (above scrim, offset above center controls when controls are visible)
        AnimatedVisibility(
            visible = seekFeedbackText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = if (showControls && !isLocked) 120.dp else 0.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                modifier = Modifier.testTag("player_seek_feedback_badge")
            ) {
                Text(
                    text = seekFeedbackText.orEmpty(),
                    style = typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
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
                title = "Stream Interrupted",
                message = playerErrorText ?: "Unable to decode video stream.",
                primaryLabel = "Retry Stream",
                onPrimaryClick = {
                    playerErrorText = null
                    isBuffering = true
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                },
                secondaryLabel = if (resolution.sources.size > 1) "Switch Source" else "Open Stream URL",
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
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("Close", color = colors.accentPrimary)
                }
            }
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
                401, 403 -> "Stream CDN rejected authorization (HTTP ${cause.responseCode}). Try switching source or refreshing."
                404, 410 -> "Stream link expired or unavailable (HTTP ${cause.responseCode}). Retry or switch source."
                else -> "Stream server returned HTTP ${cause.responseCode}."
            }
        }
        if (cause is HttpDataSource.HttpDataSourceException) {
            return "Network interruption while loading video stream. Check your connection or try another source."
        }
        cause = cause.cause
    }
    val detail = error.message?.takeIf { it.isNotBlank() } ?: "Playback failed (${error.errorCodeName})."
    return detail
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
