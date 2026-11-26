package com.laterbox.app.movies.data.cache

/**
 * Centralized per-endpoint maximum stale age ceilings (C2).
 * Entries older than CACHE_TTL_MS but within maxStaleAgeMs can render immediately via
 * bounded stale-while-revalidate; entries older than maxStaleAgeMs require a fresh load.
 */
object StaleAge {
    const val HOME_MS: Long = 24L * 60 * 60 * 1000L
    const val CATALOGUE_MS: Long = 24L * 60 * 60 * 1000L
    const val DETAIL_MS: Long = 24L * 60 * 60 * 1000L
    const val EPISODES_MS: Long = 24L * 60 * 60 * 1000L
    const val EPISODE_METADATA_MS: Long = 24L * 60 * 60 * 1000L
    const val STAFF_TITLES_MS: Long = 24L * 60 * 60 * 1000L
    const val SEARCH_MS: Long = 6L * 60 * 60 * 1000L
    const val SUGGEST_MS: Long = 1L * 60 * 60 * 1000L
}
