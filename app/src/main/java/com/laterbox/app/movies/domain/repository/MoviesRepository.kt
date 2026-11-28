package com.laterbox.app.movies.domain.repository

import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.CollectionMediaItem
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.LibrarySearchSuggestion
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PersonFilmography
import com.laterbox.app.movies.domain.model.ProviderLinkRecord
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SeriesPreferences
import com.laterbox.app.movies.domain.model.SeriesProgressSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.ViewingReminderItem
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import kotlinx.coroutines.flow.Flow

data class MovieDurationCoverageStats(
    val totalMoviesObserved: Int = 0,
    val moviesWithInitialDuration: Int = 0,
    val enrichedMovies: Int = 0,
    val failedEnrichments: Int = 0
)

data class CachedDetailsSnapshot(
    val details: MediaDetails,
    val isStale: Boolean
)

data class CachedHomeFeedSnapshot(
    val feed: MoviesHomeFeed,
    val isStale: Boolean
)

interface MoviesRepository {
    suspend fun getCachedHomeFeedWithinStaleCeiling(): CachedHomeFeedSnapshot? = null

    suspend fun getHomeFeed(forceRefresh: Boolean = false): Result<MoviesHomeFeed>

    suspend fun getCatalogue(
        kind: MediaKind?,
        page: Int = 1,
        pageSize: Int = 20,
        genre: String? = null,
        sort: CatalogueSort = CatalogueSort.POPULAR,
        country: String? = null,
        year: String? = null
    ): Result<CataloguePage>

    suspend fun getGenres(): Result<List<String>>

    suspend fun search(
        query: String,
        kind: MediaKind? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): Result<CataloguePage>

    suspend fun getSuggestions(query: String, limit: Int = 8): Result<List<SearchSuggestion>>

    suspend fun searchLibrarySuggestions(query: String, limit: Int = 6): List<LibrarySearchSuggestion> = emptyList()

    suspend fun getCachedDetailsFast(providerId: String, titleId: String): CachedDetailsSnapshot? = null

    suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean = false): Result<MediaDetails>

    fun getCachedSummary(providerId: String, titleId: String): MovieTitleSummary? = null

    suspend fun enrichMissingMovieDurations(
        items: List<MovieTitleSummary>,
        maxItems: Int = 20
    ): Map<String, Int> = emptyMap()

    fun getDurationCoverageStats(): MovieDurationCoverageStats = MovieDurationCoverageStats()

    suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>>

    suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>>

    suspend fun getSeasonEpisodeSizes(
        providerId: String,
        seriesId: String,
        seasonNumber: Int,
        episodeNumbers: List<Int> = emptyList()
    ): Result<Map<Int, Map<Int, Double>>> = Result.success(emptyMap())

    suspend fun getPersonFilmography(
        providerId: String,
        personId: String,
        personName: String,
        personImageUrl: String? = null
    ): Result<PersonFilmography> = Result.failure(
        UnsupportedOperationException("Person filmography is not supported for $providerId")
    )

    suspend fun resolvePlayback(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<PlaybackResolution>

    suspend fun getSubtitles(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<List<SubtitleTrack>>

    fun observeWatchlist(): Flow<List<MovieTitleSummary>>
    fun observeIsInWatchlist(compositeId: String): Flow<Boolean>
    suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean

    fun observeContinueWatching(limit: Int = 12): Flow<List<WatchHistoryItem>>
    suspend fun saveWatchProgress(
        item: MovieTitleSummary,
        positionMs: Long,
        durationMs: Long,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeLabel: String? = null
    )
    suspend fun getSavedPositionMs(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Long

    fun observeViewingHistory(limit: Int = 50): Flow<List<WatchHistoryItem>> = kotlinx.coroutines.flow.emptyFlow()
    fun observeMovieWatchHistory(limit: Int = 50): Flow<List<WatchHistoryItem>> = observeViewingHistory(limit)
    fun observeEpisodeWatchHistory(limit: Int = 50): Flow<List<WatchHistoryItem>> = kotlinx.coroutines.flow.emptyFlow()
    fun observeTrailerHistory(limit: Int = 50): Flow<List<WatchHistoryItem>> = kotlinx.coroutines.flow.emptyFlow()
    fun observeHiddenHistory(limit: Int = 50): Flow<List<WatchHistoryItem>> = kotlinx.coroutines.flow.emptyFlow()

    fun observeSeriesProgress(
        providerId: String,
        titleId: String,
        title: String = ""
    ): Flow<SeriesProgressSummary> = kotlinx.coroutines.flow.emptyFlow()

    suspend fun computeNextUnwatchedEpisode(
        providerId: String,
        titleId: String,
        seasons: List<SeasonSummary>,
        episodesBySeason: Map<Int, List<EpisodeItem>> = emptyMap()
    ): Pair<Int, Int>? = null

    suspend fun markWatched(
        item: MovieTitleSummary,
        durationMs: Long = 0L,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeLabel: String? = null,
        isRewatch: Boolean = false
    ) = Unit

    suspend fun markSeriesWatched(
        item: MovieTitleSummary,
        seasons: List<SeasonSummary>,
        episodesBySeason: Map<Int, List<EpisodeItem>> = emptyMap(),
        isRewatch: Boolean = false
    ): Int {
        var count = 0
        val sortedSeasons = seasons.sortedBy { it.seasonNumber }
        if (sortedSeasons.isNotEmpty()) {
            for (season in sortedSeasons) {
                val loaded = episodesBySeason[season.seasonNumber]
                if (!loaded.isNullOrEmpty()) {
                    for (ep in loaded.sortedBy { it.episodeNumber }) {
                        markWatched(
                            item = item,
                            durationMs = (ep.runtimeMinutes ?: 45) * 60_000L,
                            seasonNumber = season.seasonNumber,
                            episodeNumber = ep.episodeNumber,
                            episodeLabel = "S${season.seasonNumber} · E${ep.episodeNumber}",
                            isRewatch = isRewatch
                        )
                        count++
                    }
                } else {
                    val epNumbers = when {
                        season.availableEpisodes.isNotEmpty() -> season.availableEpisodes.filter { it > 0 }.distinct().sorted()
                        season.episodeCount > 0 -> (1..season.episodeCount).toList()
                        else -> emptyList()
                    }
                    for (epNum in epNumbers) {
                        markWatched(
                            item = item,
                            durationMs = 45 * 60_000L,
                            seasonNumber = season.seasonNumber,
                            episodeNumber = epNum,
                            episodeLabel = "S${season.seasonNumber} · E$epNum",
                            isRewatch = isRewatch
                        )
                        count++
                    }
                }
            }
        } else {
            for ((seasonNum, eps) in episodesBySeason.toSortedMap()) {
                for (ep in eps.sortedBy { it.episodeNumber }) {
                    markWatched(
                        item = item,
                        durationMs = (ep.runtimeMinutes ?: 45) * 60_000L,
                        seasonNumber = seasonNum,
                        episodeNumber = ep.episodeNumber,
                        episodeLabel = "S$seasonNum · E${ep.episodeNumber}",
                        isRewatch = isRewatch
                    )
                    count++
                }
            }
        }
        return count
    }

    suspend fun markUnwatched(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) = Unit

    suspend fun restartFromBeginning(
        providerId: String,
        titleId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) = Unit

    suspend fun removeFromContinueWatching(providerId: String, titleId: String) = Unit
    suspend fun setTitleHidden(providerId: String, titleId: String, hidden: Boolean) = Unit
    suspend fun setTitleHidden(item: MovieTitleSummary, hidden: Boolean) =
        setTitleHidden(item.providerId, item.id, hidden)

    suspend fun recordTrailerWatch(
        item: MovieTitleSummary,
        positionMs: Long = 0L,
        durationMs: Long = 0L
    ) = Unit
    suspend fun deleteViewingHistoryEntry(historyKey: String) = Unit
    suspend fun clearViewingHistory(trailersOnly: Boolean? = null) = Unit

    // Recently viewed
    fun observeRecentlyViewed(limit: Int = 20): Flow<List<MovieTitleSummary>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun recordRecentlyViewed(item: MovieTitleSummary) = Unit
    suspend fun removeRecentlyViewed(providerId: String, titleId: String) = Unit
    suspend fun clearRecentlyViewed() = Unit

    // Custom collections for Movies & TV
    fun observeCollectionIdsForMedia(providerId: String, titleId: String): Flow<List<Long>> = kotlinx.coroutines.flow.emptyFlow()
    fun observeMediaInCollection(collectionId: Long): Flow<List<CollectionMediaItem>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun addMediaToCollection(item: MovieTitleSummary, collectionId: Long) = Unit
    suspend fun removeMediaFromCollection(item: MovieTitleSummary, collectionId: Long) = Unit

    // Per-series audio/subtitle/reminder preferences
    suspend fun getSeriesPreferences(providerId: String, titleId: String): SeriesPreferences? = null
    fun observeSeriesPreferences(providerId: String, titleId: String): Flow<SeriesPreferences?> = kotlinx.coroutines.flow.flowOf(null)
    suspend fun saveSeriesAudioPreference(providerId: String, titleId: String, language: String?, label: String?) = Unit
    suspend fun saveSeriesSubtitlePreference(
        providerId: String,
        titleId: String,
        language: String?,
        label: String?,
        disabled: Boolean
    ) = Unit
    suspend fun setSeriesRemindersMuted(providerId: String, titleId: String, muted: Boolean) = Unit

    // Offline availability lookup
    fun observeOfflineAvailability(): Flow<List<OfflineMediaMatch>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun reconcileOfflineAssetsExist() = Unit
    fun observeTitleCompleted(providerId: String, titleId: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    suspend fun findOfflineEpisodeAsset(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): OfflineMediaMatch? = null

    // Viewing reminders
    fun observeActiveViewingReminders(): Flow<List<ViewingReminderItem>> = kotlinx.coroutines.flow.emptyFlow()
    fun observeViewingReminderForTitle(providerId: String, titleId: String): Flow<ViewingReminderItem?> =
        kotlinx.coroutines.flow.flowOf(null)
    suspend fun scheduleViewingReminder(
        item: MovieTitleSummary,
        triggerAtMillis: Long,
        note: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) = Unit
    suspend fun dismissViewingReminder(providerId: String, titleId: String) = Unit

    fun observeRecentSearches(limit: Int = 10): Flow<List<String>>
    suspend fun recordSearchQuery(query: String)
    suspend fun clearSearchHistory()

    suspend fun getProviderLinks(providerId: String, titleId: String): List<ProviderLinkRecord> = emptyList()
    suspend fun unlinkProviderTitle(providerId: String, titleId: String): String? = null

    // Title-level feedback (Like / Dislike) & EI behavioral hooks
    fun observeAllTitleFeedback(): Flow<List<com.laterbox.app.data.local.entity.TitleFeedbackEntity>> =
        kotlinx.coroutines.flow.emptyFlow()

    fun observeTitleReaction(
        providerId: String,
        titleId: String,
        canonicalId: String? = null
    ): Flow<com.laterbox.app.data.local.entity.TitleReaction?> = kotlinx.coroutines.flow.flowOf(null)

    suspend fun setTitleReaction(
        item: MovieTitleSummary,
        reaction: com.laterbox.app.data.local.entity.TitleReaction?,
        recordSignal: Boolean = true
    ) = Unit

    suspend fun logPlaybackStarted(item: MovieTitleSummary) = Unit
    suspend fun logEarlyPlaybackExit(item: MovieTitleSummary) = Unit

    // Signal-policy overloads so Assistant actions can avoid polluting EI signals
    suspend fun toggleWatchlistWithSignalPolicy(item: MovieTitleSummary, recordSignal: Boolean): Boolean =
        toggleWatchlist(item)

    suspend fun markWatchedWithSignalPolicy(
        item: MovieTitleSummary,
        durationMs: Long = 0L,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeLabel: String? = null,
        isRewatch: Boolean = false,
        recordSignal: Boolean
    ) = markWatched(
        item = item,
        durationMs = durationMs,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeLabel = episodeLabel,
        isRewatch = isRewatch
    )

    suspend fun scheduleViewingReminderWithSignalPolicy(
        item: MovieTitleSummary,
        triggerAtMillis: Long,
        note: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        recordSignal: Boolean
    ) = scheduleViewingReminder(item, triggerAtMillis, note, seasonNumber, episodeNumber)

    suspend fun addMediaToCollectionWithSignalPolicy(
        item: MovieTitleSummary,
        collectionId: Long,
        recordSignal: Boolean
    ) = addMediaToCollection(item, collectionId)

    suspend fun setSeriesRemindersMutedWithSignalPolicy(
        providerId: String,
        titleId: String,
        muted: Boolean,
        recordSignal: Boolean
    ) = setSeriesRemindersMuted(providerId, titleId, muted)
}
