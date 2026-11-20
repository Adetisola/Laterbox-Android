package com.laterbox.app.media.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadPlaybackProgressDao {

    @Query("SELECT * FROM download_playback_progress ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<DownloadPlaybackProgressEntity>>

    @Query("SELECT * FROM download_playback_progress WHERE progress_key = :progressKey LIMIT 1")
    suspend fun getByKey(progressKey: String): DownloadPlaybackProgressEntity?

    @Query("SELECT * FROM download_playback_progress WHERE asset_id = :assetId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestForAsset(assetId: String): DownloadPlaybackProgressEntity?

    @Upsert
    suspend fun upsert(entry: DownloadPlaybackProgressEntity)

    @Query("DELETE FROM download_playback_progress WHERE progress_key = :progressKey")
    suspend fun deleteByKey(progressKey: String)

    @Query("DELETE FROM download_playback_progress WHERE asset_id = :assetId AND progress_key LIKE 'asset:%'")
    suspend fun deleteGenericAssetProgress(assetId: String)
}
