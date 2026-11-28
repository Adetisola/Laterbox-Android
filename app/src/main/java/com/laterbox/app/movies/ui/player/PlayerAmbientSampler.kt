package com.laterbox.app.movies.ui.player

import android.graphics.Bitmap
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object PlayerAmbientSampler {
    internal const val SAMPLE_WIDTH = 32
    internal const val SAMPLE_HEIGHT = 18
    internal const val SAMPLE_INTERVAL_PLAYING_MS = 100L
    internal const val SAMPLE_INTERVAL_FAST_MS = 80L
    internal const val MIN_SAMPLE_INTERVAL_MS = 67L
    internal const val POST_SEEK_REFRESH_DELAY_MS = 80L
    internal const val COLOR_BLEND_NORMAL_MS = 120
    internal const val COLOR_BLEND_SCENE_CUT_MS = 100
    internal const val SCENE_CUT_DELTA_THRESHOLD = 0.22f
    internal const val QUANTIZATION_BUCKETS = 5
    const val AMBIENT_CENTER_ALPHA = 0.69f
    const val AMBIENT_MID_ALPHA = 0.39f
    const val AMBIENT_OUTER_ALPHA = 0.13f
    private const val AMBIENT_SATURATION_MULTIPLIER = 1.12f

    /**
     * Extracts the dominant atmospheric color from sampled ARGB pixels using 5-bucket quantization.
     * Filters out pure black letterbox bars when non-black pixels exist, clusters pixels into up to
     * 5 color centroids to avoid washed-out grey averaging on mixed scenes, preserves genuinely dark
     * scenes, and caps highlight luminance so player HUD controls remain crisp and legible.
     */
    fun extractAmbientColor(pixels: IntArray): Color {
        if (pixels.isEmpty()) return Color.Black

        val nonBlackCount = pixels.count { argb ->
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            max(r, max(g, b)) >= 14
        }

        val useFiltered = nonBlackCount > 0
        val binCount = IntArray(64)
        val binR = LongArray(64)
        val binG = LongArray(64)
        val binB = LongArray(64)

        for (argb in pixels) {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            if (useFiltered && max(r, max(g, b)) < 14) continue
            val bin = ((r ushr 6) shl 4) or ((g ushr 6) shl 2) or (b ushr 6)
            binCount[bin]++
            binR[bin] += r
            binG[bin] += g
            binB[bin] += b
        }

        // Select top QUANTIZATION_BUCKETS (5) coarse bins as initial color centroids
        val topBins = IntArray(QUANTIZATION_BUCKETS) { -1 }
        var centroidCount = 0
        for (slot in 0 until QUANTIZATION_BUCKETS) {
            var bestBin = -1
            var bestCount = 0
            for (bIdx in 0 until 64) {
                val cnt = binCount[bIdx]
                if (cnt <= bestCount) continue
                var alreadyPicked = false
                for (prev in 0 until centroidCount) {
                    if (topBins[prev] == bIdx) {
                        alreadyPicked = true
                        break
                    }
                }
                if (!alreadyPicked) {
                    bestCount = cnt
                    bestBin = bIdx
                }
            }
            if (bestBin == -1) break
            topBins[centroidCount++] = bestBin
        }

        if (centroidCount == 0) return Color.Black

        val centR = IntArray(centroidCount)
        val centG = IntArray(centroidCount)
        val centB = IntArray(centroidCount)
        for (k in 0 until centroidCount) {
            val bIdx = topBins[k]
            val cnt = binCount[bIdx].coerceAtLeast(1)
            centR[k] = (binR[bIdx] / cnt).toInt()
            centG[k] = (binG[bIdx] / cnt).toInt()
            centB[k] = (binB[bIdx] / cnt).toInt()
        }

        // Assign all valid pixels into their nearest bucket and pick the largest cluster
        val clusterCount = IntArray(centroidCount)
        val clusterR = LongArray(centroidCount)
        val clusterG = LongArray(centroidCount)
        val clusterB = LongArray(centroidCount)

        for (argb in pixels) {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            if (useFiltered && max(r, max(g, b)) < 14) continue

            var bestK = 0
            var bestDist = Int.MAX_VALUE
            for (k in 0 until centroidCount) {
                val dr = r - centR[k]
                val dg = g - centG[k]
                val db = b - centB[k]
                val dist = (dr * dr) + (dg * dg) + (db * db)
                if (dist < bestDist) {
                    bestDist = dist
                    bestK = k
                }
            }
            clusterCount[bestK]++
            clusterR[bestK] += r
            clusterG[bestK] += g
            clusterB[bestK] += b
        }

        var winningBucket = 0
        var winningSize = -1
        for (k in 0 until centroidCount) {
            if (clusterCount[k] > winningSize) {
                winningSize = clusterCount[k]
                winningBucket = k
            }
        }
        val denom = clusterCount[winningBucket].coerceAtLeast(1).toLong()
        val avgR = (clusterR[winningBucket] / denom).toInt().coerceIn(0, 255)
        val avgG = (clusterG[winningBucket] / denom).toInt().coerceIn(0, 255)
        val avgB = (clusterB[winningBucket] / denom).toInt().coerceIn(0, 255)

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
        val boostedSat = rawSat * AMBIENT_SATURATION_MULTIPLIER
        val clampedSat = if (rawSat < 0.08f) boostedSat * 0.5f else boostedSat.coerceIn(0.14f, 0.72f)
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

    internal fun resolveSampleIntervalMs(playbackSpeed: Float): Long {
        val raw = if (playbackSpeed >= 1.5f) SAMPLE_INTERVAL_FAST_MS else SAMPLE_INTERVAL_PLAYING_MS
        return raw.coerceAtLeast(MIN_SAMPLE_INTERVAL_MS)
    }

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
    fun rememberAmbientColorState(
        playerView: PlayerView?,
        player: Player? = null,
        isEnabled: Boolean,
        isPlaying: Boolean,
        isBuffering: Boolean = false,
        isInPip: Boolean,
        resizeMode: Int
    ): State<Color> {
        val isLetterboxedMode = resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM &&
            resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FILL
        val shouldSample = isEnabled && !isInPip && isLetterboxedMode && playerView != null

        var targetColor by remember { mutableStateOf(Color.Black) }
        var previousTargetColor by remember { mutableStateOf(Color.Black) }
        var seekOrFrameSignal by remember { mutableIntStateOf(0) }
        var playbackSpeed by remember { mutableFloatStateOf(player?.playbackParameters?.speed ?: 1f) }
        val inFlight = remember { AtomicBoolean(false) }
        val lastSampleScheduledMs = remember { AtomicLong(0L) }

        val samplerState = remember {
            AmbientSamplerResources(
                thread = HandlerThread("LaterboxAmbientSampler").apply { start() },
                bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888),
                pixelBuffer = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            )
        }

        val requestSampleFn = remember(playerView) {
            fun(activeView: PlayerView?) {
                if (activeView == null || samplerState.disposed.get() || samplerState.bitmap.isRecycled) return
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
                                        if (copyResult == PixelCopy.SUCCESS &&
                                            !samplerState.disposed.get() &&
                                            !samplerState.bitmap.isRecycled
                                        ) {
                                            samplerState.scope.launch {
                                                try {
                                                    if (!samplerState.disposed.get() && !samplerState.bitmap.isRecycled) {
                                                        samplerState.bitmap.getPixels(
                                                            samplerState.pixelBuffer,
                                                            0,
                                                            SAMPLE_WIDTH,
                                                            0,
                                                            0,
                                                            SAMPLE_WIDTH,
                                                            SAMPLE_HEIGHT
                                                        )
                                                        val dominant = extractAmbientColor(samplerState.pixelBuffer)
                                                        targetColor = dominant
                                                    }
                                                } finally {
                                                    inFlight.set(false)
                                                }
                                            }
                                        } else {
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
                                samplerState.scope.launch {
                                    try {
                                        if (!samplerState.disposed.get() && !samplerState.bitmap.isRecycled) {
                                            samplerState.bitmap.getPixels(
                                                samplerState.pixelBuffer,
                                                0,
                                                SAMPLE_WIDTH,
                                                0,
                                                0,
                                                SAMPLE_WIDTH,
                                                SAMPLE_HEIGHT
                                            )
                                            val dominant = extractAmbientColor(samplerState.pixelBuffer)
                                            targetColor = dominant
                                        }
                                    } finally {
                                        inFlight.set(false)
                                    }
                                }
                            } else {
                                inFlight.set(false)
                            }
                        } catch (_: Throwable) {
                            inFlight.set(false)
                        }
                    }
                    else -> {
                        inFlight.set(false)
                    }
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                samplerState.disposed.set(true)
                samplerState.scope.cancel()
                samplerState.handler.removeCallbacksAndMessages(null)
                runCatching { samplerState.thread.quitSafely() }
                runCatching {
                    if (!samplerState.bitmap.isRecycled) {
                        samplerState.bitmap.recycle()
                    }
                }
            }
        }

        DisposableEffect(player, playerView, shouldSample) {
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
                            lastSampleScheduledMs.set(0L)
                            seekOrFrameSignal++
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            lastSampleScheduledMs.set(0L)
                            seekOrFrameSignal++
                        }
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        playbackSpeed = playbackParameters.speed
                    }

                    override fun onRenderedFirstFrame() {
                        lastSampleScheduledMs.set(0L)
                        seekOrFrameSignal++
                    }
                }
                activePlayer.addListener(listener)

                val exo = activePlayer as? ExoPlayer
                val frameListener = if (exo != null) {
                    VideoFrameMetadataListener { _: Long, releaseTimeNs: Long, _: Format, _: MediaFormat? ->
                        if (samplerState.disposed.get() || !shouldSample) return@VideoFrameMetadataListener
                        val intervalMs = resolveSampleIntervalMs(playbackSpeed)
                        val nowRealMs = SystemClock.elapsedRealtime()
                        val prevRealMs = lastSampleScheduledMs.get()
                        if (nowRealMs - prevRealMs < intervalMs) return@VideoFrameMetadataListener
                        if (!lastSampleScheduledMs.compareAndSet(prevRealMs, nowRealMs)) return@VideoFrameMetadataListener

                        val alignDelayMs = ((releaseTimeNs - System.nanoTime()) / 1_000_000L).coerceIn(0L, 25L)
                        samplerState.handler.postDelayed({
                            if (!samplerState.disposed.get()) {
                                requestSampleFn(playerView)
                            }
                        }, alignDelayMs)
                    }
                } else {
                    null
                }
                if (exo != null && frameListener != null) {
                    exo.setVideoFrameMetadataListener(frameListener)
                }

                onDispose {
                    activePlayer.removeListener(listener)
                    if (exo != null && frameListener != null) {
                        runCatching { exo.clearVideoFrameMetadataListener(frameListener) }
                    }
                }
            }
        }

        LaunchedEffect(shouldSample) {
            if (!shouldSample) {
                targetColor = Color.Black
            }
        }

        LaunchedEffect(shouldSample, isPlaying, isBuffering, playerView, player, seekOrFrameSignal, playbackSpeed) {
            val activeView = if (shouldSample) playerView else null
            if (activeView == null) return@LaunchedEffect

            // Capture immediately on attach/play/seek/ready transition
            requestSampleFn(activeView)
            val hasFrameSync = player is ExoPlayer && activeView.videoSurfaceView is SurfaceView
            if (isPlaying && !isBuffering) {
                val intervalMs = resolveSampleIntervalMs(playbackSpeed)
                while (isActive) {
                    delay(intervalMs)
                    if (!hasFrameSync) {
                        requestSampleFn(activeView)
                    } else {
                        // Watchdog catch-up only if frame callbacks paused > 2x interval without a sample
                        val sinceLastMs = SystemClock.elapsedRealtime() - lastSampleScheduledMs.get()
                        if (sinceLastMs >= intervalMs * 2L) {
                            lastSampleScheduledMs.set(SystemClock.elapsedRealtime())
                            requestSampleFn(activeView)
                        }
                    }
                }
            } else if (seekOrFrameSignal > 0 && !isBuffering) {
                // Follow-up capture once the decoder commits a seeked paused frame to the surface
                delay(POST_SEEK_REFRESH_DELAY_MS)
                requestSampleFn(activeView)
            }
        }

        val desiredColor = if (shouldSample) targetColor else Color.Black
        val blendDurationMs = remember(desiredColor) {
            val ms = computeBlendDurationMs(previousTargetColor, desiredColor)
            previousTargetColor = desiredColor
            ms
        }
        return animateColorAsState(
            targetValue = desiredColor,
            animationSpec = tween(durationMillis = blendDurationMs),
            label = "player_ambient_color"
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
    ): Color = rememberAmbientColorState(
        playerView = playerView,
        player = player,
        isEnabled = isEnabled,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        isInPip = isInPip,
        resizeMode = resizeMode
    ).value

    private class AmbientSamplerResources(
        val thread: HandlerThread,
        val bitmap: Bitmap,
        val pixelBuffer: IntArray,
        val scope: CoroutineScope
    ) {
        val handler: Handler = Handler(thread.looper)
        val disposed = AtomicBoolean(false)
    }
}
