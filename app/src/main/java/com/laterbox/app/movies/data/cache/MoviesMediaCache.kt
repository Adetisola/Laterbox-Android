package com.laterbox.app.movies.data.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.launch
import java.io.File

/**
 * Progressive Media3 stream cache for authorised open-license streams.
 * Caches streamed segments up to 512 MB on disk so scrubbing and replays work smoothly
 * and remain resilient across transient connectivity drops.
 */
@OptIn(UnstableApi::class)
object MoviesMediaCache {

    private const val CACHE_DIR_NAME = "movies_media_cache"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L

    @Volatile
    private var cacheInstance: SimpleCache? = null

    @Volatile
    private var isWarmingUp: Boolean = false

    fun getCache(context: Context): SimpleCache {
        return cacheInstance ?: synchronized(this) {
            cacheInstance ?: buildCache(context.applicationContext).also { cacheInstance = it }
        }
    }

    fun getCacheIfReady(): SimpleCache? = cacheInstance

    suspend fun warmUp(context: Context): SimpleCache? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { getCache(context.applicationContext) }.getOrNull()
    }

    fun warmUpAsync(context: Context) {
        if (cacheInstance != null || isWarmingUp) return
        isWarmingUp = true
        val appContext = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                getCache(appContext)
            } catch (_: Throwable) {
            } finally {
                isWarmingUp = false
            }
        }
    }

    private fun buildCache(appContext: Context): SimpleCache {
        val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
        return SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    fun createDataSourceFactory(
        context: Context,
        headers: Map<String, String> = emptyMap(),
        useDiskCache: Boolean = true
    ): DataSource.Factory {
        val customUserAgent = headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 14; Laterbox) AppleWebKit/537.36"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(customUserAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val extraHeaders = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (extraHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(extraHeaders)
        }

        val upstreamFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
        if (!useDiskCache) {
            return upstreamFactory
        }

        // Only attach SimpleCache synchronously if already warmed in memory; otherwise warm on IO
        // without blocking the Main thread with SQLite/directory scans inside Compose remember {}.
        val readyCache = getCacheIfReady()
        if (readyCache == null) {
            warmUpAsync(context)
            return upstreamFactory
        }

        return runCatching {
            CacheDataSource.Factory()
                .setCache(readyCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }.getOrElse {
            upstreamFactory
        }
    }

    fun createMediaSourceFactory(
        context: Context,
        headers: Map<String, String> = emptyMap(),
        useDiskCache: Boolean = true
    ): DefaultMediaSourceFactory {
        return DefaultMediaSourceFactory(createDataSourceFactory(context, headers, useDiskCache))
    }

    fun cacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, CACHE_DIR_NAME)

    fun measureUsedBytes(context: Context): Long {
        val dir = cacheDir(context)
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun clearCache(context: Context): Long {
        val before = measureUsedBytes(context)
        val ready = getCacheIfReady()
        if (ready != null) {
            runCatching {
                ready.keys.toList().forEach { key ->
                    runCatching { ready.removeResource(key) }
                }
            }
        } else {
            val dir = cacheDir(context)
            if (dir.exists()) {
                dir.listFiles()?.forEach { child ->
                    runCatching { child.deleteRecursively() }
                }
            }
        }
        val after = measureUsedBytes(context)
        return (before - after).coerceAtLeast(0L)
    }

    fun trimCacheToMaxBytes(context: Context, targetMaxBytes: Long): Long {
        val before = measureUsedBytes(context)
        if (before <= targetMaxBytes) return 0L
        val ready = getCacheIfReady() ?: return 0L
        val keysByOldest = ready.keys
            .map { key ->
                val spans = runCatching { ready.getCachedSpans(key) }.getOrDefault(emptySet())
                val oldestTouch = spans.minOfOrNull { it.lastTouchTimestamp } ?: Long.MAX_VALUE
                key to oldestTouch
            }
            .sortedBy { it.second }
        for ((key, _) in keysByOldest) {
            if (measureUsedBytes(context) <= targetMaxBytes) break
            runCatching { ready.removeResource(key) }
        }
        val after = measureUsedBytes(context)
        return (before - after).coerceAtLeast(0L)
    }

    fun releaseForTesting() {
        synchronized(this) {
            runCatching { cacheInstance?.release() }
            cacheInstance = null
        }
    }
}
