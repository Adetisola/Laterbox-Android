package com.laterbox.app.remoteconfig

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.laterbox.app.ui.components.LaterboxIcons
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
fun RemoteMaintenanceStatusStrip(
    maintenance: RemoteMaintenanceConfig,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (!maintenance.shouldShowStatusStrip) return

    val backgroundColor = when {
        maintenance.isFullActive -> androidx.compose.ui.graphics.Color(0xFFB71C1C)
        maintenance.isPartialActive -> androidx.compose.ui.graphics.Color(0xFFE65100)
        else -> androidx.compose.ui.graphics.Color(0xFF1B5E20)
    }

    val defaultMessage = when {
        maintenance.isFullActive && maintenance.allowLocalOfflineAccess ->
            "Full maintenance in progress. Online streaming and Assistant are paused; saved items and downloads work offline."
        maintenance.isFullActive ->
            "Scheduled maintenance in progress. Online services are temporarily unavailable."
        maintenance.isPartialActive ->
            "Partial maintenance: Online streaming and Assistant are paused; saved links and offline downloads work normally."
        else -> maintenance.message
    }

    val displayText = maintenance.message.ifBlank { defaultMessage }
    val statusLabel = when {
        maintenance.isFullActive -> "Full maintenance notice"
        maintenance.isPartialActive -> "Partial maintenance notice"
        else -> "Service announcement"
    }

    val iconRes = when {
        maintenance.isFullActive || maintenance.isPartialActive -> LaterboxIcons.Health
        else -> LaterboxIcons.Bell
    }

    Surface(
        color = backgroundColor,
        contentColor = androidx.compose.ui.graphics.Color.White,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$statusLabel: $displayText"
            }
            .testTag("remote_maintenance_top_strip")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(id = iconRes),
                contentDescription = statusLabel,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = displayText,
                style = LaterboxTheme.typography.caption.copy(
                    fontWeight = FontWeight.Medium,
                    color = androidx.compose.ui.graphics.Color.White,
                ),
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = if (reducedMotion) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .let { mod ->
                        if (reducedMotion) {
                            mod
                        } else {
                            mod.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                velocity = 28.dp
                            )
                        }
                    }
            )
        }
    }
}

@Composable
fun RemoteFloatingNoticeDeck(
    notices: List<RemoteAnnouncementItem>,
    onActionClick: (String) -> Unit,
    onDismissClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return

    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    var selectedIndex by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    val safeIndex = selectedIndex.coerceIn(0, notices.lastIndex)
    val notice = notices[safeIndex]

    val containerColor = colors.surfaceElevated
    val borderColor = when (notice.severity) {
        RemoteAnnouncementSeverity.CRITICAL -> colors.statusDanger.copy(alpha = 0.55f)
        RemoteAnnouncementSeverity.WARNING -> colors.statusWarning.copy(alpha = 0.55f)
        RemoteAnnouncementSeverity.INFO -> colors.accentPrimary.copy(alpha = 0.38f)
    }
    val badgeLabel = when (notice.severity) {
        RemoteAnnouncementSeverity.CRITICAL -> "Important"
        RemoteAnnouncementSeverity.WARNING -> "Warning"
        RemoteAnnouncementSeverity.INFO -> "Notice"
    }
    val iconRes = when (notice.severity) {
        RemoteAnnouncementSeverity.CRITICAL -> LaterboxIcons.Health
        RemoteAnnouncementSeverity.WARNING -> LaterboxIcons.Health
        RemoteAnnouncementSeverity.INFO -> LaterboxIcons.Bell
    }
    val accentTint = when (notice.severity) {
        RemoteAnnouncementSeverity.CRITICAL -> colors.statusDanger
        RemoteAnnouncementSeverity.WARNING -> colors.statusWarning
        RemoteAnnouncementSeverity.INFO -> colors.accentPrimary
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_floating_notice_deck")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(id = iconRes),
                    contentDescription = badgeLabel,
                    tint = accentTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (notice.title.isNotBlank()) notice.title else badgeLabel,
                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = accentTint,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (notices.size > 1) {
                    TextButton(
                        onClick = {
                            selectedIndex = if (safeIndex == 0) notices.lastIndex else safeIndex - 1
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("${safeIndex + 1}/${notices.size} ▸", style = typography.caption, color = colors.textSecondary)
                    }
                }
                if (notice.dismissible) {
                    androidx.compose.material3.IconButton(
                        onClick = { onDismissClick(notice.id) },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("remote_notice_dismiss_${notice.id}")
                    ) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(id = LaterboxIcons.Close),
                            contentDescription = "Dismiss notice",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Text(
                text = notice.message,
                style = typography.bodySecondary,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (notice.actionDestination.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onActionClick(notice.actionDestination) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = notice.actionLabel.ifBlank { "Open" },
                            style = typography.buttonLabel,
                            color = accentTint,
                        )
                    }
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
