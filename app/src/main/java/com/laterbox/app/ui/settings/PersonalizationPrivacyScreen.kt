package com.laterbox.app.ui.settings

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.intelligence.TopicExtractor
import com.laterbox.app.ui.components.LaterboxBanner
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlin.math.roundToInt

private val QUICK_TUNE_GENRES = listOf(
    "Sci-Fi",
    "Drama",
    "Action",
    "Comedy",
    "Thriller",
    "Anime",
    "Documentary",
    "Mystery",
    "Animation",
    "Fantasy"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalizationPrivacyScreen(
    preferences: UserPreferences,
    facets: List<UserTasteFacetEntity>,
    signalCount: Int,
    onPersonalizationEnabledChange: (Boolean) -> Unit,
    onAssistantPersonalContextEnabledChange: (Boolean) -> Unit,
    onSetExplicitPreference: (facetKey: String, displayLabel: String, scope: IntelligenceSurfaceScope, liked: Boolean) -> Unit,
    onToggleSuppressFacet: (facetKey: String, suppressed: Boolean) -> Unit,
    onToggleIncludeInAssistant: (facetKey: String, include: Boolean) -> Unit,
    onDeleteFacet: (facetKey: String) -> Unit,
    onRebuildFromLibrary: () -> Unit,
    onResetModel: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val facetByKey = remember(facets) { facets.associateBy { it.facetKey } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("personalization_privacy_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSubpageHeader(
                title = "Personalization & Privacy",
                onBackClick = onBackClick
            )

            Spacer(modifier = Modifier.height(spacing.xs))

            Box(modifier = Modifier.padding(horizontal = spacing.base)) {
                LaterboxBanner(
                    text = "100% on-device: your taste profile and signals stay in local storage and are never synced to cloud accounts.",
                    isSuccess = true
                )
            }

            Spacer(modifier = Modifier.height(spacing.md))

            SettingsSectionHeader(title = "Controls")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                            Text(
                                text = "On-Device Personalization",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Learns your non-sensitive preferences locally to rank Movies & TV picks.",
                                style = typography.bodySecondary.copy(fontSize = 12.sp),
                                color = colors.textSecondary
                            )
                        }
                        Switch(
                            checked = preferences.personalizationEnabled,
                            onCheckedChange = onPersonalizationEnabledChange,
                            modifier = Modifier.testTag("personalization_enabled_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.surfaceElevated,
                                checkedTrackColor = colors.accentPrimary
                            )
                        )
                    }

                    SettingsDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
                            Text(
                                text = "Assistant Taste Context",
                                style = typography.uiMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Opt-in: share only approved high-level interests (never raw collection names) when chatting with Assistant.",
                                style = typography.bodySecondary.copy(fontSize = 12.sp),
                                color = colors.textSecondary
                            )
                        }
                        Switch(
                            checked = preferences.assistantPersonalContextEnabled,
                            enabled = preferences.personalizationEnabled,
                            onCheckedChange = onAssistantPersonalContextEnabledChange,
                            modifier = Modifier.testTag("assistant_personal_context_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.surfaceElevated,
                                checkedTrackColor = colors.accentPrimary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            SettingsSectionHeader(title = "Tune Movies & TV Tastes")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = "Tap any genre to pin it as a favourite, or tap again to remove.",
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        QUICK_TUNE_GENRES.forEach { genre ->
                            val key = TopicExtractor.genreFacetKey(genre)
                            val existing = facetByKey[key]
                            val isPinnedLike = existing?.facetType == TasteFacetType.EXPLICIT_LIKE.name && !existing.isSuppressedByUser
                            ThemeOptionChip(
                                label = if (isPinnedLike) "★ $genre" else genre,
                                isSelected = isPinnedLike,
                                onClick = {
                                    if (isPinnedLike) {
                                        onDeleteFacet(key)
                                    } else {
                                        onSetExplicitPreference(
                                            key,
                                            genre,
                                            IntelligenceSurfaceScope.MOVIES,
                                            true
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            SettingsSectionHeader(title = "Model Inspector")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${facets.size} learned ${if (facets.size == 1) "facet" else "facets"}",
                            style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary
                        )
                        Text(
                            text = "$signalCount / 500 local signals",
                            style = typography.metaText,
                            color = colors.textSecondary,
                            modifier = Modifier.testTag("personalization_signal_count")
                        )
                    }

                    if (facets.isEmpty()) {
                        Text(
                            text = "No taste facets stored right now. Activity in Movies & TV or reading non-sensitive links builds your model locally.",
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                        if (preferences.personalizationEnabled) {
                            LaterboxSecondaryButton(
                                text = "Rebuild from Local Library",
                                onClick = onRebuildFromLibrary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("personalization_rebuild_button")
                            )
                        }
                    } else {
                        facets.forEachIndexed { index, facet ->
                            if (index > 0) SettingsDivider()
                            FacetInspectorRow(
                                facet = facet,
                                assistantSharingEnabled = preferences.assistantPersonalContextEnabled,
                                onLike = {
                                    val scope = runCatching {
                                        IntelligenceSurfaceScope.valueOf(facet.surfaceScope)
                                    }.getOrDefault(IntelligenceSurfaceScope.ALL)
                                    onSetExplicitPreference(facet.facetKey, facet.displayLabel, scope, true)
                                },
                                onDislike = {
                                    val scope = runCatching {
                                        IntelligenceSurfaceScope.valueOf(facet.surfaceScope)
                                    }.getOrDefault(IntelligenceSurfaceScope.ALL)
                                    onSetExplicitPreference(facet.facetKey, facet.displayLabel, scope, false)
                                },
                                onToggleSuppress = {
                                    onToggleSuppressFacet(facet.facetKey, !facet.isSuppressedByUser)
                                },
                                onToggleAssistant = {
                                    onToggleIncludeInAssistant(facet.facetKey, !facet.includeInAssistantContext)
                                },
                                onDelete = { onDeleteFacet(facet.facetKey) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            SettingsSectionHeader(title = "Reset & Wipe")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = "Wipe all learned taste facets and local intelligence signals. Your saved links, collections, watchlist, watch history, and downloads are never removed.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                    LaterboxSecondaryButton(
                        text = "Reset Personalization Model",
                        onClick = { showResetConfirmDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("personalization_reset_button")
                    )
                }
            }

            Spacer(modifier = Modifier.navigationBarsPadding().height(48.dp))
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            containerColor = colors.surfaceElevated,
            title = {
                Text(
                    text = "Reset personalization model?",
                    style = typography.titleSmall,
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Clears all learned taste facets and local signals on this device. Your saved links, collections, watchlist, watch history, and downloads will stay intact.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel", style = typography.uiMedium, color = colors.textSecondary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        onResetModel()
                    },
                    modifier = Modifier.testTag("personalization_confirm_reset_button")
                ) {
                    Text("Reset Model", style = typography.uiMedium, color = colors.statusDanger)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetInspectorRow(
    facet: UserTasteFacetEntity,
    assistantSharingEnabled: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onToggleSuppress: () -> Unit,
    onToggleAssistant: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val typeBadge = when {
        facet.isSuppressedByUser -> "Muted"
        facet.facetType == TasteFacetType.EXPLICIT_LIKE.name -> "Pinned Like"
        facet.facetType == TasteFacetType.EXPLICIT_DISLIKE.name -> "Disliked"
        facet.facetType == TasteFacetType.SHORT_TERM_INTENT.name -> "Recent Intent"
        facet.facetType == TasteFacetType.ACTIVE_GOAL.name -> "Active Goal"
        else -> "Interest"
    }
    val scopeBadge = when (facet.surfaceScope) {
        IntelligenceSurfaceScope.MOVIES.name -> "Movies & TV"
        IntelligenceSurfaceScope.LIBRARY.name -> "Library"
        else -> "All"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("facet_row_${facet.facetKey}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = facet.displayLabel,
                style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (facet.isSuppressedByUser) colors.textSecondary else colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$scopeBadge · $typeBadge · ${(facet.score * 100).roundToInt()}%",
                style = typography.metaText.copy(fontSize = 11.sp),
                color = colors.textSecondary
            )
        }

        LinearProgressIndicator(
            progress = { facet.score.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = when {
                facet.isSuppressedByUser -> colors.textSecondary.copy(alpha = 0.35f)
                facet.facetType == TasteFacetType.EXPLICIT_DISLIKE.name -> colors.statusDanger
                else -> colors.accentPrimary
            },
            trackColor = colors.surfaceSecondary
        )

        if (facet.provenance.isNotBlank()) {
            Text(
                text = "Why: ${facet.provenance}",
                style = typography.metaText,
                color = colors.textSecondary
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InspectorActionPill(
                label = "Like",
                selected = facet.facetType == TasteFacetType.EXPLICIT_LIKE.name && !facet.isSuppressedByUser,
                onClick = onLike
            )
            InspectorActionPill(
                label = "Dislike",
                selected = facet.facetType == TasteFacetType.EXPLICIT_DISLIKE.name && !facet.isSuppressedByUser,
                onClick = onDislike
            )
            InspectorActionPill(
                label = if (facet.isSuppressedByUser) "Unmute" else "Mute",
                selected = facet.isSuppressedByUser,
                onClick = onToggleSuppress
            )
            if (assistantSharingEnabled && !facet.isSuppressedByUser) {
                InspectorActionPill(
                    label = if (facet.includeInAssistantContext) "Assistant: On" else "Assistant: Off",
                    selected = facet.includeInAssistantContext,
                    onClick = onToggleAssistant
                )
            }
            InspectorActionPill(
                label = "Remove",
                selected = false,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun InspectorActionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LaterboxTheme.colors
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.accentContainer else colors.surfaceSecondary,
        border = BorderStroke(
            0.75.dp,
            if (selected) colors.accentPrimary else colors.borderSubtle
        )
    ) {
        Text(
            text = label,
            style = LaterboxTheme.typography.metaText.copy(
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) colors.accentPrimary else colors.textPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
