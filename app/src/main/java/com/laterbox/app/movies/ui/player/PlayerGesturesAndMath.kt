package com.laterbox.app.movies.ui.player

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class SeekSensitivityTier(
    val multiplier: Float,
    val shortLabel: String,
    val displayLabel: String
) {
    FINE(0.25f, "0.25x", "0.25x Fine"),
    SLOW(0.5f, "0.5x", "0.5x Slow"),
    NORMAL(1.0f, "1.0x", "1.0x Normal"),
    FAST(2.0f, "2.0x", "2.0x Fast");

    companion object {
        fun fromPreference(multiplier: Float): SeekSensitivityTier =
            entries.minByOrNull { abs(it.multiplier - multiplier) } ?: NORMAL
    }
}

enum class VerticalGestureZone {
    LEFT,
    RIGHT
}

private enum class ActiveDragMode {
    UNDECIDED,
    HORIZONTAL_SEEK,
    VERTICAL_BRIGHTNESS,
    VERTICAL_VOLUME
}

object PlayerGesturesMath {
    const val DEFAULT_FULL_SWIPE_SPAN_MS = 90_000L
    const val MAX_FULL_SWIPE_SPAN_MS = 180_000L
    const val MIN_FULL_SWIPE_SPAN_MS = 30_000L

    fun classifyVerticalZone(touchX: Float, widthPx: Float): VerticalGestureZone {
        val safeWidth = widthPx.coerceAtLeast(1f)
        return if (touchX < safeWidth / 2f) VerticalGestureZone.LEFT else VerticalGestureZone.RIGHT
    }

    /**
     * Adjusts seek sensitivity dynamically as the user moves their finger vertically
     * while performing a horizontal scrub.
     * Dragging DOWN increases precision (moves toward 0.25x Fine); dragging UP speeds up
     * scrubbing (moves toward 2.0x Fast).
     */
    fun sensitivityForVerticalDragPx(
        baseSensitivity: SeekSensitivityTier,
        totalVerticalDragPx: Float,
        stepPx: Float = 88f
    ): SeekSensitivityTier {
        val safeStep = stepPx.coerceAtLeast(24f)
        // Negative Y is dragging UP (faster -> higher ordinal); positive Y is dragging DOWN (finer -> lower ordinal).
        val deltaSteps = (-totalVerticalDragPx / safeStep).roundToInt()
        val tiers = SeekSensitivityTier.entries
        val targetOrdinal = (baseSensitivity.ordinal + deltaSteps).coerceIn(0, tiers.lastIndex)
        return tiers[targetOrdinal]
    }

    fun fullSwipeSpanMs(durationMs: Long): Long {
        if (durationMs <= 0L) return DEFAULT_FULL_SWIPE_SPAN_MS
        return (durationMs / 4L).coerceIn(MIN_FULL_SWIPE_SPAN_MS, MAX_FULL_SWIPE_SPAN_MS)
    }

    fun computeIncrementalSeekDeltaMs(
        deltaXPx: Float,
        widthPx: Float,
        sensitivity: SeekSensitivityTier,
        durationMs: Long
    ): Long {
        val safeWidth = widthPx.coerceAtLeast(1f)
        val span = fullSwipeSpanMs(durationMs).toDouble()
        return ((deltaXPx.toDouble() / safeWidth.toDouble()) * span * sensitivity.multiplier.toDouble()).roundToLong()
    }

    fun clampSeekTargetMs(
        startPositionMs: Long,
        accumulatedDeltaMs: Long,
        durationMs: Long
    ): Long {
        val rawTarget = startPositionMs + accumulatedDeltaMs
        return if (durationMs > 0L) {
            rawTarget.coerceIn(0L, durationMs)
        } else {
            rawTarget.coerceAtLeast(0L)
        }
    }

    fun applyBrightnessDelta(currentBrightness: Float, deltaFraction: Float): Float =
        (currentBrightness + deltaFraction).coerceIn(0.01f, 1.0f)

    fun applyVolumeDelta(currentAccumulatedPercent: Float, deltaPercent: Float, maxPercent: Int): Float =
        (currentAccumulatedPercent + deltaPercent).coerceIn(0f, maxPercent.toFloat())

    fun computeInitialExcessDelta(totalDelta: Float, touchSlop: Float): Float {
        val slop = touchSlop.coerceAtLeast(0f)
        if (abs(totalDelta) <= slop) return 0f
        return if (totalDelta > 0f) totalDelta - slop else totalDelta + slop
    }
}

/**
 * Saves and restores window-scoped [WindowManager.LayoutParams.screenBrightness] so player
 * brightness gestures never mutate global system brightness.
 */
class PlayerBrightnessHelper(private val window: Window?) {
    constructor(activity: Activity?) : this(activity?.window)

    private val initialBrightness: Float =
        window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    var currentNormalizedBrightness: Float =
        if (initialBrightness in 0.01f..1.0f) initialBrightness else 0.5f
        private set

    fun setBrightness(normalized: Float) {
        val clamped = normalized.coerceIn(0.01f, 1.0f)
        currentNormalizedBrightness = clamped
        val targetWindow = window ?: return
        val params = targetWindow.attributes
        params.screenBrightness = clamped
        targetWindow.attributes = params
    }

    fun restoreOriginalBrightness() {
        val targetWindow = window ?: return
        val params = targetWindow.attributes
        params.screenBrightness = initialBrightness
        targetWindow.attributes = params
    }
}

/**
 * Conflict-free drag gesture handler for:
 * - Horizontal scrub across the player (with vertical drag dynamically tuning seek sensitivity)
 * - Left-side vertical swipe for window-scoped brightness
 * - Right-side vertical swipe for 0–200% volume / boost
 *
 * Ignores events already consumed by buttons/sliders and yields to tap, double-tap, and hold-2x
 * when movement remains below touchSlop.
 */
fun Modifier.playerDragGestures(
    enabled: Boolean,
    swipeToSeekEnabled: Boolean,
    brightnessGestureEnabled: Boolean,
    volumeGestureEnabled: Boolean,
    baseSensitivity: SeekSensitivityTier,
    currentPositionProvider: () -> Long,
    durationProvider: () -> Long,
    onHorizontalScrubStart: (startPosMs: Long) -> Unit,
    onHorizontalScrubUpdate: (targetPosMs: Long, deltaMs: Long, sensitivity: SeekSensitivityTier) -> Unit,
    onHorizontalScrubEnd: (targetPosMs: Long) -> Unit,
    onHorizontalScrubCancel: () -> Unit,
    onBrightnessDelta: (deltaFraction: Float) -> Unit,
    onBrightnessGestureEnd: () -> Unit,
    onVolumeDelta: (deltaPercent: Float) -> Unit,
    onVolumeGestureEnd: () -> Unit
): Modifier {
    if (!enabled) return this
    return this.pointerInput(
        swipeToSeekEnabled,
        brightnessGestureEnabled,
        volumeGestureEnabled,
        baseSensitivity
    ) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val widthPx = size.width.toFloat().coerceAtLeast(1f)
            val heightPx = size.height.toFloat().coerceAtLeast(1f)
            val verticalZone = PlayerGesturesMath.classifyVerticalZone(down.position.x, widthPx)

            var mode = ActiveDragMode.UNDECIDED
            var totalDx = 0f
            var totalDy = 0f
            var scrubLockY = 0f
            var scrubStartPosMs = 0L
            var scrubAccumulatedDeltaMs = 0L
            var activeSensitivity = baseSensitivity

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) {
                        // Cancel single-finger drag if multi-touch begins.
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        break
                    }
                    if (change.isConsumed && mode == ActiveDragMode.UNDECIDED) {
                        // A child control (e.g., Slider) claimed this gesture.
                        break
                    }

                    val delta = change.positionChange()
                    if (mode == ActiveDragMode.UNDECIDED) {
                        totalDx += delta.x
                        totalDy += delta.y
                        val movedEnoughX = abs(totalDx) > touchSlop
                        val movedEnoughY = abs(totalDy) > touchSlop
                        if (movedEnoughX || movedEnoughY) {
                            if (abs(totalDx) >= abs(totalDy)) {
                                if (!swipeToSeekEnabled) {
                                    break
                                }
                                mode = ActiveDragMode.HORIZONTAL_SEEK
                                scrubLockY = change.position.y
                                scrubStartPosMs = currentPositionProvider().coerceAtLeast(0L)
                                scrubAccumulatedDeltaMs = 0L
                                activeSensitivity = baseSensitivity
                                onHorizontalScrubStart(scrubStartPosMs)
                                val excessDx = PlayerGesturesMath.computeInitialExcessDelta(totalDx, touchSlop)
                                if (abs(excessDx) > 0f) {
                                    val durationMs = durationProvider().coerceAtLeast(0L)
                                    val incMs = PlayerGesturesMath.computeIncrementalSeekDeltaMs(
                                        deltaXPx = excessDx,
                                        widthPx = widthPx,
                                        sensitivity = activeSensitivity,
                                        durationMs = durationMs
                                    )
                                    scrubAccumulatedDeltaMs += incMs
                                    val targetMs = PlayerGesturesMath.clampSeekTargetMs(
                                        startPositionMs = scrubStartPosMs,
                                        accumulatedDeltaMs = scrubAccumulatedDeltaMs,
                                        durationMs = durationMs
                                    )
                                    scrubAccumulatedDeltaMs = targetMs - scrubStartPosMs
                                    onHorizontalScrubUpdate(targetMs, scrubAccumulatedDeltaMs, activeSensitivity)
                                }
                                change.consume()
                            } else {
                                val excessDy = PlayerGesturesMath.computeInitialExcessDelta(totalDy, touchSlop)
                                when (verticalZone) {
                                    VerticalGestureZone.LEFT -> {
                                        if (!brightnessGestureEnabled) break
                                        mode = ActiveDragMode.VERTICAL_BRIGHTNESS
                                        change.consume()
                                        val deltaFraction = -excessDy / (heightPx * 0.65f)
                                        if (abs(deltaFraction) > 0f) {
                                            onBrightnessDelta(deltaFraction)
                                        }
                                    }
                                    VerticalGestureZone.RIGHT -> {
                                        if (!volumeGestureEnabled) break
                                        mode = ActiveDragMode.VERTICAL_VOLUME
                                        change.consume()
                                        val deltaPercent = (-excessDy / (heightPx * 0.65f)) * 180f
                                        if (abs(deltaPercent) > 0f) {
                                            onVolumeDelta(deltaPercent)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        change.consume()
                        when (mode) {
                            ActiveDragMode.HORIZONTAL_SEEK -> {
                                val verticalOffsetFromScrubStart = change.position.y - scrubLockY
                                activeSensitivity = PlayerGesturesMath.sensitivityForVerticalDragPx(
                                    baseSensitivity = baseSensitivity,
                                    totalVerticalDragPx = verticalOffsetFromScrubStart
                                )
                                val durationMs = durationProvider().coerceAtLeast(0L)
                                val incMs = PlayerGesturesMath.computeIncrementalSeekDeltaMs(
                                    deltaXPx = delta.x,
                                    widthPx = widthPx,
                                    sensitivity = activeSensitivity,
                                    durationMs = durationMs
                                )
                                scrubAccumulatedDeltaMs += incMs
                                val targetMs = PlayerGesturesMath.clampSeekTargetMs(
                                    startPositionMs = scrubStartPosMs,
                                    accumulatedDeltaMs = scrubAccumulatedDeltaMs,
                                    durationMs = durationMs
                                )
                                scrubAccumulatedDeltaMs = targetMs - scrubStartPosMs
                                onHorizontalScrubUpdate(targetMs, scrubAccumulatedDeltaMs, activeSensitivity)
                            }
                            ActiveDragMode.VERTICAL_BRIGHTNESS -> {
                                val deltaFraction = -delta.y / (heightPx * 0.65f)
                                if (abs(deltaFraction) > 0f) {
                                    onBrightnessDelta(deltaFraction)
                                }
                            }
                            ActiveDragMode.VERTICAL_VOLUME -> {
                                val deltaPercent = (-delta.y / (heightPx * 0.65f)) * 180f
                                if (abs(deltaPercent) > 0f) {
                                    onVolumeDelta(deltaPercent)
                                }
                            }
                            ActiveDragMode.UNDECIDED -> Unit
                        }
                    }
                }
            } finally {
                when (mode) {
                    ActiveDragMode.HORIZONTAL_SEEK -> {
                        val durationMs = durationProvider().coerceAtLeast(0L)
                        val finalTarget = PlayerGesturesMath.clampSeekTargetMs(
                            startPositionMs = scrubStartPosMs,
                            accumulatedDeltaMs = scrubAccumulatedDeltaMs,
                            durationMs = durationMs
                        )
                        onHorizontalScrubEnd(finalTarget)
                    }
                    ActiveDragMode.VERTICAL_BRIGHTNESS -> onBrightnessGestureEnd()
                    ActiveDragMode.VERTICAL_VOLUME -> onVolumeGestureEnd()
                    ActiveDragMode.UNDECIDED -> onHorizontalScrubCancel()
                }
            }
        }
    }
}

enum class PlayerTapZone {
    LEFT,
    CENTER,
    RIGHT
}

sealed interface PlayerTapOutcome {
    data class SetControlsVisible(val visible: Boolean) : PlayerTapOutcome
    data class SeekDelta(
        val direction: Int,
        val accumulatedSeconds: Int,
        val stepSeconds: Int,
        val keepControlsVisible: Boolean
    ) : PlayerTapOutcome
}

/**
 * Pure tap state machine for video player surface taps:
 * - Zero-delay (0ms) immediate controls reveal when controls are hidden
 * - Zero-delay (0ms) immediate controls toggle when controls are visible
 * - Non-blocking quick consecutive left/right taps that trigger and accumulate +/- seek
 */
class PlayerTapGestureTracker(
    private val doubleTapWindowMs: Long = 300L,
    private val accumulationWindowMs: Long = 700L
) {
    private var lastTapUptimeMs: Long = Long.MIN_VALUE
    private var lastTapZone: PlayerTapZone? = null
    private var accumulatingDirection: Int = 0
    private var accumulatedSeconds: Int = 0

    fun reset() {
        lastTapUptimeMs = Long.MIN_VALUE
        lastTapZone = null
        accumulatingDirection = 0
        accumulatedSeconds = 0
    }

    fun onTap(
        touchX: Float,
        widthPx: Float,
        controlsVisible: Boolean,
        isLocked: Boolean,
        stepSeconds: Int,
        uptimeMs: Long
    ): PlayerTapOutcome {
        val safeWidth = widthPx.coerceAtLeast(1f)
        val zone = when {
            touchX < safeWidth * 0.35f -> PlayerTapZone.LEFT
            touchX > safeWidth * 0.65f -> PlayerTapZone.RIGHT
            else -> PlayerTapZone.CENTER
        }

        if (isLocked) {
            reset()
            return PlayerTapOutcome.SetControlsVisible(!controlsVisible)
        }

        val withinAccumulation = accumulatingDirection != 0 &&
            (uptimeMs - lastTapUptimeMs) in 0L..accumulationWindowMs
        if (withinAccumulation) {
            val tapDir = when (zone) {
                PlayerTapZone.LEFT -> -1
                PlayerTapZone.RIGHT -> 1
                PlayerTapZone.CENTER -> 0
            }
            if (tapDir == accumulatingDirection) {
                accumulatedSeconds += stepSeconds
                lastTapUptimeMs = uptimeMs
                lastTapZone = zone
                return PlayerTapOutcome.SeekDelta(
                    direction = tapDir,
                    accumulatedSeconds = accumulatedSeconds,
                    stepSeconds = stepSeconds,
                    keepControlsVisible = false
                )
            }
        }

        val isQuickConsecutiveSideTap = zone != PlayerTapZone.CENTER &&
            lastTapZone == zone &&
            (uptimeMs - lastTapUptimeMs) in 0L..doubleTapWindowMs
        if (isQuickConsecutiveSideTap) {
            val dir = if (zone == PlayerTapZone.LEFT) -1 else 1
            accumulatingDirection = dir
            accumulatedSeconds = stepSeconds
            lastTapUptimeMs = uptimeMs
            lastTapZone = zone
            return PlayerTapOutcome.SeekDelta(
                direction = dir,
                accumulatedSeconds = accumulatedSeconds,
                stepSeconds = stepSeconds,
                keepControlsVisible = false
            )
        }

        accumulatingDirection = 0
        accumulatedSeconds = 0
        lastTapUptimeMs = uptimeMs
        lastTapZone = zone
        return PlayerTapOutcome.SetControlsVisible(!controlsVisible)
    }
}

