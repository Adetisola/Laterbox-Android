package com.laterbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.laterbox.app.data.preferences.SubtitleStyleConfig
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.movies.ui.player.SeekSensitivityTier
import com.laterbox.app.movies.ui.player.SubtitleStyleDialog
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlin.math.roundToInt

private val DOUBLE_TAP_SECONDS_OPTIONS = listOf(5, 10, 15, 30)
private val QUALITY_HEIGHT_OPTIONS = listOf(480, 720, 1080, 1440)
private val AUTOPLAY_COUNTDOWN_OPTIONS = listOf(5, 10, 15)

@Composable
fun MoviesSettingsScreen(
    preferences: UserPreferences,
    onDoubleTapSeekSecondsChange: (Int) -> Unit,
    onSwipeToSeekEnabledChange: (Boolean) -> Unit,
    onSwipeSeekSensitivityChange: (Float) -> Unit,
    onBrightnessGestureEnabledChange: (Boolean) -> Unit,
    onVolumeGestureEnabledChange: (Boolean) -> Unit,
    onAutoplayNextEpisodeChange: (Boolean) -> Unit,
    onAutoplayCountdownSecondsChange: (Int) -> Unit,
    onDefaultQualityHeightChange: (Int) -> Unit,
    onSubtitleStyleChange: (SubtitleStyleConfig) -> Unit,
    onOpenViewingHistory: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    var showSubtitleStyleDialog by remember { mutableStateOf(false) }

    val activeSubtitleConfig = remember(preferences) { preferences.toSubtitleStyleConfig() }
    val activeSensitivityTier = remember(preferences.moviesSwipeSeekSensitivity) {
        SeekSensitivityTier.fromPreference(preferences.moviesSwipeSeekSensitivity)
    }

    if (showSubtitleStyleDialog) {
        SubtitleStyleDialog(
            config = activeSubtitleConfig,
            onConfigChange = onSubtitleStyleChange,
            onDismiss = { showSubtitleStyleDialog = false }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("movies_settings_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSubpageHeader(
                title = "Movies & TV",
                onBackClick = onBackClick
            )

            Spacer(modifier = Modifier.height(spacing.xs))

            // History
            SettingsSectionHeader(title = "Activity")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(spacing.radiusSmall))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Open viewing history",
                            onClick = onOpenViewingHistory
                        )
                        .padding(vertical = 4.dp)
                        .testTag("movies_setting_viewing_history_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(LaterboxIcons.Clock),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.accentPrimary
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Text(
                            text = "Viewing History",
                            style = typography.uiMedium,
                            color = colors.textPrimary
                        )
                    }
                    Icon(
                        painter = painterResource(LaterboxIcons.ChevronRight),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = colors.textSecondary.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            // Player Gestures
            SettingsSectionHeader(title = "Player Gestures")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    // Double-tap seek interval
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = "Double-Tap Seek Interval",
                            style = typography.uiMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Applies to double-tap zones and skip buttons in the video player.",
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            DOUBLE_TAP_SECONDS_OPTIONS.forEach { sec ->
                                ThemeOptionChip(
                                    label = "${sec}s",
                                    isSelected = preferences.moviesDoubleTapSeekSeconds == sec,
                                    onClick = { onDoubleTapSeekSecondsChange(sec) },
                                    modifier = Modifier.testTag("movies_setting_double_tap_$sec")
                                )
                            }
                        }
                    }

                    SettingsDivider()

                    // Swipe to seek + default sensitivity
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                                Text(
                                    text = "Swipe to Seek",
                                    style = typography.uiMedium,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "Drag horizontally to seek video.",
                                    style = typography.metaText,
                                    color = colors.textSecondary
                                )
                            }
                            Switch(
                                checked = preferences.moviesSwipeToSeekEnabled,
                                onCheckedChange = onSwipeToSeekEnabledChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.surfaceElevated,
                                    checkedTrackColor = colors.accentPrimary
                                ),
                                modifier = Modifier.testTag("movies_setting_swipe_seek_switch")
                            )
                        }

                        if (preferences.moviesSwipeToSeekEnabled) {
                            Text(
                                text = "Default Scrub Speed",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                SeekSensitivityTier.entries.forEach { tier ->
                                    ThemeOptionChip(
                                        label = tier.displayLabel,
                                        isSelected = activeSensitivityTier == tier,
                                        onClick = { onSwipeSeekSensitivityChange(tier.multiplier) },
                                        modifier = Modifier.testTag("movies_setting_seek_speed_${tier.name}")
                                    )
                                }
                            }
                        }
                    }

                    SettingsDivider()

                    // Left brightness gesture
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                            Text(
                                text = "Brightness Gesture (Left Side)",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Adjusts player brightness during playback.",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                        }
                        Switch(
                            checked = preferences.moviesBrightnessGestureEnabled,
                            onCheckedChange = onBrightnessGestureEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.surfaceElevated,
                                checkedTrackColor = colors.accentPrimary
                            ),
                            modifier = Modifier.testTag("movies_setting_brightness_switch")
                        )
                    }

                    SettingsDivider()

                    // Right volume & boost gesture
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                            Text(
                                text = "Volume & Boost Gesture (Right Side)",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Swipe vertically on the right side for 0–100% volume and up to 200% boost.",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                        }
                        Switch(
                            checked = preferences.moviesVolumeGestureEnabled,
                            onCheckedChange = onVolumeGestureEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.surfaceElevated,
                                checkedTrackColor = colors.accentPrimary
                            ),
                            modifier = Modifier.testTag("movies_setting_volume_switch")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            // Playback & Subtitles
            SettingsSectionHeader(title = "Playback & Subtitles")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    // Default stream quality
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = "Default Stream Quality",
                            style = typography.uiMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Automatically picks the closest available resolution when playback starts.",
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            QUALITY_HEIGHT_OPTIONS.forEach { q ->
                                ThemeOptionChip(
                                    label = "${q}p",
                                    isSelected = preferences.moviesDefaultQualityHeight == q,
                                    onClick = { onDefaultQualityHeightChange(q) },
                                    modifier = Modifier.testTag("movies_setting_quality_$q")
                                )
                            }
                        }
                    }

                    SettingsDivider()

                    // Autoplay next episode + countdown
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                                Text(
                                    text = "Autoplay Next Episode",
                                    style = typography.uiMedium,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "Shows an Up Next preview card with countdown at the end of an episode.",
                                    style = typography.metaText,
                                    color = colors.textSecondary
                                )
                            }
                            Switch(
                                checked = preferences.moviesAutoplayNextEpisode,
                                onCheckedChange = onAutoplayNextEpisodeChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.surfaceElevated,
                                    checkedTrackColor = colors.accentPrimary
                                ),
                                modifier = Modifier.testTag("movies_setting_autoplay_switch")
                            )
                        }

                        if (preferences.moviesAutoplayNextEpisode) {
                            Text(
                                text = "Next Episode Countdown",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                AUTOPLAY_COUNTDOWN_OPTIONS.forEach { sec ->
                                    ThemeOptionChip(
                                        label = "${sec}s",
                                        isSelected = preferences.moviesAutoplayCountdownSeconds == sec,
                                        onClick = { onAutoplayCountdownSecondsChange(sec) },
                                        modifier = Modifier.testTag("movies_setting_countdown_$sec")
                                    )
                                }
                            }
                        }
                    }

                    SettingsDivider()

                    // Subtitle appearance
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(spacing.radiusSmall))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Customize subtitle appearance",
                                onClick = { showSubtitleStyleDialog = true }
                            )
                            .padding(vertical = 4.dp)
                            .testTag("movies_setting_subtitle_style_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                            Text(
                                text = "Subtitle Appearance",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            val subSummary = if (activeSubtitleConfig.useSystemCaptions) {
                                "Using system captions"
                            } else {
                                "Size ${activeSubtitleConfig.fontSizeSp.roundToInt()} · ${(activeSubtitleConfig.backgroundOpacity * 100f).roundToInt()}% background"
                            }
                            Text(
                                text = subSummary,
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                        }
                        Icon(
                            painter = painterResource(LaterboxIcons.ChevronRight),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.textSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            Spacer(modifier = Modifier.navigationBarsPadding().height(40.dp))
        }
    }
}
