package com.laterbox.app.media.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.laterbox.app.media.data.db.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_task WHERE id = :id")
    fun getTaskById(id: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_task WHERE id = :id LIMIT 1")
    suspend fun getEntityById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_task WHERE savedLinkId = :savedLinkId ORDER BY createdAt DESC")
    fun getTasksForLink(savedLinkId: Long): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task WHERE status IN ('QUEUED', 'DOWNLOADING', 'PROCESSING', 'WAITING_FOR_WIFI', 'PAUSED', 'FAILED') ORDER BY createdAt DESC")
    fun getQueueTasks(): Flow<List<DownloadTaskEntity>>

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun dismissTaskById(id: String)

    @Query("DELETE FROM download_task WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearTerminalTasks()

    @Query("UPDATE download_task SET status = :status, nextRetryAtMs = NULL, speedBps = NULL, etaSeconds = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_task SET status = :status, updatedAt = :updatedAt WHERE id = :id AND status != 'CANCELLED'")
    suspend fun updateStatusIfNotCancelled(id: String, status: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE download_task SET stage = :stage, stageDetail = :stageDetail, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStage(id: String, stage: String?, stageDetail: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_task SET status = :status, stage = :stage, stageDetail = :stageDetail, nextRetryAtMs = NULL, updatedAt = :updatedAt WHERE id = :id AND status != 'CANCELLED'")
    suspend fun updateStatusAndStage(id: String, status: String, stage: String?, stageDetail: String?, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE download_task 
        SET progress = :progress, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun updateProgress(
        id: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE download_task 
        SET speedBps = :speedBps, etaSeconds = :etaSeconds, updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun updateTelemetry(
        id: String,
        speedBps: Long?,
        etaSeconds: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE download_task SET etag = :etag, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateEtag(id: String, etag: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE download_task 
        SET sourceUrl = :sourceUrl, headersJson = :headersJson, urlExpiresAt = :urlExpiresAt, updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun updateResolvedSource(
        id: String,
        sourceUrl: String,
        headersJson: String?,
        urlExpiresAt: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE download_task
        SET sourceUrl = :sourceUrl,
            headersJson = :headersJson,
            urlExpiresAt = :urlExpiresAt,
            etag = NULL,
            downloadedBytes = 0,
            progress = 0,
            totalBytes = :totalBytes,
            qualityLabel = COALESCE(:qualityLabel, qualityLabel),
            versionTag = COALESCE(:versionTag, versionTag),
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun resetAndUpdateResolvedSource(
        id: String,
        sourceUrl: String,
        headersJson: String?,
        urlExpiresAt: Long?,
        totalBytes: Long?,
        qualityLabel: String?,
        versionTag: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE download_task 
        SET status = :status, errorCode = :errorCode, errorMessage = :errorMessage, retryCount = :retryCount, nextRetryAtMs = NULL, speedBps = NULL, etaSeconds = NULL, updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun updateError(
        id: String,
        status: String,
        errorCode: String?,
        errorMessage: String?,
        retryCount: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE download_task 
        SET status = :status, errorCode = :errorCode, errorMessage = :errorMessage, retryCount = :retryCount, nextRetryAtMs = :nextRetryAtMs, speedBps = NULL, etaSeconds = NULL, updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun updateScheduledRetry(
        id: String,
        status: String,
        errorCode: String?,
        errorMessage: String?,
        retryCount: Int,
        nextRetryAtMs: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE download_task SET nextRetryAtMs = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun clearNextRetryAt(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_task SET allowCellularOverride = :allow, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCellularOverride(id: String, allow: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE download_task SET status = 'COMPLETED', assetId = :assetId, progress = 100, nextRetryAtMs = NULL, speedBps = NULL, etaSeconds = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markCompleted(id: String, assetId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun deleteTaskById(id: String)

    @Query("DELETE FROM download_task WHERE savedLinkId = :savedLinkId")
    suspend fun deleteTasksForLink(savedLinkId: Long)

    @Query("""
        UPDATE download_task 
        SET status = 'QUEUED', updatedAt = :updatedAt 
        WHERE status IN ('INSPECTING', 'READY', 'DOWNLOADING', 'PROCESSING')
    """)
    suspend fun resetInterruptedTasksToQueued(updatedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT id FROM download_task WHERE status IN ('QUEUED', 'WAITING_FOR_WIFI', 'INSPECTING', 'READY', 'DOWNLOADING', 'PAUSED', 'PROCESSING')")
    suspend fun getActiveAndPausedTaskIds(): List<String>

    @Query("SELECT id FROM download_task WHERE status IN ('DOWNLOADING', 'PROCESSING')")
    suspend fun getInProgressTaskIds(): List<String>

    @Query("SELECT * FROM download_task WHERE status = 'QUEUED'")
    suspend fun getQueuedTasks(): List<DownloadTaskEntity>

    @Query("SELECT COUNT(*) FROM download_task WHERE status = 'FAILED'")
    suspend fun countFailedTasks(): Int

    @Query("SELECT * FROM download_task ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentTasksSnapshot(limit: Int = 20): List<com.laterbox.app.media.data.db.entity.DownloadTaskEntity>
    @Query("SELECT * FROM download_task WHERE sourceRef LIKE :prefix || '%' ORDER BY updatedAt DESC")
    fun observeTasksBySourceRefPrefix(prefix: String): Flow<List<DownloadTaskEntity>>

    @Query("""
        SELECT * FROM download_task
        WHERE sourceRef = :sourceRef
          AND status IN ('QUEUED', 'WAITING_FOR_WIFI', 'INSPECTING', 'READY', 'DOWNLOADING', 'PROCESSING', 'PAUSED')
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    suspend fun findActiveOrPausedBySourceRef(sourceRef: String): DownloadTaskEntity?

    @Query("""
        SELECT * FROM download_task
        WHERE sourceRef LIKE :prefix || '%'
          AND status IN ('QUEUED', 'WAITING_FOR_WIFI', 'INSPECTING', 'READY', 'DOWNLOADING', 'PROCESSING', 'PAUSED')
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    suspend fun findActiveOrPausedBySourceRefPrefix(prefix: String): DownloadTaskEntity?
}
