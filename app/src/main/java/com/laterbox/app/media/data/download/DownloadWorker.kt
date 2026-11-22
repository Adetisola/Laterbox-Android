package com.laterbox.app.media.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.R
import com.laterbox.app.data.preferences.MediaDownloadNetworkPolicy
import com.laterbox.app.media.data.adapters.ytdlp.InfoJsonCache
import com.laterbox.app.media.data.db.entity.DownloadedAssetEntity
import com.laterbox.app.media.data.storage.MediaStorageManager
import com.laterbox.app.media.data.storage.ThumbnailGenerator
import com.laterbox.app.media.domain.model.AssetFileStatus
import com.laterbox.app.media.domain.model.DownloadStage
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.FailureCode
import com.laterbox.app.media.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

private data class ProgressEvent(
    val bytes: Long,
    val totalBytes: Long?,
    val percent: Int,
    val stage: DownloadStage?
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val app = appContext as? LaterboxApplication
        val container = app?.container
        val database = container?.database ?: com.laterbox.app.data.local.LaterboxDatabase.getInstance(appContext)
        val userPrefs = container?.userPreferencesRepository ?: com.laterbox.app.data.preferences.UserPreferencesRepository(appContext)
        val taskDao = database.downloadTaskDao()
        val assetDao = database.downloadedAssetDao()
        val storageManager = MediaStorageManager(appContext)

        val task = taskDao.getEntityById(taskId) ?: return Result.failure()
        if (task.status == DownloadState.COMPLETED.name || task.status == DownloadState.CANCELLED.name) {
            return Result.success()
        }

        val maintenancePaused = container?.remoteConfigManager?.currentConfig?.maintenance?.isOnlineServicesPaused == true
        if (maintenancePaused) {
            val msg = "Online downloads are paused during maintenance. Your saved offline downloads remain playable."
            taskDao.updateError(
                id = taskId,
                status = DownloadState.FAILED.name,
                errorCode = FailureCode.NETWORK_ERROR.name,
                errorMessage = msg,
                retryCount = task.retryCount
            )
            return Result.failure()
        }

        // 1. Network policy evaluation
        val prefs = userPrefs.userPreferencesFlow.first()
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isMetered = connectivityManager?.isActiveNetworkMetered ?: false

        if (isMetered && !task.allowCellularOverride) {
            val isBlocked = when (prefs.mediaDownloadNetworkPolicy) {
                MediaDownloadNetworkPolicy.WIFI_ONLY -> true
                MediaDownloadNetworkPolicy.WIFI_OVER_20MB -> {
                    task.totalBytes == null || task.totalBytes > 20L * 1024 * 1024
                }
                MediaDownloadNetworkPolicy.ANY_NETWORK -> false
            }

            if (isBlocked) {
                taskDao.updateStatus(taskId, DownloadState.WAITING_FOR_WIFI.name)
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
                val retryRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(constraints)
                    .setInputData(inputData)
                    .build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    uniqueWorkName(taskId),
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    retryRequest
                )
                return Result.success()
            }
        }

        // 2. Storage space verification
        if (!storageManager.hasSufficientSpace(task.totalBytes)) {
            taskDao.updateError(
                id = taskId,
                status = DownloadState.FAILED.name,
                errorCode = FailureCode.INSUFFICIENT_STORAGE.name,
                errorMessage = FailureCode.INSUFFICIENT_STORAGE.userMessage,
                retryCount = task.retryCount
            )
            notifyTerminalDownloadFailure(
                container = container,
                taskId = taskId,
                savedLinkId = task.savedLinkId,
                title = task.title,
                errorMessage = FailureCode.INSUFFICIENT_STORAGE.userMessage,
                retryCount = task.retryCount
            )
            return Result.failure()
        }

        // 3. Setup low-importance silent notification & attempt setForeground
        ensureNotificationChannel()
        val notificationId = taskId.hashCode()
        val initialNotification = buildNotification(task.title ?: "Downloading media", 0, null, taskId, DownloadStage.PREPARING.label)
        try {
            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(notificationId, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(notificationId, initialNotification)
            }
            setForeground(foregroundInfo)
        } catch (_: Exception) {
            // Graceful fallback: downloads continue even if notification permission is denied
        }

        // 4. Download transfer under concurrency gate
        val progressChannel = Channel<ProgressEvent>(Channel.CONFLATED)
        val progressJob = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
            var lastReportedStage: DownloadStage? = null
            var lastDbUpdateTime = 0L
            var lastDbPercent = -1

            for (event in progressChannel) {
                val now = System.currentTimeMillis()
                val stageChanged = event.stage != null && event.stage != lastReportedStage
                val percentAdvancement = event.percent - lastDbPercent >= 1 || event.percent == 100
                val timeElapsed = now - lastDbUpdateTime >= 500L

                if (stageChanged || (timeElapsed && percentAdvancement)) {
                    lastDbUpdateTime = now
                    lastDbPercent = event.percent
                    if (event.stage != null) lastReportedStage = event.stage

                    val current = taskDao.getEntityById(taskId)
                    if (current?.status == DownloadState.CANCELLED.name) break

                    taskDao.updateProgress(taskId, event.percent, event.bytes, event.totalBytes)
                    if (lastReportedStage != null) {
                        taskDao.updateStage(taskId, lastReportedStage?.name, null)
                    }

                    try {
                        val updatedNotification = buildNotification(
                            title = task.title ?: "Downloading media",
                            progress = event.percent,
                            totalBytes = event.totalBytes,
                            taskId = taskId,
                            stageLabel = lastReportedStage?.label
                        )
                        notificationManager.notify(notificationId, updatedNotification)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return try {
            downloadSemaphore.withPermit {
                taskDao.updateStatusAndStage(taskId, DownloadState.DOWNLOADING.name, DownloadStage.DOWNLOADING.name, null)
                val partFile = storageManager.getPartFile(taskId)

                val onProgressCallback: (Long, Long?, Int, DownloadStage?) -> Unit = { bytes, total, percent, stage ->
                    progressChannel.trySend(ProgressEvent(bytes, total, percent, stage))
                }

                var activeTask = task
                var refreshedSourceThisAttempt = false

                val result = if (activeTask.formatSelector != null) {
                    val ytDlpDownloader = YtDlpDownloader(appContext)
                    val infoJsonFile = InfoJsonCache.getValidInfoJson(appContext, activeTask.sourceUrl, activeTask.savedLinkId)
                    ytDlpDownloader.download(
                        url = activeTask.sourceUrl,
                        formatSelector = activeTask.formatSelector!!,
                        destinationFile = partFile,
                        taskId = taskId,
                        infoJsonFile = infoJsonFile,
                        onProgress = onProgressCallback
                    )
                } else {
                    val moviesRepo = container?.moviesRepository
                    if (moviesRepo != null &&
                        activeTask.sourceRef != null &&
                        DownloadSourceRefresher.isUrlExpiredOrExpiring(activeTask.urlExpiresAt)
                    ) {
                        val refreshed = DownloadSourceRefresher.refreshTaskSource(
                            task = activeTask,
                            taskDao = taskDao,
                            resolvePlayback = { pId, tId, se, ep ->
                                moviesRepo.resolvePlayback(pId, tId, se, ep)
                            }
                        )
                        if (refreshed != null) {
                            activeTask = refreshed
                            refreshedSourceThisAttempt = true
                        }
                    }

                    val httpDownloader = HttpDownloader()
                    var lastPersistedEtag = activeTask.etag
                    var lastTelemetryPersistTime = 0L

                    suspend fun handleTelemetry(speedBps: Long?, etaSeconds: Long?, etag: String?) {
                        if (!etag.isNullOrBlank() && etag != lastPersistedEtag) {
                            lastPersistedEtag = etag
                            taskDao.updateEtag(taskId, etag)
                        }
                        val now = System.currentTimeMillis()
                        val isTerminalClear = speedBps == null && etaSeconds == 0L
                        if (isTerminalClear || (speedBps != null && now - lastTelemetryPersistTime >= 500L)) {
                            lastTelemetryPersistTime = now
                            taskDao.updateTelemetry(taskId, speedBps, etaSeconds)
                        }
                    }

                    suspend fun executeHttpDownload(currentTask: com.laterbox.app.media.data.db.entity.DownloadTaskEntity): DownloadResult {
                        val readyCache = com.laterbox.app.movies.data.cache.MoviesMediaCache.getCacheIfReady()
                            ?: com.laterbox.app.movies.data.cache.MoviesMediaCache.warmUp(appContext)
                        if (readyCache != null && (!partFile.exists() || partFile.length() == 0L)) {
                            val reusedResult = StreamCacheReuseDownloader(readyCache, httpDownloader.httpClient)
                                .tryDownloadReusingCache(
                                    url = currentTask.sourceUrl,
                                    destinationFile = partFile,
                                    headers = currentTask.toDomain().headers,
                                    onTelemetry = ::handleTelemetry,
                                    onProgress = { bytes, total, percent, stage ->
                                        onProgressCallback(bytes, total, percent, stage)
                                    }
                                )
                            if (reusedResult != null) {
                                if (reusedResult is DownloadResult.Success && reusedResult.cacheReusedBytes > 0L) {
                                    android.util.Log.i(
                                        "DownloadWorker",
                                        "StreamCacheReuse: reused=${reusedResult.cacheReusedBytes}B, fetched=${reusedResult.networkBytesFetched}B, total=${reusedResult.totalBytes}B"
                                    )
                                }
                                return reusedResult
                            }
                        }

                        return httpDownloader.download(
                            url = currentTask.sourceUrl,
                            destinationFile = partFile,
                            headers = currentTask.toDomain().headers,
                            expectedETag = currentTask.etag,
                            onTelemetry = ::handleTelemetry,
                            onProgress = { bytes, total, percent, stage ->
                                onProgressCallback(bytes, total, percent, stage)
                            }
                        )
                    }

                    val initialResult = executeHttpDownload(activeTask)
                    if (initialResult is DownloadResult.Failure &&
                        (initialResult.httpStatus == 403 || initialResult.httpStatus == 410) &&
                        !refreshedSourceThisAttempt &&
                        moviesRepo != null &&
                        activeTask.sourceRef != null
                    ) {
                        val refreshed = DownloadSourceRefresher.refreshTaskSource(
                            task = activeTask,
                            taskDao = taskDao,
                            resolvePlayback = { pId, tId, se, ep ->
                                moviesRepo.resolvePlayback(pId, tId, se, ep)
                            }
                        )
                        if (refreshed != null) {
                            activeTask = refreshed
                            refreshedSourceThisAttempt = true
                            executeHttpDownload(activeTask)
                        } else {
                            initialResult
                        }
                    } else {
                        initialResult
                    }
                }

                progressChannel.close()
                progressJob.join()

                // Cancellation check before finalizing
                val checkCancel = taskDao.getEntityById(taskId)
                if (checkCancel?.status == DownloadState.CANCELLED.name) {
                    storageManager.deletePartFile(taskId)
                    return Result.success()
                }

                when (result) {
                    is DownloadResult.Success -> {
                        val mediaType = try { MediaType.valueOf(task.mediaType) } catch (_: Exception) { MediaType.OTHER }

                        // Validate downloaded file integrity and media tracks
                        val expectedHasAudio = mediaType == MediaType.VIDEO && (task.formatSelector?.contains("bestaudio") == true)
                        val validation = MediaValidator.validate(
                            file = partFile,
                            expectedType = mediaType,
                            expectedHasAudio = expectedHasAudio
                        )
                        if (validation is ValidationResult.Invalid) {
                            storageManager.deletePartFile(taskId)
                            taskDao.updateError(
                                id = taskId,
                                status = DownloadState.FAILED.name,
                                errorCode = validation.failureCode.name,
                                errorMessage = validation.reason,
                                retryCount = task.retryCount
                            )
                            notificationManager.cancel(notificationId)
                            notifyTerminalDownloadFailure(
                                container = container,
                                taskId = taskId,
                                savedLinkId = task.savedLinkId,
                                title = task.title,
                                errorMessage = validation.reason,
                                retryCount = task.retryCount
                            )
                            return Result.failure()
                        }

                        // 5. Audio Metadata Tagging stage
                        if (mediaType == MediaType.AUDIO && (!task.artist.isNullOrBlank() || !task.title.isNullOrBlank())) {
                            taskDao.updateStatusAndStage(taskId, DownloadState.PROCESSING.name, DownloadStage.TAGGING_METADATA.name, null)
                            AudioTaggingProcessor.tagAudioFile(
                                appContext = appContext,
                                audioFile = partFile,
                                title = task.title,
                                artist = task.artist,
                                album = task.album,
                                artworkUrl = task.artworkUrl,
                                spotifyTrackId = task.spotifyTrackId
                            )
                        }

                        // 6. Finalizing & Storage Deduplication
                        taskDao.updateStatusAndStage(taskId, DownloadState.PROCESSING.name, DownloadStage.FINALIZING.name, null)

                        val checksum = com.laterbox.app.media.data.storage.ChecksumUtil.calculateSha256(partFile)
                        val existingAsset = assetDao.findByChecksum(checksum)
                        val duplicatePath = if (existingAsset != null && storageManager.resolveFile(existingAsset.localPath).exists()) {
                            existingAsset.localPath
                        } else null

                        val finalized = storageManager.finalizeDownload(
                            taskId = taskId,
                            mediaType = mediaType,
                            suggestedExt = null,
                            existingDuplicatePath = duplicatePath
                        )

                        val finalMediaFile = storageManager.resolveFile(finalized.relativePath)
                        val assetId = UUID.randomUUID().toString()

                        var durationMs: Long? = null
                        var width: Int? = null
                        var height: Int? = null

                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            retriever.setDataSource(finalMediaFile.absolutePath)
                            val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            if (!durStr.isNullOrBlank()) {
                                durationMs = durStr.toLongOrNull()
                            }
                            val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            if (!wStr.isNullOrBlank()) {
                                width = wStr.toIntOrNull()
                            }
                            val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            if (!hStr.isNullOrBlank()) {
                                height = hStr.toIntOrNull()
                            }
                            retriever.release()
                        } catch (_: Throwable) {}

                        if (mediaType == MediaType.IMAGE && (width == null || height == null)) {
                            try {
                                val options = android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                android.graphics.BitmapFactory.decodeFile(finalMediaFile.absolutePath, options)
                                if (options.outWidth > 0 && options.outHeight > 0) {
                                    width = options.outWidth
                                    height = options.outHeight
                                }
                            } catch (_: Throwable) {}
                        }

                        // 7. Thumbnail Generation stage
                        taskDao.updateStage(taskId, DownloadStage.GENERATING_THUMBNAIL.name, null)
                        val thumbPath = ThumbnailGenerator.generateThumbnail(
                            context = appContext,
                            mediaFile = finalMediaFile,
                            mediaType = mediaType,
                            assetId = assetId,
                            artworkUrl = task.artworkUrl
                        )

                        // 8. Saving to Library
                        taskDao.updateStage(taskId, DownloadStage.SAVING_TO_LIBRARY.name, null)

                        val assetEntity = DownloadedAssetEntity(
                            id = assetId,
                            savedLinkId = task.savedLinkId,
                            sourcePlatform = task.sourcePlatform,
                            originalSourceUrl = activeTask.sourceUrl,
                            title = task.title,
                            mimeType = finalized.mimeType,
                            mediaType = mediaType.name,
                            localPath = finalized.relativePath,
                            exportedUri = null,
                            fileStatus = AssetFileStatus.AVAILABLE.name,
                            fileSize = finalized.fileSize,
                            durationMs = durationMs,
                            width = width,
                            height = height,
                            qualityLabel = task.qualityLabel,
                            checksum = finalized.checksum,
                            createdAt = System.currentTimeMillis(),
                            artist = task.artist,
                            album = task.album,
                            artworkUrl = task.artworkUrl,
                            spotifyTrackId = task.spotifyTrackId,
                            versionTag = task.versionTag,
                            thumbnailPath = thumbPath,
                            contentCategory = task.contentCategory,
                            sourceRef = activeTask.sourceRef
                        )

                        assetDao.insertAsset(assetEntity)
                        taskDao.updateStatusAndStage(taskId, DownloadState.COMPLETED.name, DownloadStage.COMPLETED.name, null)
                        taskDao.markCompleted(taskId, assetId)
                        if (activeTask.sourceRef != null) {
                            try {
                                val parsed = DownloadSourceRefresher.parseSourceRef(activeTask.sourceRef)
                                val subManager = container?.subtitleDownloadManager
                                val moviesRepo = container?.moviesRepository
                                if (parsed != null && subManager != null && moviesRepo != null) {
                                    val tracks = moviesRepo.getSubtitles(
                                        parsed.providerId,
                                        parsed.titleId,
                                        parsed.seasonNumber,
                                        parsed.episodeNumber
                                    ).getOrNull().orEmpty()
                                    if (tracks.isNotEmpty()) {
                                        val headersMap = linkedMapOf<String, String>()
                                        activeTask.headersJson?.takeIf { it.isNotBlank() }?.let { rawJson ->
                                            runCatching {
                                                val jsonObj = org.json.JSONObject(rawJson)
                                                val keys = jsonObj.keys()
                                                while (keys.hasNext()) {
                                                    val k = keys.next()
                                                    val v = jsonObj.optString(k, "")
                                                    if (k.isNotBlank() && v.isNotBlank()) {
                                                        headersMap[k] = v
                                                    }
                                                }
                                            }
                                        }
                                        subManager.downloadSubtitlesForAsset(
                                            assetId = assetId,
                                            sourceRef = activeTask.sourceRef,
                                            providerId = parsed.providerId,
                                            titleId = parsed.titleId,
                                            seasonNumber = parsed.seasonNumber,
                                            episodeNumber = parsed.episodeNumber,
                                            subtitles = tracks,
                                            fallbackHeaders = headersMap
                                        )
                                    }
                                }
                            } catch (_: Throwable) {
                                // Partial or total subtitle failure must never fail or corrupt the completed video asset.
                            }
                        }
                        try {
                            container?.activityEventRepository?.recordEvent(
                                type = com.laterbox.app.data.repository.ActivityEventType.DOWNLOAD_COMPLETED,
                                title = "Downloaded ${task.title ?: "media"}",
                                detail = "${task.mediaType}${task.qualityLabel?.let { " • $it" } ?: ""}",
                                linkId = task.savedLinkId?.takeIf { it > 0 }
                            )
                        } catch (_: Throwable) {}
                        notificationManager.cancel(notificationId)
                        Result.success()
                    }
                    is DownloadResult.Failure -> {
                        val current = taskDao.getEntityById(taskId)
                        if (current?.status == DownloadState.CANCELLED.name) {
                            storageManager.deletePartFile(taskId)
                            return Result.success()
                        }

                        val isRetryable = result.code.isRetryable
                        val newRetry = task.retryCount + 1
                        if (isRetryable && newRetry <= 3) {
                            taskDao.updateError(
                                id = taskId,
                                status = DownloadState.QUEUED.name,
                                errorCode = result.code.name,
                                errorMessage = result.message,
                                retryCount = newRetry
                            )
                            Result.retry()
                        } else {
                            taskDao.updateError(
                                id = taskId,
                                status = DownloadState.FAILED.name,
                                errorCode = result.code.name,
                                errorMessage = result.message,
                                retryCount = newRetry
                            )
                            notifyTerminalDownloadFailure(
                                container = container,
                                taskId = taskId,
                                savedLinkId = task.savedLinkId,
                                title = task.title,
                                errorMessage = result.message ?: "Download failed",
                                retryCount = newRetry
                            )
                            Result.failure()
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            progressChannel.close()
            progressJob.cancel()
            if (task.formatSelector != null) {
                YtDlpDownloader(appContext).cancel(taskId)
            }
            val currentTask = taskDao.getEntityById(taskId)
            if (currentTask?.status != DownloadState.CANCELLED.name) {
                if (isStopped) {
                    // Timeout or worker cancelled by OS: pause so it resumes cleanly
                    taskDao.updateStatus(taskId, DownloadState.PAUSED.name)
                }
            }
            throw ce
        } catch (e: Exception) {
            progressChannel.close()
            progressJob.cancel()
            val currentTask = taskDao.getEntityById(taskId)
            if (currentTask?.status != DownloadState.CANCELLED.name) {
                val newRetry = task.retryCount + 1
                val msg = e.localizedMessage ?: "Unknown error"
                taskDao.updateError(
                    id = taskId,
                    status = if (newRetry <= 3) DownloadState.QUEUED.name else DownloadState.FAILED.name,
                    errorCode = FailureCode.UNKNOWN.name,
                    errorMessage = msg,
                    retryCount = newRetry
                )
                if (newRetry <= 3) {
                    Result.retry()
                } else {
                    notifyTerminalDownloadFailure(
                        container = container,
                        taskId = taskId,
                        savedLinkId = task.savedLinkId,
                        title = task.title,
                        errorMessage = msg,
                        retryCount = newRetry
                    )
                    Result.failure()
                }
            } else {
                Result.success()
            }
        } finally {
            notificationManager.cancel(notificationId)
        }
    }

    private suspend fun notifyTerminalDownloadFailure(
        container: com.laterbox.app.di.AppContainer?,
        taskId: String,
        savedLinkId: Long?,
        title: String?,
        errorMessage: String,
        retryCount: Int
    ) {
        if (container == null) return
        try {
            val validLinkId = savedLinkId?.takeIf { it > 0 }
            container.activityEventRepository.recordEvent(
                type = com.laterbox.app.data.repository.ActivityEventType.DOWNLOAD_FAILED,
                title = "Download failed: ${title ?: "media"}",
                detail = errorMessage,
                linkId = validLinkId
            )
            container.contextualNotificationEngine.notifyDownloadFailure(
                taskId = taskId,
                savedLinkId = validLinkId ?: -1L,
                title = title,
                errorMessage = errorMessage,
                dedupKey = "download_${taskId}_r${retryCount}"
            )
        } catch (_: Throwable) {}
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent progress notifications for Laterbox media downloads"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        totalBytes: Long?,
        taskId: String,
        stageLabel: String? = null
    ): android.app.Notification {
        val cancelIntent = Intent(appContext, DownloadCancelReceiver::class.java).apply {
            putExtra(DownloadCancelReceiver.EXTRA_TASK_ID, taskId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            appContext,
            taskId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isIndeterminate = totalBytes == null || totalBytes <= 0L || progress <= 0
        val subtext = if (!stageLabel.isNullOrBlank()) "$stageLabel ($progress%)" else title
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Laterbox Download")
            .setContentText(subtext)
            .setSmallIcon(R.drawable.ic_stat_laterbox)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, isIndeterminate)
            .addAction(R.drawable.ic_huge_close, "Cancel", cancelPendingIntent)
            .build()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val CHANNEL_ID = "laterbox_media_downloads"
        val downloadSemaphore = Semaphore(permits = 2)

        fun uniqueWorkName(taskId: String): String = "media_download_$taskId"
    }
}

internal data class ParsedSourceRef(
    val providerId: String,
    val titleId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val qualityLabel: String
)

internal object DownloadSourceRefresher {
    private const val EXPIRY_BUFFER_MS = 60_000L

    fun isUrlExpiredOrExpiring(
        urlExpiresAt: Long?,
        nowMs: Long = System.currentTimeMillis(),
        bufferMs: Long = EXPIRY_BUFFER_MS
    ): Boolean = urlExpiresAt != null && urlExpiresAt <= nowMs + bufferMs

    fun parseSourceRef(sourceRef: String?): ParsedSourceRef? {
        if (sourceRef.isNullOrBlank()) return null
        val parts = sourceRef.split("|")
        if (parts.size < 5) return null
        val providerId = parts[0].trim()
        val titleId = parts[1].trim()
        if (providerId.isEmpty() || titleId.isEmpty()) return null
        val season = parts[2].trim().toIntOrNull()?.takeIf { it > 0 }
        val episode = parts[3].trim().toIntOrNull()?.takeIf { it > 0 }
        val qualityLabel = parts.subList(4, parts.size).joinToString("|").trim()
        return ParsedSourceRef(
            providerId = providerId,
            titleId = titleId,
            seasonNumber = season,
            episodeNumber = episode,
            qualityLabel = qualityLabel
        )
    }

    suspend fun refreshTaskSource(
        task: com.laterbox.app.media.data.db.entity.DownloadTaskEntity,
        taskDao: com.laterbox.app.media.data.db.dao.DownloadTaskDao,
        resolvePlayback: suspend (providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) -> Result<com.laterbox.app.movies.domain.model.PlaybackResolution>
    ): com.laterbox.app.media.data.db.entity.DownloadTaskEntity? {
        val parsed = parseSourceRef(task.sourceRef) ?: return null
        val resolution = resolvePlayback(
            parsed.providerId,
            parsed.titleId,
            parsed.seasonNumber,
            parsed.episodeNumber
        ).getOrNull() as? com.laterbox.app.movies.domain.model.PlaybackResolution.Available ?: return null

        val progressiveSources = resolution.sources.filter { src ->
            src.isAuthorised &&
                !src.format.equals("DASH", ignoreCase = true) &&
                !src.format.equals("HLS", ignoreCase = true) &&
                !src.url.contains(".mpd", ignoreCase = true) &&
                !src.url.contains(".m3u8", ignoreCase = true)
        }
        val selected = progressiveSources.firstOrNull {
            parsed.qualityLabel.isNotBlank() && it.qualityLabel.equals(parsed.qualityLabel, ignoreCase = true)
        } ?: progressiveSources.maxByOrNull { it.qualityHeight } ?: return null

        val headersJson = if (selected.headers.isNotEmpty()) {
            val obj = org.json.JSONObject()
            selected.headers.forEach { (k, v) -> obj.put(k, v) }
            obj.toString()
        } else null
        val newExpiresAt = com.laterbox.app.movies.data.download.MoviesDownloadBridge.parseUrlExpiryMillis(selected.url)

        taskDao.updateResolvedSource(
            id = task.id,
            sourceUrl = selected.url,
            headersJson = headersJson,
            urlExpiresAt = newExpiresAt
        )
        return task.copy(
            sourceUrl = selected.url,
            headersJson = headersJson,
            urlExpiresAt = newExpiresAt
        )
    }
}
