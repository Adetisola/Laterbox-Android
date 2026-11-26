package com.laterbox.app.diagnostics

import android.app.Activity

/** No-op JankStats hook for release builds (C5). */
object JankStatsPlatformHook {
    fun attach(activity: Activity): JankStatsSession? = null
}
