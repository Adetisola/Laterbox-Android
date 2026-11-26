package com.laterbox.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.data.backup.BackupRestoreResult
import com.laterbox.app.storage.AppStorageReport
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataStorageSettingsScreen(
    onExportBackup: suspend (OutputStream) -> Result<Int>,
    onImportBackup: suspend (InputStream) -> Result<BackupRestoreResult>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    storageReport: AppStorageReport? = null,
    isStorageLoading: Boolean = false,
    isClearingSafeCache: Boolean = false,
    storageFeedbackMessage: String? = null,
    storageErrorMessage: String? = null,
    onRefreshStorage: () -> Unit = {},
    onClearSafeCache: () -> Unit = {},
    onRunStorageMaintenance: () -> Unit = {},
    onDismissStorageFeedback: () -> Unit = {},
    onManageDownloadsClick: (() -> Unit)? = null
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        onRefreshStorage()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val result = onExportBackup(outputStream)
                        if (result.isSuccess) {
                            val count = result.getOrNull() ?: 0
                            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                                context = context,
                                message = "Exported $count items.",
                                variant = com.laterbox.app.ui.components.LaterboxToastVariant.SUCCESS
                            )
                        } else {
                            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                                context = context,
                                message = "Could not export backup.",
                                variant = com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
                            )
                        }
                    }
                } catch (_: Exception) {
                    com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                        context = context,
                        message = "Could not export backup to that location.",
                        variant = com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
                    )
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val result = onImportBackup(inputStream)
                        if (result.isSuccess) {
                            val stats = result.getOrNull()
                            val msg = stats?.let {
                                "Restored ${it.restoredLinksCount} links and ${it.restoredCollectionsCount} collections."
                            } ?: "Backup restored."
                            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                                context = context,
                                message = msg,
                                variant = com.laterbox.app.ui.components.LaterboxToastVariant.SUCCESS
                            )
                        } else {
                            com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                                context = context,
                                message = "Could not restore that backup file.",
                                variant = com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
                            )
                        }
                    }
                } catch (_: Exception) {
                    com.laterbox.app.ui.util.SaveFeedbackHelper.showInAppOrSystem(
                        context = context,
                        message = "Could not read the selected backup file.",
                        variant = com.laterbox.app.ui.components.LaterboxToastVariant.ERROR
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("data_storage_settings_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSubpageHeader(
                title = "Data & Storage",
                onBackClick = onBackClick
            )

            Spacer(modifier = Modifier.height(spacing.xs))

            SettingsSectionHeader(title = "Backup & Restore")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = "Library Backup",
                        style = typography.uiMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Export your library as a portable JSON file or restore from a previous backup.",
                        style = typography.bodySecondary.copy(fontSize = 12.sp),
                        color = colors.textSecondary
                    )

                    Spacer(modifier = Modifier.height(spacing.xs))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        LaterboxSecondaryButton(
                            text = "Export JSON",
                            onClick = {
                                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportLauncher.launch("laterbox_backup_$dateStr.json")
                            },
                            modifier = Modifier.weight(1f)
                        )

                        LaterboxSecondaryButton(
                            text = "Import JSON",
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/*"))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            AppStorageSectionContent(
                report = storageReport,
                isLoading = isStorageLoading,
                isClearingSafeCache = isClearingSafeCache,
                feedbackMessage = storageFeedbackMessage,
                errorMessage = storageErrorMessage,
                onRefresh = onRefreshStorage,
                onClearSafeCache = onClearSafeCache,
                onRunMaintenance = onRunStorageMaintenance,
                onDismissFeedback = onDismissStorageFeedback,
                onManageDownloadsClick = onManageDownloadsClick
            )

            Spacer(modifier = Modifier.navigationBarsPadding().height(40.dp))
        }
    }
}
