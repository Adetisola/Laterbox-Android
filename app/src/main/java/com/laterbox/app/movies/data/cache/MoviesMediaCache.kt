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

    fun getCache(context: Context): SimpleCache {
        return cacheInstance ?: synchronized(this) {
            cacheInstance ?: buildCache(context.applicationContext).also { cacheInstance = it }
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
        headers: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Laterbox) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)

        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        val upstreamFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)

        return runCatching {
            CacheDataSource.Factory()
                .setCache(getCache(context))
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }.getOrElse {
            upstreamFactory
        }
    }

    fun createMediaSourceFactory(
        context: Context,
        headers: Map<String, String> = emptyMap()
    ): DefaultMediaSourceFactory {
        return DefaultMediaSourceFactory(createDataSourceFactory(context, headers))
    }

    fun releaseForTesting() {
        synchronized(this) {
            runCatching { cacheInstance?.release() }
            cacheInstance = null
        }
    }
}
