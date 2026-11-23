package com.laterbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.data.preferences.CardTapAction
import com.laterbox.app.data.preferences.InboxViewMode
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun ReadingBehaviorSettingsScreen(
    preferences: UserPreferences,
    onAutoMarkAsReadChange: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    onDefaultCardTapActionChange: (CardTapAction) -> Unit = {},
    onInboxViewModeChange: (InboxViewMode) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
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
                title = "Reading & Behaviour",
                onBackClick = onBackClick
            )

            Spacer(modifier = Modifier.height(spacing.xs))

            SettingsSectionHeader(title = "Reading Behaviour")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-mark as read",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Mark links as read automatically when opened from the inbox or reader",
                                style = typography.bodySecondary.copy(fontSize = 12.sp),
                                color = colors.textSecondary
                            )
                        }

                        Switch(
                            checked = preferences.autoMarkAsRead,
                            onCheckedChange = onAutoMarkAsReadChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.surfaceElevated,
                                checkedTrackColor = colors.accentPrimary
                            )
                        )
                    }

                    SettingsDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = "Default Inbox Layout",
                            style = typography.uiMedium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Choose how saved links are presented in your lists",
                            style = typography.bodySecondary.copy(fontSize = 12.sp),
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(spacing.xxs))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            ThemeOptionChip(
                                label = "Hero Cards",
                                isSelected = preferences.inboxViewMode == InboxViewMode.HERO,
                                onClick = { onInboxViewModeChange(InboxViewMode.HERO) }
                            )
                            ThemeOptionChip(
                                label = "Compact List",
                                isSelected = preferences.inboxViewMode == InboxViewMode.COMPACT,
                                onClick = { onInboxViewModeChange(InboxViewMode.COMPACT) }
                            )
                            ThemeOptionChip(
                                label = "Editorial",
                                isSelected = preferences.inboxViewMode == InboxViewMode.EDITORIAL,
                                onClick = { onInboxViewModeChange(InboxViewMode.EDITORIAL) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.lg))

            SettingsSectionHeader(title = "Default Card Tap Action")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = "Choose what happens when you tap a saved card",
                        style = typography.bodySecondary.copy(fontSize = 12.sp),
                        color = colors.textSecondary
                    )
                    CardTapAction.entries.forEach { action ->
                        val isSelected = preferences.defaultCardTapAction == action
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(spacing.radiusSmall))
                                .background(
                                    if (isSelected) colors.accentContainer.copy(alpha = 0.45f)
                                    else colors.surfaceSecondary
                                )
                                .border(
                                    spacing.hairline,
                                    if (isSelected) colors.accentPrimary else colors.borderSubtle,
                                    RoundedCornerShape(spacing.radiusSmall)
                                )
                                .clickable(
                                    role = Role.RadioButton,
                                    onClickLabel = action.label
                                ) {
                                    onDefaultCardTapActionChange(action)
                                }
                                .padding(horizontal = spacing.md, vertical = spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 5.dp else 1.5.dp,
                                        color = if (isSelected) colors.accentPrimary else colors.textTertiary,
                                        shape = CircleShape
                                    )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = action.label,
                                    style = typography.uiMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) colors.accentPrimary else colors.textPrimary
                                )
                                Text(
                                    text = action.description,
                                    style = typography.metaText,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.navigationBarsPadding().height(40.dp))
        }
    }
}
