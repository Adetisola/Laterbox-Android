package com.laterbox.app.movies.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleStyleAndStackingTest {

    @Test
    fun stackSimultaneousBottomCues_leavesSingleCueUnchanged() {
        val cue = Cue.Builder()
            .setText("First line\nSecond line")
            .build()

        val result = SubtitleStyleHelper.stackSimultaneousBottomCues(listOf(cue))

        assertEquals(1, result.size)
        assertSame(cue, result.single())
    }

    @Test
    fun stackSimultaneousBottomCues_mergesSimultaneousBottomCuesWithNewlineAndPreservesSpans() {
        val speakerOne = SpannableString("- Where are we going?\n").apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val speakerTwo = Cue.Builder()
            .setText("- To the harbor.")
            .setLine(0.9f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .build()
        val cueOne = Cue.Builder()
            .setText(speakerOne)
            .build()

        val result = SubtitleStyleHelper.stackSimultaneousBottomCues(listOf(cueOne, speakerTwo))

        assertEquals(1, result.size)
        val merged = result.single()
        assertEquals("- Where are we going?\n- To the harbor.", merged.text.toString())
        assertEquals(Cue.DIMEN_UNSET, merged.line)
        assertEquals(Cue.TYPE_UNSET, merged.lineType)
        assertEquals(Cue.ANCHOR_TYPE_END, merged.lineAnchor)

        val spanned = merged.text as Spanned
        val spans = spanned.getSpans(0, spanned.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Typeface.ITALIC, spans.single().style)
    }

    @Test
    fun stackSimultaneousBottomCues_keepsTopPositionedCueSeparateFromStackedBottomCues() {
        val topSign = Cue.Builder()
            .setText("PARIS, 1998")
            .setLine(0.1f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build()
        val bottomA = Cue.Builder().setText("- Did you hear that?").build()
        val bottomB = Cue.Builder().setText("- Stay low.").build()

        val result = SubtitleStyleHelper.stackSimultaneousBottomCues(listOf(topSign, bottomA, bottomB))

        assertEquals(2, result.size)
        assertEquals("PARIS, 1998", result[0].text.toString())
        assertEquals(0.1f, result[0].line, 0.0001f)
        assertEquals("- Did you hear that?\n- Stay low.", result[1].text.toString())
    }

    @Test
    fun stackSimultaneousBottomCues_doesNotMergeBitmapCues() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bitmapCue = Cue.Builder().setBitmap(bitmap).build()
        val textCue = Cue.Builder().setText("Spoken line").build()

        val result = SubtitleStyleHelper.stackSimultaneousBottomCues(listOf(bitmapCue, textCue))

        assertEquals(2, result.size)
        assertSame(bitmapCue, result[0])
        assertSame(textCue, result[1])
    }

    @Test
    fun applyToSubtitleView_setsBottomPaddingAndPreservesCanvasViewAndUserStyling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val subtitleView = SubtitleView(context)
        val config = SubtitleStyleConfig(
            useSystemCaptions = false,
            fontSizeSp = 22f,
            textColorArgb = Color.YELLOW,
            backgroundColorArgb = Color.BLACK,
            backgroundOpacity = 0.5f,
            edgeType = 1
        )

        SubtitleStyleHelper.applyToSubtitleView(subtitleView, config)
        val stackedCues = SubtitleStyleHelper.stackSimultaneousBottomCues(
            listOf(
                Cue.Builder().setText("Line one").build(),
                Cue.Builder().setText("Line two").build()
            )
        )
        subtitleView.setCues(stackedCues)

        val viewType = readPrivateField<Int>(subtitleView, "viewType")
        val bottomPadding = readPrivateField<Float>(subtitleView, "bottomPaddingFraction")
        val style = readPrivateField<CaptionStyleCompat>(subtitleView, "style")
        val cues = readPrivateField<List<Cue>>(subtitleView, "cues")

        assertEquals(SubtitleView.VIEW_TYPE_CANVAS, viewType)
        assertEquals(SubtitleStyleHelper.MULTI_LINE_BOTTOM_PADDING_FRACTION, bottomPadding, 0.0001f)
        assertTrue(bottomPadding > SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        assertEquals(Color.YELLOW, style.foregroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
        assertEquals(1, cues.size)
        assertEquals("Line one\nLine two", cues.single().text.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readPrivateField(target: Any, fieldName: String): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as T
    }
}
