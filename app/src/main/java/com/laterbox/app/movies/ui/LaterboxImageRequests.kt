package com.laterbox.app.movies.ui

import android.content.Context
import android.graphics.Bitmap
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision

/**
 * Bounded pixel-size ImageRequest builders to avoid decoding full-resolution
 * posters/backdrops/avatars into ~2GB ARM32 heap on low-end devices.
 */
object LaterboxImageRequests {
    fun poster(context: Context, url: String?, crossfade: Boolean = false): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(360, 540)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowRgb565(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(crossfade)
            .build()

    fun backdrop(context: Context, url: String?, crossfade: Boolean = false): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(720, 405)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowRgb565(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(crossfade)
            .build()

    fun avatar(context: Context, url: String?): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(160, 160)
            .precision(Precision.INEXACT)
            .allowRgb565(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
}
