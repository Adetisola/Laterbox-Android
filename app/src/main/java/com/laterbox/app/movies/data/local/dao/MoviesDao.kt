package com.laterbox.app.movies.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.laterbox.app.movies.data.local.entity.CanonicalMediaEntity
import com.laterbox.app.movies.data.local.entity.HiddenTitleEntity
import com.laterbox.app.movies.data.local.entity.MediaCollectionCrossRef
import com.laterbox.app.movies.data.local.entity.MediaProviderLinkEntity
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.movies.data.local.entity.MovieRecentlyViewedEntity
import com.laterbox.app.movies.data.local.entity.MovieSearchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieSeriesPreferencesEntity
import com.laterbox.app.movies.data.local.entity.MovieViewingReminderEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MoviesDao {

    @Query("SELECT * FROM movie_cache_entries WHERE cache_key = :cacheKey LIMIT 1")
    suspend fun getCacheEntry(cacheKey: String): MovieCacheEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCacheEntry(entry: MovieCacheEntryEntity)

    @Query("DELETE FROM movie_cache_entries WHERE expires_at < :nowMs")
    suspend fun pruneExpiredCache(nowMs: Long)

    @Query("DELETE FROM movie_cache_entries")
    suspend fun clearAllCacheEntries()

    @Query("SELECT COUNT(*) FROM movie_cache_entries")
    suspend fun countCacheEntries(): Int

    @Query("SELECT * FROM movie_watchlist_items ORDER BY added_at DESC")
    fun observeWatchlist(): Flow<List<MovieWatchlistEntity>>

    @Query("SELECT * FROM movie_watchlist_items ORDER BY added_at DESC")
    suspend fun getWatchlistSnapshot(): List<MovieWatchlistEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM movie_watchlist_items w
            WHERE w.composite_id = :compositeId
               OR (:compositeId LIKE 'canonical:%' AND w.canonical_id = SUBSTR(:compositeId, 11))
               OR w.canonical_id = :compositeId
               OR w.canonical_id IN (
                   SELECT l.canonical_id FROM media_provider_links l
                   WHERE (l.provider_id || ':' || l.provider_title_id) = :compositeId
               )
        )
        """
    )
    fun observeIsInWatchlist(compositeId: String): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM movie_watchlist_items
            WHERE composite_id = :compositeId
               OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId)
        )
        """
    )
    fun observeIsInWatchlistByCompositeOrCanonical(compositeId: String, canonicalId: String?): Flow<Boolean>

    @Query("SELECT * FROM movie_watchlist_items WHERE composite_id = :compositeId LIMIT 1")
    suspend fun getWatchlistItem(compositeId: String): MovieWatchlistEntity?

    @Query("SELECT * FROM movie_watchlist_items WHERE canonical_id = :canonicalId LIMIT 1")
    suspend fun getWatchlistItemByCanonical(canonicalId: String): MovieWatchlistEntity?

    @Query("SELECT * FROM movie_watchlist_items WHERE canonical_id IS NULL")
    suspend fun getWatchlistItemsWithoutCanonicalId(): List<MovieWatchlistEntity>

    @Query("UPDATE movie_watchlist_items SET canonical_id = :canonicalId WHERE composite_id = :compositeId")
    suspend fun updateWatchlistCanonicalId(compositeId: String, canonicalId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchlistItem(item: MovieWatchlistEntity)

    @Query("DELETE FROM movie_watchlist_items WHERE composite_id = :compositeId")
    suspend fun deleteWatchlistItem(compositeId: String)

    @Query("DELETE FROM movie_watchlist_items WHERE canonical_id = :canonicalId")
    suspend fun deleteWatchlistItemsByCanonical(canonicalId: String)

    @Query(
        """
        SELECT * FROM movie_watch_history w
        WHERE w.is_trailer = 0
          AND w.hidden_from_continue_watching = 0
          AND w.is_hidden_title = 0
          AND (w.is_completed = 0 OR (w.position_ms > 10000 AND w.duration_ms > 0 AND (w.position_ms * 1.0 / w.duration_ms) < 0.96))
          AND NOT EXISTS (
              SELECT 1 FROM hidden_titles h
              WHERE h.composite_id = w.composite_id
                 OR (h.canonical_id IS NOT NULL AND h.canonical_id = w.canonical_id)
          )
        ORDER BY w.last_watched_at DESC
        LIMIT :limit
        """
    )
    fun observeContinueWatching(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM movie_watch_history w
        WHERE w.is_hidden_title = 0
          AND NOT EXISTS (
              SELECT 1 FROM hidden_titles h
              WHERE h.composite_id = w.composite_id
                 OR (h.canonical_id IS NOT NULL AND h.canonical_id = w.canonical_id)
          )
        ORDER BY w.last_watched_at DESC
        LIMIT :limit
        """
    )
    fun observeViewingHistory(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM movie_watch_history w
        WHERE w.is_trailer = 0 AND w.kind = 'movie' AND w.is_hidden_title = 0
          AND NOT EXISTS (
              SELECT 1 FROM hidden_titles h
              WHERE h.composite_id = w.composite_id
                 OR (h.canonical_id IS NOT NULL AND h.canonical_id = w.canonical_id)
          )
        ORDER BY w.last_watched_at DESC
        LIMIT :limit
        """
    )
    fun observeMovieWatchHistory(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM movie_watch_history w
        WHERE w.is_trailer = 0 AND w.kind IN ('tv_series', 'short_drama') AND w.is_hidden_title = 0
          AND NOT EXISTS (
              SELECT 1 FROM hidden_titles h
              WHERE h.composite_id = w.composite_id
                 OR (h.canonical_id IS NOT NULL AND h.canonical_id = w.canonical_id)
          )
        ORDER BY w.last_watched_at DESC
        LIMIT :limit
        """
    )
    fun observeEpisodeWatchHistory(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM movie_watch_history w
        WHERE w.is_trailer = 1 AND w.is_hidden_title = 0
          AND NOT EXISTS (
              SELECT 1 FROM hidden_titles h
              WHERE h.composite_id = w.composite_id
                 OR (h.canonical_id IS NOT NULL AND h.canonical_id = w.canonical_id)
          )
        ORDER BY w.last_watched_at DESC
        LIMIT :limit
        """
    )
    fun observeTrailerHistory(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query("SELECT * FROM hidden_titles ORDER BY hidden_at DESC LIMIT :limit")
    fun observeHiddenTitles(limit: Int): Flow<List<HiddenTitleEntity>>

    @Query("SELECT * FROM hidden_titles ORDER BY hidden_at DESC LIMIT :limit")
    suspend fun getHiddenTitlesSnapshot(limit: Int = 200): List<HiddenTitleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHiddenTitle(item: HiddenTitleEntity)

    @Query(
        """
        DELETE FROM hidden_titles
        WHERE media_key = :mediaKey
           OR composite_id = :compositeId
           OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId)
           OR (provider_id = :providerId AND title_id = :titleId)
        """
    )
    suspend fun deleteHiddenTitle(
        mediaKey: String,
        compositeId: String,
        canonicalId: String?,
        providerId: String,
        titleId: String
    )

    @Query("SELECT * FROM movie_watch_history WHERE composite_id = :compositeId OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId) ORDER BY last_watched_at DESC LIMIT 1")
    suspend fun getAnyHistoryForTitle(compositeId: String, canonicalId: String?): MovieWatchHistoryEntity?

    @Query("SELECT * FROM movie_recently_viewed WHERE composite_id = :compositeId OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId) ORDER BY viewed_at DESC LIMIT 1")
    suspend fun getRecentlyViewedForTitle(compositeId: String, canonicalId: String?): MovieRecentlyViewedEntity?

    @Query("SELECT * FROM movie_watch_history WHERE is_hidden_title = 1 ORDER BY last_watched_at DESC LIMIT :limit")
    fun observeHiddenHistory(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query("SELECT * FROM movie_watch_history ORDER BY last_watched_at DESC LIMIT :limit")
    suspend fun getWatchHistorySnapshot(limit: Int = 50): List<MovieWatchHistoryEntity>

    @Query("SELECT * FROM movie_watch_history WHERE is_trailer = 0 AND (composite_id = :compositeId OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId)) ORDER BY season_number ASC, episode_number ASC")
    fun observeHistoryForTitle(compositeId: String, canonicalId: String?): Flow<List<MovieWatchHistoryEntity>>

    @Query("SELECT * FROM movie_watch_history WHERE is_trailer = 0 AND (composite_id = :compositeId OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId))")
    suspend fun getHistoryListForTitle(compositeId: String, canonicalId: String?): List<MovieWatchHistoryEntity>

    @Query("SELECT * FROM movie_watch_history WHERE history_key = :historyKey AND is_trailer = 0 LIMIT 1")
    suspend fun getWatchHistoryByKey(historyKey: String): MovieWatchHistoryEntity?

    @Query(
        """
        SELECT * FROM movie_watch_history
        WHERE canonical_id = :canonicalId
          AND is_trailer = 0
          AND IFNULL(season_number, 0) = :seasonNumber
          AND IFNULL(episode_number, 0) = :episodeNumber
        ORDER BY last_watched_at DESC
        LIMIT 1
        """
    )
    suspend fun getWatchHistoryByCanonical(
        canonicalId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): MovieWatchHistoryEntity?

    @Query("SELECT * FROM movie_watch_history WHERE canonical_id IS NULL")
    suspend fun getWatchHistoryWithoutCanonicalId(): List<MovieWatchHistoryEntity>

    @Query("UPDATE movie_watch_history SET canonical_id = :canonicalId WHERE history_key = :historyKey")
    suspend fun updateWatchHistoryCanonicalId(historyKey: String, canonicalId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchHistory(item: MovieWatchHistoryEntity)

    @Query("UPDATE movie_watch_history SET hidden_from_continue_watching = :hidden WHERE composite_id = :compositeId OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId)")
    suspend fun setHiddenFromContinueWatching(compositeId: String, canonicalId: String?, hidden: Boolean)

    @Query("UPDATE movie_watch_history SET is_hidden_title = :hidden WHERE composite_id = :compositeId OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId)")
    suspend fun setTitleHidden(compositeId: String, canonicalId: String?, hidden: Boolean)

    @Query("UPDATE movie_watch_history SET position_ms = 0, hidden_from_continue_watching = 0, last_watched_at = :nowMs WHERE history_key = :historyKey")
    suspend fun resetSavedPositionPreservingCompletion(historyKey: String, nowMs: Long)

    @Query("DELETE FROM movie_watch_history WHERE history_key = :historyKey")
    suspend fun deleteWatchHistoryEntry(historyKey: String)

    @Query("DELETE FROM movie_watch_history WHERE is_trailer = 0 AND (composite_id = :compositeId OR (:canonicalId IS NOT NULL AND canonical_id = :canonicalId))")
    suspend fun deleteAllWatchHistoryForTitle(compositeId: String, canonicalId: String?)

    @Query("DELETE FROM movie_watch_history")
    suspend fun clearWatchHistory()

    @Query("DELETE FROM movie_watch_history WHERE is_trailer = :isTrailer")
    suspend fun clearWatchHistoryByTrailerFlag(isTrailer: Boolean)

    // Recently viewed
    @Query("SELECT * FROM movie_recently_viewed ORDER BY viewed_at DESC LIMIT :limit")
    fun observeRecentlyViewed(limit: Int): Flow<List<MovieRecentlyViewedEntity>>

    @Query("SELECT * FROM movie_recently_viewed ORDER BY viewed_at DESC LIMIT :limit")
    suspend fun getRecentlyViewedSnapshot(limit: Int = 20): List<MovieRecentlyViewedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecentlyViewed(item: MovieRecentlyViewedEntity)

    @Query(
        """
        DELETE FROM movie_recently_viewed
        WHERE media_key = :mediaKey
           OR composite_id = :compositeId
           OR canonical_id = :canonicalId
           OR (provider_id = :providerId AND title_id = :titleId)
        """
    )
    suspend fun deleteRecentlyViewed(
        mediaKey: String,
        compositeId: String,
        canonicalId: String,
        providerId: String,
        titleId: String
    ): Int

    @Query("DELETE FROM movie_recently_viewed")
    suspend fun clearRecentlyViewed()

    // Per-series preferences & reminder mute
    @Query("SELECT * FROM movie_series_preferences WHERE media_key = :mediaKey LIMIT 1")
    suspend fun getSeriesPreferences(mediaKey: String): MovieSeriesPreferencesEntity?

    @Query("SELECT * FROM movie_series_preferences WHERE media_key = :mediaKey LIMIT 1")
    fun observeSeriesPreferences(mediaKey: String): Flow<MovieSeriesPreferencesEntity?>

    @Query("SELECT * FROM movie_series_preferences")
    suspend fun getAllSeriesPreferencesSnapshot(): List<MovieSeriesPreferencesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeriesPreferences(prefs: MovieSeriesPreferencesEntity)

    // Media in Laterbox Collections
    @Query("SELECT collection_id FROM media_collection_cross_ref WHERE media_key = :mediaKey OR composite_id = :compositeId")
    fun observeCollectionIdsForMedia(mediaKey: String, compositeId: String): Flow<List<Long>>

    @Query("SELECT * FROM media_collection_cross_ref WHERE collection_id = :collectionId ORDER BY added_at DESC")
    fun observeMediaInCollection(collectionId: Long): Flow<List<MediaCollectionCrossRef>>

    @Query("SELECT * FROM media_collection_cross_ref ORDER BY added_at DESC LIMIT :limit")
    suspend fun getCollectionMediaSnapshot(limit: Int = 50): List<MediaCollectionCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaCollectionCrossRef(ref: MediaCollectionCrossRef)

    @Query("DELETE FROM media_collection_cross_ref WHERE (media_key = :mediaKey OR composite_id = :compositeId) AND collection_id = :collectionId")
    suspend fun removeMediaFromCollection(mediaKey: String, compositeId: String, collectionId: Long)

    // Viewing Reminders
    @Query("SELECT * FROM movie_viewing_reminders WHERE is_dismissed = 0 ORDER BY trigger_at_millis ASC")
    fun observeActiveViewingReminders(): Flow<List<MovieViewingReminderEntity>>

    @Query("SELECT * FROM movie_viewing_reminders WHERE (media_key = :mediaKey OR composite_id = :compositeId) AND is_dismissed = 0 LIMIT 1")
    fun observeViewingReminderForTitle(mediaKey: String, compositeId: String): Flow<MovieViewingReminderEntity?>

    @Query("SELECT * FROM movie_viewing_reminders WHERE media_key = :mediaKey LIMIT 1")
    suspend fun getViewingReminderByMediaKey(mediaKey: String): MovieViewingReminderEntity?

    @Query("SELECT * FROM movie_viewing_reminders WHERE is_dismissed = 0 AND trigger_at_millis <= :nowMs ORDER BY trigger_at_millis ASC")
    suspend fun getDueViewingReminders(nowMs: Long): List<MovieViewingReminderEntity>

    @Query("SELECT * FROM movie_viewing_reminders WHERE is_dismissed = 0 ORDER BY trigger_at_millis ASC")
    suspend fun getActiveViewingRemindersSnapshot(): List<MovieViewingReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertViewingReminder(reminder: MovieViewingReminderEntity)

    @Query("UPDATE movie_viewing_reminders SET is_dismissed = 1, updated_at = :nowMs WHERE media_key = :mediaKey OR composite_id = :compositeId")
    suspend fun dismissViewingReminder(mediaKey: String, compositeId: String, nowMs: Long)

    @Query("SELECT query FROM movie_search_history ORDER BY searched_at DESC LIMIT :limit")
    fun observeRecentSearches(limit: Int): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchQuery(entry: MovieSearchHistoryEntity)

    @Query("DELETE FROM movie_search_history")
    suspend fun clearSearchHistory()

    // Canonical identity & provider links (M4)
    @Query("SELECT * FROM media_provider_links WHERE provider_id = :providerId AND provider_title_id = :providerTitleId LIMIT 1")
    suspend fun getProviderLink(providerId: String, providerTitleId: String): MediaProviderLinkEntity?

    @Query("SELECT * FROM media_provider_links WHERE canonical_id = :canonicalId ORDER BY updated_at DESC")
    suspend fun getProviderLinksForCanonical(canonicalId: String): List<MediaProviderLinkEntity>

    @Query("SELECT * FROM canonical_media WHERE canonical_id = :canonicalId LIMIT 1")
    suspend fun getCanonicalById(canonicalId: String): CanonicalMediaEntity?

    @Query("SELECT * FROM canonical_media WHERE imdb_id = :imdbId LIMIT 1")
    suspend fun findCanonicalByImdbId(imdbId: String): CanonicalMediaEntity?

    @Query("SELECT * FROM canonical_media WHERE tvmaze_id = :tvmazeId LIMIT 1")
    suspend fun findCanonicalByTvMazeId(tvmazeId: String): CanonicalMediaEntity?

    @Query("SELECT * FROM canonical_media WHERE kind = :kind AND norm_title = :normTitle")
    suspend fun findCanonicalByKindAndNormTitle(kind: String, normTitle: String): List<CanonicalMediaEntity>

    @Upsert
    suspend fun upsertCanonicalMedia(entity: CanonicalMediaEntity)

    @Upsert
    suspend fun upsertCanonicalMediaBatch(entities: List<CanonicalMediaEntity>)

    @Upsert
    suspend fun upsertProviderLink(link: MediaProviderLinkEntity)

    @Upsert
    suspend fun upsertProviderLinksBatch(links: List<MediaProviderLinkEntity>)

    @Query("SELECT * FROM media_provider_links")
    suspend fun getAllProviderLinks(): List<MediaProviderLinkEntity>

    @Query("SELECT * FROM canonical_media")
    suspend fun getAllCanonicalMedia(): List<CanonicalMediaEntity>

    @Transaction
    suspend fun upsertCanonicalAndLinksBatch(
        canonicals: List<CanonicalMediaEntity>,
        links: List<MediaProviderLinkEntity>,
        watchlistUpdates: List<Pair<String, String>> = emptyList(),
        historyUpdates: List<Pair<String, String>> = emptyList()
    ) {
        if (canonicals.isNotEmpty()) upsertCanonicalMediaBatch(canonicals)
        if (links.isNotEmpty()) upsertProviderLinksBatch(links)
        for ((compositeId, cid) in watchlistUpdates) {
            updateWatchlistCanonicalId(compositeId, cid)
        }
        for ((historyKey, cid) in historyUpdates) {
            updateWatchHistoryCanonicalId(historyKey, cid)
        }
    }

    @Query("DELETE FROM media_provider_links WHERE provider_id = :providerId AND provider_title_id = :providerTitleId")
    suspend fun deleteProviderLink(providerId: String, providerTitleId: String)

    @Query("SELECT COUNT(*) FROM media_provider_links WHERE canonical_id = :canonicalId")
    suspend fun countProviderLinksForCanonical(canonicalId: String): Int

    @Query("DELETE FROM canonical_media WHERE canonical_id = :canonicalId")
    suspend fun deleteCanonicalMedia(canonicalId: String)
}
