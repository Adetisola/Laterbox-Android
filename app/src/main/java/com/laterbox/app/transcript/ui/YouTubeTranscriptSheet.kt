package com.laterbox.app.transcript.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.ExportFormat
import com.laterbox.app.transcript.domain.SingleExportOutcome
import com.laterbox.app.transcript.domain.TranscriptDiagnosticInfo
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptTargetPreference
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.export.TranscriptClipboardFormatter
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
    onManualUrlChange: (String) -> Unit = {},
    onSubmitManualUrl: () -> Unit = {},
    onSaveToLibrary: () -> Unit = {},
    onRetryFailedEntries: () -> Unit = {},
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val colors = LaterboxTheme.colors
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
            onDismiss = onDismiss,
            onManualUrlChange = onManualUrlChange,
            onSubmitManualUrl = onSubmitManualUrl,
            onSaveToLibrary = onSaveToLibrary,
            onRetryFailedEntries = onRetryFailedEntries
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
    onDismiss: () -> Unit,
    onManualUrlChange: (String) -> Unit = {},
    onSubmitManualUrl: () -> Unit = {},
    onSaveToLibrary: () -> Unit = {},
    onRetryFailedEntries: () -> Unit = {}
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

        // Manual URL Entry Card (when opened from Assistant or ready to extract a custom YouTube URL)
        if (uiState.allowManualUrlEntry && !uiState.isExtracting && uiState.document == null && !uiState.needsTargetChoice) {
            LaterboxCard(variant = LaterboxCardVariant.SECONDARY) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Text(
                        text = "Enter a YouTube video or playlist URL",
                        style = typography.cardTitle,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Laterbox extracts existing YouTube captions without downloading video or audio.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LaterboxShapeTokens.InputField)
                            .background(colors.surfacePrimary)
                            .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.InputField)
                            .padding(horizontal = spacing.md, vertical = 12.dp)
                    ) {
                        if (uiState.manualUrlInput.isBlank()) {
                            Text(
                                text = "https://www.youtube.com/watch?v=... or playlist URL",
                                style = typography.bodySecondary,
                                color = colors.textTertiary
                            )
                        }
                        BasicTextField(
                            value = uiState.manualUrlInput,
                            onValueChange = onManualUrlChange,
                            singleLine = true,
                            textStyle = typography.bodyPrimary.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accentPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onSubmitManualUrl() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    LaterboxPrimaryButton(
                        text = "Extract Transcript",
                        onClick = onSubmitManualUrl,
                        enabled = uiState.manualUrlInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
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
            TranscriptSummaryCard(
                document = document,
                onCopyAll = {
                    val text = TranscriptClipboardFormatter.formatPlaylist(document)
                    copyTextToClipboard(
                        context = context,
                        label = document.title,
                        text = text,
                        toastMessage = if (document.isPlaylist) "All transcripts copied." else "Transcript copied."
                    )
                }
            )

            if (!document.hasAnySuccessfulTranscript) {
                val singleEntry = document.entries.firstOrNull()
                val canRetrySubset = document.isPlaylist && document.retryableFailedCount > 0 &&
                    document.retryableFailedCount < document.videosFound
                LaterboxBanner(
                    text = if (document.isPlaylist) {
                        "No transcripts could be extracted for any of the ${document.videosFound} playlist videos."
                    } else {
                        singleEntry?.statusMessage
                            ?: "No accessible captions were found for this video."
                    },
                    isWarning = true,
                    actionText = if (canRetrySubset) {
                        "Retry Failed (${document.retryableFailedCount})"
                    } else {
                        "Retry"
                    },
                    onActionClick = if (canRetrySubset) onRetryFailedEntries else onRetryExtraction
                )
            } else if (document.isPlaylist && document.retryableFailedCount > 0) {
                LaterboxBanner(
                    text = "${document.retryableFailedCount} video(s) failed with a transient error or timeout. Already extracted transcripts are preserved.",
                    isWarning = true,
                    actionText = "Retry Failed (${document.retryableFailedCount})",
                    onActionClick = onRetryFailedEntries
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
                    onSaveToLibraryClick = onSaveToLibrary,
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
                    anySavedToLibrary = exportResult.anySavedToLibrary,
                    onOpenOutcome = { outcome -> openExportedFile(context, outcome) },
                    onShareOutcome = { outcome -> shareExportedFile(context, outcome) }
                )
            }

            // Individual Video Entries Preview
            TranscriptEntriesSection(
                document = document,
                onCopyEntry = { entry ->
                    val text = TranscriptClipboardFormatter.formatSingleVideo(entry)
                    copyTextToClipboard(
                        context = context,
                        label = entry.title,
                        text = text,
                        toastMessage = "Transcript copied."
                    )
                }
            )
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
private fun TranscriptSummaryCard(
    document: TranscriptDocument,
    onCopyAll: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    LaterboxCard(variant = LaterboxCardVariant.DEFAULT) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
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

                if (document.isPlaylist && document.hasAnySuccessfulTranscript) {
                    LaterboxTextButton(
                        text = "Copy All",
                        onClick = onCopyAll,
                        color = colors.accentPrimary
                    )
                }
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
    onSaveToLibraryClick: () -> Unit,
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

            LaterboxSecondaryButton(
                text = "Save to Laterbox Library (Documents)",
                onClick = onSaveToLibraryClick,
                enabled = uiState.canExport,
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

            Text(
                text = "Exported files are also stored under Downloads → Library → Documents.",
                style = typography.caption,
                color = colors.textTertiary
            )
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
    anySavedToLibrary: Boolean,
    onOpenOutcome: (SingleExportOutcome) -> Unit,
    onShareOutcome: (SingleExportOutcome) -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        val summaryText = when {
            allSucceeded && anySavedToLibrary -> "Saved ${outcomes.size} file(s) to Documents Library and exported."
            allSucceeded -> "Exported ${outcomes.size} file(s) successfully."
            anySucceeded && anyFailed -> "Partial export: some files failed to save."
            anySavedToLibrary && anyFailed -> "Saved to Downloads Library (Documents), but external export failed."
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
                                variant = if (outcome.success || outcome.savedToLibrary) LaterboxBadgeVariant.SUCCESS else LaterboxBadgeVariant.DANGER,
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

                    if ((outcome.success || outcome.savedToLibrary) && !outcome.uri.isNullOrBlank()) {
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
private fun TranscriptEntriesSection(
    document: TranscriptDocument,
    onCopyEntry: (VideoTranscriptEntry) -> Unit
) {
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
                onToggleExpand = { expandedStates[index] = !isExpanded },
                onCopy = { onCopyEntry(entry) }
            )
        }
    }
}

@Composable
private fun TranscriptEntryCard(
    entry: VideoTranscriptEntry,
    isPlaylist: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    var showDiagnostics by remember(entry.videoId, entry.position) { mutableStateOf(false) }

    val badgeVariant = when (entry.status) {
        VideoTranscriptStatus.SUCCESS -> LaterboxBadgeVariant.SUCCESS
        VideoTranscriptStatus.NO_CAPTIONS, VideoTranscriptStatus.CANCELLED -> LaterboxBadgeVariant.WARNING
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.hasTranscript) {
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Copy,
                            contentDescription = "Copy transcript for ${entry.title}",
                            tint = colors.accentPrimary,
                            onClick = onCopy
                        )
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
                                text = "... ${entry.paragraphs.size - previewLimit} more sections included when copying or exporting.",
                                style = typography.metaText.copy(fontWeight = FontWeight.Medium),
                                color = colors.accentPrimary
                            )
                        }
                    } else {
                        Text(
                            text = entry.statusMessage ?: "No accessible captions were found for this video.",
                            style = typography.bodySecondary,
                            color = if (entry.status == VideoTranscriptStatus.NO_CAPTIONS) {
                                colors.statusWarning
                            } else {
                                colors.statusDanger
                            }
                        )

                        val diag = entry.diagnosticInfo
                        if (diag != null) {
                            LaterboxTextButton(
                                text = if (showDiagnostics) "Hide details" else "Technical details",
                                onClick = { showDiagnostics = !showDiagnostics }
                            )
                            if (showDiagnostics) {
                                DiagnosticDetailsBox(diag)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticDetailsBox(diag: TranscriptDiagnosticInfo) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val summary = buildString {
        appendLine("Stage: ${diag.stage}")
        diag.lastKnownStage?.takeIf { it.isNotBlank() }?.let { appendLine("Last known stage: $it") }
        diag.lastMeaningfulEvent?.takeIf { it.isNotBlank() }?.let { ev ->
            val clipped = if (ev.length > 140) ev.take(137) + "..." else ev
            appendLine("Last meaningful event: $clipped")
        }
        diag.lastMeaningfulActivityAgeMs?.let { appendLine("Last activity age: ${it}ms") }
        diag.playlistPosition?.let { appendLine("Playlist position: #$it") }
        appendLine("Category: ${diag.errorCategory.name}")
        diag.failureClassification?.let { appendLine("Classification: $it") }
        diag.executionEngine?.let { appendLine("Engine: $it") }
        diag.ytDlpVersion?.let { appendLine("Extractor: yt-dlp $it") }
        diag.playerClient?.let {
            val eff = diag.effectivePlayerClient?.takeIf { e -> e.isNotBlank() && e != it }
            if (eff != null) {
                appendLine("Client: $it (effective: $eff)")
            } else {
                appendLine("Client: $it")
            }
        } ?: diag.effectivePlayerClient?.let { appendLine("Effective client: $it") }
        if (!diag.quickJsIdentity.isNullOrBlank() || !diag.quickJsVersion.isNullOrBlank()) {
            val qjsId = diag.quickJsIdentity ?: "quickjs"
            val qjsVer = diag.quickJsVersion ?: "unknown"
            appendLine("QuickJS: $qjsId $qjsVer")
        }
        if (diag.ejsSolverAvailable != null || !diag.ejsSolverVersion.isNullOrBlank() || diag.isPlayerCachePatched != null) {
            val ejsState = if (diag.ejsSolverAvailable == true) "available" else "unavailable"
            val ejsVer = diag.ejsSolverVersion?.let { " $it" }.orEmpty()
            val cacheStr = diag.isPlayerCachePatched?.let { ", playerCachePatched=$it" }.orEmpty()
            appendLine("EJS solver: $ejsState$ejsVer$cacheStr")
        }
        if (diag.attemptNumber > 1) appendLine("Attempt: #${diag.attemptNumber}")
        if (diag.retryCount > 0) appendLine("Retries: ${diag.retryCount}")
        if (diag.retryReasons.isNotEmpty()) appendLine("Retry reasons: ${diag.retryReasons.joinToString()}")
        diag.warmDaemonHealthyAtStart?.let { appendLine("Warm daemon healthy at start: $it") }
        diag.primaryRouteOutcome?.takeIf { it.isNotBlank() }?.let { appendLine("Primary route: $it") }
        diag.watchHtmlOutcome?.takeIf { it.isNotBlank() }?.let { appendLine("Watch HTML route: $it") }
        diag.recoveryRouteOutcome?.takeIf { it.isNotBlank() }?.let { appendLine("Recovery route: $it") }
        if (diag.fallbackAttempted) {
            val resultPart = diag.fallbackResult?.let { " ($it)" }.orEmpty()
            appendLine("Standard fallback attempted: true$resultPart")
        }
        diag.daemonStartupMs?.let { appendLine("Daemon startup: ${it}ms") }
        diag.socketWaitMs?.let { appendLine("Socket wait: ${it}ms") }
        diag.extractMs?.let { appendLine("Extract execution: ${it}ms") }
        diag.jsChallengesSolved?.takeIf { it > 0 }?.let { appendLine("JS challenges solved: $it") }
        diag.heartbeatCount?.takeIf { it > 0 }?.let { appendLine("Heartbeats received: $it") }
        diag.timeoutMs?.let { appendLine("Timeout budget: ${it}ms") }
        diag.deadlineExtendedMs?.takeIf { it > 0L }?.let { appendLine("Deadline extension: +${it}ms") }
        diag.elapsedMs?.let { appendLine("Elapsed: ${it}ms") }
        diag.warningsCount?.takeIf { it > 0 }?.let { appendLine("Warnings count: $it") }
        diag.lastWarning?.takeIf { it.isNotBlank() }?.let { warn ->
            val clipped = if (warn.length > 140) warn.take(137) + "..." else warn
            appendLine("Last warning: $clipped")
        }
        diag.httpStatusCode?.let { appendLine("HTTP status: $it") }
        diag.exceptionType?.let { appendLine("Error type: $it") }
        diag.sanitizedDetail?.takeIf { it.isNotBlank() }?.let { appendLine("Detail: $it") }
        if (diag.recentDebugLogs.isNotEmpty()) {
            appendLine("Recent logs:")
            diag.recentDebugLogs.takeLast(5).forEach { logLine ->
                val clipped = if (logLine.length > 140) logLine.take(137) + "..." else logLine
                appendLine("  - $clipped")
            }
        }
    }.trim()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LaterboxShapeTokens.Card)
            .background(colors.surfacePrimary)
            .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Card)
            .padding(spacing.sm)
    ) {
        Text(
            text = summary,
            style = typography.caption,
            color = colors.textSecondary
        )
    }
}

private fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String,
    toastMessage: String
) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
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
