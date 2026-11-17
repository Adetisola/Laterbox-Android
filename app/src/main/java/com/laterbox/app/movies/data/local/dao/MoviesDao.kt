package com.laterbox.app.movies.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT EXISTS(SELECT 1 FROM movie_watchlist_items WHERE composite_id = :compositeId)")
    fun observeIsInWatchlist(compositeId: String): Flow<Boolean>

    @Query("SELECT * FROM movie_watchlist_items WHERE composite_id = :compositeId LIMIT 1")
    suspend fun getWatchlistItem(compositeId: String): MovieWatchlistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchlistItem(item: MovieWatchlistEntity)

    @Query("DELETE FROM movie_watchlist_items WHERE composite_id = :compositeId")
    suspend fun deleteWatchlistItem(compositeId: String)

    @Query("SELECT * FROM movie_watch_history ORDER BY last_watched_at DESC LIMIT :limit")
    fun observeContinueWatching(limit: Int): Flow<List<MovieWatchHistoryEntity>>

    @Query("SELECT * FROM movie_watch_history WHERE history_key = :historyKey LIMIT 1")
    suspend fun getWatchHistoryByKey(historyKey: String): MovieWatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchHistory(item: MovieWatchHistoryEntity)

    @Query("SELECT query FROM movie_search_history ORDER BY searched_at DESC LIMIT :limit")
    fun observeRecentSearches(limit: Int): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchQuery(entry: MovieSearchHistoryEntity)

    @Query("DELETE FROM movie_search_history")
    suspend fun clearSearchHistory()
}
