package com.laterbox.app.ui.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationPermissionHelper {

    fun isNotificationPermissionGranted(context: Context): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

/**
 * Composable helper that verifies POST_NOTIFICATIONS (Android 13+) and system notification state
 * right before scheduling a reminder or enabling smart digests.
 */
@Composable
fun rememberNotificationPermissionRequester(
    onDenied: () -> Unit = {}
): (onReady: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = pendingCallback
        pendingCallback = null
        if (granted && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            cb?.invoke()
        } else {
            onDenied()
            cb?.invoke()
        }
    }

    return remember(context, onDenied) {
        { onReady ->
            if (NotificationPermissionHelper.isNotificationPermissionGranted(context)) {
                onReady()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingCallback = onReady
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onDenied()
                onReady()
            }
        }
    }
}
