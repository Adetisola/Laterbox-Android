package com.laterbox.app.media.ui.viewer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.movies.playback.LaterboxPlaybackBridge
import com.laterbox.app.ui.components.LaterboxIcons
import java.io.File

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(
    asset: MediaAsset,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    playbackPreferences: com.laterbox.app.movies.ui.PlayerPlaybackPreferences = com.laterbox.app.movies.ui.PlayerPlaybackPreferences(),
    onSubtitleStyleChange: (com.laterbox.app.data.preferences.SubtitleStyleConfig) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (asset.mediaType == com.laterbox.app.media.domain.model.MediaType.VIDEO) {
        com.laterbox.app.movies.ui.LocalVideoPlayerScreen(
            asset = asset,
            onBackClick = onDismiss,
            playbackPreferences = playbackPreferences,
            onSubtitleStyleChange = onSubtitleStyleChange,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val file = File(appContext.filesDir, "media/${asset.localPath}")
    val attachedPlayerView = remember { androidx.compose.runtime.mutableStateOf<PlayerView?>(null) }

    BackHandler {
        attachedPlayerView.value?.player = null
        LaterboxPlaybackBridge.stopAndDetach(appContext)
        onDismiss()
    }

    val audioArtworkUrl = remember(asset.id, asset.artworkUrl, asset.thumbnailPath) {
        com.laterbox.app.movies.playback.LaterboxPlaybackArtworkHelper.resolveLocalThumbnailUri(
            context = appContext,
            artworkUrl = asset.artworkUrl,
            thumbnailPath = asset.thumbnailPath
        )
    }

    val exoPlayer = remember(asset.id) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        ExoPlayer.Builder(appContext)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                val metadata = com.laterbox.app.movies.playback.LaterboxPlaybackArtworkHelper.buildPlaybackMediaMetadata(
                    title = asset.title ?: "Audio Playback",
                    subtitle = asset.artist,
                    artist = asset.artist,
                    album = asset.album,
                    artworkUrl = audioArtworkUrl
                )
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMediaMetadata(metadata)
                    .build()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        LaterboxPlaybackBridge.attachPlayer(
            context = appContext,
            player = exoPlayer,
            title = asset.title ?: "Audio Playback",
            subtitle = asset.artist
        )
        LaterboxPlaybackBridge.updateMetadata(
            title = asset.title ?: "Audio Playback",
            subtitle = asset.artist,
            artworkUrl = audioArtworkUrl
        )
        onDispose {
            attachedPlayerView.value?.player = null
            attachedPlayerView.value = null
            LaterboxPlaybackBridge.releasePlayer(appContext, exoPlayer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    attachedPlayerView.value = this
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
                attachedPlayerView.value = view
            },
            onRelease = { view ->
                view.player = null
                attachedPlayerView.value = null
            },
            modifier = Modifier.fillMaxSize()
        )

        TopAppBar(
            title = {
                Text(
                    text = asset.title ?: "Media Player",
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        attachedPlayerView.value?.player = null
                        LaterboxPlaybackBridge.stopAndDetach(appContext)
                        onDismiss()
                    }
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Close),
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = asset.mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share media"))
                        } catch (e: Exception) {
                            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                                context = context,
                                message = "Cannot share: ${e.message}",
                                variant = com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
                            )
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Share),
                        contentDescription = "Share",
                        tint = Color.White
                    )
                }
                IconButton(onClick = onExport) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Copy),
                        contentDescription = "Export to storage",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                titleContentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .align(Alignment.TopCenter)
        )
    }
}
