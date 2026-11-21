package com.laterbox.app.remoteconfig

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun RemoteAnnouncementBannerCard(
    title: String,
    message: String,
    severity: RemoteAnnouncementSeverity,
    dismissible: Boolean,
    actionLabel: String = "",
    actionDestination: String = "",
    onActionClick: (String) -> Unit = {},
    onDismissClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    val bgColor = when (severity) {
        RemoteAnnouncementSeverity.CRITICAL -> colors.statusDanger.copy(alpha = 0.12f)
        RemoteAnnouncementSeverity.WARNING -> colors.statusWarning.copy(alpha = 0.12f)
        RemoteAnnouncementSeverity.INFO -> colors.accentContainer
    }
    val borderColor = when (severity) {
        RemoteAnnouncementSeverity.CRITICAL -> colors.statusDanger.copy(alpha = 0.35f)
        RemoteAnnouncementSeverity.WARNING -> colors.statusWarning.copy(alpha = 0.35f)
        RemoteAnnouncementSeverity.INFO -> colors.accentPrimary.copy(alpha = 0.28f)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(0.75.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_announcement_banner")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = typography.bodySecondary,
                    color = colors.textSecondary,
                )
            }
            if (actionDestination.isNotBlank() && actionLabel.isNotBlank() || dismissible && onDismissClick != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissible && onDismissClick != null) {
                        TextButton(onClick = onDismissClick) {
                            Text("Dismiss", color = colors.textSecondary)
                        }
                    }
                    if (actionDestination.isNotBlank() && actionLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(onClick = { onActionClick(actionDestination) }) {
                            Text(actionLabel, color = colors.accentPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteFeaturedCalloutCard(
    component: RemoteUiComponent,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.32f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_featured_callout_${component.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = component.title,
                    style = typography.uiMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                if (component.subtitle.isNotBlank()) {
                    Text(
                        text = component.subtitle,
                        style = typography.bodySecondary,
                        color = colors.textSecondary,
                    )
                }
            }
            if (component.actionDestination.isNotBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { onActionClick(component.actionDestination) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentPrimary,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    )
                ) {
                    Text(text = component.actionLabel.ifBlank { "Open" })
                }
            }
        }
    }
}

@Composable
fun RemoteMaintenanceNoticeBanner(
    maintenance: RemoteMaintenanceConfig,
    modifier: Modifier = Modifier,
) {
    if (!maintenance.enabled || maintenance.mode == RemoteMaintenanceMode.NONE) return
    val defaultTitle = if (maintenance.mode == RemoteMaintenanceMode.FULL) {
        "Scheduled Service Maintenance"
    } else {
        "Online Streaming Maintenance"
    }
    val defaultMsg = if (maintenance.allowLocalOfflineAccess) {
        "Remote services are temporarily paused. Your local Inbox, Reader, Collections, and Downloads remain available offline."
    } else {
        "Online services are temporarily paused for maintenance."
    }

    RemoteAnnouncementBannerCard(
        title = maintenance.title.ifBlank { defaultTitle },
        message = maintenance.message.ifBlank { defaultMsg },
        severity = if (maintenance.mode == RemoteMaintenanceMode.FULL) {
            RemoteAnnouncementSeverity.CRITICAL
        } else {
            RemoteAnnouncementSeverity.WARNING
        },
        dismissible = false,
        modifier = modifier,
    )
}

@Composable
fun RemoteForcedUpdateDialog(
    versionPolicy: RemoteVersionPolicy,
    onUpdateNow: () -> Unit,
) {
    val colors = LaterboxTheme.colors
    val tagNote = versionPolicy.recommendedTag.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    val body = versionPolicy.updateMessage.ifBlank {
        "This version of Laterbox is no longer supported$tagNote. Please update to continue using Laterbox."
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        containerColor = colors.surfacePrimary,
        title = {
            Text(
                text = "Update Required$tagNote",
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            Button(
                onClick = onUpdateNow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                )
            ) {
                Text("Update Now")
            }
        }
    )
}
