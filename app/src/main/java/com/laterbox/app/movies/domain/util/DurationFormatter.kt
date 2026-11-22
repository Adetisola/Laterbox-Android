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
                add(if (hours == 1) "1HR" else "${hours}HRS")
            }
            if (remainingMinutes > 0) {
                add(if (remainingMinutes == 1) "1MIN" else "${remainingMinutes}MINS")
            }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
