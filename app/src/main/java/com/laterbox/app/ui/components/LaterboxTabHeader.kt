package com.laterbox.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.laterbox.app.ui.theme.LaterboxTheme

/**
 * Shared top-level tab header used across primary Laterbox destinations
 * (Inbox, Collections, Movies & TV, Downloads, Archive) to guarantee consistent
 * spacing, menu button styling, typography, and right-aligned action buttons.
 */
@Composable
fun LaterboxTabHeader(
    title: String,
    subtitle: String? = null,
    onMenuClick: (() -> Unit)? = null,
    menuButtonTestTag: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.base, vertical = spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (onMenuClick != null) {
                val menuModifier = if (!menuButtonTestTag.isNullOrBlank()) {
                    Modifier.testTag(menuButtonTestTag)
                } else {
                    Modifier
                }
                LaterboxIconButton(
                    iconRes = LaterboxIcons.Menu,
                    contentDescription = "Open navigation menu",
                    onClick = onMenuClick,
                    modifier = menuModifier
                )
                Spacer(modifier = Modifier.width(spacing.sm))
            }
            Column {
                Text(
                    text = title,
                    style = typography.displayLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = typography.metaText,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            content = actions
        )
    }
}
