package com.laterbox.app.transcript.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.ExportFormat
import com.laterbox.app.transcript.domain.SingleExportOutcome
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptTargetPreference
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.export.TranscriptFormatting
import com.laterbox.app.ui.components.LaterboxBadge
import com.laterbox.app.ui.components.LaterboxBadgeVariant
import com.laterbox.app.ui.components.LaterboxBanner
import com.laterbox.app.ui.components.LaterboxCard
import com.laterbox.app.ui.components.LaterboxCardVariant
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxPrimaryButton
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.components.LaterboxShapeTokens
import com.laterbox.app.ui.components.LaterboxTextButton
import com.laterbox.app.ui.theme.LaterboxTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeTranscriptSheet(
    uiState: YouTubeTranscriptUiState,
    onChooseTarget: (TranscriptTargetPreference) -> Unit,
    onCancelExtraction: () -> Unit,
    onRetryExtraction: () -> Unit,
    onToggleFormat: (ExportFormat) -> Unit,
    onSetSelectAllFormats: (Boolean) -> Unit,
    hasWritableSafFolder: (Context) -> Boolean,
    onExportToSafTree: (Uri, String?, Boolean) -> Unit,
    onExportToDownloads: () -> Unit,
    onDestinationPickerCancelled: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) {
            onDestinationPickerCancelled()
        } else {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (_: Throwable) {}
            val dirName = try {
                DocumentFile.fromTreeUri(context, treeUri)?.name
            } catch (_: Throwable) {
                null
            }
            onExportToSafTree(treeUri, dirName, true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = LaterboxTheme.shapes.Sheet,
        containerColor = colors.surfaceElevated,
        contentColor = colors.textPrimary,
        tonalElevation = LaterboxTheme.elevations.sheet,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = spacing.sm)
                    .size(width = spacing.dragHandleWidth, height = spacing.dragHandleHeight)
                    .clip(RoundedCornerShape(spacing.radiusFull))
                    .background(colors.borderSubtle)
            )
        }
    ) {
        YouTubeTranscriptSheetContent(
            uiState = uiState,
            onChooseTarget = onChooseTarget,
            onCancelExtraction = onCancelExtraction,
            onRetryExtraction = onRetryExtraction,
            onToggleFormat = onToggleFormat,
            onSetSelectAllFormats = onSetSelectAllFormats,
            hasWritableSafFolder = hasWritableSafFolder,
            onLaunchFolderPicker = { folderPickerLauncher.launch(null) },
            onExportToSafTree = onExportToSafTree,
            onExportToDownloads = onExportToDownloads,
            onDismiss = onDismiss
        )
    }
}

@Composable
internal fun YouTubeTranscriptSheetContent(
    uiState: YouTubeTranscriptUiState,
    onChooseTarget: (TranscriptTargetPreference) -> Unit,
    onCancelExtraction: () -> Unit,
    onRetryExtraction: () -> Unit,
    onToggleFormat: (ExportFormat) -> Unit,
    onSetSelectAllFormats: (Boolean) -> Unit,
    hasWritableSafFolder: (Context) -> Boolean,
    onLaunchFolderPicker: () -> Unit,
    onExportToSafTree: (Uri, String?, Boolean) -> Unit,
    onExportToDownloads: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.base)
            .padding(bottom = spacing.xl)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YouTube Transcripts",
                    style = typography.sheetTitle,
                    color = colors.textPrimary
                )
                if (uiState.sourceUrl.isNotBlank()) {
                    Text(
                        text = uiState.sourceUrl,
                        style = typography.metaText,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            LaterboxIconButton(
                iconRes = LaterboxIcons.Close,
                contentDescription = "Close transcript sheet",
                tint = colors.textPrimary,
                onClick = onDismiss
            )
        }

        // Step 1: Target Disambiguation for video URL inside a playlist
        if (uiState.needsTargetChoice) {
            LaterboxCard(variant = LaterboxCardVariant.SECONDARY) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Text(
                        text = "This link includes both a video and a playlist",
                        style = typography.cardTitle,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Choose whether to extract captions from this single video or from every video in the playlist.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        LaterboxSecondaryButton(
                            text = "This Video",
                            onClick = { onChooseTarget(TranscriptTargetPreference.VIDEO) },
                            modifier = Modifier.weight(1f)
                        )
                        LaterboxPrimaryButton(
                            text = "Entire Playlist",
                            onClick = { onChooseTarget(TranscriptTargetPreference.PLAYLIST) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Step 2: Live Extraction Progress
        if (uiState.isExtracting) {
            ExtractionProgressCard(
                uiState = uiState,
                onCancel = onCancelExtraction
            )
        }

        // Error Banner (when no document or failed overall)
        if (!uiState.isExtracting && !uiState.errorMessage.isNullOrBlank()) {
            LaterboxBanner(
                text = uiState.errorMessage,
                isWarning = true,
                actionText = "Retry",
                onActionClick = onRetryExtraction
            )
        }

        // Cancelled notice (preserving partial results)
        if (!uiState.isExtracting && uiState.wasCancelled && uiState.document != null) {
            val doc = uiState.document
            LaterboxBanner(
                text = "Extraction cancelled after ${doc.entries.size} of ${doc.videosFound} videos. Partial results are preserved below.",
                isWarning = false,
                actionText = "Retry Full",
                onActionClick = onRetryExtraction
            )
        }

        // Step 3: Extracted Document Summary, Entries, and Multi-Format Export
        val document = uiState.document
        if (document != null && !uiState.isExtracting) {
            TranscriptSummaryCard(document = document)

            if (!document.hasAnySuccessfulTranscript) {
                LaterboxBanner(
                    text = if (document.isPlaylist) {
                        "No accessible captions were found for any of the ${document.videosFound} playlist videos."
                    } else {
                        document.entries.firstOrNull()?.statusMessage
                            ?: "No accessible captions were found for this video."
                    },
                    isWarning = true,
                    actionText = "Retry",
                    onActionClick = onRetryExtraction
                )
            }

            // Multi-format Export Selection (available whenever >= 1 transcript succeeded)
            if (document.hasAnySuccessfulTranscript) {
                MultiFormatExportCard(
                    uiState = uiState,
                    hasWritableSafFolder = hasWritableSafFolder(context),
                    onToggleFormat = onToggleFormat,
                    onSetSelectAllFormats = onSetSelectAllFormats,
                    onExportClick = {
                        val savedUriStr = uiState.savedExportFolderUri
                        if (savedUriStr != null && hasWritableSafFolder(context)) {
                            onExportToSafTree(
                                Uri.parse(savedUriStr),
                                uiState.savedExportFolderName,
                                false
                            )
                        } else {
                            onLaunchFolderPicker()
                        }
                    },
                    onChangeFolderClick = onLaunchFolderPicker,
                    onExportToDownloadsClick = onExportToDownloads
                )
            }

            // Export Results
            val exportResult = uiState.exportResult
            if (exportResult != null) {
                ExportResultSection(
                    outcomes = exportResult.outcomes,
                    allSucceeded = exportResult.allSucceeded,
                    anySucceeded = exportResult.anySucceeded,
                    anyFailed = exportResult.anyFailed,
                    onOpenOutcome = { outcome -> openExportedFile(context, outcome) },
                    onShareOutcome = { outcome -> shareExportedFile(context, outcome) }
                )
            }

            // Individual Video Entries Preview
            TranscriptEntriesSection(document = document)
        }
    }
}

@Composable
private fun ExtractionProgressCard(
    uiState: YouTubeTranscriptUiState,
    onCancel: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val progress = uiState.progress

    LaterboxCard(variant = LaterboxCardVariant.SECONDARY) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = progress.message ?: "Extracting transcripts...",
                    style = typography.uiMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                LaterboxTextButton(
                    text = "Cancel",
                    onClick = onCancel,
                    color = colors.statusDanger
                )
            }

            if (progress.totalVideos > 1) {
                val fraction = (progress.currentVideoIndex.toFloat() / progress.totalVideos.toFloat())
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accentPrimary,
                    trackColor = colors.borderSubtle
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accentPrimary,
                    trackColor = colors.borderSubtle
                )
            }

            if (!progress.currentVideoTitle.isNullOrBlank()) {
                Text(
                    text = progress.currentVideoTitle,
                    style = typography.bodySecondary,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (progress.totalVideos > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    LaterboxBadge(
                        text = "${progress.successfulCount} extracted",
                        variant = LaterboxBadgeVariant.SUCCESS
                    )
                    if (progress.noCaptionsCount > 0) {
                        LaterboxBadge(
                            text = "${progress.noCaptionsCount} no captions",
                            variant = LaterboxBadgeVariant.WARNING
                        )
                    }
                    if (progress.failedCount > 0) {
                        LaterboxBadge(
                            text = "${progress.failedCount} failed",
                            variant = LaterboxBadgeVariant.DANGER
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptSummaryCard(document: TranscriptDocument) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    LaterboxCard(variant = LaterboxCardVariant.DEFAULT) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                LaterboxBadge(
                    text = if (document.isPlaylist) "Playlist" else "Single Video",
                    variant = LaterboxBadgeVariant.ACCENT
                )
                Text(
                    text = TranscriptFormatting.formatDateIso(document.extractedAtMillis),
                    style = typography.metaText,
                    color = colors.textTertiary
                )
            }

            Text(
                text = document.title,
                style = typography.cardTitle,
                color = colors.textPrimary
            )

            if (document.isPlaylist) {
                Spacer(modifier = Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "• ${document.videosFound} videos found.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "• ${document.successfulCount} transcripts extracted successfully.",
                        style = typography.bodySecondary,
                        color = if (document.successfulCount > 0) colors.statusSuccess else colors.textSecondary
                    )
                    if (document.noCaptionsCount > 0) {
                        Text(
                            text = "• ${document.noCaptionsCount} videos had no available captions.",
                            style = typography.bodySecondary,
                            color = colors.statusWarning
                        )
                    }
                    if (document.inaccessibleCount > 0) {
                        Text(
                            text = "• ${document.inaccessibleCount} videos could not be accessed.",
                            style = typography.bodySecondary,
                            color = colors.statusDanger
                        )
                    }
                }
            } else {
                val entry = document.entries.firstOrNull()
                if (entry != null) {
                    val metaParts = buildList {
                        entry.channel?.takeIf { it.isNotBlank() }?.let { add(it) }
                        TranscriptFormatting.formatDuration(entry.durationSeconds)?.let { add(it) }
                        entry.captionLanguage?.takeIf { it.isNotBlank() }?.let { add(it) }
                        if (entry.captionType != CaptionType.UNKNOWN) add(entry.captionType.displayLabel)
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiFormatExportCard(
    uiState: YouTubeTranscriptUiState,
    hasWritableSafFolder: Boolean,
    onToggleFormat: (ExportFormat) -> Unit,
    onSetSelectAllFormats: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onChangeFolderClick: () -> Unit,
    onExportToDownloadsClick: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val allSelected = uiState.selectedFormats.size == ExportFormat.entries.size

    LaterboxCard(variant = LaterboxCardVariant.DEFAULT) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Export Formats",
                    style = typography.cardTitle,
                    color = colors.textPrimary
                )
                LaterboxTextButton(
                    text = if (allSelected) "Clear All" else "Select All",
                    onClick = { onSetSelectAllFormats(!allSelected) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                ExportFormat.entries.forEach { format ->
                    val checked = format in uiState.selectedFormats
                    FormatCheckboxRow(
                        format = format,
                        checked = checked,
                        onToggle = { onToggleFormat(format) }
                    )
                }
            }

            if (uiState.selectedFormats.isEmpty()) {
                Text(
                    text = "Select at least one export format above to enable export.",
                    style = typography.metaText,
                    color = colors.statusDanger
                )
            }

            if (!uiState.exportStatusNotice.isNullOrBlank()) {
                LaterboxBanner(
                    text = uiState.exportStatusNotice,
                    isWarning = false
                )
            }

            // Current SAF destination indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LaterboxShapeTokens.Card)
                    .background(colors.surfaceSecondary)
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Destination folder",
                        style = typography.caption,
                        color = colors.textTertiary
                    )
                    Text(
                        text = if (hasWritableSafFolder) {
                            uiState.savedExportFolderName ?: "Selected folder"
                        } else {
                            "Choose folder on export"
                        },
                        style = typography.bodySecondary,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                LaterboxTextButton(
                    text = if (hasWritableSafFolder) "Change" else "Choose",
                    onClick = onChangeFolderClick
                )
            }

            LaterboxPrimaryButton(
                text = if (uiState.selectedFormats.size > 1) {
                    "Export Selected Formats (${uiState.selectedFormats.size})"
                } else {
                    "Export Selected Formats"
                },
                onClick = onExportClick,
                enabled = uiState.canExport,
                isLoading = uiState.isExporting,
                leadingIcon = LaterboxIcons.Download,
                modifier = Modifier.fillMaxWidth()
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                LaterboxSecondaryButton(
                    text = "Quick Save to Downloads/Laterbox",
                    onClick = onExportToDownloadsClick,
                    enabled = uiState.canExport,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FormatCheckboxRow(
    format: ExportFormat,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val borderColor = if (checked) colors.accentPrimary else colors.borderSubtle
    val bgColor = if (checked) colors.accentContainer.copy(alpha = 0.45f) else colors.surfaceSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LaterboxShapeTokens.Card)
            .background(bgColor)
            .border(BorderStroke(spacing.hairline, borderColor), LaterboxShapeTokens.Card)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accentPrimary,
                uncheckedColor = colors.textSecondary,
                checkmarkColor = colors.onAccentPrimary
            )
        )
        Spacer(modifier = Modifier.width(spacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = format.displayName,
                style = typography.uiMedium,
                color = colors.textPrimary
            )
            Text(
                text = format.description,
                style = typography.metaText,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun ExportResultSection(
    outcomes: List<SingleExportOutcome>,
    allSucceeded: Boolean,
    anySucceeded: Boolean,
    anyFailed: Boolean,
    onOpenOutcome: (SingleExportOutcome) -> Unit,
    onShareOutcome: (SingleExportOutcome) -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        val summaryText = when {
            allSucceeded -> "Exported ${outcomes.size} file(s) successfully."
            anySucceeded && anyFailed -> "Partial export: some files failed to save."
            else -> "Export failed: no files could be saved."
        }
        LaterboxBanner(
            text = summaryText,
            isWarning = anyFailed,
            isSuccess = allSucceeded
        )

        outcomes.forEach { outcome ->
            LaterboxCard(variant = LaterboxCardVariant.SECONDARY) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            LaterboxBadge(
                                text = outcome.format.extension.uppercase(),
                                variant = if (outcome.success) LaterboxBadgeVariant.SUCCESS else LaterboxBadgeVariant.DANGER,
                                isMonospace = true
                            )
                            Text(
                                text = outcome.filename,
                                style = typography.uiMedium.copy(fontSize = 13.sp),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (outcome.success && !outcome.displayLocation.isNullOrBlank()) {
                            Text(
                                text = outcome.displayLocation,
                                style = typography.caption,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (!outcome.success) {
                            Text(
                                text = outcome.errorMessage ?: "Export failed",
                                style = typography.caption,
                                color = colors.statusDanger
                            )
                        }
                    }

                    if (outcome.success && !outcome.uri.isNullOrBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            LaterboxTextButton(
                                text = "Open",
                                onClick = { onOpenOutcome(outcome) }
                            )
                            LaterboxTextButton(
                                text = "Share",
                                onClick = { onShareOutcome(outcome) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptEntriesSection(document: TranscriptDocument) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val expandedStates = remember(document.sourceUrl, document.extractedAtMillis) {
        mutableStateMapOf<Int, Boolean>().apply {
            if (!document.isPlaylist && document.entries.isNotEmpty()) {
                put(0, true)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = if (document.isPlaylist) "Videos (${document.entries.size})" else "Transcript Preview",
            style = typography.cardTitle,
            color = colors.textPrimary
        )

        document.entries.forEachIndexed { index, entry ->
            val isExpanded = expandedStates[index] == true
            TranscriptEntryCard(
                entry = entry,
                isPlaylist = document.isPlaylist,
                isExpanded = isExpanded,
                onToggleExpand = { expandedStates[index] = !isExpanded }
            )
        }
    }
}

@Composable
private fun TranscriptEntryCard(
    entry: VideoTranscriptEntry,
    isPlaylist: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val badgeVariant = when (entry.status) {
        VideoTranscriptStatus.SUCCESS -> LaterboxBadgeVariant.SUCCESS
        VideoTranscriptStatus.NO_CAPTIONS -> LaterboxBadgeVariant.WARNING
        else -> LaterboxBadgeVariant.DANGER
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = LaterboxShapeTokens.Card,
        color = colors.surfaceSecondary,
        border = BorderStroke(spacing.hairline, colors.borderSubtle)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggleExpand)
                    .padding(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (isPlaylist) "${entry.position}. ${entry.title}" else entry.title,
                        style = typography.uiMedium,
                        color = colors.textPrimary,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        LaterboxBadge(
                            text = entry.status.displayLabel,
                            variant = badgeVariant
                        )
                        if (entry.hasTranscript && !entry.captionLanguage.isNullOrBlank()) {
                            val typePart = if (entry.captionType != CaptionType.UNKNOWN) {
                                " · ${entry.captionType.displayLabel}"
                            } else ""
                            Text(
                                text = "${entry.captionLanguage}$typePart",
                                style = typography.caption,
                                color = colors.textSecondary
                            )
                        }
                    }
                }

                Icon(
                    painter = painterResource(
                        id = if (isExpanded) LaterboxIcons.ChevronDown else LaterboxIcons.ChevronRight
                    ),
                    contentDescription = if (isExpanded) "Collapse transcript" else "Expand transcript",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    HorizontalDivider(color = colors.borderSubtle, thickness = spacing.hairline)
                    Spacer(modifier = Modifier.height(2.dp))

                    if (entry.hasTranscript) {
                        val previewLimit = 80
                        val shownParagraphs = entry.paragraphs.take(previewLimit)
                        shownParagraphs.forEach { paragraph ->
                            val ts = TranscriptFormatting.formatTimestamp(paragraph.startMs)
                            val line = if (ts != null) "$ts ${paragraph.text}" else paragraph.text
                            Text(
                                text = line,
                                style = typography.bodySecondary,
                                color = colors.textPrimary
                            )
                        }
                        if (entry.paragraphs.size > previewLimit) {
                            Text(
                                text = "... ${entry.paragraphs.size - previewLimit} more sections included in exported file(s).",
                                style = typography.metaText.copy(fontWeight = FontWeight.Medium),
                                color = colors.accentPrimary
                            )
                        }
                    } else {
                        Text(
                            text = entry.statusMessage ?: "No accessible captions were found for this video.",
                            style = typography.bodySecondary,
                            color = colors.statusDanger
                        )
                    }
                }
            }
        }
    }
}

private fun openExportedFile(context: Context, outcome: SingleExportOutcome) {
    val uriStr = outcome.uri ?: return
    val uri = Uri.parse(uriStr)
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, outcome.format.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(viewIntent, "Open ${outcome.filename}"))
    } catch (_: Throwable) {
        Toast.makeText(
            context,
            "No app found to open ${outcome.format.extension.uppercase()} files",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun shareExportedFile(context: Context, outcome: SingleExportOutcome) {
    val uriStr = outcome.uri ?: return
    val uri = Uri.parse(uriStr)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = outcome.format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, outcome.filename)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(shareIntent, "Share ${outcome.filename}"))
    } catch (_: Throwable) {
        Toast.makeText(context, "Unable to share ${outcome.filename}", Toast.LENGTH_SHORT).show()
    }
}
