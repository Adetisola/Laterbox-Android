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
    onResetActivityCounters: (() -> Unit)? = null
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    var showLicensesDialog by remember { mutableStateOf(false) }
    var showResetMetricsDialog by remember { mutableStateOf(false) }

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
                title = "About & Privacy",
                onBackClick = onBackClick
            )

            Spacer(modifier = Modifier.height(spacing.xs))

            SettingsSectionHeader(title = "About Laterbox")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = "Laterbox v${BuildConfig.VERSION_NAME}",
                        style = typography.uiMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "A calm, local-first reading inbox. Your links and notes remain entirely on your device. No user accounts, no telemetry, and no third-party tracking.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )

                    Spacer(modifier = Modifier.height(spacing.xs))

                    Text(
                        text = "Third-Party Services & Privacy Notice",
                        style = typography.uiMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "• On-Device Media Extraction: Media inspection and downloads run locally on your device via yt-dlp, QuickJS, and FFmpeg without intermediate proxy servers.\n" +
                               "• Public X/Twitter Previews: api.fxtwitter.com (FxEmbed) is contacted strictly to resolve public X post metadata and media when saved, without accounts or tracking.\n" +
                               "• Remote AI Assistant & Smart Reminders: Your chat prompts, relevant saved link metadata (titles, URLs, notes, and sanitized app health/activity summaries — never private API keys or full storage paths), and Smart Digest snippets are sent remotely to Google AI Studio (Gemini) only after you enable and accept the AI notice. Chat history is stored locally on your device.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )

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
                            text = "Computed purely on your device from Room and local preferences. Zero analytics or telemetry leave your phone.",
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

            Spacer(modifier = Modifier.height(80.dp))
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
                            text = "Libraries & Media Engines:\n• yt-dlp (The Unlicense)\n• QuickJS (MIT License)\n• FFmpeg (LGPL / GPL)\n• AndroidX Jetpack & Jetpack Compose (Apache 2.0)\n• Room SQLite Database (Apache 2.0)\n• Coil Image Loading (Apache 2.0)\n• Jsoup HTML Parser (MIT License)\n• Kotlin & Coroutines (Apache 2.0)",
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
