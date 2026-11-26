package com.laterbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    onResetAnalyticsInstallId: (() -> Unit)? = null,
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
                onResetAnalyticsInstallId = onResetAnalyticsInstallId,
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
    onResetAnalyticsInstallId: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    var showLicensesDialog by remember { mutableStateOf(false) }
    var showResetMetricsDialog by remember { mutableStateOf(false) }
    var showPrivacyRightsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_about_laterbox_section")
    ) {
        SettingsSectionHeader(title = "About Laterbox")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Laterbox",
                        style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                }
                Text(
                    text = "Laterbox is your personal media library. Discover movies and TV, save what you love, and download for offline viewing. Built for a clean, fast experience.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )

                Spacer(modifier = Modifier.height(spacing.xs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    LaterboxSecondaryButton(
                        text = "Privacy Policy",
                        onClick = {
                            com.laterbox.app.ui.util.BrowserLauncher.openUrl(
                                context,
                                "https://laterbox-website.vercel.app/privacy",
                                colors,
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LaterboxSecondaryButton(
                        text = "Terms & Conditions",
                        onClick = {
                            com.laterbox.app.ui.util.BrowserLauncher.openUrl(
                                context,
                                "https://laterbox-website.vercel.app/terms",
                                colors,
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                LaterboxSecondaryButton(
                    text = "Privacy & Data Rights",
                    onClick = { showPrivacyRightsDialog = true },
                    modifier = Modifier.fillMaxWidth()
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
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("insights_metrics_row"),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        item {
                            MetricTile(
                                label = "Saved Items",
                                value = "${localMetrics.totalLinksSaved}",
                                detail = "${localMetrics.inboxUnreadCount} unread • ${localMetrics.archivedCount} archived"
                            )
                        }
                        item {
                            val collectionLabel = if (localMetrics.collectionsCount == 1) "Collection" else "Collections"
                            MetricTile(
                                label = "Collections",
                                value = "${localMetrics.collectionsCount}",
                                detail = "${localMetrics.collectionsCount} $collectionLabel"
                            )
                        }
                        item {
                            MetricTile(
                                label = "Offline Media",
                                value = "${localMetrics.mediaAssetsCount}",
                                detail = formatStorageBytes(localMetrics.estimatedStorageBytes)
                            )
                        }
                        item {
                            MetricTile(
                                label = "Reminders",
                                value = "${localMetrics.activeScheduledReminders}",
                                detail = if (localMetrics.remindersDeliveredCount > 0) {
                                    "${localMetrics.remindersOpenedCount}/${localMetrics.remindersDeliveredCount} opened (${localMetrics.reminderOpenRatePercent ?: 0}%)"
                                } else {
                                    "Active scheduled"
                                }
                            )
                        }
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

        Spacer(modifier = Modifier.navigationBarsPadding().height(40.dp))
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
                    text = "This clears your local reading and reminder counters without affecting saved links or downloads.",
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

    // Privacy & Data Rights Dialog
    if (showPrivacyRightsDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyRightsDialog = false },
            containerColor = colors.surfaceElevated,
            modifier = Modifier.testTag("settings_analytics_privacy_dialog"),
            title = {
                Text(
                    text = "Privacy & Data Rights",
                    style = typography.titleSmall,
                    color = colors.textPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Text(
                        text = "Built-in analytics measure stability and feature usage with a local install ID, never your saved links, queries, or prompts.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                    if (onResetAnalyticsInstallId != null) {
                        SettingsDivider()
                        LaterboxSecondaryButton(
                            text = "Reset Installation Identifier",
                            onClick = {
                                onResetAnalyticsInstallId()
                                com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                                    context = context,
                                    message = "Installation identifier reset.",
                                    variant = com.laterbox.app.ui.components.LaterboxToastVariant.SUCCESS
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyRightsDialog = false }) {
                    Text(
                        text = "Done",
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
    val spacing = LaterboxTheme.spacing
    val shape = RoundedCornerShape(spacing.radiusMedium)

    Column(
        modifier = modifier
            .widthIn(min = 148.dp)
            .clip(shape)
            .background(colors.surfaceSecondary.copy(alpha = 0.7f))
            .border(spacing.hairline, colors.borderSubtle, shape)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = typography.metaText.copy(fontSize = 11.5.sp),
            color = colors.textSecondary
        )
        Text(
            text = value,
            style = typography.displayLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
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
