package com.laterbox.app.movies.ui.player

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import com.laterbox.app.movies.ui.PlayerPlaybackPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class PlayerAmbientSamplerTest {

    @Test
    fun extractAmbientColor_emptyPixels_returnsBlack() {
        assertEquals(Color.Black, PlayerAmbientSampler.extractAmbientColor(intArrayOf()))
    }

    @Test
    fun extractAmbientColor_filtersBlackLetterboxBarsAndClampsBrightness() {
        val blackBar = AndroidColor.rgb(0, 0, 0)
        val brightWarmRed = AndroidColor.rgb(255, 80, 40)
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
        val white = AndroidColor.rgb(255, 255, 255)
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
}
