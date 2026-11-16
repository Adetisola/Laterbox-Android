package com.laterbox.app.ui.components

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.ui.theme.LaterboxTheme

/**
 * Compact floating action dock for the Inbox screen.
 * Keeps the primary reading surface calm while offering 1-tap access to Save Link
 * and an expandable Ask Laterbox assistant entry point.
 */
@Composable
fun InboxActionDock(
    assistantEnabled: Boolean,
    onAskClick: () -> Unit,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("inbox_action_dock"),
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
                    .testTag("inbox_dock_ask_button")
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
                .testTag("inbox_dock_save_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.Plus),
                    contentDescription = null,
                    tint = colors.onAccentPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Save",
                    style = typography.uiMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.onAccentPrimary
                )
            }
        }
    }
}
