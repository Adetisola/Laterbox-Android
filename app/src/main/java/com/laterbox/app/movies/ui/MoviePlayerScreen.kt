package com.laterbox.app.movies.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.delay

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
                    onBackClick = onBackClick,
                    onSelectSource = onSelectSource,
                    onSelectSubtitle = onSelectSubtitle,
                    onSaveProgress = onSaveProgress,
                    onOpenExternalUrl = onOpenExternalUrl
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
private fun ActiveVideoPlayerContent(
    resolution: PlaybackResolution.Available,
    selectedSource: PlaybackSource,
    selectedSubtitle: SubtitleTrack?,
    startPositionMs: Long,
    onBackClick: () -> Unit,
    onSelectSource: (PlaybackSource, Long) -> Unit,
    onSelectSubtitle: (SubtitleTrack?, Long) -> Unit,
    onSaveProgress: (Long, Long) -> Unit,
    onOpenExternalUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    var showQualityDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var playerErrorText by remember { mutableStateOf<String?>(null) }
    val latestSaveProgress by rememberUpdatedState(onSaveProgress)

    val exoPlayer = remember(resolution.title) {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(exoPlayer, selectedSource.id, selectedSubtitle?.id) {
        playerErrorText = null
        val itemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(selectedSource.url))

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
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitle == null)
            .build()

        exoPlayer.setMediaItem(itemBuilder.build())
        if (startPositionMs > 0L) {
            exoPlayer.seekTo(startPositionMs)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerErrorText = error.message ?: "Stream playback failed."
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            latestSaveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(5_000L)
            if (exoPlayer.isPlaying) {
                latestSaveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    latestSaveProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
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
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
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

            if (resolution.subtitles.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selectedSubtitle != null) colors.accentPrimary else Color.White.copy(alpha = 0.16f),
                    modifier = Modifier
                        .padding(end = 6.dp)
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
                            text = selectedSubtitle?.languageCode?.uppercase() ?: "CC",
                            style = typography.caption.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White.copy(alpha = 0.16f),
                modifier = Modifier
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

        if (playerErrorText != null) {
            PlayerMessageOverlay(
                title = "Stream Interrupted",
                message = playerErrorText ?: "Unable to decode video stream.",
                primaryLabel = "Retry Stream",
                onPrimaryClick = {
                    playerErrorText = null
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                },
                secondaryLabel = "Open Stream URL",
                onSecondaryClick = { onOpenExternalUrl(selectedSource.url) }
            )
        }
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            containerColor = colors.surfaceElevated,
            title = { Text("Playback Quality", style = typography.sheetTitle, color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    resolution.sources.forEach { source ->
                        val isSelected = source.id == selectedSource.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
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

    if (showSubtitleDialog) {
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            containerColor = colors.surfaceElevated,
            title = { Text("Subtitles", style = typography.sheetTitle, color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("Close", color = colors.accentPrimary)
                }
            }
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
                }
            }
        }
    }
}
