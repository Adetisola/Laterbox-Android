package com.laterbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.BuildConfig
import com.laterbox.app.diagnostics.LocalLibraryMetrics
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.theme.LaterboxTheme
import com.laterbox.app.update.AppUpdateDialog
import com.laterbox.app.update.AppUpdateInfo
import java.util.Locale

@Composable
fun AboutPrivacySettingsScreen(
    isCheckingUpdate: Boolean,
    availableUpdateInfo: AppUpdateInfo?,
    onCheckForUpdates: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onPostponeUpdate: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    localMetrics: LocalLibraryMetrics? = null,
    onResetActivityCounters: (() -> Unit)? = null,
    analyticsEnabled: Boolean = true,
    onAnalyticsEnabledChange: ((Boolean) -> Unit)? = null
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSubpageHeader(
                title = "About Laterbox",
                onBackClick = onBackClick
            )

            Spacer(modifier = Modifier.height(spacing.xs))

            AboutLaterboxSection(
                isCheckingUpdate = isCheckingUpdate,
                availableUpdateInfo = availableUpdateInfo,
                onCheckForUpdates = onCheckForUpdates,
                onDismissUpdateDialog = onDismissUpdateDialog,
                onPostponeUpdate = onPostponeUpdate,
                localMetrics = localMetrics,
                onResetActivityCounters = onResetActivityCounters,
                analyticsEnabled = analyticsEnabled,
                onAnalyticsEnabledChange = onAnalyticsEnabledChange
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun AboutLaterboxSection(
    isCheckingUpdate: Boolean,
    availableUpdateInfo: AppUpdateInfo?,
    onCheckForUpdates: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    onPostponeUpdate: (String) -> Unit,
    localMetrics: LocalLibraryMetrics? = null,
    onResetActivityCounters: (() -> Unit)? = null,
    analyticsEnabled: Boolean = true,
    onAnalyticsEnabledChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    var showLicensesDialog by remember { mutableStateOf(false) }
    var showResetMetricsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_about_laterbox_section")
    ) {
        SettingsSectionHeader(title = "About Laterbox")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = "Laterbox v${BuildConfig.VERSION_NAME}",
                    style = typography.uiMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "A calm, local-first space for your saved links, notes, collections, and offline media. Your library stays on your device with no accounts, no ads, and no personal profiling.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )

                Spacer(modifier = Modifier.height(spacing.xs))

                Text(
                    text = "Privacy & Third-Party Services Notice",
                    style = typography.uiMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "• Saved Links & Previews: When you save a link, Laterbox loads page details directly from that website. For public social posts that block standard previews, a public preview lookup service may be used without sending any personal data.\n" +
                           "• Media & Streaming: Videos, audio, and subtitles you choose to play or download are fetched directly over your connection.\n" +
                           "• Optional AI Assistant: When you chat with Laterbox Assistant, your prompt and bounded library context are routed through Laterbox's backend to Google Gemini (with optional Google Search grounding for outside facts). Downloaded files and personal identifiers are never sent.\n" +
                           "• Anonymous Usage Analytics (Opt-Out Anytime): By default, Laterbox sends lightweight anonymous app-usage signals (random install/session IDs, app version, high-level screen/feature counts — never titles, prompts, search queries, filenames, or personal data) to help measure stability and active adoption.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )

                if (onAnalyticsEnabledChange != null) {
                    SettingsDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Anonymous Usage Analytics",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Shares anonymous screen/feature counts and session heartbeats only. Zero titles, queries, or personal info.",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = analyticsEnabled,
                            onCheckedChange = onAnalyticsEnabledChange,
                            modifier = Modifier.testTag("settings_analytics_toggle"),
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = colors.surfaceElevated,
                                checkedTrackColor = colors.accentPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(spacing.xs))

                LaterboxSecondaryButton(
                    text = if (isCheckingUpdate) "Checking for updates..." else "Check for updates",
                    enabled = !isCheckingUpdate,
                    onClick = onCheckForUpdates,
                    modifier = Modifier.fillMaxWidth()
                )

                LaterboxSecondaryButton(
                    text = "Open Source Licenses & Typography",
                    onClick = { showLicensesDialog = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (localMetrics != null) {
            Spacer(modifier = Modifier.height(spacing.md))

            SettingsSectionHeader(title = "Library & Activity Insights (On-Device)")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = "Computed locally on your device. No usage telemetry leaves your phone.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )

                    SettingsDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        MetricTile(
                            label = "Saved Library",
                            value = "${localMetrics.totalLinksSaved} links",
                            detail = "${localMetrics.inboxUnreadCount} unread • ${localMetrics.archivedCount} archived",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Reading Progress",
                            value = "${localMetrics.readCompletionPercent}% read",
                            detail = "${localMetrics.readerOpensCount} Reader opens",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        MetricTile(
                            label = "Offline Media",
                            value = "${localMetrics.mediaAssetsCount} files",
                            detail = formatStorageBytes(localMetrics.estimatedStorageBytes),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Reminders",
                            value = "${localMetrics.activeScheduledReminders} active",
                            detail = if (localMetrics.remindersDeliveredCount > 0) {
                                "${localMetrics.remindersOpenedCount}/${localMetrics.remindersDeliveredCount} opened (${localMetrics.reminderOpenRatePercent ?: 0}%)"
                            } else {
                                "No reminders delivered yet"
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val captureSummary = "Share ${localMetrics.shareCapturesCount} • Quick Tile ${localMetrics.quickTileCapturesCount} • In-App ${localMetrics.manualCapturesCount}"
                    val activationSuffix = localMetrics.timeToFirstSaveMs?.let { durationMs ->
                        " • First save in ${formatDurationBrief(durationMs)}"
                    }.orEmpty()
                    Text(
                        text = "Capture channels: $captureSummary$activationSuffix",
                        style = typography.metaText,
                        color = colors.textSecondary
                    )

                    if (onResetActivityCounters != null) {
                        LaterboxSecondaryButton(
                            text = "Reset Activity Counters",
                            onClick = { showResetMetricsDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    // App Update Dialog
    if (availableUpdateInfo != null) {
        AppUpdateDialog(
            updateInfo = availableUpdateInfo,
            onDismiss = onDismissUpdateDialog,
            onPostpone = onPostponeUpdate
        )
    }

    // Reset Activity Counters Dialog
    if (showResetMetricsDialog && onResetActivityCounters != null) {
        AlertDialog(
            onDismissRequest = { showResetMetricsDialog = false },
            containerColor = colors.surfaceElevated,
            title = {
                Text(
                    text = "Reset activity counters?",
                    style = typography.titleSmall,
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "This clears your on-device Reader open counts, reminder open counts, and capture channel counters. Your saved links, collections, and downloaded media are not affected.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
            },
            dismissButton = {
                TextButton(onClick = { showResetMetricsDialog = false }) {
                    Text(
                        text = "Cancel",
                        style = typography.uiMedium,
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetMetricsDialog = false
                        onResetActivityCounters()
                    }
                ) {
                    Text(
                        text = "Reset",
                        style = typography.uiMedium,
                        color = colors.accentPrimary
                    )
                }
            }
        )
    }

    // Licenses Dialog
    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            containerColor = colors.surfaceElevated,
            title = {
                Text(
                    text = "Open Source Licenses",
                    style = typography.titleSmall,
                    color = colors.textPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Text(
                        text = "Typography:\n• Newsreader — SIL Open Font License 1.1 (Production Type)\n• Inter — SIL Open Font License 1.1 (Rasmus Andersson)\n• JetBrains Mono — SIL Open Font License 1.1 (JetBrains)",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text = "Open Source Libraries:\n• AndroidX Jetpack & Compose (Apache 2.0)\n• AndroidX Media3 / ExoPlayer (Apache 2.0)\n• Room Persistence Library (Apache 2.0)\n• Coil Image Loading (Apache 2.0)\n• Jsoup HTML Parser (MIT License)\n• Kotlin & Coroutines (Apache 2.0)\n• yt-dlp (The Unlicense)\n• QuickJS (MIT License)\n• FFmpeg (LGPL / GPL)",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text(
                        text = "Close",
                        style = typography.uiMedium,
                        color = colors.accentPrimary
                    )
                }
            }
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = typography.metaText.copy(fontSize = 11.5.sp),
            color = colors.textSecondary
        )
        Text(
            text = value,
            style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary
        )
        Text(
            text = detail,
            style = typography.metaText.copy(fontSize = 11.5.sp),
            color = colors.textSecondary
        )
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB stored"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.getDefault(), "%.2f GB stored", mb / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%.1f MB stored", mb)
    }
}

private fun formatDurationBrief(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
    return when {
        totalSeconds < 60L -> "${totalSeconds}s"
        totalSeconds < 3600L -> "${totalSeconds / 60L}m ${totalSeconds % 60L}s"
        else -> "${totalSeconds / 3600L}h ${(totalSeconds % 3600L) / 60L}m"
    }
}
