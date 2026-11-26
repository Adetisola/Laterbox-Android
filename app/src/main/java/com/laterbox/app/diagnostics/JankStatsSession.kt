package com.laterbox.app.diagnostics

interface JankStatsSession {
    fun setRoute(route: String)
    fun pause()
    fun resume()
    fun dispose()
}
