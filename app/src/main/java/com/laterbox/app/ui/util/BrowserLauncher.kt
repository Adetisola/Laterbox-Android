package com.laterbox.app.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.toArgb
import com.laterbox.app.ui.components.LaterboxToastVariant
import com.laterbox.app.ui.theme.LaterboxColors

object BrowserLauncher {

    fun isSafeHttpUrl(url: String?): Boolean {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.authority.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun openUrl(context: Context, url: String, colors: LaterboxColors? = null) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        val validUri = try {
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase()
            when {
                scheme == null -> Uri.parse("https://$trimmed")
                scheme == "http" || scheme == "https" -> uri
                else -> {
                    SaveFeedbackHelper.showInAppOrSystem(context, "Unsupported link", LaterboxToastVariant.WARNING)
                    return
                }
            }
        } catch (e: Exception) {
            SaveFeedbackHelper.showInAppOrSystem(context, "Invalid link URL", LaterboxToastVariant.ERROR)
            return
        }

        try {
            val builder = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_ON)

            if (colors != null) {
                val colorParams = CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(colors.backgroundPrimary.toArgb())
                    .setNavigationBarColor(colors.surfacePrimary.toArgb())
                    .build()
                builder.setDefaultColorSchemeParams(colorParams)
            }

            val customTabsIntent = builder.build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, validUri)
        } catch (e: Exception) {
            // Fallback to standard ACTION_VIEW if no Custom Tabs provider is available
            try {
                val intent = Intent(Intent.ACTION_VIEW, validUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (fallbackError: Exception) {
                SaveFeedbackHelper.showInAppOrSystem(context, "Could not open browser", LaterboxToastVariant.ERROR)
            }
        }
    }
}
