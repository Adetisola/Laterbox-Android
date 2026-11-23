package com.laterbox.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.ui.theme.LaterboxMotion
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class LaterboxToastVariant {
    NEUTRAL,
    SUCCESS,
    WARNING,
    ERROR,
    OFFLINE
}

@Immutable
data class LaterboxToastItem(
    val id: String,
    val message: String,
    val variant: LaterboxToastVariant = LaterboxToastVariant.NEUTRAL,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
    val durationMs: Long? = LaterboxToastController.DEFAULT_DURATION_MS,
    val dedupeKey: String? = null,
    val persistent: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

class LaterboxToastController(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_DURATION_MS = 5_000L
        const val MAX_VISIBLE_TOASTS = 3
        const val OFFLINE_DEDUPE_KEY = "system_offline"
    }

    private val lock = Any()
    private val _toasts = MutableStateFlow<List<LaterboxToastItem>>(emptyList())
    val toasts: StateFlow<List<LaterboxToastItem>> = _toasts.asStateFlow()

    private val autoDismissJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    var isInAppHostActive: Boolean = false

    fun showNeutral(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long? = DEFAULT_DURATION_MS,
        dedupeKey: String? = null
    ): String = show(
        message = message,
        variant = LaterboxToastVariant.NEUTRAL,
        actionLabel = actionLabel,
        onAction = onAction,
        durationMs = durationMs,
        dedupeKey = dedupeKey
    )

    fun showSuccess(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long? = DEFAULT_DURATION_MS,
        dedupeKey: String? = null
    ): String = show(
        message = message,
        variant = LaterboxToastVariant.SUCCESS,
        actionLabel = actionLabel,
        onAction = onAction,
        durationMs = durationMs,
        dedupeKey = dedupeKey
    )

    fun showWarning(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long? = DEFAULT_DURATION_MS,
        dedupeKey: String? = null
    ): String = show(
        message = message,
        variant = LaterboxToastVariant.WARNING,
        actionLabel = actionLabel,
        onAction = onAction,
        durationMs = durationMs,
        dedupeKey = dedupeKey
    )

    fun showError(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMs: Long? = DEFAULT_DURATION_MS,
        dedupeKey: String? = null
    ): String = show(
        message = message,
        variant = LaterboxToastVariant.ERROR,
        actionLabel = actionLabel,
        onAction = onAction,
        durationMs = durationMs,
        dedupeKey = dedupeKey
    )

    fun showOffline(
        message: String = "You're offline",
        onManualDismiss: (() -> Unit)? = null
    ): String = show(
        message = message,
        variant = LaterboxToastVariant.OFFLINE,
        onDismiss = onManualDismiss,
        durationMs = null,
        dedupeKey = OFFLINE_DEDUPE_KEY,
        persistent = true
    )

    fun show(
        message: String,
        variant: LaterboxToastVariant = LaterboxToastVariant.NEUTRAL,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        durationMs: Long? = if (variant == LaterboxToastVariant.OFFLINE) null else DEFAULT_DURATION_MS,
        dedupeKey: String? = null,
        persistent: Boolean = variant == LaterboxToastVariant.OFFLINE
    ): String {
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) return ""
        val effectiveDedupeKey = dedupeKey?.takeIf { it.isNotBlank() } ?: "${variant.name}:$cleanMessage"
        val effectiveDuration = if (persistent) null else (durationMs ?: DEFAULT_DURATION_MS)
        val now = clockMs()

        val resultItem: LaterboxToastItem
        val evictedIds = mutableListOf<String>()
        var replacedOnDismiss: (() -> Unit)? = null

        synchronized(lock) {
            val current = _toasts.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.dedupeKey == effectiveDedupeKey }

            if (existingIdx >= 0) {
                val existing = current.removeAt(existingIdx)
                replacedOnDismiss = existing.onDismiss
                resultItem = existing.copy(
                    message = cleanMessage,
                    variant = variant,
                    actionLabel = actionLabel,
                    onAction = onAction,
                    onDismiss = onDismiss,
                    durationMs = effectiveDuration,
                    persistent = persistent,
                    createdAtMs = now
                )
                // Place updated notification at top as newest feedback
                current.add(0, resultItem)
            } else {
                resultItem = LaterboxToastItem(
                    id = UUID.randomUUID().toString(),
                    message = cleanMessage,
                    variant = variant,
                    actionLabel = actionLabel,
                    onAction = onAction,
                    onDismiss = onDismiss,
                    durationMs = effectiveDuration,
                    dedupeKey = effectiveDedupeKey,
                    persistent = persistent,
                    createdAtMs = now
                )
                current.add(0, resultItem)
            }

            while (current.size > MAX_VISIBLE_TOASTS) {
                // Evict oldest non-persistent toast first; persistent offline toast stays unevicted.
                val evictIndex = current.indexOfLast { !it.persistent }
                    .let { if (it >= 0) it else current.lastIndex }
                val removed = current.removeAt(evictIndex)
                evictedIds.add(removed.id)
            }

            _toasts.value = current
        }

        evictedIds.forEach { id -> autoDismissJobs.remove(id)?.cancel() }
        replacedOnDismiss?.invoke()
        scheduleAutoDismiss(resultItem)
        return resultItem.id
    }

    suspend fun showAndAwaitAction(
        message: String,
        variant: LaterboxToastVariant = LaterboxToastVariant.NEUTRAL,
        actionLabel: String? = null,
        durationMs: Long = DEFAULT_DURATION_MS,
        dedupeKey: String? = null
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        show(
            message = message,
            variant = variant,
            actionLabel = actionLabel,
            onAction = if (actionLabel != null) {
                { deferred.complete(true) }
            } else {
                null
            },
            onDismiss = { deferred.complete(false) },
            durationMs = durationMs,
            dedupeKey = dedupeKey,
            persistent = false
        )
        return deferred.await()
    }

    fun dismiss(id: String, byUser: Boolean = true) {
        autoDismissJobs.remove(id)?.cancel()
        val removed: LaterboxToastItem?
        synchronized(lock) {
            val current = _toasts.value.toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            removed = if (idx >= 0) current.removeAt(idx) else null
            if (removed != null) {
                _toasts.value = current
            }
        }
        if (byUser) {
            removed?.onDismiss?.invoke()
        }
    }

    fun dismissByDedupeKey(dedupeKey: String, invokeDismissCallback: Boolean = false) {
        val matchingIds = synchronized(lock) {
            _toasts.value.filter { it.dedupeKey == dedupeKey }.map { it.id }
        }
        matchingIds.forEach { dismiss(it, byUser = invokeDismissCallback) }
    }

    fun performAction(id: String) {
        autoDismissJobs.remove(id)?.cancel()
        val target: LaterboxToastItem?
        synchronized(lock) {
            val current = _toasts.value.toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            target = if (idx >= 0) current.removeAt(idx) else null
            if (target != null) {
                _toasts.value = current
            }
        }
        target?.onAction?.invoke()
    }

    fun clearAll() {
        autoDismissJobs.values.forEach { it.cancel() }
        autoDismissJobs.clear()
        synchronized(lock) {
            _toasts.value = emptyList()
        }
    }

    private fun scheduleAutoDismiss(item: LaterboxToastItem) {
        autoDismissJobs.remove(item.id)?.cancel()
        val timeout = item.durationMs
        if (!item.persistent && timeout != null && timeout > 0L) {
            val job = scope.launch {
                delay(timeout)
                autoDismissJobs.remove(item.id)
                val removed: LaterboxToastItem?
                synchronized(lock) {
                    val current = _toasts.value.toMutableList()
                    val idx = current.indexOfFirst { it.id == item.id }
                    removed = if (idx >= 0) current.removeAt(idx) else null
                    if (removed != null) {
                        _toasts.value = current
                    }
                }
                removed?.onDismiss?.invoke()
            }
            autoDismissJobs[item.id] = job
        }
    }
}

val LocalLaterboxToastController = staticCompositionLocalOf<LaterboxToastController?> { null }

@Composable
fun LaterboxToastHost(
    toasts: List<LaterboxToastItem>,
    onDismissToast: (String) -> Unit,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 16.dp,
    reducedMotion: Boolean = false,
    toastController: LaterboxToastController? = null
) {
    if (toastController != null) {
        DisposableEffect(toastController) {
            toastController.isInAppHostActive = true
            onDispose {
                toastController.isInAppHostActive = false
            }
        }
    }

    if (toasts.isEmpty()) return

    val colModifier = modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)
        .let { base ->
            if (reducedMotion) {
                base
            } else {
                base.animateContentSize(
                    animationSpec = LaterboxMotion.tweenSpec(
                        LaterboxMotion.durationShort,
                        LaterboxMotion.decelerateEasing,
                        reducedMotion = false
                    )
                )
            }
        }
        .testTag("laterbox_toast_host")

    Column(
        modifier = colModifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        toasts.take(LaterboxToastController.MAX_VISIBLE_TOASTS).forEach { toast ->
            LaterboxToastCard(
                toast = toast,
                onDismiss = { onDismissToast(toast.id) },
                onActionClick = { onActionClick(toast.id) }
            )
        }
    }
}

@Composable
private fun LaterboxToastCard(
    toast: LaterboxToastItem,
    onDismiss: () -> Unit,
    onActionClick: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    val accentColor = when (toast.variant) {
        LaterboxToastVariant.SUCCESS -> colors.statusSuccess
        LaterboxToastVariant.WARNING -> colors.statusWarning
        LaterboxToastVariant.ERROR -> colors.statusDanger
        LaterboxToastVariant.OFFLINE -> colors.statusWarning
        LaterboxToastVariant.NEUTRAL -> colors.accentPrimary
    }

    val badgeIconRes = when (toast.variant) {
        LaterboxToastVariant.SUCCESS -> LaterboxIcons.Check
        LaterboxToastVariant.WARNING -> LaterboxIcons.Bell
        LaterboxToastVariant.ERROR -> LaterboxIcons.Close
        LaterboxToastVariant.OFFLINE -> LaterboxIcons.Globe
        LaterboxToastVariant.NEUTRAL -> LaterboxIcons.Sparkles
    }

    val liveMode = if (toast.variant == LaterboxToastVariant.ERROR || toast.variant == LaterboxToastVariant.OFFLINE) {
        LiveRegionMode.Assertive
    } else {
        LiveRegionMode.Polite
    }

    Surface(
        shape = LaterboxShapeTokens.Card,
        color = colors.surfaceElevated,
        shadowElevation = 6.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (toast.variant == LaterboxToastVariant.NEUTRAL) {
                colors.borderSubtle
            } else {
                accentColor.copy(alpha = 0.35f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = liveMode }
            .testTag("laterbox_toast_card_${toast.variant.name.lowercase()}_${toast.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = badgeIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accentColor
                )
            }

            Text(
                text = toast.message,
                style = typography.bodySecondary.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            toast.actionLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Box(
                    modifier = Modifier
                        .clip(LaterboxShapeTokens.Chip)
                        .clickable(role = Role.Button, onClick = onActionClick)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("laterbox_toast_action_${toast.id}")
                ) {
                    Text(
                        text = label,
                        style = typography.uiMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = colors.accentPrimary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Dismiss notification",
                        onClick = onDismiss
                    )
                    .testTag("laterbox_toast_dismiss_${toast.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.Close),
                    contentDescription = "Dismiss notification",
                    modifier = Modifier.size(14.dp),
                    tint = colors.textSecondary
                )
            }
        }
    }
}
