package com.laterbox.app.ui.sheets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.MetadataStatus
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.transcript.domain.YouTubeUrlParser
import com.laterbox.app.ui.components.ContentTypeBadge
import com.laterbox.app.ui.components.CustomReminderPickerDialog
import com.laterbox.app.ui.components.DateGrouper
import com.laterbox.app.ui.components.FaviconImage
import com.laterbox.app.ui.components.LaterboxConfirmDialog
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxPrimaryButton
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.components.LaterboxShapeTokens
import com.laterbox.app.ui.components.LinkQuickActionsMenu
import com.laterbox.app.ui.reader.ArticleReaderContent
import com.laterbox.app.ui.reader.ArticleReaderUiState
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.launch

enum class DetailSheetTab {
    DETAILS,
    READER
}

fun SavedLink.isReaderEligible(): Boolean =
    contentType in setOf(ContentType.ARTICLE, ContentType.WEBSITE, ContentType.DISCUSSION) &&
        !YouTubeUrlParser.isYouTubeUrl(originalUrl)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedItemDetailSheet(
    uiState: SavedItemDetailUiState,
    sheetState: SheetState,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onToggleCollection: (Long) -> Unit,
    onCreateCollection: (String) -> Unit,
    onSetReminder: (Long?) -> Unit = {},
    onSaveEdits: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
    onQuickOpen: (String) -> Unit,
    onRetryMetadata: () -> Unit,
    onDismissRequest: () -> Unit,
    onCollectionChange: (Long?) -> Unit = {},
    onInspectMedia: () -> Unit = {},
    onRetryInspection: () -> Unit = onInspectMedia,
    onCancelInspection: () -> Unit = {},
    onDownloadCandidate: (com.laterbox.app.media.domain.model.MediaCandidate) -> Unit = {},
    onOpenMediaAsset: (com.laterbox.app.media.domain.model.MediaAsset) -> Unit = {},
    onExportMediaAsset: (com.laterbox.app.media.domain.model.MediaAsset) -> Unit = {},
    onDeleteMediaAsset: (com.laterbox.app.media.domain.model.MediaAsset) -> Unit = {},
    onOverrideCellular: (String) -> Unit = {},
    onCancelTask: (String) -> Unit = {},
    onDismissTask: (String) -> Unit = {},
    onDeleteWithFiles: (Boolean) -> Unit = {},
    onExtractTranscript: () -> Unit = {},
    onReadInLaterbox: (() -> Unit)? = null,
    onOpenOfflineMedia: (() -> Unit)? = null,
    initialTab: DetailSheetTab = DetailSheetTab.DETAILS,
    sessionKey: Any = 0L,
    readerUiState: ArticleReaderUiState = ArticleReaderUiState.Loading,
    fontScale: Float = 1.0f,
    onRefreshReader: () -> Unit = {},
    onMarkReadAndArchive: (() -> Unit)? = null,
    onTabChanged: (DetailSheetTab) -> Unit = {}
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val link = uiState.link ?: return

    var showDeleteConfirm by remember(link.id, sessionKey) { mutableStateOf(false) }
    var showMoreMenu by remember(link.id, sessionKey) { mutableStateOf(false) }
    var isDescriptionExpanded by remember(link.id, sessionKey) { mutableStateOf(false) }
    var isEditExpanded by rememberSaveable(link.id, sessionKey) { mutableStateOf(false) }
    var showCreateCollectionDialog by remember(link.id, sessionKey) { mutableStateOf(false) }
    var showCustomReminderPicker by remember(link.id, sessionKey) { mutableStateOf(false) }
    var showNotifPermissionWarning by remember(link.id, sessionKey) {
        mutableStateOf(
            link.reminderAt != null &&
                !com.laterbox.app.ui.util.NotificationPermissionHelper.isNotificationPermissionGranted(context)
        )
    }

    val ensureNotificationPermission = com.laterbox.app.ui.util.rememberNotificationPermissionRequester(
        onDenied = { showNotifPermissionWarning = true }
    )

    val hasTextEdits = uiState.editedTitle != link.title ||
        uiState.editedNote != (link.personalNote ?: "")
    val hasPendingEdits = hasTextEdits ||
        uiState.selectedCollectionIds != link.collectionIds.toSet()
    val showEditFields = isEditExpanded || hasTextEdits

    val playableAsset = uiState.assets.firstOrNull {
        it.mediaType in listOf(MediaType.VIDEO, MediaType.AUDIO, MediaType.IMAGE)
    }
    val isYouTube = YouTubeUrlParser.isYouTubeUrl(link.originalUrl)
    val isReaderEligible = link.isReaderEligible()
    val canReadInApp = isReaderEligible

    val detailsScrollState = rememberSaveable(link.id, sessionKey, saver = ScrollState.Saver) {
        ScrollState(0)
    }
    val readerScrollState = rememberSaveable(link.id, sessionKey, saver = ScrollState.Saver) {
        ScrollState(0)
    }

    val initialPage = if (isReaderEligible && initialTab == DetailSheetTab.READER) 1 else 0
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { if (isReaderEligible) 2 else 1 }
    )

    LaunchedEffect(sessionKey, link.id, initialTab, isReaderEligible) {
        val targetPage = if (isReaderEligible && initialTab == DetailSheetTab.READER) 1 else 0
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    val activeTab = if (isReaderEligible && pagerState.currentPage == 1) {
        DetailSheetTab.READER
    } else {
        DetailSheetTab.DETAILS
    }

    LaunchedEffect(pagerState.settledPage, isReaderEligible) {
        val settledTab = if (isReaderEligible && pagerState.settledPage == 1) {
            DetailSheetTab.READER
        } else {
            DetailSheetTab.DETAILS
        }
        if (settledTab == DetailSheetTab.READER) {
            focusManager.clearFocus()
        }
        onTabChanged(settledTab)
    }

    val switchToTab: (DetailSheetTab) -> Unit = { targetTab ->
        if (isReaderEligible) {
            if (targetTab == DetailSheetTab.READER) {
                focusManager.clearFocus()
            }
            onTabChanged(targetTab)
            val targetPage = if (targetTab == DetailSheetTab.READER) 1 else 0
            coroutineScope.launch {
                pagerState.animateScrollToPage(targetPage)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = LaterboxTheme.shapes.Sheet,
        containerColor = if (activeTab == DetailSheetTab.READER) colors.backgroundPrimary else colors.surfaceElevated,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isReaderEligible) Modifier.fillMaxHeight(0.94f) else Modifier)
                .navigationBarsPadding()
                .testTag("saved_item_detail_sheet")
        ) {
            // Sticky Header: Domain, ContentType, and Top Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.base)
                    .padding(bottom = spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    FaviconImage(
                        faviconUrl = link.faviconUrl,
                        domain = link.domain,
                        size = 20.dp
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text(
                        text = link.domain,
                        style = typography.uiMedium.copy(fontSize = 13.sp),
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    ContentTypeBadge(contentType = link.contentType)
                    val isMediaReady = uiState.assets.isNotEmpty() || (
                        uiState.inspectionResult is com.laterbox.app.media.domain.model.InspectionResult.Available &&
                            (uiState.inspectionResult as com.laterbox.app.media.domain.model.InspectionResult.Available).candidates.any {
                                it.mediaType in listOf(MediaType.VIDEO, MediaType.AUDIO)
                            }
                        )
                    if (isMediaReady) {
                        Spacer(modifier = Modifier.width(spacing.xs))
                        com.laterbox.app.ui.components.MediaReadyBadge()
                    }
                }

                // Action buttons: Refresh (Reader only), More Menu (⋮) & Close (×)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    if (isReaderEligible && activeTab == DetailSheetTab.READER) {
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.Refresh,
                            contentDescription = "Refresh reader view",
                            tint = colors.textPrimary,
                            onClick = onRefreshReader,
                            modifier = Modifier.testTag("reader_refresh_button")
                        )
                    }

                    Box {
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.MoreVertical,
                            contentDescription = "More actions",
                            tint = colors.textPrimary,
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.testTag("detail_sheet_more_button")
                        )

                        LinkQuickActionsMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            link = link,
                            onToggleRead = onToggleRead,
                            onCopyLink = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("URL", link.originalUrl))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            },
                            onShareLink = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, link.originalUrl)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                            },
                            onArchive = onToggleArchive,
                            onDelete = { showDeleteConfirm = true }
                        )
                    }

                    LaterboxIconButton(
                        iconRes = LaterboxIcons.Close,
                        contentDescription = "Close details",
                        tint = colors.textPrimary,
                        onClick = onDismissRequest,
                        modifier = Modifier.testTag("detail_sheet_close_button")
                    )
                }
            }

            // Subtle sticky Details | Reader segmented control (only for reader-eligible content)
            if (isReaderEligible) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceSecondary,
                    border = BorderStroke(spacing.hairline, colors.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.base)
                        .padding(bottom = spacing.sm)
                        .testTag("detail_reader_tab_switcher")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DetailSheetTabButton(
                            label = "Details",
                            selected = activeTab == DetailSheetTab.DETAILS,
                            onClick = { switchToTab(DetailSheetTab.DETAILS) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("detail_tab_details")
                        )
                        DetailSheetTabButton(
                            label = "Reader",
                            selected = activeTab == DetailSheetTab.READER,
                            onClick = { switchToTab(DetailSheetTab.READER) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("detail_tab_reader")
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))

            val renderDetailsBody: @Composable (Modifier) -> Unit = { bodyModifier ->
                Column(
                    modifier = bodyModifier
                        .verticalScroll(detailsScrollState)
                        .padding(horizontal = spacing.base)
                        .padding(top = spacing.md, bottom = spacing.xl)
                        .testTag("detail_hub_scroll")
                ) {

            // 1. Editorial Headline & Relative Timestamp
            Text(
                text = uiState.editedTitle.ifBlank { link.domain },
                style = typography.editorialTitle.copy(fontSize = 21.sp, lineHeight = 28.sp),
                color = colors.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            val statusLabel = when {
                link.isArchived -> "Archived"
                link.isRead -> "Read"
                else -> "Unread"
            }
            Text(
                text = "Saved ${DateGrouper.formatRelativeTime(link.createdAt)} · $statusLabel",
                style = typography.metaText,
                color = colors.textSecondary
            )

            Spacer(modifier = Modifier.height(spacing.md))

            // 2. Primary Consumption CTA Bar
            if (playableAsset != null) {
                LaterboxPrimaryButton(
                    text = if (playableAsset.mediaType == MediaType.IMAGE) "View Offline Image" else "▶ Play Offline",
                    onClick = {
                        if (onOpenOfflineMedia != null) {
                            onOpenOfflineMedia()
                        } else {
                            onOpenMediaAsset(playableAsset)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    if (canReadInApp) {
                        LaterboxSecondaryButton(
                            text = "Read in Laterbox",
                            onClick = {
                                onReadInLaterbox?.invoke()
                                switchToTab(DetailSheetTab.READER)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    LaterboxSecondaryButton(
                        text = "Open Web ↗",
                        onClick = { onQuickOpen(link.originalUrl) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (canReadInApp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    LaterboxPrimaryButton(
                        text = "Read in Laterbox",
                        onClick = {
                            onReadInLaterbox?.invoke()
                            switchToTab(DetailSheetTab.READER)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    LaterboxSecondaryButton(
                        text = "Open Web ↗",
                        onClick = { onQuickOpen(link.originalUrl) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                LaterboxPrimaryButton(
                    text = "Open Web ↗",
                    onClick = { onQuickOpen(link.originalUrl) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 3. Above-the-fold Personal Note Callout & Description Summary
            if (!showEditFields && uiState.editedNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(spacing.md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(LaterboxShapeTokens.Card)
                        .background(colors.accentContainer.copy(alpha = 0.45f))
                        .border(spacing.hairline, colors.accentPrimary.copy(alpha = 0.25f), LaterboxShapeTokens.Card)
                        .padding(spacing.md),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(colors.accentPrimary)
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = uiState.editedNote,
                        style = typography.bodyPrimary.copy(fontStyle = FontStyle.Italic),
                        color = colors.textPrimary
                    )
                }
            }

            if (!link.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(spacing.sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(LaterboxShapeTokens.Card)
                        .background(colors.surfaceSecondary)
                        .padding(spacing.md)
                ) {
                    Column {
                        Text(
                            text = link.description,
                            style = typography.bodySecondary,
                            color = colors.textSecondary,
                            maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isDescriptionExpanded) "Show less" else "Read more",
                            style = typography.uiMedium.copy(fontSize = 12.sp),
                            color = colors.accentPrimary,
                            modifier = Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (isDescriptionExpanded) "Collapse description" else "Expand description"
                                ) {
                                    isDescriptionExpanded = !isDescriptionExpanded
                                }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            // 4. Above-the-fold YouTube Transcript & Offline Media Actions
            if (isYouTube) {
                YouTubeTranscriptDetailCard(
                    url = link.originalUrl,
                    onExtractTranscript = onExtractTranscript
                )
                Spacer(modifier = Modifier.height(spacing.md))
            }

            com.laterbox.app.media.ui.linkdetail.MediaSection(
                linkUrl = link.originalUrl,
                assets = uiState.assets,
                activeTasks = uiState.activeTasks,
                inspectionResult = uiState.inspectionResult,
                isInspecting = uiState.isInspecting,
                onInspectClick = onInspectMedia,
                onRetryClick = onRetryInspection,
                onCancelInspection = onCancelInspection,
                onDownloadCandidate = onDownloadCandidate,
                onOpenAsset = onOpenMediaAsset,
                onExportAsset = onExportMediaAsset,
                onDeleteAsset = onDeleteMediaAsset,
                onOverrideCellular = onOverrideCellular,
                onCancelTask = onCancelTask,
                onDismissTask = onDismissTask
            )

            Spacer(modifier = Modifier.height(spacing.md))

            // 5. Collapsible Title & Personal Note Editor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LaterboxShapeTokens.Card)
                    .background(colors.surfaceSecondary)
                    .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Card)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (showEditFields) "Hide title and note editor" else "Edit title and note"
                    ) {
                        isEditExpanded = !showEditFields
                    }
                    .padding(horizontal = spacing.md, vertical = 12.dp)
                    .testTag("detail_edit_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showEditFields) "Hide Title & Note Editor" else "✎ Edit Title & Personal Note",
                    style = typography.uiMedium,
                    color = colors.accentPrimary
                )
                Icon(
                    painter = painterResource(id = LaterboxIcons.ChevronDown),
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (showEditFields) {
                Spacer(modifier = Modifier.height(spacing.sm))
                OutlinedTextField(
                    value = uiState.editedTitle,
                    onValueChange = onTitleChange,
                    label = { Text("Title") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(spacing.sm))

                val maxNoteLength = 500
                val remainingChars = maxNoteLength - uiState.editedNote.length
                OutlinedTextField(
                    value = uiState.editedNote,
                    onValueChange = {
                        if (it.length <= maxNoteLength + 20) onNoteChange(it)
                    },
                    label = { Text("Personal Note") },
                    placeholder = { Text("Add takeaways, quotes, or thoughts...") },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = if (remainingChars <= 50) {
                        {
                            Text(
                                text = if (remainingChars >= 0) "$remainingChars characters remaining" else "Exceeded by ${-remainingChars} characters",
                                color = if (remainingChars >= 0) colors.textTertiary else colors.statusDanger,
                                style = typography.metaText
                            )
                        }
                    } else null,
                    isError = remainingChars < 0,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Save Edits Button (if any title, note, or collection edit is pending)
            if (hasPendingEdits) {
                Spacer(modifier = Modifier.height(spacing.sm))
                Button(
                    onClick = onSaveEdits,
                    enabled = !uiState.isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentPrimary,
                        contentColor = colors.onAccentPrimary
                    ),
                    shape = LaterboxShapeTokens.Button,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.minTouchTarget)
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            color = colors.onAccentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text("Save Changes", style = typography.uiMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            // 6. Collections Section with toggle chips and trailing + creator
            Text(
                text = "Collection",
                style = typography.metaText,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uiState.collections.forEach { collection ->
                    val isSelected = uiState.selectedCollectionIds.contains(collection.id)
                    CollectionSelectionChip(
                        name = collection.name,
                        isSelected = isSelected,
                        onClick = { onToggleCollection(collection.id) }
                    )
                }

                // Trailing "+" chip to create new collection inline
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = spacing.minTouchTarget, minHeight = spacing.minTouchTarget)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Create new collection",
                            onClick = { showCreateCollectionDialog = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(LaterboxShapeTokens.Chip)
                            .background(colors.surfaceSecondary)
                            .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Chip)
                            .padding(horizontal = spacing.md),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = LaterboxIcons.Plus),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = colors.accentPrimary
                            )
                            Text(
                                text = "New",
                                style = typography.metaText.copy(fontSize = 12.sp),
                                color = colors.accentPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            // 7. Reminder section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.Clock),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.textSecondary
                )
                Text(
                    text = "Reminder",
                    style = typography.metaText,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.height(spacing.xs))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tonightMillis = remember { com.laterbox.app.reminders.ReminderPresets.getTonightMillis() }
                val tomorrowMillis = remember { com.laterbox.app.reminders.ReminderPresets.getTomorrowMorningMillis() }
                val weekendMillis = remember { com.laterbox.app.reminders.ReminderPresets.getWeekendMillis() }

                if (link.reminderAt != null) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(LaterboxShapeTokens.Chip)
                            .background(colors.surfaceSecondary)
                            .border(1.dp, colors.accentPrimary, LaterboxShapeTokens.Chip)
                            .padding(start = spacing.sm, end = spacing.xs),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xxs)
                        ) {
                            Text(
                                text = com.laterbox.app.reminders.ReminderPresets.formatReminderTime(link.reminderAt),
                                style = typography.metaText.copy(fontSize = 12.sp),
                                color = colors.accentPrimary,
                                modifier = Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = "Edit reminder time",
                                    onClick = { showCustomReminderPicker = true }
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(spacing.radiusSmall))
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Clear reminder",
                                        onClick = {
                                            showNotifPermissionWarning = false
                                            onSetReminder(null)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = LaterboxIcons.Close),
                                    contentDescription = "Clear reminder",
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    ReminderDetailChip(
                        label = "Change…",
                        onClick = { showCustomReminderPicker = true }
                    )
                } else {
                    ReminderDetailChip(
                        label = "Tonight (8 PM)",
                        onClick = {
                            ensureNotificationPermission { onSetReminder(tonightMillis) }
                        }
                    )
                    ReminderDetailChip(
                        label = "Tomorrow (9 AM)",
                        onClick = {
                            ensureNotificationPermission { onSetReminder(tomorrowMillis) }
                        }
                    )
                    ReminderDetailChip(
                        label = "Weekend",
                        onClick = {
                            ensureNotificationPermission { onSetReminder(weekendMillis) }
                        }
                    )
                    ReminderDetailChip(
                        label = "+ Custom…",
                        onClick = {
                            ensureNotificationPermission { showCustomReminderPicker = true }
                        }
                    )
                }
            }

            if (showNotifPermissionWarning && !com.laterbox.app.ui.util.NotificationPermissionHelper.isNotificationPermissionGranted(context)) {
                Spacer(modifier = Modifier.height(spacing.xs))
                com.laterbox.app.ui.components.LaterboxBanner(
                    text = "Notifications are blocked. Reminder is saved, but won't alert you until notifications are enabled.",
                    isWarning = true,
                    actionText = "Settings",
                    onActionClick = {
                        com.laterbox.app.ui.util.NotificationPermissionHelper.openAppNotificationSettings(context)
                    }
                )
            }

            // Compact metadata status badge with inline retry
            if (link.metadataStatus == MetadataStatus.FAILED || link.metadataStatus == MetadataStatus.PARTIAL || link.metadataStatus == MetadataStatus.FETCHING) {
                Spacer(modifier = Modifier.height(spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(LaterboxShapeTokens.Card)
                        .background(colors.surfaceSecondary)
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (link.metadataStatus) {
                            MetadataStatus.FETCHING -> "Fetching metadata..."
                            MetadataStatus.PARTIAL -> "Partial preview"
                            else -> "Preview fetch failed"
                        },
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                    if (link.metadataStatus != MetadataStatus.FETCHING) {
                        Surface(
                            shape = LaterboxShapeTokens.Chip,
                            color = colors.accentPrimary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, colors.accentPrimary.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 36.dp)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Retry fetching link preview metadata",
                                    onClick = onRetryMetadata
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = LaterboxIcons.Refresh),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.accentPrimary
                                )
                                Text(
                                    text = "Retry",
                                    style = typography.uiMedium.copy(fontSize = 13.sp),
                                    color = colors.accentPrimary
                                )
                            }
                        }
                    } else {
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            // 8. Original Link box with copy button
            Text(
                text = "Original link",
                style = typography.metaText,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LaterboxShapeTokens.Card)
                    .background(colors.surfaceSecondary)
                    .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Card)
                    .padding(start = spacing.md, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = link.originalUrl,
                        style = typography.codeText.copy(fontSize = 12.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 28.dp)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(36.dp)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        colors.surfaceSecondary
                                    )
                                )
                            )
                    )
                }

                LaterboxIconButton(
                    iconRes = LaterboxIcons.Copy,
                    contentDescription = "Copy original link",
                    tint = colors.textPrimary,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", link.originalUrl))
                        Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            }
                }
            }

            if (isReaderEligible) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("detail_reader_pager")
                ) { page ->
                    if (page == 0) {
                        renderDetailsBody(
                            Modifier
                                .fillMaxSize()
                                .focusProperties { canFocus = activeTab == DetailSheetTab.DETAILS }
                        )
                    } else {
                        ArticleReaderContent(
                            link = link,
                            uiState = readerUiState,
                            fontScale = fontScale,
                            scrollState = readerScrollState,
                            showTopBar = false,
                            onRefresh = onRefreshReader,
                            onOpenOriginal = { onQuickOpen(link.originalUrl) },
                            onMarkReadAndArchive = {
                                if (onMarkReadAndArchive != null) {
                                    onMarkReadAndArchive()
                                } else {
                                    if (!link.isRead) onToggleRead()
                                    if (!link.isArchived) onToggleArchive() else onDismissRequest()
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .focusProperties { canFocus = activeTab == DetailSheetTab.READER }
                        )
                    }
                }
            } else {
                renderDetailsBody(Modifier.fillMaxWidth())
            }

        // New Collection inline creation dialog
        if (showCreateCollectionDialog) {
            var newCollectionName by remember { mutableStateOf("") }
            LaterboxConfirmDialog(
                title = "New Collection",
                message = "Enter a name for this collection.",
                confirmText = "Create",
                onConfirm = {
                    val name = newCollectionName.trim()
                    if (name.isNotBlank()) {
                        onCreateCollection(name)
                        showCreateCollectionDialog = false
                    }
                },
                onDismiss = { showCreateCollectionDialog = false },
                content = {
                    OutlinedTextField(
                        value = newCollectionName,
                        onValueChange = { newCollectionName = it.take(50) },
                        label = { Text("Collection name") },
                        singleLine = true,
                        shape = LaterboxTheme.shapes.InputField,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accentPrimary,
                            unfocusedBorderColor = colors.borderSubtle,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
        }

        // Delete confirmation dialog
        if (showDeleteConfirm) {
            if (uiState.assets.isNotEmpty()) {
                LaterboxConfirmDialog(
                    title = "Delete Link",
                    message = "\"${link.title}\" has ${uiState.assets.size} downloaded file(s). Do you want to keep them in your Downloads library or delete them?",
                    confirmText = "Delete Link & Files",
                    onConfirm = {
                        showDeleteConfirm = false
                        onDeleteWithFiles(true)
                    },
                    onDismiss = { showDeleteConfirm = false },
                    neutralText = "Keep Files",
                    onNeutral = {
                        showDeleteConfirm = false
                        onDeleteWithFiles(false)
                    },
                    isDestructive = true
                )
            } else {
                LaterboxConfirmDialog(
                    title = "Delete Link?",
                    message = "\"${link.title}\" will be permanently deleted.",
                    confirmText = "Delete",
                    onConfirm = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    onDismiss = { showDeleteConfirm = false },
                    isDestructive = true
                )
            }
        }

        if (showCustomReminderPicker) {
            CustomReminderPickerDialog(
                initialTimestamp = link.reminderAt,
                onDismissRequest = { showCustomReminderPicker = false },
                onConfirm = { millis ->
                    onSetReminder(millis)
                    showCustomReminderPicker = false
                }
            )
        }
        }
    }
}

@Composable
private fun CollectionSelectionChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val bg = if (isSelected) colors.accentContainer else colors.surfaceSecondary
    val borderColor = if (isSelected) colors.accentPrimary else colors.borderSubtle
    val textColor = if (isSelected) colors.accentPrimary else colors.textSecondary

    Box(
        modifier = Modifier
            .sizeIn(minWidth = spacing.minTouchTarget, minHeight = spacing.minTouchTarget)
            .clickable(
                role = Role.Button,
                onClickLabel = "Select $name collection",
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(LaterboxShapeTokens.Chip)
                .background(bg)
                .border(spacing.hairline, borderColor, LaterboxShapeTokens.Chip)
                .padding(horizontal = spacing.md),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isSelected) {
                    Icon(
                        painter = painterResource(id = LaterboxIcons.Check),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.accentPrimary
                    )
                }
                Text(
                    text = name,
                    style = typography.metaText.copy(fontSize = 12.sp),
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun ReminderDetailChip(
    label: String,
    onClick: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Box(
        modifier = Modifier
            .sizeIn(minWidth = spacing.minTouchTarget, minHeight = spacing.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(LaterboxShapeTokens.Chip)
                .background(colors.surfaceSecondary)
                .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Chip)
                .padding(horizontal = spacing.md),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = typography.metaText.copy(fontSize = 12.sp),
                color = colors.textPrimary
            )
        }
    }
}

@Composable
internal fun YouTubeTranscriptDetailCard(
    url: String,
    onExtractTranscript: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val isPlaylistUrl = com.laterbox.app.transcript.domain.YouTubeUrlParser.isPlaylistUrl(url)

    com.laterbox.app.ui.components.LaterboxCard(
        variant = com.laterbox.app.ui.components.LaterboxCardVariant.SECONDARY
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "YouTube Transcripts",
                    style = typography.cardTitle,
                    color = colors.textPrimary
                )
                Text(
                    text = if (isPlaylistUrl) {
                        "Extract captions for all playlist videos and export as MD, TXT, or PDF"
                    } else {
                        "Extract verbatim captions and export as MD, TXT, or PDF"
                    },
                    style = typography.metaText,
                    color = colors.textSecondary
                )
            }
            com.laterbox.app.ui.components.LaterboxSecondaryButton(
                text = "Extract Transcript",
                onClick = onExtractTranscript,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DetailSheetTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = if (selected) colors.surfaceElevated else Color.Transparent,
        tonalElevation = if (selected) 1.dp else 0.dp,
        shadowElevation = if (selected) 1.dp else 0.dp,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(11.dp))
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = typography.uiMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

