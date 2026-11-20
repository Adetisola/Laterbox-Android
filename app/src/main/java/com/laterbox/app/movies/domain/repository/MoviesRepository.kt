package com.laterbox.app.movies.domain.repository

import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.CollectionMediaItem
import com.laterbox.app.movies.domain.model.EpisodeItem
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

interface MoviesRepository {
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

    suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean = false): Result<MediaDetails>

    fun getCachedSummary(providerId: String, titleId: String): MovieTitleSummary? = null

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
}
