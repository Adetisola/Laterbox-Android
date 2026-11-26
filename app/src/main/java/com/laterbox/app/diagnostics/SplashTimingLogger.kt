package com.laterbox.app.diagnostics

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic splash screen release telemetry (A8 / C7).
 * Captures DataStore first-emission latency vs the 800ms safety ceiling.
 */
object SplashTimingLogger {
    const val TAG = "LaterboxSplash"
    const val SAFETY_CEILING_MS = 500L

    data class SplashSample(
        val splashStartElapsedMs: Long,
        val prefsFirstEmissionElapsedMs: Long?,
        val prefsLoadedBeforeCeiling: Boolean,
        val elapsedMs: Long
    )

    private val recentSamples = CopyOnWriteArrayList<SplashSample>()

    fun nowElapsedMs(): Long = SystemClock.elapsedRealtime()

    fun shouldKeepSplashOnScreen(
        prefsLoaded: Boolean,
        splashStartElapsedMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val elapsed = (nowElapsedMs - splashStartElapsedMs).coerceAtLeast(0L)
        return !prefsLoaded && elapsed < SAFETY_CEILING_MS
    }

    fun recordRelease(
        splashStartElapsedMs: Long,
        prefsFirstEmissionElapsedMs: Long?,
        prefsLoadedBeforeCeiling: Boolean,
        releasedAtElapsedMs: Long = SystemClock.elapsedRealtime()
    ): SplashSample {
        val elapsedMs = (releasedAtElapsedMs - splashStartElapsedMs).coerceAtLeast(0L)
        val sample = SplashSample(
            splashStartElapsedMs = splashStartElapsedMs,
            prefsFirstEmissionElapsedMs = prefsFirstEmissionElapsedMs,
            prefsLoadedBeforeCeiling = prefsLoadedBeforeCeiling,
            elapsedMs = elapsedMs
        )
        recentSamples.add(sample)
        while (recentSamples.size > 20) {
            recentSamples.removeAt(0)
        }
        runCatching {
            Log.d(
                TAG,
                "splash_start_elapsed_ms=$splashStartElapsedMs " +
                    "prefs_first_emission_elapsed_ms=${prefsFirstEmissionElapsedMs ?: -1L} " +
                    "prefs_loaded_before_ceiling=$prefsLoadedBeforeCeiling " +
                    "elapsed_ms=$elapsedMs"
            )
        }
        return sample
    }

    fun recentSamples(): List<SplashSample> = recentSamples.toList()

    fun resetForTest() {
        recentSamples.clear()
    }
}
