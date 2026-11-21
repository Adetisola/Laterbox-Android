package com.laterbox.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.ui.navigation.LaterboxDestination
import com.laterbox.app.ui.theme.LaterboxTheme

enum class TabQuickActionKind {
    SAVE_LINK,
    NEW_COLLECTION,
    WATCHLIST
}

data class TabQuickActionSpec(
    val kind: TabQuickActionKind,
    val primaryLabel: String,
    @DrawableRes val primaryIconRes: Int,
    val primaryButtonTestTag: String
)

object ContextualTabDockResolver {
    fun resolveForRoute(route: String?): TabQuickActionSpec? = when (route) {
        LaterboxDestination.Inbox.route -> TabQuickActionSpec(
            kind = TabQuickActionKind.SAVE_LINK,
            primaryLabel = "Save Link",
            primaryIconRes = LaterboxIcons.Plus,
            primaryButtonTestTag = "inbox_dock_save_button"
        )
        LaterboxDestination.Collections.route -> TabQuickActionSpec(
            kind = TabQuickActionKind.NEW_COLLECTION,
            primaryLabel = "New Collection",
            primaryIconRes = LaterboxIcons.FolderAdd,
            primaryButtonTestTag = "collections_dock_new_button"
        )
        LaterboxDestination.Movies.route -> TabQuickActionSpec(
            kind = TabQuickActionKind.WATCHLIST,
            primaryLabel = "Watchlist",
            primaryIconRes = LaterboxIcons.Bookmark,
            primaryButtonTestTag = "movies_dock_watchlist_button"
        )
        else -> null
    }
}

/**
 * Compact floating quick-action dock shared across primary content tabs
 * (Inbox, Collections, Movies & TV), adapting the primary action to each tab's context.
 */
@Composable
fun InboxActionDock(
    assistantEnabled: Boolean,
    onAskClick: () -> Unit,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier,
    primaryLabel: String = "Save Link",
    @DrawableRes primaryIconRes: Int = LaterboxIcons.Plus,
    primaryButtonTestTag: String = "inbox_dock_save_button",
    dockTestTag: String = "inbox_action_dock",
    askButtonTestTag: String = "inbox_dock_ask_button"
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(dockTestTag),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (assistantEnabled) {
            Surface(
                onClick = onAskClick,
                shape = RoundedCornerShape(24.dp),
                color = colors.surfaceElevated,
                contentColor = colors.textPrimary,
                shadowElevation = 5.dp,
                border = BorderStroke(spacing.hairline, colors.borderSubtle),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .semantics { role = Role.Button }
                    .testTag(askButtonTestTag)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = LaterboxIcons.Sparkles),
                        contentDescription = null,
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Ask Laterbox",
                        style = typography.uiMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = colors.textPrimary
                    )
                }
            }
        }

        Surface(
            onClick = onSaveClick,
            shape = RoundedCornerShape(24.dp),
            color = colors.accentPrimary,
            contentColor = colors.onAccentPrimary,
            shadowElevation = 5.dp,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .semantics { role = Role.Button }
                .testTag(primaryButtonTestTag)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = primaryIconRes),
                    contentDescription = null,
                    tint = colors.onAccentPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = primaryLabel,
                    style = typography.uiMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.onAccentPrimary
                )
            }
        }
    }
}
