package com.laterbox.app.movies.domain.util

/**
 * Shared human-readable duration formatter for Laterbox movie cards, hero banners, and detail views.
 */
object DurationFormatter {

    fun formatMinutes(totalMinutes: Int?): String? {
        val minutes = totalMinutes ?: return null
        if (minutes <= 0) return null
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        val parts = buildList {
            if (hours > 0) {
                add(if (hours == 1) "1hr" else "${hours}hrs")
            }
            if (remainingMinutes > 0) {
                add(if (remainingMinutes == 1) "1min" else "${remainingMinutes}mins")
            }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
