package com.laterbox.app.movies.ui.player

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlin.math.roundToInt

private val TEXT_COLOR_OPTIONS = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Yellow" to 0xFFFFEB3B.toInt(),
    "Cyan" to 0xFF4DD0E1.toInt(),
    "Green" to 0xFFA5D6A7.toInt()
)

private val BG_COLOR_OPTIONS = listOf(
    "Black" to 0xFF000000.toInt(),
    "Charcoal" to 0xFF1F242D.toInt(),
    "Navy" to 0xFF0D1B2A.toInt()
)

private val EDGE_OPTIONS = listOf(
    0 to "None",
    1 to "Outline",
    2 to "Shadow"
)

@Composable
fun SubtitleStyleDialog(
    config: SubtitleStyleConfig,
    onConfigChange: (SubtitleStyleConfig) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        modifier = modifier.testTag("subtitle_style_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Subtitle Appearance",
                    style = typography.sheetTitle,
                    color = colors.textPrimary
                )
                TextButton(
                    onClick = { onConfigChange(SubtitleStyleConfig()) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("subtitle_style_reset_button")
                ) {
                    Text("Reset", style = typography.caption, color = colors.accentPrimary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SubtitleStyleEditorContent(
                    config = config,
                    onConfigChange = onConfigChange
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("subtitle_style_done_button")
            ) {
                Text("Done", color = colors.accentPrimary)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStyleEditorContent(
    config: SubtitleStyleConfig,
    onConfigChange: (SubtitleStyleConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Live Cinema Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 7f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1A2230),
                            Color(0xFF0E131B)
                        )
                    )
                )
                .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("subtitle_style_preview"),
            contentAlignment = Alignment.BottomCenter
        ) {
            val previewTextColor = if (config.useSystemCaptions) {
                Color.White
            } else {
                Color(config.textColorArgb)
            }
            val previewBgColor = if (config.useSystemCaptions) {
                Color.Black.copy(alpha = 0.67f)
            } else {
                Color(config.backgroundColorArgb).copy(alpha = config.backgroundOpacity.coerceIn(0f, 1f))
            }
            val previewSize = if (config.useSystemCaptions) 18.sp else config.fontSizeSp.coerceIn(12f, 36f).sp
            val previewShadow = when (if (config.useSystemCaptions) 2 else config.edgeType) {
                0 -> null
                1 -> Shadow(color = Color.Black, offset = Offset(0f, 0f), blurRadius = 6f)
                else -> Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 4f)
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = previewBgColor
            ) {
                Text(
                    text = if (config.useSystemCaptions) {
                        "System Caption Preview"
                    } else {
                        "Sample subtitle line.\nEasy to read over any scene."
                    },
                    style = typography.uiLabel.copy(
                        fontSize = previewSize,
                        lineHeight = previewSize * 1.25f,
                        fontWeight = FontWeight.Medium,
                        shadow = previewShadow
                    ),
                    color = previewTextColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // System Captions Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceSecondary)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "Use Android System Captions",
                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary
                )
                Text(
                    text = "Follow Android Accessibility caption preferences",
                    style = typography.metaText,
                    color = colors.textSecondary
                )
            }
            Switch(
                checked = config.useSystemCaptions,
                onCheckedChange = { enabled ->
                    onConfigChange(config.copy(useSystemCaptions = enabled))
                },
                modifier = Modifier.testTag("subtitle_use_system_switch")
            )
        }

        if (config.useSystemCaptions) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_CAPTIONING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                border = BorderStroke(1.dp, colors.borderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subtitle_open_android_settings_button")
            ) {
                Text("Open Android Caption Settings", color = colors.textPrimary)
            }
        }

        val customAlpha = if (config.useSystemCaptions) 0.45f else 1f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(customAlpha),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Text Size
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Text Size", style = typography.uiLabel, color = colors.textPrimary)
                    Text(
                        "${config.fontSizeSp.roundToInt()} sp",
                        style = typography.caption,
                        color = colors.accentPrimary
                    )
                }
                Slider(
                    value = config.fontSizeSp.coerceIn(14f, 34f),
                    onValueChange = { sizeSp ->
                        if (!config.useSystemCaptions) {
                            onConfigChange(config.copy(fontSizeSp = sizeSp))
                        }
                    },
                    valueRange = 14f..34f,
                    enabled = !config.useSystemCaptions,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accentPrimary,
                        activeTrackColor = colors.accentPrimary
                    ),
                    modifier = Modifier.testTag("subtitle_size_slider")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Small" to 16f, "Normal" to 20f, "Large" to 25f, "XL" to 30f).forEach { (label, spVal) ->
                        val selected = kotlin.math.abs(config.fontSizeSp - spVal) < 1.2f
                        StyleOptionChip(
                            label = label,
                            selected = selected,
                            enabled = !config.useSystemCaptions,
                            onClick = { onConfigChange(config.copy(fontSizeSp = spVal)) }
                        )
                    }
                }
            }

            // Text Color
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Text Color", style = typography.uiLabel, color = colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TEXT_COLOR_OPTIONS.forEach { (label, argb) ->
                        ColorSwatchCircle(
                            color = Color(argb),
                            label = label,
                            selected = config.textColorArgb == argb,
                            enabled = !config.useSystemCaptions,
                            onClick = { onConfigChange(config.copy(textColorArgb = argb)) },
                            modifier = Modifier.testTag("subtitle_text_color_$label")
                        )
                    }
                }
            }

            // Background Color + Opacity
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Background Opacity", style = typography.uiLabel, color = colors.textPrimary)
                    Text(
                        "${(config.backgroundOpacity.coerceIn(0f, 1f) * 100f).roundToInt()}%",
                        style = typography.caption,
                        color = colors.accentPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BG_COLOR_OPTIONS.forEach { (label, argb) ->
                        ColorSwatchCircle(
                            color = Color(argb),
                            label = label,
                            selected = config.backgroundColorArgb == argb,
                            enabled = !config.useSystemCaptions,
                            onClick = { onConfigChange(config.copy(backgroundColorArgb = argb)) },
                            modifier = Modifier.testTag("subtitle_bg_color_$label")
                        )
                    }
                }
                Slider(
                    value = config.backgroundOpacity.coerceIn(0f, 1f),
                    onValueChange = { opacity ->
                        if (!config.useSystemCaptions) {
                            onConfigChange(config.copy(backgroundOpacity = opacity))
                        }
                    },
                    valueRange = 0f..1f,
                    enabled = !config.useSystemCaptions,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accentPrimary,
                        activeTrackColor = colors.accentPrimary
                    ),
                    modifier = Modifier.testTag("subtitle_bg_opacity_slider")
                )
            }

            // Edge / Shadow Style
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Edge Style", style = typography.uiLabel, color = colors.textPrimary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EDGE_OPTIONS.forEach { (edgeType, label) ->
                        StyleOptionChip(
                            label = label,
                            selected = config.edgeType == edgeType,
                            enabled = !config.useSystemCaptions,
                            onClick = { onConfigChange(config.copy(edgeType = edgeType)) },
                            modifier = Modifier.testTag("subtitle_edge_$edgeType")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleOptionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.accentPrimary else colors.surfaceSecondary,
        border = BorderStroke(1.dp, if (selected) colors.accentPrimary else colors.borderSubtle),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) colors.onAccentPrimary else colors.textPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ColorSwatchCircle(
    color: Color,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val themeColors = LaterboxTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) themeColors.accentPrimary else themeColors.borderSubtle,
                    shape = CircleShape
                )
                .clickable(enabled = enabled, onClick = onClick)
        )
        Text(
            text = label,
            style = LaterboxTheme.typography.caption.copy(fontSize = 10.sp),
            color = themeColors.textSecondary
        )
    }
}
