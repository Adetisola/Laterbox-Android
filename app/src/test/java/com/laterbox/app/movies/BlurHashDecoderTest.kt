package com.laterbox.app.movies

import com.laterbox.app.movies.ui.BlurHashDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlurHashDecoderTest {

    @Test
    fun decode_validProductionBlurHashReturnsExpectedDimensionsAndCachesBitmap() {
        val hash = "ePIFua-;Rht7M|M{M{oIaeWC.TMxe-t7aykDRkV?t6WBMxM{M{RiV["
        val bitmap = BlurHashDecoder.decodeToAndroidBitmap(hash, width = 20, height = 30)
        assertNotNull(bitmap)
        assertEquals(20, bitmap!!.width)
        assertEquals(30, bitmap.height)

        val first = BlurHashDecoder.decode(hash, width = 20, height = 30)
        val second = BlurHashDecoder.decode(hash, width = 20, height = 30)
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun decode_malformedOrTruncatedBlurHashSafelyReturnsNull() {
        assertNull(BlurHashDecoder.decode(null))
        assertNull(BlurHashDecoder.decode(""))
        assertNull(BlurHashDecoder.decode("short"))
        assertNull(BlurHashDecoder.decode("ePIFua-;Rht7M|"))
        assertNull(BlurHashDecoder.decode("!!!!invalid_base83_chars!!!!"))
        assertNull(BlurHashDecoder.decode("ePIFua-;Rht7M|M{M{oIaeWC.TMxe-t7aykDRkV?t6WBMxM{M{RiV[", width = 0, height = 10))
    }
}
