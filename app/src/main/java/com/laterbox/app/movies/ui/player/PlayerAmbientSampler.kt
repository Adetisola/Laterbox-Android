package com.laterbox.app.movies.ui.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object PlayerAmbientSampler {
    private const val SAMPLE_WIDTH = 16
    private const val SAMPLE_HEIGHT = 9
    internal const val SAMPLE_INTERVAL_PLAYING_MS = 160L
    internal const val SAMPLE_INTERVAL_FAST_MS = 125L
    internal const val POST_SEEK_REFRESH_DELAY_MS = 110L
    internal const val COLOR_BLEND_NORMAL_MS = 220
    internal const val COLOR_BLEND_SCENE_CUT_MS = 160
    internal const val SCENE_CUT_DELTA_THRESHOLD = 0.22f
    const val AMBIENT_CENTER_ALPHA = 0.64f
    const val AMBIENT_MID_ALPHA = 0.34f
    const val AMBIENT_OUTER_ALPHA = 0.08f

    /**
     * Computes a restrained, atmospheric background tint from sampled ARGB pixels.
     * Filters out pure black letterbox pixels when non-black content exists, preserves
     * genuinely dark scenes without artificial gray wash, and clamps bright scenes so HUD
     * controls remain legible.
     */
    fun extractAmbientColor(pixels: IntArray): Color {
        if (pixels.isEmpty()) return Color.Black

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var weightedCount = 0L

        var fallbackRSum = 0L
        var fallbackGSum = 0L
        var fallbackBSum = 0L

        for (argb in pixels) {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            fallbackRSum += r
            fallbackGSum += g
            fallbackBSum += b

            val peak = max(r, max(g, b))
            if (peak >= 14) {
                rSum += r
                gSum += g
                bSum += b
                weightedCount++
            }
        }

        val count = if (weightedCount > 0L) weightedCount else pixels.size.toLong()
        val avgR = ((if (weightedCount > 0L) rSum else fallbackRSum) / count).toInt().coerceIn(0, 255)
        val avgG = ((if (weightedCount > 0L) gSum else fallbackGSum) / count).toInt().coerceIn(0, 255)
        val avgB = ((if (weightedCount > 0L) bSum else fallbackBSum) / count).toInt().coerceIn(0, 255)

        val rf = avgR / 255f
        val gf = avgG / 255f
        val bf = avgB / 255f
        val cMax = max(rf, max(gf, bf))
        if (cMax <= 0.004f) {
            return Color.Black
        }
        val cMin = min(rf, min(gf, bf))
        val delta = cMax - cMin

        val hue = when {
            delta < 0.0001f -> 0f
            cMax == rf -> 60f * (((gf - bf) / delta) % 6f)
            cMax == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val rawSat = if (cMax <= 0.0001f) 0f else (delta / cMax)
        val clampedSat = if (rawSat < 0.08f) rawSat * 0.5f else rawSat.coerceIn(0.14f, 0.66f)
        val clampedVal = if (cMax < 0.14f) {
            (cMax * 0.85f).coerceIn(0f, 0.14f)
        } else {
            cMax.coerceIn(0.14f, 0.54f)
        }

        val (outR, outG, outB) = hsvToRgb(hue, clampedSat, clampedVal)
        return Color(
            red = outR,
            green = outG,
            blue = outB,
            alpha = 1f
        )
    }

    internal fun resolveSampleIntervalMs(playbackSpeed: Float): Long =
        if (playbackSpeed >= 1.5f) SAMPLE_INTERVAL_FAST_MS else SAMPLE_INTERVAL_PLAYING_MS

    internal fun computeBlendDurationMs(previous: Color, next: Color): Int {
        val maxChannelDelta = max(
            abs(next.red - previous.red),
            max(abs(next.green - previous.green), abs(next.blue - previous.blue))
        )
        val prevLuma = 0.2126f * previous.red + 0.7152f * previous.green + 0.0722f * previous.blue
        val nextLuma = 0.2126f * next.red + 0.7152f * next.green + 0.0722f * next.blue
        val lumaDelta = abs(nextLuma - prevLuma)
        return if (maxChannelDelta >= SCENE_CUT_DELTA_THRESHOLD || lumaDelta >= 0.16f) {
            COLOR_BLEND_SCENE_CUT_MS
        } else {
            COLOR_BLEND_NORMAL_MS
        }
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
        val c = v * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(
            (r1 + m).coerceIn(0f, 1f),
            (g1 + m).coerceIn(0f, 1f),
            (b1 + m).coerceIn(0f, 1f)
        )
    }

    @OptIn(UnstableApi::class)
    @Composable
    fun rememberAmbientColor(
        playerView: PlayerView?,
        player: Player? = null,
        isEnabled: Boolean,
        isPlaying: Boolean,
        isBuffering: Boolean = false,
        isInPip: Boolean,
        resizeMode: Int
    ): Color {
        val isLetterboxedMode = resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM &&
            resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FILL
        val shouldSample = isEnabled && !isInPip && isLetterboxedMode && playerView != null

        var targetColor by remember { mutableStateOf(Color.Black) }
        var previousTargetColor by remember { mutableStateOf(Color.Black) }
        var seekOrFrameSignal by remember { mutableIntStateOf(0) }
        var playbackSpeed by remember { mutableFloatStateOf(player?.playbackParameters?.speed ?: 1f) }
        val inFlight = remember { AtomicBoolean(false) }

        val samplerState = remember {
            AmbientSamplerResources(
                thread = HandlerThread("LaterboxAmbientSampler").apply { start() },
                bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888),
                pixelBuffer = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
            )
        }

        DisposableEffect(Unit) {
            onDispose {
                samplerState.disposed.set(true)
                runCatching { samplerState.thread.quitSafely() }
                runCatching {
                    if (!samplerState.bitmap.isRecycled) {
                        samplerState.bitmap.recycle()
                    }
                }
            }
        }

        DisposableEffect(player, shouldSample) {
            val activePlayer = if (shouldSample) player else null
            if (activePlayer == null) {
                onDispose {}
            } else {
                playbackSpeed = activePlayer.playbackParameters.speed
                val listener = object : Player.Listener {
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        ) {
                            seekOrFrameSignal++
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            seekOrFrameSignal++
                        }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        playbackSpeed = playbackParameters.speed
                    }

                    override fun onRenderedFirstFrame() {
                        seekOrFrameSignal++
                    }
                }
                activePlayer.addListener(listener)
                onDispose {
                    activePlayer.removeListener(listener)
                }
            }
        }

        LaunchedEffect(shouldSample) {
            if (!shouldSample) {
                targetColor = Color.Black
            }
        }

        LaunchedEffect(shouldSample, isPlaying, isBuffering, playerView, seekOrFrameSignal, playbackSpeed) {
            val activeView = if (shouldSample) playerView else null
            if (activeView == null) return@LaunchedEffect

            fun requestSample() {
                if (samplerState.disposed.get() || samplerState.bitmap.isRecycled) return
                if (!inFlight.compareAndSet(false, true)) return

                val videoSurface = activeView.videoSurfaceView
                if (videoSurface == null || !videoSurface.isShown || videoSurface.width <= 0 || videoSurface.height <= 0) {
                    inFlight.set(false)
                    return
                }

                when (videoSurface) {
                    is SurfaceView -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && videoSurface.holder.surface.isValid) {
                            runCatching {
                                PixelCopy.request(
                                    videoSurface,
                                    samplerState.bitmap,
                                    { copyResult ->
                                        try {
                                            if (copyResult == PixelCopy.SUCCESS &&
                                                !samplerState.disposed.get() &&
                                                !samplerState.bitmap.isRecycled
                                            ) {
                                                samplerState.bitmap.getPixels(
                                                    samplerState.pixelBuffer,
                                                    0,
                                                    SAMPLE_WIDTH,
                                                    0,
                                                    0,
                                                    SAMPLE_WIDTH,
                                                    SAMPLE_HEIGHT
                                                )
                                                targetColor = extractAmbientColor(samplerState.pixelBuffer)
                                            }
                                        } finally {
                                            inFlight.set(false)
                                        }
                                    },
                                    samplerState.handler
                                )
                            }.onFailure {
                                inFlight.set(false)
                            }
                        } else {
                            inFlight.set(false)
                        }
                    }
                    is TextureView -> {
                        try {
                            if (videoSurface.isAvailable && !samplerState.disposed.get() && !samplerState.bitmap.isRecycled) {
                                videoSurface.getBitmap(samplerState.bitmap)
                                samplerState.bitmap.getPixels(
                                    samplerState.pixelBuffer,
                                    0,
                                    SAMPLE_WIDTH,
                                    0,
                                    0,
                                    SAMPLE_WIDTH,
                                    SAMPLE_HEIGHT
                                )
                                targetColor = extractAmbientColor(samplerState.pixelBuffer)
                            }
                        } finally {
                            inFlight.set(false)
                        }
                    }
                    else -> {
                        inFlight.set(false)
                    }
                }
            }

            // Capture immediately on attach/play/seek/ready transition
            requestSample()
            if (isPlaying && !isBuffering) {
                val intervalMs = resolveSampleIntervalMs(playbackSpeed)
                while (isActive) {
                    delay(intervalMs)
                    requestSample()
                }
            } else if (seekOrFrameSignal > 0 && !isBuffering) {
                // Follow-up capture once the decoder commits a seeked paused frame to the surface
                delay(POST_SEEK_REFRESH_DELAY_MS)
                requestSample()
            }
        }

        val desiredColor = if (shouldSample) targetColor else Color.Black
        val blendDurationMs = remember(desiredColor) {
            val ms = computeBlendDurationMs(previousTargetColor, desiredColor)
            previousTargetColor = desiredColor
            ms
        }
        val animatedColor by animateColorAsState(
            targetValue = desiredColor,
            animationSpec = tween(durationMillis = blendDurationMs),
            label = "player_ambient_color"
        )
        return animatedColor
    }

    private class AmbientSamplerResources(
        val thread: HandlerThread,
        val bitmap: Bitmap,
        val pixelBuffer: IntArray
    ) {
        val handler: Handler = Handler(thread.looper)
        val disposed = AtomicBoolean(false)
    }
}
