package com.laterbox.app.movies.ui.player

import androidx.compose.ui.graphics.Color
import com.laterbox.app.movies.ui.PlayerPlaybackPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class PlayerAmbientSamplerTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    @Test
    fun extractAmbientColor_emptyPixels_returnsBlack() {
        assertEquals(Color.Black, PlayerAmbientSampler.extractAmbientColor(intArrayOf()))
    }

    @Test
    fun extractAmbientColor_filtersBlackLetterboxBarsAndClampsBrightness() {
        val blackBar = argb(0, 0, 0)
        val brightWarmRed = argb(255, 80, 40)
        val pixels = IntArray(144) { idx ->
            if (idx < 72) blackBar else brightWarmRed
        }

        val extracted = PlayerAmbientSampler.extractAmbientColor(pixels)
        assertTrue("Expected red channel dominant over blue", extracted.red > extracted.blue)
        assertTrue("Expected non-zero luminance from filtered video region", extracted.red >= 0.14f)
        val peak = max(extracted.red, max(extracted.green, extracted.blue))
        assertTrue("Expected brightness clamped <= 0.55f for HUD contrast, got $peak", peak <= 0.55f)
    }

    @Test
    fun extractAmbientColor_clampsPureWhiteToRestrainedGlow() {
        val white = argb(255, 255, 255)
        val pixels = IntArray(64) { white }
        val extracted = PlayerAmbientSampler.extractAmbientColor(pixels)
        val peak = max(extracted.red, max(extracted.green, extracted.blue))
        assertTrue("White scenes must stay clamped <= 0.55f", peak <= 0.55f)
        assertTrue("White scenes keep neutral floor >= 0.12f", peak >= 0.12f)
    }

    @Test
    fun playbackPreferences_ambientModeDefaultsToDisabled() {
        val defaults = PlayerPlaybackPreferences()
        assertFalse(defaults.ambientModeEnabled)
    }

    @Test
    fun ambientBackdropAlphas_stayRestrainedWithSlightlyIncreasedProminence() {
        assertEquals(0.69f, PlayerAmbientSampler.AMBIENT_CENTER_ALPHA, 0.0001f)
        assertEquals(0.39f, PlayerAmbientSampler.AMBIENT_MID_ALPHA, 0.0001f)
        assertEquals(0.13f, PlayerAmbientSampler.AMBIENT_OUTER_ALPHA, 0.0001f)
        assertTrue(PlayerAmbientSampler.AMBIENT_CENTER_ALPHA > PlayerAmbientSampler.AMBIENT_MID_ALPHA)
        assertTrue(PlayerAmbientSampler.AMBIENT_MID_ALPHA > PlayerAmbientSampler.AMBIENT_OUTER_ALPHA)
        assertTrue(PlayerAmbientSampler.AMBIENT_CENTER_ALPHA <= 0.70f)
    }

    @Test
    fun extractAmbientColor_preservesDarkScenesInsteadOfBoostingToBrightGray() {
        val pureBlack = IntArray(64) { argb(0, 0, 0) }
        assertEquals(Color.Black, PlayerAmbientSampler.extractAmbientColor(pureBlack))

        val dimScene = IntArray(64) { argb(18, 12, 22) }
        val extracted = PlayerAmbientSampler.extractAmbientColor(dimScene)
        val peak = max(extracted.red, max(extracted.green, extracted.blue))
        assertTrue("Dark scenes must stay dimly atmospheric (<0.10f), got $peak", peak < 0.10f)
    }

    @Test
    fun ambientSamplingAndBlending_stayWithinResponsiveRealTimeTargets() {
        val normalInterval = PlayerAmbientSampler.resolveSampleIntervalMs(1.0f)
        val fastInterval = PlayerAmbientSampler.resolveSampleIntervalMs(2.0f)
        assertTrue("Expected 67ms..100ms (~10-15fps) interval, got $normalInterval", normalInterval in 67L..100L)
        assertTrue("Expected fast speed >= 67ms battery floor and <= normal interval", fastInterval in 67L..normalInterval)
        assertEquals(32, PlayerAmbientSampler.SAMPLE_WIDTH)
        assertEquals(18, PlayerAmbientSampler.SAMPLE_HEIGHT)

        val sceneCutBlend = PlayerAmbientSampler.computeBlendDurationMs(
            previous = Color(0.54f, 0.50f, 0.45f, 1f),
            next = Color.Black
        )
        val subtleBlend = PlayerAmbientSampler.computeBlendDurationMs(
            previous = Color(0.30f, 0.25f, 0.20f, 1f),
            next = Color(0.33f, 0.27f, 0.22f, 1f)
        )
        assertTrue("Scene cut blend should fall in 100-150ms target, got $sceneCutBlend", sceneCutBlend in 100..150)
        assertTrue("Gentle blend should fall in 100-150ms target, got $subtleBlend", subtleBlend in 100..150)
        assertTrue(sceneCutBlend < subtleBlend)
    }

    @Test
    fun extractAmbientColor_5BucketQuantizationPicksDominantColorOverGreyMush() {
        val dominantRed = argb(230, 35, 35)
        val minorityCyan = argb(25, 220, 230)
        val pixels = IntArray(32 * 18) { idx ->
            if (idx < 360) dominantRed else minorityCyan
        }

        val extracted = PlayerAmbientSampler.extractAmbientColor(pixels)
        assertTrue("Expected dominant red cluster to win over cyan", extracted.red > extracted.blue * 2f)
        assertTrue("Expected vivid saturated glow rather than neutral grey mush", (extracted.red - extracted.green) > 0.15f)
    }
}
