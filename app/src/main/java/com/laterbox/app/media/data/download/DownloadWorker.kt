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
        if (task.status == DownloadState.COMPLETED.name ||
            task.status == DownloadState.CANCELLED.name ||
            task.status == DownloadState.PAUSED.name
        ) {
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

        val nowMs = clockMsProvider()
        val scheduledRetryAtMs = task.nextRetryAtMs
        if (task.status == DownloadState.QUEUED.name &&
            scheduledRetryAtMs != null &&
            scheduledRetryAtMs > nowMs + 1_000L
        ) {
            val remainingDelayMs = (scheduledRetryAtMs - nowMs).coerceIn(
                1_000L,
                com.laterbox.app.network.RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS * 1_000L
            )
            scheduleUniqueDelayedRetry(
                context = appContext,
                taskId = taskId,
                allowCellular = task.allowCellularOverride,
                delayMs = remainingDelayMs,
                policy = androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE
            )
            return Result.success()
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
                scheduleUniqueDelayedRetry(
                    context = appContext,
                    taskId = taskId,
                    allowCellular = task.allowCellularOverride,
                    delayMs = 0L,
                    unmeteredOnly = true,
                    policy = androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE
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
                var latestObservedBytes = activeTask.downloadedBytes
                var latestObservedTotalBytes = activeTask.totalBytes?.takeIf { it > 0L }
                var latestObservedPercent = activeTask.progress

                val trackingProgressCallback: (Long, Long?, Int, DownloadStage?) -> Unit = { bytes, total, percent, stage ->
                    latestObservedBytes = bytes
                    if (total != null && total > 0L) {
                        latestObservedTotalBytes = total
                    }
                    latestObservedPercent = percent
                    onProgressCallback(bytes, total, percent, stage)
                }

                val result = if (activeTask.formatSelector != null) {
                    val ytDlpDownloader = YtDlpDownloader(appContext)
                    val infoJsonFile = InfoJsonCache.getValidInfoJson(appContext, activeTask.sourceUrl, activeTask.savedLinkId)
                    ytDlpDownloader.download(
                        url = activeTask.sourceUrl,
                        formatSelector = activeTask.formatSelector!!,
                        destinationFile = partFile,
                        taskId = taskId,
                        infoJsonFile = infoJsonFile,
                        onProgress = trackingProgressCallback
                    )
                } else {
                    var lastPersistedEtag = activeTask.etag
                    var lastPersistedTotalBytes = activeTask.totalBytes?.takeIf {
                        it > 0L && (activeTask.downloadedBytes > 0L || !activeTask.etag.isNullOrBlank())
                    }

                    val httpTrackingProgressCallback: (Long, Long?, Int, DownloadStage?) -> Unit = { bytes, total, percent, stage ->
                        if (total != null && total > 0L) {
                            lastPersistedTotalBytes = total
                        }
                        trackingProgressCallback(bytes, total, percent, stage)
                    }

                    val moviesRepo = container?.moviesRepository
                    if (moviesRepo != null &&
                        activeTask.sourceRef != null &&
                        DownloadSourceRefresher.isUrlExpiredOrExpiring(activeTask.urlExpiresAt)
                    ) {
                        val refreshed = DownloadSourceRefresher.refreshTaskSource(
                            task = activeTask.copy(
                                etag = lastPersistedEtag,
                                totalBytes = lastPersistedTotalBytes
                            ),
                            taskDao = taskDao,
                            partFile = partFile,
                            resolvePlayback = { pId, tId, se, ep ->
                                (moviesRepo as? com.laterbox.app.movies.data.repository.MoviesRepositoryImpl)
                                    ?.invalidatePlaybackCache(pId, tId, se, ep)
                                moviesRepo.resolvePlayback(pId, tId, se, ep)
                            }
                        )
                        if (refreshed != null) {
                            activeTask = refreshed
                            lastPersistedEtag = refreshed.etag
                            lastPersistedTotalBytes = refreshed.totalBytes?.takeIf { it > 0L }
                            latestObservedBytes = refreshed.downloadedBytes
                            latestObservedTotalBytes = refreshed.totalBytes?.takeIf { it > 0L }
                            latestObservedPercent = refreshed.progress
                            refreshedSourceThisAttempt = true
                        }
                    }

                    val httpDownloader = HttpDownloader()
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
                                        httpTrackingProgressCallback(bytes, total, percent, stage)
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
                            expectedETag = lastPersistedEtag ?: currentTask.etag,
                            expectedTotalBytes = lastPersistedTotalBytes,
                            onTelemetry = ::handleTelemetry,
                            onProgress = { bytes, total, percent, stage ->
                                httpTrackingProgressCallback(bytes, total, percent, stage)
                            }
                        )
                    }

                    val initialResult = executeHttpDownload(activeTask)
                    val refreshableStatus = initialResult is DownloadResult.Failure &&
                        initialResult.httpStatus in setOf(401, 403, 404, 410, 429)
                    val retryAfterSec = (initialResult as? DownloadResult.Failure)?.retryAfterSeconds
                    val shouldDeferRetryWithoutRefresh = initialResult is DownloadResult.Failure &&
                        initialResult.httpStatus == 429 &&
                        retryAfterSec != null &&
                        retryAfterSec > 10L
                    if (initialResult is DownloadResult.Failure &&
                        refreshableStatus &&
                        !shouldDeferRetryWithoutRefresh &&
                        !refreshedSourceThisAttempt &&
                        moviesRepo != null &&
                        activeTask.sourceRef != null
                    ) {
                        val failedHost = android.net.Uri.parse(activeTask.sourceUrl).host?.lowercase()
                        android.util.Log.w(
                            "DownloadWorker",
                            "Download HTTP ${initialResult.httpStatus} from host=${failedHost ?: "unknown"} retryAfter=${retryAfterSec ?: "none"}; refreshing source"
                        )
                        com.laterbox.app.diagnostics.MediaNetworkDiagnostics.default.recordLifecycle(
                            com.laterbox.app.diagnostics.MediaLifecycleEvent(
                                timestampMs = System.currentTimeMillis(),
                                kind = com.laterbox.app.diagnostics.MediaRequestKind.DOWNLOAD,
                                stage = "REFRESH_REQUESTED",
                                host = failedHost,
                                format = "PROGRESSIVE",
                                statusCode = initialResult.httpStatus,
                                refreshRequested = true,
                                failureCategory = initialResult.code.name,
                            )
                        )
                        if (initialResult.httpStatus == 429) {
                            val waitMs = ((retryAfterSec ?: 2L).coerceIn(1L, 10L)) * 1_000L
                            kotlinx.coroutines.delay(waitMs)
                        }
                        val refreshed = DownloadSourceRefresher.refreshTaskSource(
                            task = activeTask.copy(
                                etag = lastPersistedEtag,
                                totalBytes = lastPersistedTotalBytes
                            ),
                            taskDao = taskDao,
                            excludeHost = if (initialResult.httpStatus == 429) failedHost else null,
                            partFile = partFile,
                            resolvePlayback = { pId, tId, se, ep ->
                                (moviesRepo as? com.laterbox.app.movies.data.repository.MoviesRepositoryImpl)
                                    ?.invalidatePlaybackCache(pId, tId, se, ep)
                                moviesRepo.resolvePlayback(pId, tId, se, ep)
                            }
                        )
                        if (refreshed != null && refreshed.sourceUrl != activeTask.sourceUrl) {
                            val newHost = android.net.Uri.parse(refreshed.sourceUrl).host?.lowercase()
                            com.laterbox.app.diagnostics.MediaNetworkDiagnostics.default.recordLifecycle(
                                com.laterbox.app.diagnostics.MediaLifecycleEvent(
                                    timestampMs = System.currentTimeMillis(),
                                    kind = com.laterbox.app.diagnostics.MediaRequestKind.DOWNLOAD,
                                    stage = "REFRESH_APPLIED",
                                    host = newHost,
                                    format = "PROGRESSIVE",
                                    statusCode = initialResult.httpStatus,
                                    refreshRequested = true,
                                    refreshedCredentialsApplied = !refreshed.headersJson.isNullOrBlank() ||
                                        refreshed.sourceUrl != activeTask.sourceUrl,
                                )
                            )
                            activeTask = refreshed
                            lastPersistedEtag = refreshed.etag
                            lastPersistedTotalBytes = refreshed.totalBytes?.takeIf { it > 0L }
                            latestObservedBytes = refreshed.downloadedBytes
                            latestObservedTotalBytes = refreshed.totalBytes?.takeIf { it > 0L }
                            latestObservedPercent = refreshed.progress
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

                if (result is DownloadResult.Failure && activeTask.formatSelector == null) {
                    val diskBytes = if (partFile.exists()) partFile.length() else 0L
                    val effectiveTotal = latestObservedTotalBytes?.takeIf { it > 0L }
                    val pct = if (effectiveTotal != null && effectiveTotal > 0L) {
                        ((diskBytes * 100L) / effectiveTotal).toInt().coerceIn(0, 99)
                    } else {
                        latestObservedPercent.coerceIn(0, 99)
                    }
                    taskDao.updateProgress(
                        id = taskId,
                        progress = pct,
                        downloadedBytes = diskBytes,
                        totalBytes = effectiveTotal
                    )
                }

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
                        val finalHost = runCatching { android.net.Uri.parse(activeTask.sourceUrl).host?.lowercase() }.getOrNull()
                        com.laterbox.app.diagnostics.MediaNetworkDiagnostics.default.recordLifecycle(
                            com.laterbox.app.diagnostics.MediaLifecycleEvent(
                                timestampMs = System.currentTimeMillis(),
                                kind = com.laterbox.app.diagnostics.MediaRequestKind.DOWNLOAD,
                                stage = "FINAL_FAILURE",
                                host = finalHost,
                                format = "PROGRESSIVE",
                                statusCode = result.httpStatus,
                                refreshRequested = refreshedSourceThisAttempt,
                                refreshedCredentialsApplied = if (refreshedSourceThisAttempt) true else null,
                                failureCategory = result.code.name,
                            )
                        )
                        val current = taskDao.getEntityById(taskId)
                        if (current?.status == DownloadState.CANCELLED.name) {
                            storageManager.deletePartFile(taskId)
                            return Result.success()
                        }
                        if (current?.status == DownloadState.PAUSED.name) {
                            return Result.success()
                        }

                        val isRetryable = result.code.isRetryable
                        val newRetry = task.retryCount + 1
                        if (isRetryable && newRetry <= 3) {
                            val retryAfterSec = result.retryAfterSeconds
                                ?.takeIf { it > 0L }
                                ?.coerceAtMost(com.laterbox.app.network.RetryAfterParser.MAX_BACKGROUND_DOWNLOAD_DELAY_SECONDS)
                            if (result.code == FailureCode.RATE_LIMITED && retryAfterSec != null) {
                                val delayMs = retryAfterSec * 1_000L
                                val nextRetryAtMs = clockMsProvider() + delayMs
                                taskDao.updateScheduledRetry(
                                    id = taskId,
                                    status = DownloadState.QUEUED.name,
                                    errorCode = result.code.name,
                                    errorMessage = result.message,
                                    retryCount = newRetry,
                                    nextRetryAtMs = nextRetryAtMs
                                )
                                scheduleUniqueDelayedRetry(
                                    context = appContext,
                                    taskId = taskId,
                                    allowCellular = activeTask.allowCellularOverride,
                                    delayMs = delayMs,
                                    policy = androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE
                                )
                                Result.success()
                            } else {
                                taskDao.updateError(
                                    id = taskId,
                                    status = DownloadState.QUEUED.name,
                                    errorCode = result.code.name,
                                    errorMessage = result.message,
                                    retryCount = newRetry
                                )
                                Result.retry()
                            }
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
            container.analyticsTracker.trackErrorObserved(
                errorCategory = "download_error",
                featureId = "media_download",
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

        internal var clockMsProvider: () -> Long = { System.currentTimeMillis() }

        fun uniqueWorkName(taskId: String): String = "media_download_$taskId"

        internal fun scheduleUniqueDelayedRetry(
            context: Context,
            taskId: String,
            allowCellular: Boolean,
            delayMs: Long,
            unmeteredOnly: Boolean = false,
            policy: androidx.work.ExistingWorkPolicy = androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE
        ) {
            val networkType = when {
                unmeteredOnly -> NetworkType.UNMETERED
                allowCellular -> NetworkType.CONNECTED
                else -> NetworkType.NOT_ROAMING
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            val builder = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(androidx.work.workDataOf(KEY_TASK_ID to taskId))
            if (delayMs > 0L) {
                builder.setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(taskId),
                policy,
                builder.build()
            )
        }
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
        excludeHost: String? = null,
        partFile: java.io.File? = null,
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
        val excludedDomain = com.laterbox.app.network.CdnFailureDomainResolver.resolve(excludeHost)
        val nonExcluded = if (excludedDomain != null) {
            progressiveSources.filter { src ->
                com.laterbox.app.network.CdnFailureDomainResolver.resolve(src.url) != excludedDomain
            }
        } else {
            progressiveSources
        }
        if (nonExcluded.isEmpty()) return null
        val pool = nonExcluded
        val selected = pool.firstOrNull {
            parsed.qualityLabel.isNotBlank() && it.qualityLabel.equals(parsed.qualityLabel, ignoreCase = true)
        } ?: pool.maxByOrNull { it.qualityHeight } ?: return null

        val headersJson = if (selected.headers.isNotEmpty()) {
            val obj = org.json.JSONObject()
            selected.headers.forEach { (k, v) -> obj.put(k, v) }
            obj.toString()
        } else null
        val newExpiresAt = com.laterbox.app.movies.data.download.MoviesDownloadBridge.parseUrlExpiryMillis(selected.url)

        val expectedQuality = parsed.qualityLabel.takeIf { it.isNotBlank() }
            ?: task.versionTag?.takeIf { it.isNotBlank() }
        val sameQuality = expectedQuality != null &&
            selected.qualityLabel.isNotBlank() &&
            selected.qualityLabel.equals(expectedQuality, ignoreCase = true)

        val previousPath = StreamCacheReuseDownloader.normalizeUrlPath(task.sourceUrl)
        val nextPath = StreamCacheReuseDownloader.normalizeUrlPath(selected.url)
        val sameNormalizedPath = previousPath != null && nextPath != null && previousPath == nextPath

        val selectedEstimatedBytes = selected.sizeMb
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1024.0 * 1024.0).toLong() }
        val existingTotalBytes = task.totalBytes?.takeIf { it > 0L }
        val sizeCompatible = if (selectedEstimatedBytes != null && existingTotalBytes != null) {
            val tolerance = maxOf(2L * 1024L * 1024L, existingTotalBytes / 50L)
            kotlin.math.abs(selectedEstimatedBytes - existingTotalBytes) <= tolerance
        } else {
            true
        }

        val representationCompatible = sameQuality && sameNormalizedPath && sizeCompatible

        return if (representationCompatible) {
            taskDao.updateResolvedSource(
                id = task.id,
                sourceUrl = selected.url,
                headersJson = headersJson,
                urlExpiresAt = newExpiresAt
            )
            task.copy(
                sourceUrl = selected.url,
                headersJson = headersJson,
                urlExpiresAt = newExpiresAt
            )
        } else {
            runCatching {
                if (partFile != null && partFile.exists()) {
                    partFile.delete()
                }
            }
            val newQualityLabel = selected.qualityLabel.takeIf { it.isNotBlank() }?.let { "$it · ${selected.format}" }
            val newVersionTag = selected.qualityLabel.takeIf { it.isNotBlank() }
            taskDao.resetAndUpdateResolvedSource(
                id = task.id,
                sourceUrl = selected.url,
                headersJson = headersJson,
                urlExpiresAt = newExpiresAt,
                totalBytes = selectedEstimatedBytes,
                qualityLabel = newQualityLabel,
                versionTag = newVersionTag
            )
            task.copy(
                sourceUrl = selected.url,
                headersJson = headersJson,
                urlExpiresAt = newExpiresAt,
                etag = null,
                downloadedBytes = 0L,
                progress = 0,
                totalBytes = selectedEstimatedBytes,
                qualityLabel = newQualityLabel ?: task.qualityLabel,
                versionTag = newVersionTag ?: task.versionTag
            )
        }
    }
}
