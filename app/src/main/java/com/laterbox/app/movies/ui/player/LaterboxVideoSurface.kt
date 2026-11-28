package com.laterbox.app.movies.ui.player

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.laterbox.app.R
import com.laterbox.app.data.preferences.SubtitleStyleConfig

/**
 * Shared Media3 [PlayerView] host used by streaming/offline playback and trailer playback.
 * Guarantees safe [PlayerView.setPlayer] attachment and detachment on disposal so a [Player]
 * is never released while a [PlayerView] still holds a reference to it.
 */
@OptIn(UnstableApi::class)
@Composable
fun LaterboxVideoSurface(
    player: Player?,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    keepScreenOn: Boolean = true,
    useTextureView: Boolean = false,
    subtitleStyle: SubtitleStyleConfig? = null,
    hideSubtitles: Boolean = false,
    onPlayerViewAttached: ((PlayerView?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var appliedResizeMode by remember { mutableIntStateOf(-1) }
    var appliedSubtitleStyle by remember { mutableStateOf<SubtitleStyleConfig?>(null) }
    var attachedPlayer by remember { mutableStateOf<Player?>(null) }
    var attachedPlayerView by remember { mutableStateOf<PlayerView?>(null) }

    val cueStackingListener = remember {
        object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                attachedPlayerView?.subtitleView?.setCues(
                    SubtitleStyleHelper.stackSimultaneousBottomCues(cueGroup.cues)
                )
            }
        }
    }

    fun syncPlayerCueListener(view: PlayerView, nextPlayer: Player?) {
        attachedPlayerView = view
        if (attachedPlayer !== nextPlayer) {
            attachedPlayer?.removeListener(cueStackingListener)
            attachedPlayer = nextPlayer
            nextPlayer?.addListener(cueStackingListener)
            val currentCues = nextPlayer?.currentCues?.cues.orEmpty()
            if (currentCues.isNotEmpty()) {
                view.subtitleView?.setCues(
                    SubtitleStyleHelper.stackSimultaneousBottomCues(currentCues)
                )
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val view = if (useTextureView) {
                LayoutInflater.from(ctx)
                    .inflate(R.layout.laterbox_player_texture_view, null, false) as PlayerView
            } else {
                PlayerView(ctx)
            }
            view.apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                useController = false
                controllerAutoShow = false
                isClickable = false
                isFocusable = false
                this.keepScreenOn = keepScreenOn
                this.resizeMode = resizeMode
                appliedResizeMode = resizeMode
                if (subtitleStyle != null) {
                    SubtitleStyleHelper.applyToSubtitleView(subtitleView, subtitleStyle)
                    appliedSubtitleStyle = subtitleStyle
                }
                subtitleView?.visibility = if (hideSubtitles) android.view.View.GONE else android.view.View.VISIBLE
                this.player = player
                syncPlayerCueListener(this, player)
                onPlayerViewAttached?.invoke(this)
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
            syncPlayerCueListener(view, player)
            if (view.keepScreenOn != keepScreenOn) {
                view.keepScreenOn = keepScreenOn
            }
            if (appliedResizeMode != resizeMode) {
                view.resizeMode = resizeMode
                appliedResizeMode = resizeMode
            }
            if (subtitleStyle != null && appliedSubtitleStyle != subtitleStyle) {
                SubtitleStyleHelper.applyToSubtitleView(view.subtitleView, subtitleStyle)
                appliedSubtitleStyle = subtitleStyle
            }
            val desiredSubtitleVis = if (hideSubtitles) android.view.View.GONE else android.view.View.VISIBLE
            if (view.subtitleView?.visibility != desiredSubtitleVis) {
                view.subtitleView?.visibility = desiredSubtitleVis
            }
            onPlayerViewAttached?.invoke(view)
        },
        onRelease = { view ->
            attachedPlayer?.removeListener(cueStackingListener)
            attachedPlayer = null
            attachedPlayerView = null
            view.keepScreenOn = false
            view.player = null
            onPlayerViewAttached?.invoke(null)
        },
        modifier = modifier.fillMaxSize()
    )
}
