package com.laterbox.app.movies.ui.player

import android.graphics.Color as AndroidColor
import android.text.SpannableStringBuilder
import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.core.graphics.ColorUtils
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import kotlin.math.roundToInt

object SubtitleStyleHelper {
    internal const val MULTI_LINE_BOTTOM_PADDING_FRACTION = 0.10f

    @OptIn(UnstableApi::class)
    fun applyToSubtitleView(
        subtitleView: SubtitleView?,
        config: SubtitleStyleConfig
    ) {
        if (subtitleView == null) return

        subtitleView.setBottomPaddingFraction(MULTI_LINE_BOTTOM_PADDING_FRACTION)

        if (config.useSystemCaptions) {
            subtitleView.setApplyEmbeddedStyles(true)
            subtitleView.setApplyEmbeddedFontSizes(true)
            subtitleView.setUserDefaultStyle()
            subtitleView.setUserDefaultTextSize()
            return
        }

        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setApplyEmbeddedFontSizes(false)

        val alphaInt = (config.backgroundOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val bgWithAlpha = ColorUtils.setAlphaComponent(config.backgroundColorArgb, alphaInt)
        val edgeCompat = when (config.edgeType) {
            0 -> CaptionStyleCompat.EDGE_TYPE_NONE
            1 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            else -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        }

        subtitleView.setStyle(
            CaptionStyleCompat(
                config.textColorArgb,
                bgWithAlpha,
                AndroidColor.TRANSPARENT,
                edgeCompat,
                AndroidColor.BLACK,
                null
            )
        )
        subtitleView.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            config.fontSizeSp.coerceIn(12f, 36f)
        )
    }

    /**
     * Merges simultaneous bottom-anchored text cues into a single multi-line cue separated by
     * newlines so Media3 CanvasSubtitleOutput stacks dialogue lines vertically instead of
     * drawing overlapping cues at the same bottom anchor.
     */
    @OptIn(UnstableApi::class)
    fun stackSimultaneousBottomCues(cues: List<Cue>): List<Cue> {
        if (cues.size <= 1) return cues

        val bottomTextCues = mutableListOf<Cue>()
        val otherCues = mutableListOf<Cue>()
        for (cue in cues) {
            if (isBottomAnchoredTextCue(cue)) {
                bottomTextCues.add(cue)
            } else {
                otherCues.add(cue)
            }
        }
        if (bottomTextCues.size <= 1) return cues

        val mergedText = SpannableStringBuilder()
        for (cue in bottomTextCues) {
            val part = cue.text?.toString()?.trimEnd('\n', '\r') ?: continue
            if (part.isBlank()) continue
            if (mergedText.isNotEmpty()) {
                mergedText.append('\n')
            }
            val raw = cue.text!!
            val trimmedEnd = raw.length - (raw.length - part.length)
            mergedText.append(raw, 0, trimmedEnd)
        }
        if (mergedText.isEmpty()) return cues

        val template = bottomTextCues.first()
        val stackedCue = template.buildUpon()
            .setText(mergedText)
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .build()

        return buildList(otherCues.size + 1) {
            addAll(otherCues)
            add(stackedCue)
        }
    }

    private fun isBottomAnchoredTextCue(cue: Cue): Boolean {
        if (cue.bitmap != null || cue.text.isNullOrBlank()) return false
        if (cue.line == Cue.DIMEN_UNSET) return true
        if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line >= 0.7f) return true
        if (cue.lineType == Cue.LINE_TYPE_NUMBER && cue.line < 0f && cue.line >= -2f) return true
        return false
    }
}
