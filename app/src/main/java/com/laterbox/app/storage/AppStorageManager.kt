package com.laterbox.app.storage

import android.content.Context
import android.os.StatFs
import coil.Coil
import com.laterbox.app.media.data.db.dao.DownloadTaskDao
import com.laterbox.app.media.data.storage.MediaStorageManager
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.movies.data.cache.MoviesMediaCache
import com.laterbox.app.movies.data.local.dao.MoviesDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class AppStorageReport(
    val downloadedMediaBytes: Long,
    val inProgressDownloadsBytes: Long,
    val streamPlaybackCacheBytes: Long,
    val imageCacheBytes: Long,
    val databaseAndMetadataBytes: Long,
    val extractorRuntimesBytes: Long,
    val tempAndOtherCacheBytes: Long,
    val safeClearableBytes: Long,
    val totalUsedBytes: Long,
    val deviceFreeBytes: Long,
    val deviceTotalBytes: Long,
    val storagePressureHigh: Boolean,
    val cachedApiEntryCount: Int
)

data class StorageCleanupResult(
    val bytesFreed: Long,
    val expiredEntriesPruned: Int,
    val report: AppStorageReport
)

class AppStorageManager(
    private val context: Context,
    private val moviesDao: MoviesDao,
    private val downloadTaskDao: DownloadTaskDao,
    private val mediaRepository: MediaRepository? = null,
    private val storageManager: MediaStorageManager = MediaStorageManager(context),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val maintenanceMutex = Mutex()

    suspend fun inspectStorage(): AppStorageReport = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val cacheDir = context.cacheDir

        val downloadedMediaBytes = measureDirBytes(File(filesDir, "media")) +
            measureDirBytes(File(filesDir, "media_thumbnails")) +
            measureDirBytes(File(filesDir, "subtitles"))

        val inProgressDownloadsBytes = measureDirBytes(storageManager.getCurrentPartsDir()) +
            measureDirBytes(storageManager.legacyPartsBaseDir)

        val streamPlaybackCacheBytes = MoviesMediaCache.measureUsedBytes(context)
        val imageCacheBytes = measureDirBytes(File(cacheDir, "image_cache"))

        val dbBytes = measureDatabaseBytes()
        val runtimesBytes = measureDirBytes(File(context.noBackupFilesDir, "youtubedl-android")) +
            measureDirBytes(File(filesDir, "youtubedl-android")) +
            measureDirBytes(File(filesDir, "python"))

        val knownCacheSubdirs = setOf("image_cache", "movies_media_cache", "media_parts")
        val tempAndOtherCacheBytes = (cacheDir.listFiles() ?: emptyArray())
            .filter { it.name !in knownCacheSubdirs }
            .sumOf { fileOrDir -> measurePathBytes(fileOrDir) }

        val safeClearableBytes = streamPlaybackCacheBytes + imageCacheBytes + tempAndOtherCacheBytes
        val totalUsedBytes = downloadedMediaBytes +
            inProgressDownloadsBytes +
            streamPlaybackCacheBytes +
            imageCacheBytes +
            dbBytes +
            runtimesBytes +
            tempAndOtherCacheBytes

        val (freeBytes, totalBytes) = readDeviceStorageStats()
        val pressureHigh = (freeBytes in 1L until LOW_FREE_SPACE_THRESHOLD_BYTES) ||
            safeClearableBytes > CACHE_PRESSURE_THRESHOLD_BYTES

        val cachedEntriesCount = runCatching { moviesDao.countCacheEntries() }.getOrDefault(0)

        AppStorageReport(
            downloadedMediaBytes = downloadedMediaBytes,
            inProgressDownloadsBytes = inProgressDownloadsBytes,
            streamPlaybackCacheBytes = streamPlaybackCacheBytes,
            imageCacheBytes = imageCacheBytes,
            databaseAndMetadataBytes = dbBytes,
            extractorRuntimesBytes = runtimesBytes,
            tempAndOtherCacheBytes = tempAndOtherCacheBytes,
            safeClearableBytes = safeClearableBytes,
            totalUsedBytes = totalUsedBytes,
            deviceFreeBytes = freeBytes,
            deviceTotalBytes = totalBytes,
            storagePressureHigh = pressureHigh,
            cachedApiEntryCount = cachedEntriesCount
        )
    }

    /**
     * Clears only disposable caches (Coil image disk/memory cache, Media3 stream segment cache,
     * loose temp files, and expired API cache entries). Never deletes downloaded media,
     * in-progress `.part` files, watchlist, collections, or watch history.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearSafeCaches(): StorageCleanupResult = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            val before = inspectStorage()
            val expiredBefore = runCatching { moviesDao.countCacheEntries() }.getOrDefault(0)

            runCatching {
                val loader = Coil.imageLoader(context.applicationContext)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            deleteDirectoryContents(File(context.cacheDir, "image_cache"))
            MoviesMediaCache.clearCache(context)

            val protectedCacheDirs = setOf("image_cache", "movies_media_cache", "media_parts")
            context.cacheDir.listFiles()?.forEach { entry ->
                if (entry.name !in protectedCacheDirs) {
                    runCatching { entry.deleteRecursively() }
                }
            }

            runCatching { moviesDao.pruneExpiredCache(nowMs()) }
            val expiredAfter = runCatching { moviesDao.countCacheEntries() }.getOrDefault(0)
            val after = inspectStorage()

            StorageCleanupResult(
                bytesFreed = (before.safeClearableBytes - after.safeClearableBytes).coerceAtLeast(0L),
                expiredEntriesPruned = (expiredBefore - expiredAfter).coerceAtLeast(0),
                report = after
            )
        }
    }

    /**
     * Coordinated background/startup storage maintenance:
     * 1. Migrates legacy `cacheDir/media_parts` -> `filesDir/download_parts` safely.
     * 2. Sweeps orphaned `.part` files while protecting active/in-progress workers.
     * 3. Prunes expired API metadata cache rows and stale temp files.
     * 4. Under storage pressure, trims oversized disposable stream/image caches.
     */
    suspend fun runMaintenanceCleanup(): StorageCleanupResult = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            val before = inspectStorage()
            val entriesBefore = runCatching { moviesDao.countCacheEntries() }.getOrDefault(0)

            if (mediaRepository != null) {
                runCatching { mediaRepository.runOrphanSweep() }
            } else {
                val activeIds = runCatching { downloadTaskDao.getActiveAndPausedTaskIds().toSet() }
                    .getOrDefault(emptySet())
                val inProgressIds = runCatching { downloadTaskDao.getInProgressTaskIds().toSet() }
                    .getOrDefault(emptySet())
                storageManager.migrateLegacyPartFilesIfNeeded(activeIds, inProgressIds)
                storageManager.sweepOrphanPartFiles(activeIds, inProgressIds)
            }

            val currentMs = nowMs()
            runCatching { moviesDao.pruneExpiredCache(currentMs) }

            val staleThresholdMs = currentMs - STALE_TEMP_AGE_MS
            val knownCacheSubdirs = setOf("image_cache", "movies_media_cache", "media_parts")
            context.cacheDir.listFiles()?.forEach { entry ->
                if (entry.name !in knownCacheSubdirs && entry.lastModified() < staleThresholdMs) {
                    runCatching { entry.deleteRecursively() }
                }
            }

            val (freeBytes, _) = readDeviceStorageStats()
            val pressureHigh = (freeBytes in 1L until LOW_FREE_SPACE_THRESHOLD_BYTES) ||
                before.safeClearableBytes > CACHE_PRESSURE_THRESHOLD_BYTES
            if (pressureHigh) {
                MoviesMediaCache.trimCacheToMaxBytes(context, PRESSURE_STREAM_CACHE_TARGET_BYTES)
                trimDirOldestFiles(File(context.cacheDir, "image_cache"), PRESSURE_IMAGE_CACHE_TARGET_BYTES)
            }

            val entriesAfter = runCatching { moviesDao.countCacheEntries() }.getOrDefault(0)
            val after = inspectStorage()
            StorageCleanupResult(
                bytesFreed = (before.totalUsedBytes - after.totalUsedBytes).coerceAtLeast(0L),
                expiredEntriesPruned = (entriesBefore - entriesAfter).coerceAtLeast(0),
                report = after
            )
        }
    }

    private fun measureDatabaseBytes(): Long {
        var sum = 0L
        runCatching {
            val dbNames = context.databaseList()
            for (name in dbNames) {
                val dbFile = context.getDatabasePath(name)
                if (dbFile.exists()) sum += dbFile.length()
                val wal = File("${dbFile.path}-wal")
                if (wal.exists()) sum += wal.length()
                val shm = File("${dbFile.path}-shm")
                if (shm.exists()) sum += shm.length()
            }
        }
        return sum
    }

    private fun measureDirBytes(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return measurePathBytes(dir)
    }

    private fun measurePathBytes(path: File): Long {
        if (!path.exists()) return 0L
        return runCatching {
            if (path.isFile) {
                path.length()
            } else {
                path.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            }
        }.getOrDefault(0L)
    }

    private fun deleteDirectoryContents(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
    }

    private fun trimDirOldestFiles(dir: File, maxBytes: Long) {
        if (!dir.exists() || !dir.isDirectory) return
        var current = measureDirBytes(dir)
        if (current <= maxBytes) return
        val filesByOldest = dir.walkBottomUp()
            .filter { it.isFile && it.name != "journal" }
            .sortedBy { it.lastModified() }
            .toList()
        for (f in filesByOldest) {
            if (current <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                current = (current - len).coerceAtLeast(0L)
            }
        }
    }

    private fun readDeviceStorageStats(): Pair<Long, Long> {
        return runCatching {
            val stat = StatFs(context.filesDir.path)
            stat.availableBytes to stat.totalBytes
        }.getOrDefault(0L to 0L)
    }

    companion object {
        private const val LOW_FREE_SPACE_THRESHOLD_BYTES = 250L * 1024L * 1024L
        private const val CACHE_PRESSURE_THRESHOLD_BYTES = 650L * 1024L * 1024L
        private const val PRESSURE_STREAM_CACHE_TARGET_BYTES = 150L * 1024L * 1024L
        private const val PRESSURE_IMAGE_CACHE_TARGET_BYTES = 60L * 1024L * 1024L
        private const val STALE_TEMP_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
