package com.laterbox.app.diagnostics

import android.app.Activity
import android.util.Log
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.laterbox.app.BuildConfig

/**
 * Benchmark-only JankStats tracker logging janky frame durations (> deadline) with current route (C5/A10).
 */
object JankStatsPlatformHook {
    private const val TAG = "LaterboxJank"

    fun attach(activity: Activity): JankStatsSession? {
        if (!BuildConfig.ENABLE_JANK_STATS) return null
        val window = activity.window ?: return null
        val holder = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
        val listener = JankStats.OnFrameListener { frameData ->
            if (frameData.isJank) {
                val uiMs = frameData.frameDurationUiNanos / 1_000_000.0
                val states = frameData.states.joinToString(",") { "${it.key}=${it.value}" }
                Log.i(TAG, String.format("jank_frame uiMs=%.2f states=[%s]", uiMs, states))
            }
        }
        val jankStats = JankStats.createAndTrack(window, listener)
        jankStats.isTrackingEnabled = true
        return object : JankStatsSession {
            override fun setRoute(route: String) {
                holder.state?.putState("route", route)
            }

            override fun pause() {
                jankStats.isTrackingEnabled = false
            }

            override fun resume() {
                jankStats.isTrackingEnabled = true
            }

            override fun dispose() {
                jankStats.isTrackingEnabled = false
            }
        }
    }
}
