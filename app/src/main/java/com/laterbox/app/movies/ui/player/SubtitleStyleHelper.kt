package com.laterbox.app.movies.ui.player

import android.graphics.Color as AndroidColor
import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.core.graphics.ColorUtils
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import kotlin.math.roundToInt

object SubtitleStyleHelper {
    @OptIn(UnstableApi::class)
    fun applyToSubtitleView(
        subtitleView: SubtitleView?,
        config: SubtitleStyleConfig
    ) {
        if (subtitleView == null) return

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
}
