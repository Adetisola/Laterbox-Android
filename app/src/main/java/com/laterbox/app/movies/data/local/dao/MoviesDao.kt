package com.laterbox.app.movies.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.laterbox.app.movies.data.local.entity.CanonicalMediaEntity
import com.laterbox.app.movies.data.local.entity.MediaProviderLinkEntity
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.movies.data.local.entity.MovieSearchHistoryEntity
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

    @Query("SELECT * FROM movie_watchlist_items ORDER BY added_at DESC")
    fun observeWatchlist(): Flow<List<MovieWatchlistEntity>>

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

    @Query("SELECT * FROM movie_watch_history ORDER BY last_watched_at DESC LIMIT :limit")
    fun observeContinueWatching(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query("SELECT * FROM movie_watch_history WHERE history_key = :historyKey LIMIT 1")
    suspend fun getWatchHistoryByKey(historyKey: String): MovieWatchHistoryEntity?

    @Query(
        """
        SELECT * FROM movie_watch_history
        WHERE canonical_id = :canonicalId
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

    @androidx.room.Upsert
    suspend fun upsertCanonicalMedia(entity: CanonicalMediaEntity)

    @androidx.room.Upsert
    suspend fun upsertProviderLink(link: MediaProviderLinkEntity)

    @Query("DELETE FROM media_provider_links WHERE provider_id = :providerId AND provider_title_id = :providerTitleId")
    suspend fun deleteProviderLink(providerId: String, providerTitleId: String)

    @Query("SELECT COUNT(*) FROM media_provider_links WHERE canonical_id = :canonicalId")
    suspend fun countProviderLinksForCanonical(canonicalId: String): Int

    @Query("DELETE FROM canonical_media WHERE canonical_id = :canonicalId")
    suspend fun deleteCanonicalMedia(canonicalId: String)
}
