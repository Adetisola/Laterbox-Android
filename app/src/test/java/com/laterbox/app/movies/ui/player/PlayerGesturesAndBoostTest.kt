package com.laterbox.app.movies.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGesturesAndBoostTest {

    // -------------------------------------------------------------------------
    // PlayerAudioBoostController math & safety guarantees
    // -------------------------------------------------------------------------

    @Test
    fun audioBoost_gainIsZeroAtOrBelow100PercentAndLinearUpToMax900Mb() {
        assertEquals(0, PlayerAudioBoostController.computeGainMillibels(0))
        assertEquals(0, PlayerAudioBoostController.computeGainMillibels(50))
        assertEquals(0, PlayerAudioBoostController.computeGainMillibels(100))
        assertEquals(450, PlayerAudioBoostController.computeGainMillibels(150))
        assertEquals(900, PlayerAudioBoostController.computeGainMillibels(200))
        // Never exceeds MAX_BOOST_GAIN_MB (+900 mB)
        assertEquals(900, PlayerAudioBoostController.computeGainMillibels(250))
    }

    @Test
    fun audioBoost_exoPlayerVolumeNeverExceedsOneFloat() {
        assertEquals(0f, PlayerAudioBoostController.computeExoPlayerVolume(0), 0.0001f)
        assertEquals(1.0f, PlayerAudioBoostController.computeExoPlayerVolume(1), 0.0001f)
        assertEquals(1.0f, PlayerAudioBoostController.computeExoPlayerVolume(100), 0.0001f)
        assertEquals(1.0f, PlayerAudioBoostController.computeExoPlayerVolume(150), 0.0001f)
        assertEquals(1.0f, PlayerAudioBoostController.computeExoPlayerVolume(200), 0.0001f)
    }

    @Test
    fun audioBoost_isBoostActiveAndFallbackClampingWorkProperly() {
        assertFalse(PlayerAudioBoostController.isBoostActive(0))
        assertFalse(PlayerAudioBoostController.isBoostActive(100))
        assertTrue(PlayerAudioBoostController.isBoostActive(101))
        assertTrue(PlayerAudioBoostController.isBoostActive(200))

        assertEquals(160, PlayerAudioBoostController.clampEffectivePercent(160, boostSupported = true))
        assertEquals(200, PlayerAudioBoostController.clampEffectivePercent(240, boostSupported = true))
        assertEquals(100, PlayerAudioBoostController.clampEffectivePercent(160, boostSupported = false))
        assertEquals(0, PlayerAudioBoostController.clampEffectivePercent(-10, boostSupported = false))

        assertEquals(0, PlayerAudioBoostController.computeStreamVolumeIndex(0, maxIndex = 15))
        assertEquals(8, PlayerAudioBoostController.computeStreamVolumeIndex(50, maxIndex = 15))
        assertEquals(15, PlayerAudioBoostController.computeStreamVolumeIndex(100, maxIndex = 15))
    }

    // -------------------------------------------------------------------------
    // PlayerGesturesMath zone classification, scrub sensitivity & clamping
    // -------------------------------------------------------------------------

    @Test
    fun verticalZone_splitsPlayerIntoOuter28PercentEdgeZonesAnd44PercentCenterDeadZone() {
        assertEquals(VerticalGestureZone.LEFT, PlayerGesturesMath.classifyVerticalZone(100f, 1000f))
        assertEquals(VerticalGestureZone.LEFT, PlayerGesturesMath.classifyVerticalZone(280f, 1000f))
        assertEquals(null, PlayerGesturesMath.classifyVerticalZone(281f, 1000f))
        assertEquals(null, PlayerGesturesMath.classifyVerticalZone(500f, 1000f))
        assertEquals(null, PlayerGesturesMath.classifyVerticalZone(719f, 1000f))
        assertEquals(VerticalGestureZone.RIGHT, PlayerGesturesMath.classifyVerticalZone(720f, 1000f))
        assertEquals(VerticalGestureZone.RIGHT, PlayerGesturesMath.classifyVerticalZone(950f, 1000f))
    }

    @Test
    fun horizontalScrub_verticalDragDynamicallyAdjustsSeekSensitivity() {
        val base = SeekSensitivityTier.NORMAL
        // Dragging down (positive Y) increases precision -> Slow / Fine
        assertEquals(
            SeekSensitivityTier.SLOW,
            PlayerGesturesMath.sensitivityForVerticalDragPx(base, totalVerticalDragPx = 90f, stepPx = 88f)
        )
        assertEquals(
            SeekSensitivityTier.FINE,
            PlayerGesturesMath.sensitivityForVerticalDragPx(base, totalVerticalDragPx = 220f, stepPx = 88f)
        )
        // Clamped at FINE even on huge downward drag
        assertEquals(
            SeekSensitivityTier.FINE,
            PlayerGesturesMath.sensitivityForVerticalDragPx(base, totalVerticalDragPx = 800f, stepPx = 88f)
        )
        // Dragging up (negative Y) speeds up -> Fast, clamped at FAST
        assertEquals(
            SeekSensitivityTier.FAST,
            PlayerGesturesMath.sensitivityForVerticalDragPx(base, totalVerticalDragPx = -90f, stepPx = 88f)
        )
        assertEquals(
            SeekSensitivityTier.FAST,
            PlayerGesturesMath.sensitivityForVerticalDragPx(base, totalVerticalDragPx = -500f, stepPx = 88f)
        )
    }

    @Test
    fun horizontalScrub_incrementalDeltaAndTargetClampScaleWithSensitivityAndDuration() {
        // Duration 360_000ms (6 min) -> fullSwipeSpanMs = 90_000ms
        val dur = 360_000L
        assertEquals(90_000L, PlayerGesturesMath.fullSwipeSpanMs(dur))

        val normalDelta = PlayerGesturesMath.computeIncrementalSeekDeltaMs(
            deltaXPx = 100f,
            widthPx = 1000f,
            sensitivity = SeekSensitivityTier.NORMAL,
            durationMs = dur
        )
        val fineDelta = PlayerGesturesMath.computeIncrementalSeekDeltaMs(
            deltaXPx = 100f,
            widthPx = 1000f,
            sensitivity = SeekSensitivityTier.FINE,
            durationMs = dur
        )
        val fastDelta = PlayerGesturesMath.computeIncrementalSeekDeltaMs(
            deltaXPx = 100f,
            widthPx = 1000f,
            sensitivity = SeekSensitivityTier.FAST,
            durationMs = dur
        )

        assertEquals(9_000L, normalDelta)
        assertEquals(2_250L, fineDelta)
        assertEquals(18_000L, fastDelta)

        assertEquals(0L, PlayerGesturesMath.clampSeekTargetMs(5_000L, -10_000L, dur))
        assertEquals(dur, PlayerGesturesMath.clampSeekTargetMs(350_000L, 20_000L, dur))
        assertEquals(150_000L, PlayerGesturesMath.clampSeekTargetMs(100_000L, 50_000L, dur))
    }

    @Test
    fun brightnessAndVolumeDeltas_clampStrictlyWithinSafeBounds() {
        assertEquals(0.01f, PlayerGesturesMath.applyBrightnessDelta(0.1f, -0.5f), 0.0001f)
        assertEquals(1.0f, PlayerGesturesMath.applyBrightnessDelta(0.9f, 0.5f), 0.0001f)
        assertEquals(0.65f, PlayerGesturesMath.applyBrightnessDelta(0.5f, 0.15f), 0.0001f)

        assertEquals(0f, PlayerGesturesMath.applyVolumeDelta(10f, -30f, maxPercent = 200), 0.0001f)
        assertEquals(200f, PlayerGesturesMath.applyVolumeDelta(180f, 50f, maxPercent = 200), 0.0001f)
        assertEquals(100f, PlayerGesturesMath.applyVolumeDelta(90f, 50f, maxPercent = 100), 0.0001f)
    }

    @Test
    fun computeInitialExcessDelta_returnsZeroWithinSlopAndPreservesSignedExcessOutsideSlop() {
        val slop = 24f
        assertEquals(0f, PlayerGesturesMath.computeInitialExcessDelta(0f, slop), 0.0001f)
        assertEquals(0f, PlayerGesturesMath.computeInitialExcessDelta(24f, slop), 0.0001f)
        assertEquals(0f, PlayerGesturesMath.computeInitialExcessDelta(-24f, slop), 0.0001f)

        assertEquals(11f, PlayerGesturesMath.computeInitialExcessDelta(35f, slop), 0.0001f)
        assertEquals(-16f, PlayerGesturesMath.computeInitialExcessDelta(-40f, slop), 0.0001f)
        assertEquals(10f, PlayerGesturesMath.computeInitialExcessDelta(10f, -5f), 0.0001f)
    }
}
