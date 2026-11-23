package com.laterbox.app.ui.onboarding

import android.app.StatusBarManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.laterbox.app.R
import com.laterbox.app.capture.ClipboardSuggestionManager
import com.laterbox.app.capture.UrlCaptureExtractor
import com.laterbox.app.capture.UrlExtractionResult
import com.laterbox.app.domain.util.UrlNormalizer
import com.laterbox.app.quicksave.QuickSaveTileService
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.theme.LaterboxTheme
import com.laterbox.app.ui.util.NotificationPermissionHelper
import com.laterbox.app.ui.util.rememberNotificationPermissionRequester

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onCompleteOnboardingWithOptions: ((seedStarterLink: Boolean, clipboardUrl: String?) -> Unit)? = null
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(1) }
    var seedStarterLink by remember { mutableStateOf(true) }

    val detectedClipboardLink = remember(context) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipMeta = ClipboardSuggestionManager.extractClipMetadata(cm)
            if (clipMeta != null && !clipMeta.isSensitive && !clipMeta.text.isNullOrBlank()) {
                val extracted = UrlCaptureExtractor.extract(clipMeta.text)
                if (extracted is UrlExtractionResult.SingleUrl) {
                    extracted.normalizedUrl to UrlNormalizer.extractDomain(extracted.normalizedUrl)
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }
    var saveClipboardUrl by remember(detectedClipboardLink) {
        mutableStateOf(detectedClipboardLink != null)
    }

    var notificationsGranted by remember {
        mutableStateOf(NotificationPermissionHelper.isNotificationPermissionGranted(context))
    }
    var notificationPermissionDenied by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        notificationsGranted = NotificationPermissionHelper.isNotificationPermissionGranted(context)
        onPauseOrDispose { }
    }

    val requestNotifications = rememberNotificationPermissionRequester(
        onDenied = {
            notificationsGranted = NotificationPermissionHelper.isNotificationPermissionGranted(context)
            if (!notificationsGranted) notificationPermissionDenied = true
        }
    )

    val completeFlow: () -> Unit = {
        val clipUrl = if (saveClipboardUrl) detectedClipboardLink?.first else null
        if (onCompleteOnboardingWithOptions != null) {
            onCompleteOnboardingWithOptions(seedStarterLink, clipUrl)
        } else {
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LaterboxTheme.colors.surfacePrimary,
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LaterboxTheme.spacing.l, vertical = LaterboxTheme.spacing.m)
        ) {
            // Header: 2-Step Indicators & Skip Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (1..2).forEach { stepIndex ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (currentStep == stepIndex) LaterboxTheme.colors.accentPrimary
                                    else LaterboxTheme.colors.borderSubtle
                                )
                        )
                        if (stepIndex < 2) Spacer(modifier = Modifier.width(6.dp))
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Skip",
                        style = LaterboxTheme.typography.metadataMono,
                        color = LaterboxTheme.colors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(LaterboxTheme.spacing.m))

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding_step"
            ) { step ->
                when (step) {
                    1 -> StepOneValueAndStarter(
                        seedStarterLink = seedStarterLink,
                        onSeedStarterLinkChange = { seedStarterLink = it },
                        clipboardDomain = detectedClipboardLink?.second,
                        saveClipboardUrl = saveClipboardUrl,
                        onSaveClipboardUrlChange = { saveClipboardUrl = it }
                    )

                    else -> StepTwoCaptureAndReminders(
                        notificationsGranted = notificationsGranted,
                        notificationPermissionDenied = notificationPermissionDenied,
                        onEnableNotificationsClick = {
                            requestNotifications {
                                notificationsGranted = NotificationPermissionHelper.isNotificationPermissionGranted(context)
                                if (notificationsGranted) notificationPermissionDenied = false
                            }
                        },
                        onOpenNotificationSettings = {
                            NotificationPermissionHelper.openAppNotificationSettings(context)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(LaterboxTheme.spacing.l))

            // Primary Action Button
            Button(
                onClick = {
                    if (currentStep == 1) {
                        currentStep = 2
                    } else {
                        completeFlow()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LaterboxTheme.spacing.minTouchTarget)
                    .testTag("onboarding_primary_cta"),
                shape = RoundedCornerShape(LaterboxTheme.spacing.radiusSmall),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LaterboxTheme.colors.accentPrimary,
                    contentColor = LaterboxTheme.colors.surfaceElevated
                )
            ) {
                Text(
                    text = if (currentStep == 1) "Next: Reminders & Capture →" else "Enter Laterbox",
                    style = LaterboxTheme.typography.uiMedium.copy(fontWeight = FontWeight.Medium)
                )
            }

            Spacer(modifier = Modifier.height(LaterboxTheme.spacing.m))
        }
    }
}

@Composable
private fun StepOneValueAndStarter(
    seedStarterLink: Boolean,
    onSeedStarterLinkChange: (Boolean) -> Unit,
    clipboardDomain: String?,
    saveClipboardUrl: Boolean,
    onSaveClipboardUrlChange: (Boolean) -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Column {
        Text(
            text = "STEP 1 OF 2 — SAVE, READ OFFLINE & REMEMBER",
            style = typography.metadataMono,
            color = colors.accentPrimary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = "A calm, private library for everything worth revisiting.",
            style = typography.editorialDisplay.copy(fontSize = 23.sp, lineHeight = 29.sp),
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(spacing.s))
        Text(
            text = "Share links from any browser or social app, read clean distraction-free articles offline, extract YouTube transcripts, and set gentle reminders — stored 100% locally.",
            style = typography.bodyPrimary,
            color = colors.textSecondary
        )

        Spacer(modifier = Modifier.height(spacing.md))

        // Interactive Starter Guide Seed Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(spacing.cardRadius))
                .background(colors.surfaceSecondary)
                .border(spacing.hairline, colors.borderSubtle, RoundedCornerShape(spacing.cardRadius))
                .clickable(role = Role.Switch) { onSeedStarterLinkChange(!seedStarterLink) }
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                Text(
                    text = "Add a starter guide to my Inbox",
                    style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary
                )
                Text(
                    text = "Try Reader Mode & card triage immediately",
                    style = typography.metaText,
                    color = colors.textSecondary
                )
            }
            Switch(
                checked = seedStarterLink,
                onCheckedChange = onSeedStarterLinkChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.surfaceElevated,
                    checkedTrackColor = colors.accentPrimary
                )
            )
        }

        // Optional 1-Tap Clipboard URL Offer
        if (clipboardDomain != null) {
            Spacer(modifier = Modifier.height(spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(spacing.cardRadius))
                    .background(colors.accentContainer.copy(alpha = 0.45f))
                    .border(spacing.hairline, colors.accentPrimary.copy(alpha = 0.35f), RoundedCornerShape(spacing.cardRadius))
                    .clickable(role = Role.Switch) { onSaveClipboardUrlChange(!saveClipboardUrl) }
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = LaterboxIcons.Globe),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = colors.accentPrimary
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Column {
                        Text(
                            text = "Save link on clipboard",
                            style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary
                        )
                        Text(
                            text = clipboardDomain,
                            style = typography.metaText,
                            color = colors.accentPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = saveClipboardUrl,
                    onCheckedChange = onSaveClipboardUrlChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.surfaceElevated,
                        checkedTrackColor = colors.accentPrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun StepTwoCaptureAndReminders(
    notificationsGranted: Boolean,
    notificationPermissionDenied: Boolean,
    onEnableNotificationsClick: () -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val context = LocalContext.current
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Column {
        Text(
            text = "STEP 2 OF 2 — REMINDERS & 1-TAP CAPTURE",
            style = typography.metadataMono,
            color = colors.accentPrimary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = "Remember what you saved, when it counts.",
            style = typography.editorialDisplay.copy(fontSize = 23.sp, lineHeight = 29.sp),
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(spacing.s))
        Text(
            text = "Allow gentle reminder notifications for links you schedule, and add the Quick Settings tile to capture copied URLs from anywhere.",
            style = typography.bodyPrimary,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(spacing.md))

        // Reminder Notification Permission Card
        if (notificationsGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(spacing.cardRadius))
                    .background(colors.accentContainer.copy(alpha = 0.4f))
                    .border(spacing.hairline, colors.accentPrimary.copy(alpha = 0.35f), RoundedCornerShape(spacing.cardRadius))
                    .padding(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.Check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.accentPrimary
                )
                Text(
                    text = "Reminder notifications enabled",
                    style = typography.uiMedium,
                    color = colors.accentPrimary
                )
            }
        } else {
            LaterboxSecondaryButton(
                text = if (notificationPermissionDenied) "Open Notification Settings" else "Enable Reminder Notifications",
                onClick = if (notificationPermissionDenied) onOpenNotificationSettings else onEnableNotificationsClick,
                leadingIcon = LaterboxIcons.Bell,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(spacing.sm))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LaterboxSecondaryButton(
                text = "Add 'Save to Laterbox' Quick Tile",
                onClick = {
                    val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                    val componentName = ComponentName(context, QuickSaveTileService::class.java)
                    statusBarManager?.requestAddTileService(
                        componentName,
                        context.getString(R.string.save_to_laterbox),
                        AndroidIcon.createWithResource(context, R.drawable.ic_tile_save),
                        { it.run() },
                        { result ->
                            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                                com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                                    context = context,
                                    message = "Tile added to Quick Settings!",
                                    variant = com.laterbox.app.ui.components.LaterboxToastVariant.SUCCESS
                                )
                            }
                        }
                    )
                },
                leadingIcon = LaterboxIcons.Plus,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(spacing.cardRadius))
                    .background(colors.surfaceSecondary)
                    .border(spacing.hairline, colors.borderSubtle, RoundedCornerShape(spacing.cardRadius))
                    .padding(spacing.md),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Quick Tile: Pull down Quick Settings → Edit → Drag 'Save to Laterbox'",
                    style = typography.metadataMono,
                    color = colors.textPrimary
                )
            }
        }
    }
}
