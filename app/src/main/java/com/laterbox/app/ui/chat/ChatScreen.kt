package com.laterbox.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.ai.AssistantActionHandler
import com.laterbox.app.ai.ChatAction
import com.laterbox.app.ai.speech.ComposerSpeechUiState
import com.laterbox.app.data.local.entity.ChatMessageEntity
import com.laterbox.app.data.repository.ChatMessageStatus
import com.laterbox.app.data.repository.ChatRole
import com.laterbox.app.diagnostics.AppHealthReport
import com.laterbox.app.diagnostics.AppHealthStatus
import com.laterbox.app.ui.components.ComposerInsetHost
import com.laterbox.app.ui.components.FloatingChatComposer
import com.laterbox.app.ui.components.LaterboxBanner
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxPrimaryButton
import com.laterbox.app.ui.components.LaterboxShapeTokens
import com.laterbox.app.ui.components.MarkdownText
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onMenuClick: (() -> Unit)?,
    onNewChatClick: () -> Unit,
    onRunHealthCheck: () -> Unit,
    onAcceptAiDisclosure: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onComposerTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopStreaming: () -> Unit,
    onRetryMessage: (String) -> Unit,
    onQuickPromptClick: (String) -> Unit,
    onActionClick: (ChatAction) -> Unit,
    onConfirmMutatingAction: () -> Unit,
    onDismissMutatingAction: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    onAlwaysAllowMutatingAction: () -> Unit = {},
    onAddLinkClick: (() -> Unit)? = null,
    onMicClick: (() -> Unit)? = null,
    speechState: ComposerSpeechUiState = ComposerSpeechUiState(),
    onStopDictation: () -> Unit = {},
    onCancelDictation: () -> Unit = {},
    showAudioConsent: Boolean = false,
    onAcceptAudioConsent: () -> Unit = {},
    onDismissAudioConsent: () -> Unit = {},
    onMarkdownLinkClick: ((String) -> Unit)? = null,
    onRenameConversation: (String) -> Unit = {},
    onDeleteConversation: () -> Unit = {}
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val listState = rememberLazyListState()
    var showContextTransparencyDialog by remember { mutableStateOf(false) }
    var showConversationMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
    val itemCount = uiState.messages.size
    LaunchedEffect(itemCount, uiState.streamingPartialText.length, isImeVisible, uiState.pendingMutatingAction != null) {
        if (itemCount > 0) {
            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    if (showRenameDialog && uiState.activeConversationId != null) {
        var draftTitle by remember(uiState.activeConversationId, uiState.activeConversationTitle) {
            mutableStateOf(uiState.activeConversationTitle)
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = colors.surfaceElevated,
            title = {
                Text(
                    text = "Rename conversation",
                    style = typography.titleLarge,
                    color = colors.textPrimary
                )
            },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { if (it.length <= 60) draftTitle = it },
                    singleLine = true,
                    placeholder = { Text("Conversation title") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_rename_input")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draftTitle.trim().isNotEmpty(),
                    onClick = {
                        val trimmed = draftTitle.trim()
                        showRenameDialog = false
                        if (trimmed.isNotEmpty()) {
                            onRenameConversation(trimmed)
                        }
                    }
                ) {
                    Text("Save", color = colors.accentPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    if (showDeleteConfirmDialog && uiState.activeConversationId != null) {
        com.laterbox.app.ui.components.LaterboxConfirmDialog(
            title = "Delete conversation?",
            message = "\"${uiState.activeConversationTitle}\" will be permanently removed from this device.",
            confirmText = "Delete",
            onConfirm = {
                showDeleteConfirmDialog = false
                onDeleteConversation()
            },
            onDismiss = { showDeleteConfirmDialog = false },
            isDestructive = true
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.base, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onMenuClick != null) {
                    LaterboxIconButton(
                        iconRes = LaterboxIcons.Menu,
                        contentDescription = "Open navigation menu",
                        onClick = onMenuClick
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.activeConversationTitle,
                        style = typography.titleLarge.copy(fontSize = 18.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Laterbox Assistant",
                        style = typography.metaText,
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(spacing.xs))

                LaterboxIconButton(
                    iconRes = LaterboxIcons.Health,
                    contentDescription = "Run App Health Check",
                    onClick = onRunHealthCheck,
                    enabled = !uiState.isRunningHealthCheck
                )

                Spacer(modifier = Modifier.width(spacing.xs))

                LaterboxIconButton(
                    iconRes = LaterboxIcons.Chat,
                    contentDescription = "New chat",
                    onClick = onNewChatClick
                )

                if (uiState.activeConversationId != null) {
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Box {
                        LaterboxIconButton(
                            iconRes = LaterboxIcons.MoreVertical,
                            contentDescription = "Conversation actions",
                            onClick = { showConversationMenu = true },
                            modifier = Modifier.testTag("chat_more_actions_button")
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = showConversationMenu,
                            onDismissRequest = { showConversationMenu = false },
                            containerColor = colors.surfaceElevated
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                modifier = Modifier.testTag("chat_action_rename"),
                                text = { Text("Rename", color = colors.textPrimary) },
                                onClick = {
                                    showConversationMenu = false
                                    showRenameDialog = true
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                modifier = Modifier.testTag("chat_action_delete"),
                                text = { Text("Delete", color = colors.statusDanger) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(LaterboxIcons.Delete),
                                        contentDescription = null,
                                        tint = colors.statusDanger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showConversationMenu = false
                                    showDeleteConfirmDialog = true
                                }
                            )
                        }
                    }
                }
            }

            if (uiState.assistantEnabled && uiState.aiDisclosureAccepted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.base)
                        .padding(bottom = spacing.xs),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Row(
                        modifier = Modifier
                            .testTag("ai_context_transparency_pill")
                            .clip(LaterboxShapeTokens.Chip)
                            .background(colors.surfacePrimary)
                            .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Chip)
                            .clickable(role = Role.Button) { showContextTransparencyDialog = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = LaterboxIcons.Inbox),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = colors.accentPrimary
                        )
                        Text(
                            text = "Local Library Context · Sent only on prompt",
                            style = typography.metaText.copy(fontSize = 11.5.sp),
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Inspect",
                            style = typography.metaText.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = colors.accentPrimary
                        )
                    }
                }
            }

            if (!uiState.assistantEnabled) {
                LaterboxBanner(
                    text = "Laterbox Assistant is currently disabled in Settings.",
                    isWarning = true,
                    actionText = "Settings",
                    onActionClick = onOpenAiSettings,
                    modifier = Modifier.padding(horizontal = spacing.base, vertical = spacing.xs)
                )
            }

            uiState.statusNotice?.let { notice ->
                LaterboxBanner(
                    text = notice,
                    actionText = "Dismiss",
                    onActionClick = onDismissNotice,
                    modifier = Modifier.padding(horizontal = spacing.base, vertical = spacing.xs)
                )
            }

            if (!uiState.aiDisclosureAccepted && uiState.assistantEnabled) {
                AiDisclosureConsentCard(
                    onAccept = onAcceptAiDisclosure,
                    onOpenSettings = onOpenAiSettings,
                    modifier = Modifier.padding(horizontal = spacing.base, vertical = spacing.xs)
                )
            }

            uiState.latestHealthReport?.let { report ->
                HealthSummaryCard(
                    report = report,
                    modifier = Modifier.padding(horizontal = spacing.base, vertical = spacing.xs)
                )
            }

            // Messages or Starter Prompts
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (uiState.messages.isEmpty()) {
                    ChatEmptyStarter(
                        assistantEnabled = uiState.assistantEnabled,
                        aiDisclosureAccepted = uiState.aiDisclosureAccepted,
                        onQuickPromptClick = onQuickPromptClick,
                        onRunHealthCheck = onRunHealthCheck
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.base,
                            end = spacing.base,
                            top = spacing.sm,
                            bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            val isActiveStreamingBubble =
                                uiState.isStreaming && message.id == uiState.streamingAssistantMessageId
                            val displayedContent = if (isActiveStreamingBubble) {
                                uiState.streamingPartialText
                            } else {
                                message.content
                            }
                            ChatMessageBubble(
                                message = message,
                                displayedContent = displayedContent,
                                isStreamingThis = isActiveStreamingBubble,
                                onRetry = { onRetryMessage(message.id) },
                                onActionClick = onActionClick,
                                onMarkdownLinkClick = onMarkdownLinkClick
                            )
                        }
                    }
                }
            }

            // Docked Floating Composer anchored calmly at the bottom
            if (uiState.assistantEnabled) {
                ComposerInsetHost(horizontalPadding = spacing.base) {
                    FloatingChatComposer(
                        text = uiState.composerText,
                        onTextChange = onComposerTextChange,
                        onSend = onSendMessage,
                        isStreaming = uiState.isStreaming,
                        onStopStreaming = onStopStreaming,
                        placeholder = "Ask about your links, downloads, or app health...",
                        onAddLinkClick = onAddLinkClick,
                        onMicClick = onMicClick,
                        speechState = speechState,
                        onStopDictation = onStopDictation,
                        onCancelDictation = onCancelDictation,
                        pendingApproval = uiState.pendingMutatingAction,
                        onDenyAction = onDismissMutatingAction,
                        onAllowActionOnce = onConfirmMutatingAction,
                        onAlwaysAllowAction = onAlwaysAllowMutatingAction,
                        showAudioConsent = showAudioConsent,
                        onAcceptAudioConsent = onAcceptAudioConsent,
                        onDismissAudioConsent = onDismissAudioConsent
                    )
                }
            }
        }

        if (showContextTransparencyDialog) {
            AlertDialog(
                onDismissRequest = { showContextTransparencyDialog = false },
                containerColor = colors.surfaceElevated,
                title = {
                    Text(
                        text = "What Laterbox Shares on Prompt",
                        style = typography.titleSmall,
                        color = colors.textPrimary
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Text(
                            text = "• Sent only when you press Send: Prompts go through Laterbox's secured backend to Google Gemini only when you interact.",
                            style = typography.bodySecondary.copy(fontSize = 13.sp),
                            color = colors.textSecondary
                        )
                        Text(
                            text = "• Bounded library context: Up to 10 relevant saved links, active collections, and capability readiness summaries (never private file paths or full downloaded videos).",
                            style = typography.bodySecondary.copy(fontSize = 13.sp),
                            color = colors.textSecondary
                        )
                        Text(
                            text = "• Live web answers: When you ask about outside news or release dates, verified Google Search grounding may be consulted.",
                            style = typography.bodySecondary.copy(fontSize = 13.sp),
                            color = colors.textSecondary
                        )
                        Text(
                            text = "• Action control: Mutating actions obey per-category permissions you can revoke in Settings, and destructive deletes always confirm.",
                            style = typography.bodySecondary.copy(fontSize = 13.sp),
                            color = colors.textPrimary
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showContextTransparencyDialog = false
                            onOpenAiSettings()
                        }
                    ) {
                        Text(
                            text = "AI Settings",
                            style = typography.uiMedium,
                            color = colors.textSecondary
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showContextTransparencyDialog = false }) {
                        Text(
                            text = "Got it",
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
private fun AiDisclosureConsentCard(
    onAccept: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_disclosure_card"),
        shape = LaterboxShapeTokens.Card,
        color = colors.surfacePrimary,
        border = BorderStroke(spacing.hairline, colors.accentPrimary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.Sparkles),
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Remote AI Privacy Notice",
                    style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary
                )
            }
            Text(
                text = "When you send a message, your prompt and relevant saved link metadata (titles, URLs, notes, and app diagnostics—never API keys or private storage paths) are sent remotely to Google AI Studio (Gemini). If you use voice dictation, recorded audio is also sent to Gemini for transcription (with Android speech recognition as fallback). You can disable this or review your API key anytime in Settings.",
                style = typography.bodySecondary.copy(fontSize = 13.sp),
                color = colors.textSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LaterboxPrimaryButton(
                    text = "Accept & Continue",
                    onClick = onAccept,
                    modifier = Modifier.testTag("accept_ai_disclosure_button")
                )
                Text(
                    text = "Reminders & AI settings",
                    style = typography.metaText.copy(fontWeight = FontWeight.Medium),
                    color = colors.accentPrimary,
                    modifier = Modifier
                        .clip(LaterboxShapeTokens.Button)
                        .clickable(role = Role.Button, onClick = onOpenSettings)
                        .padding(horizontal = spacing.sm, vertical = spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun HealthSummaryCard(
    report: AppHealthReport,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val statusColor = when (report.overallStatus) {
        AppHealthStatus.HEALTHY -> colors.statusSuccess
        AppHealthStatus.ATTENTION_NEEDED -> colors.accentPrimary
        AppHealthStatus.CRITICAL -> colors.statusDanger
    }
    val statusLabel = when (report.overallStatus) {
        AppHealthStatus.HEALTHY -> "Healthy"
        AppHealthStatus.ATTENTION_NEEDED -> "Attention Needed"
        AppHealthStatus.CRITICAL -> "Critical Issue"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LaterboxShapeTokens.Card,
        color = colors.surfacePrimary,
        border = BorderStroke(spacing.hairline, colors.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Icon(
                        painter = painterResource(id = LaterboxIcons.Health),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = statusColor
                    )
                    Text(
                        text = "App Health: $statusLabel",
                        style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = statusColor
                    )
                }
                Text(
                    text = "${report.availableStorageMb()} MB free",
                    style = typography.metaText,
                    color = colors.textSecondary
                )
            }

            Text(
                text = "yt-dlp: ${report.ytDlpStateLabel}${report.ytDlpVersion?.let { " v$it" } ?: ""} • FFmpeg: ${report.ffmpegStateLabel} • QuickJS: ${if (report.quickJsAvailable) "Ready" else "Missing"}",
                style = typography.metaText,
                color = colors.textSecondary
            )

            if (report.issues.isNotEmpty()) {
                report.issues.take(3).forEach { issue ->
                    Text(
                        text = "• ${issue.title}: ${issue.suggestedFix}",
                        style = typography.bodySecondary.copy(fontSize = 12.sp),
                        color = colors.textPrimary
                    )
                }
            } else {
                Text(
                    text = "Media extractor, storage, permissions, and connectivity look healthy.",
                    style = typography.bodySecondary.copy(fontSize = 12.sp),
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun ChatEmptyStarter(
    assistantEnabled: Boolean,
    aiDisclosureAccepted: Boolean,
    onQuickPromptClick: (String) -> Unit,
    onRunHealthCheck: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val starterPrompts = remember {
        listOf(
            "Can you extract YouTube transcripts?",
            "Summarize what I saved recently",
            "Why did a download or inspection fail?",
            "Find my unread videos",
            "How do I save links faster?"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.base, vertical = spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = LaterboxIcons.Sparkles),
            contentDescription = null,
            tint = colors.accentPrimary,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = "Ask Laterbox anything",
            style = typography.titleLarge,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = "Search your saved library, extract YouTube transcripts, check downloads, or run diagnostics.",
            style = typography.bodySecondary,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(spacing.md))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            starterPrompts.forEach { prompt ->
                StarterChip(
                    label = prompt,
                    enabled = assistantEnabled && aiDisclosureAccepted,
                    onClick = { onQuickPromptClick(prompt) }
                )
            }
            StarterChip(
                label = "Check App Health",
                enabled = true,
                onClick = onRunHealthCheck
            )
        }
    }
}

@Composable
private fun StarterChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Box(
        modifier = Modifier
            .clip(LaterboxShapeTokens.Chip)
            .background(colors.surfacePrimary)
            .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Chip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = typography.metaText.copy(fontSize = 13.sp),
            color = if (enabled) colors.textPrimary else colors.textTertiary,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
    message: ChatMessageEntity,
    displayedContent: String,
    isStreamingThis: Boolean,
    onRetry: () -> Unit,
    onActionClick: (ChatAction) -> Unit,
    onMarkdownLinkClick: ((String) -> Unit)?
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val isUser = message.role == ChatRole.USER.name

    val parsedActions = remember(message.actionsJson) {
        AssistantActionHandler.fromJsonList(message.actionsJson)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = if (isUser) colors.accentPrimary else colors.surfacePrimary,
                border = if (isUser) null else BorderStroke(spacing.hairline, colors.borderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isStreamingThis && displayedContent.isBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = colors.accentPrimary
                            )
                            Text(
                                text = "Thinking…",
                                style = typography.bodySecondary,
                                color = colors.textSecondary
                            )
                        }
                    } else if (displayedContent.isNotBlank()) {
                        MarkdownText(
                            markdown = displayedContent,
                            isUserMessage = isUser,
                            isStreaming = isStreamingThis,
                            onLinkClick = onMarkdownLinkClick
                        )
                    }

                    if (message.status == ChatMessageStatus.CANCELLED.name) {
                        Text(
                            text = "Stopped",
                            style = typography.metaText.copy(fontSize = 11.sp),
                            color = colors.textTertiary
                        )
                    }

                    if (message.status == ChatMessageStatus.FAILED.name) {
                        Text(
                            text = message.errorMessage ?: "Couldn't complete response.",
                            style = typography.bodySecondary.copy(fontSize = 12.sp),
                            color = colors.statusDanger
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(LaterboxShapeTokens.Chip)
                                .background(colors.surfaceSecondary)
                                .clickable(role = Role.Button, onClick = onRetry)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = LaterboxIcons.Refresh),
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = colors.accentPrimary
                            )
                            Text(
                                text = "Retry",
                                style = typography.metaText.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.accentPrimary
                            )
                        }
                    }
                }
            }

            if (!isUser && parsedActions.isNotEmpty() && !isStreamingThis) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    parsedActions.forEach { action ->
                        Box(
                            modifier = Modifier
                                .clip(LaterboxShapeTokens.Chip)
                                .background(colors.accentContainer)
                                .border(spacing.hairline, colors.accentPrimary.copy(alpha = 0.35f), LaterboxShapeTokens.Chip)
                                .clickable(role = Role.Button) { onActionClick(action) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = action.label,
                                style = typography.metaText.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = colors.accentPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
