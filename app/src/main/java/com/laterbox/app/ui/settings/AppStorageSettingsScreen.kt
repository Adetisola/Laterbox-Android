package com.laterbox.app.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.storage.AppStorageReport
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme

internal data class StorageBarFractions(
    val downloaded: Float,
    val cache: Float,
    val appData: Float
)

/**
 * Computes proportional segment weights for the 3 storage classifications (`Downloaded`, `Cache`,
 * `App data`), applying a minimal visual floor only for tiny non-zero categories without distorting
 * overall proportions.
 */
internal fun computeStorageBarFractions(
    downloadedBytes: Long,
    cacheBytes: Long,
    appDataBytes: Long,
    minVisibleFraction: Float = 0.03f
): StorageBarFractions {
    val d = downloadedBytes.coerceAtLeast(0L).toDouble()
    val c = cacheBytes.coerceAtLeast(0L).toDouble()
    val a = appDataBytes.coerceAtLeast(0L).toDouble()
    val total = d + c + a
    if (total <= 0.0) {
        return StorageBarFractions(0f, 0f, 0f)
    }

    val raw = doubleArrayOf(d / total, c / total, a / total)
    val adjusted = raw.copyOf()
    var deficit = 0.0
    var surplusPool = 0.0

    for (i in adjusted.indices) {
        if (adjusted[i] > 0.0 && adjusted[i] < minVisibleFraction) {
            deficit += (minVisibleFraction - adjusted[i])
            adjusted[i] = minVisibleFraction.toDouble()
        } else if (adjusted[i] > minVisibleFraction) {
            surplusPool += (adjusted[i] - minVisibleFraction)
        }
    }

    if (deficit > 0.0 && surplusPool > 0.0) {
        val ratio = (deficit / surplusPool).coerceAtMost(1.0)
        for (i in adjusted.indices) {
            if (raw[i] > minVisibleFraction) {
                val trim = (raw[i] - minVisibleFraction) * ratio
                adjusted[i] = (raw[i] - trim).coerceAtLeast(minVisibleFraction.toDouble())
            }
        }
    }

    val sum = adjusted.sum().takeIf { it > 0.0 } ?: 1.0
    return StorageBarFractions(
        downloaded = (adjusted[0] / sum).toFloat(),
        cache = (adjusted[1] / sum).toFloat(),
        appData = (adjusted[2] / sum).toFloat()
    )
}

private val CacheCategoryColor = Color(0xFFD9982B)

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
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    onManageDownloadsClick: (() -> Unit)? = null
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing

    LaunchedEffect(Unit) {
        if (report == null && !isLoading) {
            onRefresh()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("app_storage_settings_screen")
    ) {
        SettingsSubpageHeader(
            title = "App Storage",
            onBackClick = onBackClick
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(spacing.xs))

            AppStorageSectionContent(
                report = report,
                isLoading = isLoading,
                isClearingSafeCache = isClearingSafeCache,
                feedbackMessage = feedbackMessage,
                errorMessage = errorMessage,
                onRefresh = onRefresh,
                onClearSafeCache = onClearSafeCache,
                onRunMaintenance = onRunMaintenance,
                onDismissFeedback = onDismissFeedback,
                onManageDownloadsClick = onManageDownloadsClick
            )

            Spacer(modifier = Modifier.navigationBarsPadding().height(40.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    onManageDownloadsClick: (() -> Unit)? = null
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current

    fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

    val downloadedColor = colors.accentPrimary
    val cacheColor = CacheCategoryColor
    val appDataColor = colors.textTertiary.copy(alpha = 0.7f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        SettingsSectionHeader(title = "Storage Overview")

        if (feedbackMessage != null) {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = feedbackMessage,
                        style = typography.bodySecondary,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissFeedback) {
                        Text("OK", color = colors.accentPrimary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.xs))
        }

        if (errorMessage != null && report != null) {
            SettingsCard(modifier = Modifier.testTag("storage_error_banner")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        style = typography.metaText,
                        color = colors.statusDanger,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh, enabled = !isLoading) {
                        Text("Retry", color = colors.accentPrimary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.xs))
        }

        if (isLoading && report == null) {
            SettingsCard(modifier = Modifier.testTag("storage_loading_card")) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.accentPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Calculating storage usage…",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                }
            }
        } else if (report == null && errorMessage != null) {
            SettingsCard(modifier = Modifier.testTag("storage_error_card")) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Couldn't load storage breakdown",
                        style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary
                    )
                    Text(
                        text = errorMessage,
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isLoading,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("storage_retry_button")
                    ) {
                        Text(
                            text = "Retry",
                            style = typography.uiLabel,
                            color = colors.accentPrimary
                        )
                    }
                }
            }
        } else if (report == null) {
            SettingsCard(modifier = Modifier.testTag("storage_empty_prompt_card")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Storage breakdown ready to inspect",
                            style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Measure downloaded media, cache, and app data.",
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("storage_calculate_button")
                    ) {
                        Text(
                            text = "Inspect",
                            style = typography.uiLabel,
                            color = colors.accentPrimary
                        )
                    }
                }
            }
        } else {
            val fractions = computeStorageBarFractions(
                downloadedBytes = report.downloadedBytes,
                cacheBytes = report.cacheBytes,
                appDataBytes = report.appDataBytes
            )
            val animDownloaded by animateFloatAsState(
                targetValue = fractions.downloaded,
                animationSpec = tween(durationMillis = 360),
                label = "storage_bar_downloaded"
            )
            val animCache by animateFloatAsState(
                targetValue = fractions.cache,
                animationSpec = tween(durationMillis = 360),
                label = "storage_bar_cache"
            )
            val animAppData by animateFloatAsState(
                targetValue = fractions.appData,
                animationSpec = tween(durationMillis = 360),
                label = "storage_bar_app_data"
            )

            // 1. Visual Storage Overview Card (Total + Device Free + Stacked Bar + Legend)
            SettingsCard(modifier = Modifier.testTag("storage_overview_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Total App Storage",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                            Text(
                                text = formatBytes(report.totalUsedBytes),
                                style = typography.wordmark.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                                modifier = Modifier.testTag("storage_total_used")
                            )
                        }
                        IconButton(
                            onClick = onRefresh,
                            enabled = !isLoading
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Refresh),
                                contentDescription = "Refresh storage calculation",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp)),
                            color = colors.accentPrimary,
                            trackColor = colors.surfaceSecondary
                        )
                    }

                    // Horizontal Stacked Storage Indicator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surfaceSecondary)
                            .testTag("storage_stacked_bar"),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (animDownloaded > 0.001f) {
                            Box(
                                modifier = Modifier
                                    .weight(animDownloaded)
                                    .fillMaxHeight()
                                    .background(downloadedColor)
                            )
                        }
                        if (animCache > 0.001f) {
                            Box(
                                modifier = Modifier
                                    .weight(animCache)
                                    .fillMaxHeight()
                                    .background(cacheColor)
                            )
                        }
                        if (animAppData > 0.001f) {
                            Box(
                                modifier = Modifier
                                    .weight(animAppData)
                                    .fillMaxHeight()
                                    .background(appDataColor)
                            )
                        }
                    }

                    // Legend with matching colors & category values
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("storage_legend"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StorageLegendChip(
                            color = downloadedColor,
                            label = "Downloaded",
                            value = formatBytes(report.downloadedBytes)
                        )
                        StorageLegendChip(
                            color = cacheColor,
                            label = "Cache",
                            value = formatBytes(report.cacheBytes)
                        )
                        StorageLegendChip(
                            color = appDataColor,
                            label = "App data",
                            value = formatBytes(report.appDataBytes)
                        )
                    }

                    if (report.deviceTotalBytes > 0L) {
                        Text(
                            text = "Device free space: ${formatBytes(report.deviceFreeBytes)} available of ${formatBytes(report.deviceTotalBytes)}",
                            style = typography.caption,
                            color = colors.textSecondary,
                            modifier = Modifier.testTag("storage_device_free")
                        )
                    }

                    if (report.storagePressureHigh) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.accentPrimary.copy(alpha = 0.12f),
                            border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Available space is running low. Clearing cache can recover ${formatBytes(report.cacheBytes)} without removing your downloads.",
                                style = typography.metaText,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.xs))

            // 2. Prominent Category Cards: Downloaded & Cache
            SettingsCard(modifier = Modifier.testTag("storage_downloaded_card")) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base ->
                            if (onManageDownloadsClick != null) {
                                base.clickable { onManageDownloadsClick() }
                            } else {
                                base
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(downloadedColor.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Download),
                                contentDescription = null,
                                tint = downloadedColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "Downloaded",
                            style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = formatBytes(report.downloadedBytes),
                            style = typography.uiMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.textPrimary
                        )
                        if (onManageDownloadsClick != null) {
                            Icon(
                                painter = painterResource(LaterboxIcons.ChevronRight),
                                contentDescription = "Manage downloads",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.xs))

            SettingsCard(modifier = Modifier.testTag("storage_cache_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(cacheColor.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Refresh),
                                    contentDescription = null,
                                    tint = cacheColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "Cache",
                                style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary
                            )
                        }

                        Text(
                            text = formatBytes(report.cacheBytes),
                            style = typography.uiMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.textPrimary
                        )
                    }

                    Button(
                        onClick = onClearSafeCache,
                        enabled = !isClearingSafeCache && report.safeClearableBytes > 0L,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentPrimary,
                            contentColor = colors.onAccentPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
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
                            Text("Clear Cache (${formatBytes(report.safeClearableBytes)})")
                        }
                    }

                    OutlinedButton(
                        onClick = onRunMaintenance,
                        enabled = !isClearingSafeCache,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("run_storage_maintenance_button")
                    ) {
                        Text(
                            text = "Sweep Orphan Temp Files",
                            style = typography.uiLabel,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.xs))

            // 3. Quieter Informational Row: App data
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.base)
                    .clip(RoundedCornerShape(spacing.radiusMedium))
                    .background(colors.surfaceSecondary.copy(alpha = 0.65f))
                    .padding(horizontal = spacing.md, vertical = 12.dp)
                    .testTag("storage_app_data_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(appDataColor)
                    )
                    Text(
                        text = "App data",
                        style = typography.uiLabel,
                        color = colors.textSecondary
                    )
                }
                Text(
                    text = formatBytes(report.appDataBytes),
                    style = typography.uiLabel.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun StorageLegendChip(
    color: Color,
    label: String,
    value: String
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = typography.caption,
            color = colors.textSecondary
        )
        Text(
            text = value,
            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary
        )
    }
}
