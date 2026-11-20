package com.laterbox.app.movies.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * Pure-Kotlin BlurHash decoder with defensive validation and an in-memory LRU cache.
 * All Compose decoding runs off the main thread on [Dispatchers.Default].
 */
object BlurHashDecoder {
    private const val BASE83_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private val charMap: IntArray = IntArray(128) { -1 }.apply {
        BASE83_ALPHABET.forEachIndexed { idx, ch ->
            if (ch.code < size) this[ch.code] = idx
        }
    }

    private val bitmapCache = object : LruCache<String, ImageBitmap>(128) {}

    fun getCached(blurHash: String?, width: Int, height: Int): ImageBitmap? {
        val clean = blurHash?.trim()?.takeIf { it.length >= 6 } ?: return null
        return bitmapCache.get(cacheKey(clean, width, height))
    }

    fun decode(
        blurHash: String?,
        width: Int = 20,
        height: Int = 30,
        punch: Float = 1f
    ): ImageBitmap? {
        val clean = blurHash?.trim() ?: return null
        if (clean.length < 6 || width <= 0 || height <= 0) return null
        val key = cacheKey(clean, width, height)
        bitmapCache.get(key)?.let { return it }

        val bitmap = decodeToAndroidBitmap(clean, width, height, punch) ?: return null
        val imageBitmap = bitmap.asImageBitmap()
        bitmapCache.put(key, imageBitmap)
        return imageBitmap
    }

    internal fun decodeToAndroidBitmap(
        blurHash: String,
        width: Int,
        height: Int,
        punch: Float = 1f
    ): Bitmap? = runCatching {
        val clean = blurHash.trim()
        if (clean.length < 6 || width <= 0 || height <= 0) return null
        val numCompEnc = decode83(clean, 0, 1) ?: return null
        val numCompX = (numCompEnc % 9) + 1
        val numCompY = (numCompEnc / 9) + 1
        val expectedLength = 4 + 2 * numCompX * numCompY
        if (clean.length != expectedLength) return null

        val maxAcEnc = decode83(clean, 1, 2) ?: return null
        val maxAc = (maxAcEnc + 1) / 166f

        val colors = Array(numCompX * numCompY) { i ->
            if (i == 0) {
                val dcEnc = decode83(clean, 2, 6) ?: return null
                decodeDc(dcEnc)
            } else {
                val start = 4 + i * 2
                val acEnc = decode83(clean, start, start + 2) ?: return null
                decodeAc(acEnc, maxAc * punch)
            }
        }

        val pixels = composePixels(width, height, numCompX, numCompY, colors)
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    private fun composePixels(
        width: Int,
        height: Int,
        numCompX: Int,
        numCompY: Int,
        colors: Array<FloatArray>
    ): IntArray {
        val cosinesX = Array(width) { x ->
            FloatArray(numCompX) { i ->
                cos(Math.PI * x * i / width).toFloat()
            }
        }
        val cosinesY = Array(height) { y ->
            FloatArray(numCompY) { j ->
                cos(Math.PI * y * j / height).toFloat()
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val cy = cosinesY[y]
            for (x in 0 until width) {
                val cx = cosinesX[x]
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numCompY) {
                    val wy = cy[j]
                    val rowOffset = j * numCompX
                    for (i in 0 until numCompX) {
                        val basis = cx[i] * wy
                        val c = colors[rowOffset + i]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                pixels[y * width + x] = Color.rgb(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
            }
        }
        return pixels
    }

    private fun decode83(str: String, from: Int, to: Int): Int? {
        if (from < 0 || to > str.length || from >= to) return null
        var value = 0
        for (i in from until to) {
            val code = str[i].code
            val digit = if (code < charMap.size) charMap[code] else -1
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    private fun decodeDc(value: Int): FloatArray {
        val r = value ushr 16
        val g = (value ushr 8) and 255
        val b = value and 255
        return floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))
    }

    private fun decodeAc(value: Int, maxAc: Float): FloatArray {
        val r = value / (19 * 19)
        val g = (value / 19) % 19
        val b = value % 19
        return floatArrayOf(
            signedPow2((r - 9) / 9f) * maxAc,
            signedPow2((g - 9) / 9f) * maxAc,
            signedPow2((b - 9) / 9f) * maxAc
        )
    }

    private fun signedPow2(value: Float): Float = value.pow(2f).withSign(value)

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun cacheKey(hash: String, width: Int, height: Int): String = "$hash|$width|$height"
}

@Composable
fun rememberDecodedBlurHash(
    blurHash: String?,
    width: Int = 20,
    height: Int = 30
): ImageBitmap? {
    val cleanHash = blurHash?.trim()?.takeIf { it.length >= 6 } ?: return null
    val state = androidx.compose.runtime.remember(cleanHash, width, height) {
        androidx.compose.runtime.mutableStateOf(BlurHashDecoder.getCached(cleanHash, width, height))
    }
    androidx.compose.runtime.LaunchedEffect(cleanHash, width, height) {
        if (state.value == null) {
            state.value = withContext(Dispatchers.Default) {
                BlurHashDecoder.decode(cleanHash, width = width, height = height)
            }
        }
    }
    return state.value
}
