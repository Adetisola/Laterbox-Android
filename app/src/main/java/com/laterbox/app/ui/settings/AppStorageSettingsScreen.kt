package com.laterbox.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.laterbox.app.storage.AppStorageReport
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun AppStorageSettingsScreen(
    report: AppStorageReport?,
    isLoading: Boolean,
    isClearingSafeCache: Boolean,
    feedbackMessage: String?,
    onRefresh: () -> Unit,
    onClearSafeCache: () -> Unit,
    onRunMaintenance: () -> Unit,
    onDismissFeedback: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("app_storage_settings_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSubpageHeader(
                title = "Data & Storage",
                onBackClick = onBackClick
            )

            AppStorageSectionContent(
                report = report,
                isLoading = isLoading,
                isClearingSafeCache = isClearingSafeCache,
                feedbackMessage = feedbackMessage,
                onRefresh = onRefresh,
                onClearSafeCache = onClearSafeCache,
                onRunMaintenance = onRunMaintenance,
                onDismissFeedback = onDismissFeedback
            )

            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
fun AppStorageSectionContent(
    report: AppStorageReport?,
    isLoading: Boolean,
    isClearingSafeCache: Boolean,
    feedbackMessage: String?,
    onRefresh: () -> Unit,
    onClearSafeCache: () -> Unit,
    onRunMaintenance: () -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        onRefresh()
    }

    fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

    Column(modifier = modifier.fillMaxWidth()) {
        feedbackMessage?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.accentContainer,
                border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.base, vertical = spacing.xs)
                    .testTag("app_storage_feedback_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = msg,
                        style = typography.metaText.copy(fontWeight = FontWeight.Medium),
                        color = colors.accentPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissFeedback) {
                        Text("Dismiss", color = colors.accentPrimary)
                    }
                }
            }
        }

        if (isLoading && report == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = colors.accentPrimary)
            }
        } else if (report != null) {
            SettingsSectionHeader(title = "Storage Overview")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Total Laterbox App Data",
                            style = typography.uiMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = formatBytes(report.totalUsedBytes),
                            style = typography.uiMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.accentPrimary,
                            modifier = Modifier.testTag("app_storage_total_used")
                        )
                    }
                    if (report.deviceTotalBytes > 0L) {
                        Text(
                            text = "Device free space: ${formatBytes(report.deviceFreeBytes)} of ${formatBytes(report.deviceTotalBytes)}",
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                    }
                    if (report.storagePressureHigh) {
                        Text(
                            text = "Storage is getting tight. Clearing safe caches can free ${formatBytes(report.safeClearableBytes)} without affecting your downloads.",
                            style = typography.metaText,
                            color = colors.statusWarning
                        )
                    }
                    Text(
                        text = "Actual device space used by Laterbox files, caches, and saved records.",
                        style = typography.caption,
                        color = colors.textTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            SettingsSectionHeader(title = "Storage Breakdown")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StorageBreakdownRow(
                        label = "Downloaded Media & Subtitles",
                        detail = "Offline movies, episodes, videos & subtitles",
                        value = formatBytes(report.downloadedMediaBytes)
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                    StorageBreakdownRow(
                        label = "In-Progress Downloads",
                        detail = "Resumable partial downloads stored safely",
                        value = formatBytes(report.inProgressDownloadsBytes)
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                    StorageBreakdownRow(
                        label = "Stream Playback Cache",
                        detail = "Disposable buffered stream segments",
                        value = formatBytes(report.streamPlaybackCacheBytes)
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                    StorageBreakdownRow(
                        label = "Image & Poster Cache",
                        detail = "Disposable artwork thumbnails",
                        value = formatBytes(report.imageCacheBytes)
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                    StorageBreakdownRow(
                        label = "Saved Library & Records",
                        detail = "Saved links, collections & watch history",
                        value = formatBytes(report.databaseAndMetadataBytes)
                    )
                    if (report.extractorRuntimesBytes > 0L) {
                        HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                        StorageBreakdownRow(
                            label = "Media Download Tools",
                            detail = "On-device media download and processing components",
                            value = formatBytes(report.extractorRuntimesBytes)
                        )
                    }
                    if (report.tempAndOtherCacheBytes > 0L) {
                        HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                        StorageBreakdownRow(
                            label = "Temp & Update Cache",
                            detail = "Disposable temporary files",
                            value = formatBytes(report.tempAndOtherCacheBytes)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            SettingsSectionHeader(title = "Safe Cache Cleanup")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Disposable Caches",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Clears poster cache, buffered stream segments, and temp files. Your downloaded media, active downloads, watchlist, and viewing history are never touched.",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                        }
                        Text(
                            text = formatBytes(report.safeClearableBytes),
                            style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }

                    Button(
                        onClick = onClearSafeCache,
                        enabled = !isClearingSafeCache,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clear_safe_cache_button")
                    ) {
                        if (isClearingSafeCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.onAccentPrimary
                            )
                        } else {
                            Text("Clear Safe Cache (${formatBytes(report.safeClearableBytes)})")
                        }
                    }

                    OutlinedButton(
                        onClick = onRunMaintenance,
                        enabled = !isClearingSafeCache,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("run_storage_maintenance_button")
                    ) {
                        Text("Sweep Orphan Temp Files", color = colors.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageBreakdownRow(
    label: String,
    detail: String,
    value: String
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = typography.uiLabel,
                color = colors.textPrimary
            )
            Text(
                text = detail,
                style = typography.caption,
                color = colors.textSecondary
            )
        }
        Text(
            text = value,
            style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary
        )
    }
}
