package com.laterbox.app.ui.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.ui.components.LaterboxToastController
import com.laterbox.app.ui.components.LaterboxToastVariant

/**
 * Standardized, debounced feedback helper for link save and update operations
 * and unified in-app toast routing with system Toast fallback when outside the main Activity.
 */
object SaveFeedbackHelper {

    private const val DEBOUNCE_WINDOW_MS = 1500L
    private var lastToastTime = 0L
    private var lastToastMessage = ""
    private var activeToast: Toast? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showSaveSuccess(context: Context, isUpdate: Boolean = false) {
        val message = if (isUpdate) "Updated in Laterbox" else "Saved to Laterbox"
        showInAppOrSystem(context, message, LaterboxToastVariant.SUCCESS)
    }

    fun showInAppOrSystem(
        context: Context,
        message: String,
        variant: LaterboxToastVariant = LaterboxToastVariant.NEUTRAL,
        controller: LaterboxToastController? = null,
        durationMs: Long? = LaterboxToastController.DEFAULT_DURATION_MS
    ) {
        val targetController = controller
            ?: (context.applicationContext as? LaterboxApplication)?.container?.toastController
        if (targetController != null && targetController.isInAppHostActive) {
            targetController.show(
                message = message,
                variant = variant,
                durationMs = durationMs
            )
            return
        }
        if (controller != null) {
            controller.show(
                message = message,
                variant = variant,
                durationMs = durationMs
            )
            return
        }
        showToast(context, message)
    }

    @Synchronized
    fun showToast(context: Context, message: String) {
        val now = System.currentTimeMillis()
        if (message == lastToastMessage && (now - lastToastTime) < DEBOUNCE_WINDOW_MS) {
            return
        }
        lastToastTime = now
        lastToastMessage = message

        mainHandler.post {
            try {
                activeToast?.cancel()
                val toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT)
                activeToast = toast
                toast.show()
            } catch (_: Exception) {}
        }
    }
}
