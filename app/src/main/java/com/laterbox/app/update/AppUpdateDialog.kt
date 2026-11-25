package com.laterbox.app.update

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.laterbox.app.R
import com.laterbox.app.remoteconfig.RemoteConfigLayoutHelper
import com.laterbox.app.ui.components.LaterboxPrimaryButton
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.components.LaterboxShapeTokens
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private enum class UpdateStep {
    PROMPT,
    RESOLVING,
    DOWNLOADING_PATCH,
    RECONSTRUCTING,
    DOWNLOADING_FULL,
    VERIFYING,
    PERMISSION_NEEDED,
    READY_TO_INSTALL,
    FAILED
}

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo? = null,
    policyPrompt: RemoteConfigLayoutHelper.AdaptiveUpdatePromptPolicy? = null,
    onResolvePolicyUpdate: (suspend () -> UpdateCheckResult)? = null,
    onDismiss: () -> Unit,
    onPostpone: (version: String) -> Unit,
    appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
    authHeaderProvider: (suspend (Boolean) -> Pair<String, String>?)? = null,
    dismissible: Boolean = !(policyPrompt?.isBlocking ?: false),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    val effectiveAppCheckProvider: suspend (Boolean) -> String? = remember(context, appCheckTokenProvider) {
        appCheckTokenProvider ?: { forceRefresh ->
            (context.applicationContext as? com.laterbox.app.LaterboxApplication)
                ?.container
                ?.getBackendAppCheckToken(forceRefresh)
        }
    }
    val effectiveAuthHeaderProvider: suspend (Boolean) -> Pair<String, String>? = remember(
        context,
        authHeaderProvider,
        appCheckTokenProvider
    ) {
        authHeaderProvider ?: { forceRefresh ->
            if (appCheckTokenProvider != null) {
                appCheckTokenProvider.invoke(forceRefresh)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_APP_CHECK to it }
            } else {
                (context.applicationContext as? com.laterbox.app.LaterboxApplication)
                    ?.container
                    ?.getBackendAuthHeader(forceRefresh)
            }
        }
    }

    var resolvedUpdateInfo by remember(updateInfo, policyPrompt?.targetTag, policyPrompt?.mode) {
        mutableStateOf(updateInfo)
    }
    val initialVersionLabel = resolvedUpdateInfo?.versionName?.takeIf { it.isNotBlank() }
        ?: policyPrompt?.targetTag?.trim()?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotBlank() }
        ?: ""
    val releasePageFallbackUrl = resolvedUpdateInfo?.releasePageUrl?.takeIf { it.isNotBlank() }
        ?: policyPrompt?.fallbackUrl?.takeIf { it.isNotBlank() }
        ?: "https://github.com/Adetisola/Laterbox/releases"

    var currentStep by remember(policyPrompt?.mode, policyPrompt?.targetTag) {
        mutableStateOf(UpdateStep.PROMPT)
    }
    var downloadPercent by remember { mutableIntStateOf(0) }
    var reconstructPercent by remember { mutableIntStateOf(0) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalDownloadBytes by remember { mutableLongStateOf(updateInfo?.effectiveDownloadSize ?: 0L) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }
    var fallbackNotice by remember { mutableStateOf<String?>(null) }

    // Check if an APK for the resolved release is already cached on disk and verified
    LaunchedEffect(resolvedUpdateInfo?.versionName, resolvedUpdateInfo?.assetName) {
        val activeInfo = resolvedUpdateInfo ?: return@LaunchedEffect
        val existing = ApkInstallerHelper.getExistingDownloadedApk(context, activeInfo.versionName, activeInfo.assetName)
        if (existing != null) {
            downloadedFile = existing
            currentStep = UpdateStep.READY_TO_INSTALL
        }
    }

    fun verifyAndProceed(file: File) {
        downloadedFile = file
        val verification = ApkInstallerHelper.verifyApkDetailed(context, file)
        when (verification) {
            is ApkInstallerHelper.ApkVerificationResult.Valid -> {
                if (!ApkInstallerHelper.canInstallPackages(context)) {
                    currentStep = UpdateStep.PERMISSION_NEEDED
                } else {
                    currentStep = UpdateStep.READY_TO_INSTALL
                    ApkInstallerHelper.launchInstaller(context, file)
                }
            }
            is ApkInstallerHelper.ApkVerificationResult.SignatureMismatch -> {
                errorMessage = verification.message
                currentStep = UpdateStep.FAILED
            }
            is ApkInstallerHelper.ApkVerificationResult.InvalidPackage -> {
                errorMessage = "Package mismatch: expected ${verification.expected}, found ${verification.actual}"
                currentStep = UpdateStep.FAILED
            }
            is ApkInstallerHelper.ApkVerificationResult.IncompatibleAbi -> {
                errorMessage = verification.message
                currentStep = UpdateStep.FAILED
            }
            is ApkInstallerHelper.ApkVerificationResult.Error -> {
                errorMessage = verification.message
                currentStep = UpdateStep.FAILED
            }
        }
    }

    fun startFullDownloadFor(info: AppUpdateInfo, notice: String? = null) {
        fallbackNotice = notice
        currentStep = UpdateStep.DOWNLOADING_FULL
        downloadPercent = 0
        downloadedBytes = 0L
        totalDownloadBytes = info.apkSize

        coroutineScope.launch {
            val file = ApkInstallerHelper.downloadApk(
                context = context,
                downloadUrl = info.downloadUrl,
                versionName = info.versionName,
                assetName = info.assetName,
                expectedSizeBytes = info.apkSize,
                appCheckTokenProvider = effectiveAppCheckProvider,
                authHeaderProvider = effectiveAuthHeaderProvider,
                onProgress = { progress ->
                    when (progress) {
                        is ApkInstallerHelper.DownloadProgress.Downloading -> {
                            downloadPercent = progress.percent
                            downloadedBytes = progress.bytesDownloaded
                            if (progress.totalBytes > 0) {
                                totalDownloadBytes = progress.totalBytes
                            }
                        }
                        is ApkInstallerHelper.DownloadProgress.Completed -> {
                            downloadedFile = progress.file
                            currentStep = UpdateStep.VERIFYING
                        }
                        is ApkInstallerHelper.DownloadProgress.Error -> {
                            errorMessage = progress.message
                            currentStep = UpdateStep.FAILED
                        }
                    }
                }
            )

            if (file != null) {
                verifyAndProceed(file)
            }
        }
    }

    fun startDownloadAndInstallFor(info: AppUpdateInfo) {
        saveStatusMessage = null
        fallbackNotice = null

        val delta = info.deltaPatch
        if (info.isDeltaAvailable && delta != null) {
            currentStep = UpdateStep.DOWNLOADING_PATCH
            downloadPercent = 0
            downloadedBytes = 0L
            totalDownloadBytes = delta.patchSize

            coroutineScope.launch {
                var patchError: ApkInstallerHelper.DownloadProgress.Error? = null
                val patchFile = ApkInstallerHelper.downloadPatch(
                    context = context,
                    deltaPatch = delta,
                    appCheckTokenProvider = effectiveAppCheckProvider,
                    authHeaderProvider = effectiveAuthHeaderProvider,
                    onProgress = { progress ->
                        when (progress) {
                            is ApkInstallerHelper.DownloadProgress.Downloading -> {
                                downloadPercent = progress.percent
                                downloadedBytes = progress.bytesDownloaded
                                if (progress.totalBytes > 0) {
                                    totalDownloadBytes = progress.totalBytes
                                }
                            }
                            is ApkInstallerHelper.DownloadProgress.Completed -> Unit
                            is ApkInstallerHelper.DownloadProgress.Error -> {
                                patchError = progress
                            }
                        }
                    }
                )

                if (patchFile == null || !patchFile.exists()) {
                    if (patchError?.isAuthOrRateLimit == true) {
                        errorMessage = patchError?.message
                            ?: "Update download is temporarily unavailable. Please try again later."
                        currentStep = UpdateStep.FAILED
                        return@launch
                    }
                    if (patchError?.kind?.isBackendUnreachable == true) {
                        errorMessage = patchError?.message ?: "Couldn't reach the update server."
                        currentStep = UpdateStep.FAILED
                        return@launch
                    }
                    Log.w("AppUpdateDialog", "Delta patch download failed, falling back to full APK download")
                    startFullDownloadFor(info, "Delta update could not be applied. Downloading complete update...")
                    return@launch
                }

                currentStep = UpdateStep.RECONSTRUCTING
                reconstructPercent = 0

                val reconstructedApk = try {
                    ApkInstallerHelper.applyDeltaPatch(
                        context = context,
                        deltaPatch = delta,
                        patchFile = patchFile,
                        targetVersionName = info.versionName,
                        targetAssetName = info.assetName,
                        onProgress = { pct ->
                            reconstructPercent = pct
                        }
                    )
                } catch (e: Exception) {
                    Log.w("AppUpdateDialog", "Delta reconstruction failed: ${e.message}, falling back to full APK download", e)
                    try { patchFile.delete() } catch (_: Exception) {}
                    startFullDownloadFor(info, "Delta update could not be applied. Downloading complete update...")
                    return@launch
                }

                currentStep = UpdateStep.VERIFYING
                verifyAndProceed(reconstructedApk)
            }
        } else {
            val diagCode = info.deltaDiagnosticReason?.logCode ?: "none"
            val diagDetail = info.deltaDiagnosticDetail ?: "No compatible delta patch"
            Log.i("AppUpdateDialog", "Starting full APK download (delta reason=$diagCode): $diagDetail")
            startFullDownloadFor(info)
        }
    }

    fun startUpdateFlow() {
        val activeInfo = resolvedUpdateInfo
        if (activeInfo != null) {
            startDownloadAndInstallFor(activeInfo)
            return
        }
        val resolver = onResolvePolicyUpdate
        if (resolver == null) {
            ApkInstallerHelper.openFallbackReleasePage(context, releasePageFallbackUrl)
            return
        }
        saveStatusMessage = null
        fallbackNotice = null
        currentStep = UpdateStep.RESOLVING
        coroutineScope.launch {
            when (val res = resolver()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    resolvedUpdateInfo = res.updateInfo
                    val cachedApk = ApkInstallerHelper.getExistingDownloadedApk(
                        context,
                        res.updateInfo.versionName,
                        res.updateInfo.assetName,
                    )
                    if (cachedApk != null) {
                        verifyAndProceed(cachedApk)
                    } else {
                        startDownloadAndInstallFor(res.updateInfo)
                    }
                }
                is UpdateCheckResult.NetworkError -> {
                    errorMessage = res.message
                    currentStep = UpdateStep.FAILED
                }
                is UpdateCheckResult.ApiError -> {
                    errorMessage = res.message
                    currentStep = UpdateStep.FAILED
                }
                is UpdateCheckResult.AuthError -> {
                    errorMessage = res.message
                    currentStep = UpdateStep.FAILED
                }
                is UpdateCheckResult.RateLimited -> {
                    errorMessage = res.message
                    currentStep = UpdateStep.FAILED
                }
                is UpdateCheckResult.NoCompatibleApk -> {
                    errorMessage = "No compatible APK package was found for this device on v${res.releaseVersion}."
                    currentStep = UpdateStep.FAILED
                }
                is UpdateCheckResult.InvalidRelease -> {
                    errorMessage = res.message
                    currentStep = UpdateStep.FAILED
                }
                is UpdateCheckResult.UpToDate -> {
                    errorMessage = "This device already matches or exceeds the selected release package."
                    currentStep = UpdateStep.FAILED
                }
            }
        }
    }

    val isProcessing = currentStep == UpdateStep.RESOLVING ||
        currentStep == UpdateStep.DOWNLOADING_PATCH ||
        currentStep == UpdateStep.RECONSTRUCTING ||
        currentStep == UpdateStep.DOWNLOADING_FULL ||
        currentStep == UpdateStep.VERIFYING

    val isRequiredModal = !dismissible || (policyPrompt?.isBlocking == true)
    val modalTitle = if (isRequiredModal) "Update Required" else "Update Available"

    Dialog(
        onDismissRequest = {
            if (dismissible && !isProcessing) {
                onDismiss()
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = dismissible && !isProcessing,
            dismissOnClickOutside = dismissible && !isProcessing,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(LaterboxShapeTokens.Card)
                .background(colors.surfaceElevated)
                .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Card)
                .padding(spacing.lg)
                .testTag(if (isRequiredModal) "adaptive_update_modal_required" else "adaptive_update_modal_available")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header: Laterbox brand logo beside Update Available / Update Required title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1B1A18))
                            .border(spacing.hairline, colors.borderSubtle, RoundedCornerShape(10.dp))
                            .testTag("adaptive_update_modal_logo"),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "Laterbox logo",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = modalTitle,
                            style = typography.sheetTitle,
                            color = colors.textPrimary
                        )
                        val infoSnapshot = resolvedUpdateInfo
                        if (initialVersionLabel.isNotBlank() || infoSnapshot != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                if (initialVersionLabel.isNotBlank()) {
                                    Text(
                                        text = "v$initialVersionLabel",
                                        style = typography.bodySecondary,
                                        color = colors.accentPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (infoSnapshot != null && infoSnapshot.targetAbiLabel.isNotBlank()) {
                                    val displaySize = infoSnapshot.effectiveDownloadSize
                                    val sizeText = if (displaySize > 0) {
                                        val mb = displaySize / (1024f * 1024f)
                                        " • ${String.format(Locale.US, "%.1f", mb)} MB"
                                    } else ""
                                    Text(
                                        text = "• ${infoSnapshot.targetAbiLabel}$sizeText",
                                        style = typography.caption,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                        if (infoSnapshot != null && infoSnapshot.isDeltaAvailable && infoSnapshot.deltaPatch != null && infoSnapshot.apkSize > 0) {
                            val savingsPercent = ((infoSnapshot.apkSize - infoSnapshot.deltaPatch.patchSize) * 100 / infoSnapshot.apkSize).toInt().coerceIn(1, 99)
                            Spacer(modifier = Modifier.height(spacing.xs))
                            Box(
                                modifier = Modifier
                                    .clip(LaterboxShapeTokens.Chip)
                                    .background(colors.accentPrimary.copy(alpha = 0.12f))
                                    .border(spacing.hairline, colors.accentPrimary.copy(alpha = 0.25f), LaterboxShapeTokens.Chip)
                                    .padding(horizontal = spacing.sm, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "⚡ Delta update • Save $savingsPercent% data",
                                    style = typography.caption,
                                    color = colors.accentPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(spacing.md))

                when (currentStep) {
                    UpdateStep.PROMPT -> {
                        val activeInfo = resolvedUpdateInfo
                        val customMessage = policyPrompt?.customMessage?.trim().orEmpty()
                        val defaultPolicyBody = if (isRequiredModal) {
                            val targetTagLabel = policyPrompt?.targetTag?.takeIf { it.isNotBlank() } ?: "the latest release"
                            "Update to $targetTagLabel to continue using Laterbox."
                        } else {
                            val targetTagLabel = policyPrompt?.targetTag?.takeIf { it.isNotBlank() } ?: "a newer release"
                            "Laterbox $targetTagLabel is available with improvements and fixes."
                        }

                        if (customMessage.isNotBlank()) {
                            Text(
                                text = customMessage,
                                style = typography.bodySecondary,
                                color = colors.textSecondary,
                                lineHeight = 19.sp
                            )
                            Spacer(modifier = Modifier.height(spacing.md))
                        } else if (activeInfo == null) {
                            Text(
                                text = defaultPolicyBody,
                                style = typography.bodySecondary,
                                color = colors.textSecondary,
                                lineHeight = 19.sp
                            )
                            Spacer(modifier = Modifier.height(spacing.md))
                        }

                        // Release Notes
                        if (activeInfo != null && activeInfo.releaseNotes.isNotBlank()) {
                            Text(
                                text = "What's New:",
                                style = typography.fieldLabel,
                                color = colors.textSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(spacing.xs))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(LaterboxShapeTokens.InputField)
                                    .background(colors.surfacePrimary)
                                    .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.InputField)
                                    .padding(spacing.sm)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = activeInfo.releaseNotes.trim(),
                                    style = typography.bodySecondary,
                                    color = colors.textSecondary,
                                    lineHeight = 18.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(spacing.md))
                        }

                        // Actions: Update Now, View Release, Later (only when dismissible)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            LaterboxPrimaryButton(
                                text = "Update Now",
                                onClick = { startUpdateFlow() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("adaptive_update_modal_primary_button")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "View on GitHub",
                                    style = typography.fieldLabel,
                                    color = colors.textTertiary,
                                    modifier = Modifier
                                        .clickable {
                                            ApkInstallerHelper.openFallbackReleasePage(context, releasePageFallbackUrl)
                                        }
                                        .padding(vertical = spacing.xs)
                                )
                                if (dismissible) {
                                    LaterboxSecondaryButton(
                                        text = "Later",
                                        onClick = {
                                            val postponeVersion = activeInfo?.versionName
                                                ?: initialVersionLabel
                                            if (postponeVersion.isNotBlank()) {
                                                onPostpone(postponeVersion)
                                            }
                                            onDismiss()
                                        },
                                        modifier = Modifier.testTag("adaptive_update_modal_later_button")
                                    )
                                }
                            }
                        }
                    }

                    UpdateStep.RESOLVING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = colors.accentPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(spacing.sm))
                            Text(
                                text = "Preparing update package...",
                                style = typography.bodyPrimary,
                                color = colors.textPrimary
                            )
                        }
                    }

                    UpdateStep.DOWNLOADING_PATCH -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val progressLabel = if (totalDownloadBytes > 0) {
                                val downloadedMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024f * 1024f))
                                val totalMb = String.format(Locale.US, "%.1f", totalDownloadBytes / (1024f * 1024f))
                                "Downloading update... $downloadedMb MB / $totalMb MB ($downloadPercent%)"
                            } else {
                                "Downloading update... ($downloadPercent%)"
                            }

                            Text(
                                text = progressLabel,
                                style = typography.bodyPrimary,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(spacing.sm))
                            LinearProgressIndicator(
                                progress = { downloadPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(LaterboxShapeTokens.Chip),
                                color = colors.accentPrimary,
                                trackColor = colors.surfacePrimary
                            )
                            Spacer(modifier = Modifier.height(spacing.md))
                            Text(
                                text = "Downloading a small update for your current app version.",
                                style = typography.caption,
                                color = colors.textTertiary
                            )
                        }
                    }

                    UpdateStep.RECONSTRUCTING -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (reconstructPercent > 0) "Preparing update... ($reconstructPercent%)" else "Preparing update...",
                                style = typography.bodyPrimary,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(spacing.sm))
                            LinearProgressIndicator(
                                progress = { (reconstructPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(LaterboxShapeTokens.Chip),
                                color = colors.accentPrimary,
                                trackColor = colors.surfacePrimary
                            )
                            Spacer(modifier = Modifier.height(spacing.md))
                            Text(
                                text = "Getting the update package ready on your device.",
                                style = typography.caption,
                                color = colors.textTertiary
                            )
                        }
                    }

                    UpdateStep.DOWNLOADING_FULL -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (fallbackNotice != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(LaterboxShapeTokens.Chip)
                                        .background(colors.statusWarning.copy(alpha = 0.12f))
                                        .border(spacing.hairline, colors.statusWarning.copy(alpha = 0.3f), LaterboxShapeTokens.Chip)
                                        .padding(spacing.sm)
                                ) {
                                    Text(
                                        text = fallbackNotice ?: "",
                                        style = typography.caption,
                                        color = colors.statusWarning
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacing.sm))
                            }

                            val progressLabel = if (totalDownloadBytes > 0) {
                                val downloadedMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024f * 1024f))
                                val totalMb = String.format(Locale.US, "%.1f", totalDownloadBytes / (1024f * 1024f))
                                "Downloading update... $downloadedMb MB / $totalMb MB ($downloadPercent%)"
                            } else {
                                "Downloading update... ($downloadPercent%)"
                            }

                            Text(
                                text = progressLabel,
                                style = typography.bodyPrimary,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(spacing.sm))
                            LinearProgressIndicator(
                                progress = { downloadPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(LaterboxShapeTokens.Chip),
                                color = colors.accentPrimary,
                                trackColor = colors.surfacePrimary
                            )
                            Spacer(modifier = Modifier.height(spacing.md))
                            Text(
                                text = "You can resume if your connection drops.",
                                style = typography.caption,
                                color = colors.textTertiary
                            )
                        }
                    }

                    UpdateStep.VERIFYING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = colors.accentPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(spacing.sm))
                            Text(
                                text = "Checking update...",
                                style = typography.bodyPrimary,
                                color = colors.textPrimary
                            )
                        }
                    }

                    UpdateStep.PERMISSION_NEEDED -> {
                        Text(
                            text = "To install the update, Laterbox needs permission to install packages from this source.",
                            style = typography.bodyPrimary,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(spacing.md))
                        LaterboxPrimaryButton(
                            text = "Grant Permission",
                            onClick = {
                                ApkInstallerHelper.openInstallPermissionSettings(context)
                                currentStep = UpdateStep.READY_TO_INSTALL
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    UpdateStep.READY_TO_INSTALL -> {
                        val readyVersion = resolvedUpdateInfo?.versionName?.takeIf { it.isNotBlank() }
                            ?: initialVersionLabel
                        Text(
                            text = "Package ready for installation.",
                            style = typography.bodyPrimary,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(spacing.xs))
                        Text(
                            text = if (readyVersion.isNotBlank()) {
                                "Version $readyVersion is downloaded and verified."
                            } else {
                                "Update package is downloaded and verified."
                            },
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )

                        if (saveStatusMessage != null) {
                            Spacer(modifier = Modifier.height(spacing.xs))
                            Text(
                                text = saveStatusMessage ?: "",
                                style = typography.caption,
                                color = colors.accentPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(spacing.md))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            LaterboxPrimaryButton(
                                text = "Install Update",
                                onClick = {
                                    downloadedFile?.let { file ->
                                        ApkInstallerHelper.launchInstaller(context, file)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                LaterboxSecondaryButton(
                                    text = "Save to Downloads",
                                    onClick = {
                                        downloadedFile?.let { file ->
                                            val uri = ApkInstallerHelper.saveApkToDownloads(context, file)
                                            saveStatusMessage = if (uri != null) {
                                                "Saved to Downloads: ${file.name}"
                                            } else {
                                                "Could not save to Downloads folder"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                LaterboxSecondaryButton(
                                    text = "Share APK",
                                    onClick = {
                                        downloadedFile?.let { file ->
                                            ApkInstallerHelper.shareApk(context, file)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Re-download",
                                    style = typography.caption,
                                    color = colors.textTertiary,
                                    modifier = Modifier
                                        .clickable {
                                            downloadedFile?.delete()
                                            downloadedFile = null
                                            startUpdateFlow()
                                        }
                                        .padding(vertical = spacing.xs)
                                )
                                if (dismissible) {
                                    LaterboxSecondaryButton(
                                        text = "Dismiss",
                                        onClick = onDismiss
                                    )
                                }
                            }
                        }
                    }

                    UpdateStep.FAILED -> {
                        Text(
                            text = errorMessage,
                            style = typography.bodySecondary,
                            color = colors.statusDanger
                        )

                        if (errorMessage.contains("could not be verified", ignoreCase = true) ||
                            errorMessage.contains("Signature mismatch", ignoreCase = true)
                        ) {
                            Spacer(modifier = Modifier.height(spacing.xs))
                            Text(
                                text = "Tip: You can save the APK to your Downloads folder now, uninstall your existing app, and then install the APK from your Downloads.",
                                style = typography.caption,
                                color = colors.textSecondary
                            )
                        }

                        if (saveStatusMessage != null) {
                            Spacer(modifier = Modifier.height(spacing.xs))
                            Text(
                                text = saveStatusMessage ?: "",
                                style = typography.caption,
                                color = colors.accentPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(spacing.md))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            if (downloadedFile != null) {
                                LaterboxPrimaryButton(
                                    text = "Save APK to Downloads",
                                    onClick = {
                                        downloadedFile?.let { file ->
                                            val uri = ApkInstallerHelper.saveApkToDownloads(context, file)
                                            saveStatusMessage = if (uri != null) {
                                                "Saved to Downloads: ${file.name}"
                                            } else {
                                                "Could not save to Downloads folder"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LaterboxPrimaryButton(
                                    text = if (resolvedUpdateInfo == null) "Retry" else "Retry Download",
                                    onClick = { startUpdateFlow() },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "View on GitHub",
                                    style = typography.fieldLabel,
                                    color = colors.textTertiary,
                                    modifier = Modifier
                                        .clickable {
                                            ApkInstallerHelper.openFallbackReleasePage(context, releasePageFallbackUrl)
                                            if (dismissible) {
                                                onDismiss()
                                            }
                                        }
                                        .padding(vertical = spacing.xs)
                                )
                                if (dismissible) {
                                    LaterboxSecondaryButton(
                                        text = "Dismiss",
                                        onClick = onDismiss
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
