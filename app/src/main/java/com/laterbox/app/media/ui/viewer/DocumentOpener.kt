package com.laterbox.app.media.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.ui.components.LaterboxToastVariant
import com.laterbox.app.ui.util.SaveFeedbackHelper
import java.io.File

object DocumentOpener {
    fun openDocument(context: Context, asset: MediaAsset) {
        val file = File(context.filesDir, "media/${asset.localPath}")
        if (!file.exists()) {
            SaveFeedbackHelper.showInAppOrSystem(context, "File does not exist on device", LaterboxToastVariant.ERROR)
            return
        }

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, asset.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "Open with..."))
        } catch (_: ActivityNotFoundException) {
            SaveFeedbackHelper.showInAppOrSystem(context, "No app found to open this file", LaterboxToastVariant.WARNING)
        } catch (e: Exception) {
            SaveFeedbackHelper.showInAppOrSystem(context, "Unable to open document: ${e.message}", LaterboxToastVariant.ERROR)
        }
    }
}
