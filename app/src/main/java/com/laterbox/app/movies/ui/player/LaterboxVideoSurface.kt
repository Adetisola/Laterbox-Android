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
    onPlayerViewAttached: ((PlayerView?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var appliedResizeMode by remember { mutableIntStateOf(-1) }
    var appliedSubtitleStyle by remember { mutableStateOf<SubtitleStyleConfig?>(null) }

    AndroidView(
        factory = { ctx ->
            val view = if (useTextureView) {
                LayoutInflater.from(ctx)
                    .inflate(R.layout.laterbox_player_texture_view, null, false) as PlayerView
            } else {
                PlayerView(ctx).apply {
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            }
            view.apply {
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
                this.player = player
                onPlayerViewAttached?.invoke(this)
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
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
            onPlayerViewAttached?.invoke(view)
        },
        onRelease = { view ->
            view.keepScreenOn = false
            view.player = null
            onPlayerViewAttached?.invoke(null)
        },
        modifier = modifier.fillMaxSize()
    )
}
