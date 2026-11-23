package com.laterbox.app.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Handles downloading, cryptographic signature verification, resilient resumption,
 * and system package installer launching for GitHub release APKs.
 */
object ApkInstallerHelper {

    private const val TAG = "ApkInstallerHelper"

    sealed class DownloadProgress {
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percent: Int) : DownloadProgress()
        data class Completed(val file: File) : DownloadProgress()
        data class Error(
            val message: String,
            val isAuthOrRateLimit: Boolean = false,
            val kind: UpdateFailureKind = UpdateFailureKind.UNKNOWN
        ) : DownloadProgress()
    }

    sealed class ApkVerificationResult {
        data object Valid : ApkVerificationResult()
        data class SignatureMismatch(val message: String) : ApkVerificationResult()
        data class InvalidPackage(val expected: String, val actual: String) : ApkVerificationResult()
        data class IncompatibleAbi(val message: String) : ApkVerificationResult()
        data class Error(val message: String) : ApkVerificationResult()
    }

    internal data class ResolvedAssetUrlResult(
        val downloadUrl: String? = null,
        val errorMessage: String? = null,
        val isAuthOrRateLimit: Boolean = false,
        val failureKind: UpdateFailureKind = UpdateFailureKind.UNKNOWN,
        val httpCode: Int? = null
    )

    private class DownloadFailure(
        message: String,
        val kind: UpdateFailureKind,
        val httpCode: Int? = null,
        val isAuthOrRateLimit: Boolean = false,
        /** True when the failure already went through its own bounded retries (asset resolver). */
        val alreadyRetried: Boolean = false
    ) : IOException(message)

    internal fun isBackendAssetResolverUrl(url: String): Boolean {
        return try {
            val path = URI(url).path ?: ""
            path.startsWith("/updates/laterbox/assets/") && path.endsWith("/download-url")
        } catch (_: Exception) {
            false
        }
    }

    private fun extractBackendError(connection: HttpURLConnection): Triple<String?, String?, Boolean> {
        val raw = try {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
        val code = connection.responseCode
        var detailCode: String? = null
        var detailMsg: String? = null
        if (!raw.isNullOrBlank()) {
            try {
                val root = JSONObject(raw)
                val detailObj = root.optJSONObject("detail")
                if (detailObj != null) {
                    detailCode = detailObj.optString("code", "").trim().ifBlank { null }
                    detailMsg = detailObj.optString("message", "").trim().ifBlank { null }
                } else {
                    detailMsg = root.optString("detail", "").trim().ifBlank { null }
                        ?: root.optString("message", "").trim().ifBlank { null }
                }
            } catch (_: Exception) {
                detailMsg = raw.trim().take(240).ifBlank { null }
            }
        }
        val isAuthOrLimit = code in listOf(401, 403, 429) ||
            detailCode == "github_auth_failed" ||
            detailCode == "github_rate_limited"
        return Triple(detailCode, detailMsg, isAuthOrLimit)
    }

    private suspend fun resolveHeader(
        authHeaderProvider: (suspend (Boolean) -> Pair<String, String>?)?,
        appCheckTokenProvider: (suspend (Boolean) -> String?)?,
        forceRefresh: Boolean
    ): Pair<String, String>? {
        if (authHeaderProvider != null) {
            return authHeaderProvider.invoke(forceRefresh)?.takeIf { it.second.isNotBlank() }
        }
        return appCheckTokenProvider?.invoke(forceRefresh)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_APP_CHECK to it
        }
    }

    internal suspend fun resolveSignedAssetDownloadUrl(
        resolverUrl: String,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
        authHeaderProvider: (suspend (Boolean) -> Pair<String, String>?)? = null
    ): ResolvedAssetUrlResult = withContext(Dispatchers.IO) {
        val backendHost = UpdateNetworkSupport.safeHost(resolverUrl)
        var attemptsLeft = UpdateNetworkSupport.retryPolicyFor(UpdateFailureKind.DNS).maxConsecutiveAttempts
        var consecutive = 0
        var lastKind: UpdateFailureKind? = null
        var result = ResolvedAssetUrlResult(
            errorMessage = "Network error while resolving asset download URL.",
            failureKind = UpdateFailureKind.UNKNOWN
        )
        var transportAttempt = 0
        while (true) {
            transportAttempt++
            try {
                var pendingRefreshedHeader: Pair<String, String>? = null
                for (authAttempt in 0..1) {
                    var conn: HttpURLConnection? = null
                    try {
                        val tokenStart = System.nanoTime()
                        val sentHeader = pendingRefreshedHeader?.also { pendingRefreshedHeader = null }
                            ?: resolveHeader(authHeaderProvider, appCheckTokenProvider, forceRefresh = authAttempt > 0)
                        val tokenMs = (System.nanoTime() - tokenStart) / 1_000_000
                        val reqStart = System.nanoTime()
                        conn = UpdateNetworkSupport.connectionFactory(URL(resolverUrl)).apply {
                            requestMethod = "GET"
                            connectTimeout = 15_000
                            readTimeout = 20_000
                            instanceFollowRedirects = true
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("User-Agent", "Laterbox-App")
                            setRequestProperty("X-Laterbox-Client", "android")
                            setRequestProperty("X-Laterbox-Version-Code", com.laterbox.app.BuildConfig.VERSION_CODE.toString())
                            setRequestProperty("X-Laterbox-Version-Name", com.laterbox.app.BuildConfig.VERSION_NAME)
                            if (sentHeader != null && sentHeader.second.isNotBlank()) {
                                setRequestProperty(sentHeader.first, sentHeader.second)
                            }
                        }

                        val code = conn.responseCode
                        Log.i(TAG, "Resolver host=$backendHost status=$code appCheckMs=$tokenMs requestMs=${(System.nanoTime() - reqStart) / 1_000_000}")
                        if (code in 200..299) {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(body)
                            val signedUrl = json.optString("download_url", "").trim()
                            if (signedUrl.isBlank() || !UpdateNetworkSupport.isAllowedAssetUrl(signedUrl)) {
                                return@withContext ResolvedAssetUrlResult(
                                    errorMessage = "Update server returned an invalid download URL.",
                                    failureKind = UpdateFailureKind.CONFIG,
                                    isAuthOrRateLimit = false
                                )
                            }
                            return@withContext ResolvedAssetUrlResult(downloadUrl = signedUrl)
                        }

                        val (_, detailMsg, isAuthOrLimit) = extractBackendError(conn)
                        if (code == 401 && authAttempt == 0 && (authHeaderProvider != null || appCheckTokenProvider != null)) {
                            val refreshed = resolveHeader(authHeaderProvider, appCheckTokenProvider, forceRefresh = true)
                            if (refreshed != null && refreshed != sentHeader) {
                                pendingRefreshedHeader = refreshed
                                continue
                            }
                        }

                        val fallbackMsg = when (code) {
                            401, 403 -> "Authentication failed while preparing asset download (HTTP $code)."
                            429 -> "Update server rate limit reached. Please try again later."
                            else -> "Failed to prepare update download (HTTP $code)."
                        }
                        val kind = if (isAuthOrLimit && code !in listOf(401, 403, 429)) {
                            UpdateFailureKind.AUTH
                        } else {
                            UpdateNetworkSupport.classifyHttpStatus(code)
                        }
                        val transient = kind == UpdateFailureKind.SERVER
                        result = ResolvedAssetUrlResult(
                            errorMessage = sanitizeMessage(detailMsg) ?: fallbackMsg,
                            isAuthOrRateLimit = isAuthOrLimit,
                            failureKind = kind,
                            httpCode = code
                        )
                        if (!transient) return@withContext result
                        throw DownloadFailure(result.errorMessage ?: fallbackMsg, kind, code)
                    } finally {
                        conn?.disconnect()
                    }
                }
                return@withContext ResolvedAssetUrlResult(
                    errorMessage = "Authentication failed while preparing asset download.",
                    failureKind = UpdateFailureKind.AUTH,
                    isAuthOrRateLimit = true,
                    httpCode = 401
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = if (e is DownloadFailure) e.kind else UpdateNetworkSupport.classifyThrowable(e)
                result = ResolvedAssetUrlResult(
                    errorMessage = if (e is DownloadFailure) e.message else UpdateNetworkSupport.userMessage(kind),
                    isAuthOrRateLimit = false,
                    failureKind = kind,
                    httpCode = (e as? DownloadFailure)?.httpCode
                )
                Log.w(TAG, "Resolver attempt $transportAttempt failed host=$backendHost kind=$kind")
                consecutive = if (kind == lastKind) consecutive + 1 else 1
                lastKind = kind
                if (kind.isBackendUnreachable && !UpdateNetworkSupport.shouldRetry(kind, consecutive, transportAttempt) ||
                    !kind.isBackendUnreachable && kind != UpdateFailureKind.SERVER ||
                    kind == UpdateFailureKind.SERVER && !UpdateNetworkSupport.shouldRetry(kind, consecutive, transportAttempt)
                ) {
                    return@withContext result
                }
                val wait = UpdateNetworkSupport.backoffDelayMs(UpdateNetworkSupport.retryPolicyFor(kind), consecutive)
                if (wait > 0) UpdateNetworkSupport.sleeper(wait)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        result
    }

    fun getApkFileName(versionName: String, assetName: String = ""): String {
        return if (assetName.isNotBlank() && assetName.endsWith(".apk", ignoreCase = true)) {
            val base = assetName.removeSuffix(".apk").removeSuffix(".APK")
            if (base.contains(versionName)) "$base.apk" else "$base-$versionName.apk"
        } else {
            "Laterbox-$versionName.apk"
        }
    }

    /**
     * Checks if an APK for the specified version is already downloaded and verified on disk.
     */
    fun getExistingDownloadedApk(context: Context, versionName: String, assetName: String = ""): File? {
        val updatesDir = File(context.cacheDir, "updates")
        val fileName = getApkFileName(versionName, assetName)
        val file = File(updatesDir, fileName)
        return if (file.exists() && file.length() > 0 && verifyApk(context, file)) {
            file
        } else {
            null
        }
    }

    private fun parseContentRange(header: String?): Pair<Long, Long?>? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).find(header.trim())
            ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull()
        return start to total
    }

    private val urlInTextRegex = Regex("""https?://\S+""")

    private fun sanitizeMessage(raw: String?): String? =
        raw?.replace(urlInTextRegex, "<url>")?.trim()?.take(200)?.ifBlank { null }

    private suspend fun downloadFileInternal(
        outputFile: File,
        downloadUrl: String,
        expectedTotalBytes: Long = -1L,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
        authHeaderProvider: (suspend (Boolean) -> Pair<String, String>?)? = null,
        publicFallbackUrl: String? = null,
        onProgress: (DownloadProgress) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val usesBackendResolver = isBackendAssetResolverUrl(downloadUrl)
        var cachedSignedUrl: String? = null
        var usingPublicFallback = false
        var signedUrlRefreshCount = 0
        var existingBytes = if (outputFile.exists()) outputFile.length() else 0L
        var totalAttempts = 0
        var consecutiveFailures = 0
        var lastFailureKind: UpdateFailureKind? = null
        var lastErrorMessage = "Download failed"
        var lastIsAuthOrRateLimit = false
        var lastKind = UpdateFailureKind.UNKNOWN

        while (true) {
            totalAttempts++
            val bytesAtAttemptStart = if (outputFile.exists()) outputFile.length() else 0L
            var activeConnection: HttpURLConnection? = null
            try {
                val resolverActive = usesBackendResolver && !usingPublicFallback
                if (resolverActive && cachedSignedUrl == null) {
                    val resolved = resolveSignedAssetDownloadUrl(
                        resolverUrl = downloadUrl,
                        appCheckTokenProvider = appCheckTokenProvider,
                        authHeaderProvider = authHeaderProvider
                    )
                    if (resolved.downloadUrl == null) {
                        if (resolved.failureKind.isBackendUnreachable &&
                            publicFallbackUrl != null &&
                            UpdateNetworkSupport.isAllowedAssetUrl(publicFallbackUrl)
                        ) {
                            Log.w(TAG, "Backend unreachable (${resolved.failureKind}); switching to public GitHub release download")
                            usingPublicFallback = true
                            cachedSignedUrl = publicFallbackUrl
                        } else {
                            throw DownloadFailure(
                                resolved.errorMessage ?: "Failed to resolve asset download URL.",
                                resolved.failureKind,
                                resolved.httpCode,
                                resolved.isAuthOrRateLimit,
                                alreadyRetried = true
                            )
                        }
                    } else {
                        cachedSignedUrl = resolved.downloadUrl
                    }
                }

                var currentUrl = cachedSignedUrl ?: downloadUrl
                if (!UpdateNetworkSupport.isAllowedAssetUrl(currentUrl)) {
                    throw DownloadFailure("Update download URL is not allowed.", UpdateFailureKind.CONFIG)
                }
                var redirectCount = 0
                val requestStart = System.nanoTime()
                var ttfbMs = -1L

                // Follow HTTP redirects without attaching any GitHub or Firebase credentials.
                // Every hop is validated against the asset host allowlist.
                while (redirectCount <= 5) {
                    val conn = UpdateNetworkSupport.connectionFactory(URL(currentUrl)).apply {
                        connectTimeout = 20_000
                        readTimeout = 40_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "Laterbox-App")
                        if (existingBytes > 0) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }

                    val code = conn.responseCode
                    if (ttfbMs < 0) ttfbMs = (System.nanoTime() - requestStart) / 1_000_000
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val newLocation = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (newLocation.isNullOrBlank()) {
                            throw DownloadFailure("HTTP redirect without Location header", UpdateFailureKind.CONFIG)
                        }
                        val resolvedLocation = try {
                            URL(URL(currentUrl), newLocation).toString()
                        } catch (_: Exception) {
                            throw DownloadFailure("Invalid redirect location.", UpdateFailureKind.CONFIG)
                        }
                        if (!UpdateNetworkSupport.isAllowedAssetUrl(resolvedLocation)) {
                            throw DownloadFailure("Redirect to a disallowed host was blocked.", UpdateFailureKind.CONFIG)
                        }
                        currentUrl = resolvedLocation
                        redirectCount++
                    } else {
                        activeConnection = conn
                        break
                    }
                }

                val connection = activeConnection
                    ?: throw DownloadFailure("Too many redirects.", UpdateFailureKind.CONFIG)

                val responseCode = connection.responseCode

                if (responseCode in listOf(401, 403, 410)) {
                    if (resolverActive && signedUrlRefreshCount < 2) {
                        Log.i(TAG, "Pre-signed CDN URL returned HTTP $responseCode; refreshing signed URL and preserving partial progress ($existingBytes bytes)")
                        cachedSignedUrl = null
                        signedUrlRefreshCount++
                        UpdateNetworkSupport.sleeper(500L)
                        continue
                    }
                    val kind = if (responseCode == 410) UpdateFailureKind.EXPIRED_URL else UpdateFailureKind.AUTH
                    throw DownloadFailure(
                        "Download authorization failed or rate limit reached (HTTP $responseCode).",
                        kind, responseCode, isAuthOrRateLimit = true
                    )
                }
                if (responseCode == 429) {
                    throw DownloadFailure(
                        UpdateNetworkSupport.userMessage(UpdateFailureKind.RATE_LIMIT),
                        UpdateFailureKind.RATE_LIMIT, responseCode, isAuthOrRateLimit = true
                    )
                }

                val (isAppend, totalBytes) = when (responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        // 206 Partial Content: Server supports resuming
                        val parsedRange = parseContentRange(connection.getHeaderField("Content-Range"))
                        if (parsedRange != null && parsedRange.first != existingBytes) {
                            val badStart = parsedRange.first
                            val expectedStart = existingBytes
                            outputFile.delete()
                            existingBytes = 0L
                            throw DownloadFailure(
                                "Unexpected Content-Range start ($badStart != $expectedStart)",
                                UpdateFailureKind.INTERRUPTED
                            )
                        }
                        val remaining = connection.contentLengthLong.takeIf { it > 0 }
                            ?: connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                        val total = parsedRange?.second
                            ?: if (remaining > 0) existingBytes + remaining else expectedTotalBytes
                        Pair(true, total)
                    }
                    HttpURLConnection.HTTP_OK -> {
                        // 200 OK: Full file being sent (or starting from offset 0)
                        existingBytes = 0L
                        val len = connection.contentLengthLong.takeIf { it > 0 } ?: expectedTotalBytes
                        Pair(false, len)
                    }
                    416 -> {
                        if (expectedTotalBytes > 0 && outputFile.exists() && outputFile.length() == expectedTotalBytes) {
                            withContext(Dispatchers.Main) {
                                onProgress(DownloadProgress.Completed(outputFile))
                            }
                            return@withContext outputFile
                        }
                        // Requested Range Not Satisfiable: Restart clean
                        outputFile.delete()
                        existingBytes = 0L
                        continue
                    }
                    else -> {
                        throw DownloadFailure(
                            "Unexpected HTTP response code: $responseCode",
                            UpdateNetworkSupport.classifyHttpStatus(responseCode), responseCode
                        )
                    }
                }

                var bytesReadTotal = existingBytes
                var lastReportedPercent = -1
                val transferStart = System.nanoTime()

                connection.inputStream.use { input ->
                    FileOutputStream(outputFile, isAppend).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesReadTotal += bytesRead
                            val percent = if (totalBytes > 0) {
                                ((bytesReadTotal * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(DownloadProgress.Downloading(bytesReadTotal, totalBytes, percent))
                                }
                            }
                        }
                        output.flush()
                    }
                }

                val expectedLength = if (totalBytes > 0) totalBytes else expectedTotalBytes
                if (expectedLength > 0 && outputFile.length() < expectedLength) {
                    existingBytes = outputFile.length()
                    throw DownloadFailure(
                        "Download interrupted prematurely ($existingBytes / $expectedLength bytes)",
                        UpdateFailureKind.INTERRUPTED
                    )
                }

                // Verify file existence and non-empty size
                if (outputFile.exists() && outputFile.length() > 0) {
                    val ms = ((System.nanoTime() - transferStart) / 1_000_000).coerceAtLeast(1)
                    val got = bytesReadTotal - (if (isAppend) bytesAtAttemptStart else 0L)
                    Log.i(
                        TAG,
                        "Download ok host=${UpdateNetworkSupport.safeHost(currentUrl)} fallback=$usingPublicFallback " +
                            "status=$responseCode redirects=$redirectCount ttfbMs=$ttfbMs bytes=$got " +
                            "durationMs=$ms kBps=${got / ms} attempts=$totalAttempts"
                    )
                    withContext(Dispatchers.Main) {
                        onProgress(DownloadProgress.Completed(outputFile))
                    }
                    return@withContext outputFile
                } else {
                    throw DownloadFailure("Downloaded file is empty or missing", UpdateFailureKind.INTERRUPTED)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind: UpdateFailureKind
                if (e is DownloadFailure) {
                    kind = e.kind
                    lastErrorMessage = sanitizeMessage(e.message) ?: UpdateNetworkSupport.userMessage(kind, e.httpCode)
                    lastIsAuthOrRateLimit = e.isAuthOrRateLimit
                } else {
                    kind = UpdateNetworkSupport.classifyThrowable(e)
                    lastErrorMessage = UpdateNetworkSupport.userMessage(kind)
                    lastIsAuthOrRateLimit = false
                }
                lastKind = kind
                Log.w(TAG, "Download attempt $totalAttempts failed kind=$kind: ${sanitizeMessage(e.message)}")
                if (outputFile.exists()) {
                    existingBytes = outputFile.length()
                }
                val progressed = existingBytes > bytesAtAttemptStart
                consecutiveFailures = if (progressed || kind != lastFailureKind) 1 else consecutiveFailures + 1
                lastFailureKind = kind
                if ((e is DownloadFailure && e.alreadyRetried) ||
                    !UpdateNetworkSupport.shouldRetry(kind, consecutiveFailures, totalAttempts)
                ) {
                    break
                }
                val wait = UpdateNetworkSupport.backoffDelayMs(
                    UpdateNetworkSupport.retryPolicyFor(kind), consecutiveFailures
                )
                if (wait > 0) UpdateNetworkSupport.sleeper(wait)
            } finally {
                try {
                    activeConnection?.disconnect()
                } catch (_: Exception) {}
            }
        }

        Log.e(TAG, "Download failed after $totalAttempts attempts kind=$lastKind: $lastErrorMessage")
        withContext(Dispatchers.Main) {
            onProgress(DownloadProgress.Error(lastErrorMessage, isAuthOrRateLimit = lastIsAuthOrRateLimit, kind = lastKind))
        }
        null
    }

    /**
     * Resiliently downloads the APK with HTTP Range header support to resume partial downloads.
     * Supports both backend `/updates/laterbox/assets/{id}/download-url` resolvers and direct CDN URLs.
     * Retries automatically on transient network drops and expired signed URLs without discarding data.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        versionName: String,
        assetName: String = "",
        expectedSizeBytes: Long = -1L,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
        publicFallbackUrl: String? = UpdateNetworkSupport.publicAssetUrl(versionName, assetName),
        authHeaderProvider: (suspend (Boolean) -> Pair<String, String>?)? = null,
        onProgress: (DownloadProgress) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val fileName = getApkFileName(versionName, assetName)
        val outputFile = File(updatesDir, fileName)

        // If the file is already fully downloaded and verified, return immediately
        if (outputFile.exists() && outputFile.length() > 0 && verifyApk(context, outputFile)) {
            withContext(Dispatchers.Main) {
                onProgress(DownloadProgress.Completed(outputFile))
            }
            return@withContext outputFile
        }

        downloadFileInternal(
            outputFile = outputFile,
            downloadUrl = downloadUrl,
            expectedTotalBytes = expectedSizeBytes,
            appCheckTokenProvider = appCheckTokenProvider,
            authHeaderProvider = authHeaderProvider,
            publicFallbackUrl = publicFallbackUrl,
            onProgress = onProgress
        )
    }

    private val patchTargetVersionRegex = Regex("""-to-([0-9][0-9A-Za-z._\-]*)\.patch$""")

    /**
     * Downloads a delta patch file, verifying its SHA-256 digest against the manifest.
     */
    suspend fun downloadPatch(
        context: Context,
        deltaPatch: DeltaPatchInfo,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
        publicFallbackUrl: String? = patchTargetVersionRegex.find(deltaPatch.patchFilename)
            ?.groupValues?.get(1)
            ?.let { UpdateNetworkSupport.publicAssetUrl(it, deltaPatch.patchFilename) },
        authHeaderProvider: (suspend (Boolean) -> Pair<String, String>?)? = null,
        onProgress: (DownloadProgress) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val outputFile = File(updatesDir, deltaPatch.patchFilename)

        if (outputFile.exists() && outputFile.length() > 0) {
            val sha = InstalledApkHelper.computeSha256(outputFile)
            if (sha == deltaPatch.patchSha256.lowercase()) {
                withContext(Dispatchers.Main) {
                    onProgress(DownloadProgress.Completed(outputFile))
                }
                return@withContext outputFile
            } else {
                outputFile.delete()
            }
        }

        val downloaded = downloadFileInternal(
            outputFile = outputFile,
            downloadUrl = deltaPatch.downloadUrl,
            expectedTotalBytes = deltaPatch.patchSize,
            appCheckTokenProvider = appCheckTokenProvider,
            authHeaderProvider = authHeaderProvider,
            publicFallbackUrl = publicFallbackUrl,
            onProgress = onProgress
        )

        if (downloaded != null && downloaded.exists()) {
            val actualSha = InstalledApkHelper.computeSha256(downloaded)
            if (actualSha != deltaPatch.patchSha256.lowercase()) {
                Log.w(TAG, "Patch SHA-256 mismatch: got=$actualSha, expected=${deltaPatch.patchSha256}")
                downloaded.delete()
                withContext(Dispatchers.Main) {
                    onProgress(DownloadProgress.Error("Patch file SHA-256 verification failed"))
                }
                return@withContext null
            }
            return@withContext downloaded
        }
        null
    }

    /**
     * Applies a downloaded delta patch to the installed APK, reconstructs the complete target APK,
     * verifies its SHA-256 against the manifest, and cleans up intermediate patch files.
     */
    suspend fun applyDeltaPatch(
        context: Context,
        deltaPatch: DeltaPatchInfo,
        patchFile: File,
        targetVersionName: String,
        targetAssetName: String = "",
        onProgress: ((Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val installedApk = InstalledApkHelper.getInstalledApkFile(context)
            ?: throw IOException("Currently installed APK is not accessible on device")

        val currentInstalledSha = InstalledApkHelper.computeSha256(installedApk)
        if (currentInstalledSha != deltaPatch.baseApkSha256.lowercase()) {
            throw IOException("Base APK SHA-256 mismatch: installed=$currentInstalledSha, expected=${deltaPatch.baseApkSha256}")
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFileName = getApkFileName(targetVersionName, deltaPatch.targetApkFilename.ifBlank { targetAssetName })
        val reconstructedFile = File(updatesDir, targetFileName)

        com.laterbox.app.update.patch.BsPatch.applyPatch(
            oldFile = installedApk,
            patchFile = patchFile,
            newFile = reconstructedFile,
            onProgress = onProgress
        )

        val actualTargetSha = InstalledApkHelper.computeSha256(reconstructedFile)
        if (actualTargetSha != deltaPatch.targetApkSha256.lowercase()) {
            reconstructedFile.delete()
            throw IOException("Reconstructed APK SHA-256 mismatch: got=$actualTargetSha, expected=${deltaPatch.targetApkSha256}")
        }

        // Clean up temporary patch file on success
        try {
            patchFile.delete()
        } catch (_: Exception) {}

        reconstructedFile
    }

    /**
     * Verifies that the downloaded APK matches Laterbox's package name and signing identity.
     */
    fun verifyApkDetailed(context: Context, apkFile: File): ApkVerificationResult {
        try {
            val pm = context.packageManager
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?: @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return ApkVerificationResult.Error("Unable to parse APK package info")

            // 1. Verify package identity
            val expectedPackage = context.packageName.removeSuffix(".debug")
            val archivePackage = (archiveInfo.packageName ?: "").removeSuffix(".debug")
            if (archivePackage != expectedPackage && archiveInfo.packageName != context.packageName) {
                Log.w(TAG, "Package name mismatch: expected $expectedPackage, found $archivePackage")
                return ApkVerificationResult.InvalidPackage(expectedPackage, archivePackage)
            }

            // 2. Verify signing certificates match installed application using cryptographic digests
            val archiveSigners: Set<String> = InstalledApkHelper.getArchiveSignersSha256(pm, apkFile, archiveInfo)
            val installedSigners: Set<String> = InstalledApkHelper.getInstalledSignersSha256(context)

            if (installedSigners.isNotEmpty() && archiveSigners.isNotEmpty() && installedSigners.intersect(archiveSigners).isEmpty()) {
                Log.w(TAG, "Signing certificate mismatch: installed=$installedSigners, archive=$archiveSigners")
                return ApkVerificationResult.SignatureMismatch(
                    "Signature mismatch: The update was signed with an unexpected certificate. Android security requires matching signing certificates."
                )
            }

            // 3. Verify native library ABI compatibility
            val apkAbis = InstalledApkHelper.extractApkNativeAbis(apkFile)

            if (apkAbis.isNotEmpty()) {
                val deviceAbis = Build.SUPPORTED_ABIS ?: emptyArray()
                val isCompatible = deviceAbis.any { it in apkAbis }
                if (!isCompatible) {
                    val msg = "Incompatible architecture: This APK was packaged for [${apkAbis.joinToString()}], which is not compatible with your device [${deviceAbis.joinToString()}]."
                    Log.w(TAG, msg)
                    return ApkVerificationResult.IncompatibleAbi(msg)
                }
            }

            return ApkVerificationResult.Valid
        } catch (e: Exception) {
            Log.e(TAG, "Verification error", e)
            return ApkVerificationResult.Error(e.message ?: "Unknown verification error")
        }
    }

    fun verifyApk(context: Context, apkFile: File): Boolean {
        return verifyApkDetailed(context, apkFile) is ApkVerificationResult.Valid
    }

    /**
     * Checks if the app has permission to request package installs (Android 8.0+).
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens system Settings so user can enable "Install unknown apps" for Laterbox.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open install permission settings", e)
            }
        }
    }

    /**
     * Launches Android's PackageInstaller for the verified APK file.
     */
    fun launchInstaller(context: Context, apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            false
        }
    }

    /**
     * Copies the downloaded APK into the public Downloads directory so the user can easily access,
     * inspect, or install it manually if system installer encounters errors.
     */
    fun saveApkToDownloads(context: Context, apkFile: File): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, apkFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        apkFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    return uri
                }
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                val destFile = File(downloadsDir, apkFile.name)
                apkFile.copyTo(destFile, overwrite = true)
                return Uri.fromFile(destFile)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save APK to Downloads", e)
            null
        }
    }

    /**
     * Shares the APK via Android's system share sheet so the user can send or backup the file.
     */
    fun shareApk(context: Context, apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(shareIntent, "Save or Share Laterbox APK").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share APK", e)
            false
        }
    }

    /**
     * Fallback to open GitHub Releases page in external browser if direct APK install fails.
     */
    fun openFallbackReleasePage(context: Context, releasePageUrl: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releasePageUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open release fallback page", e)
        }
    }

    /**
     * Cleans up cached update APK files in [context.cacheDir/updates].
     * Automatically deletes any APK corresponding to the currently running version or older,
     * as well as temporary or unparseable partial files older than 24 hours.
     * Retains newer, verified APKs that the user may have downloaded but not yet installed.
     *
     * @return Number of deleted files.
     */
    fun cleanupOldUpdateApks(context: Context, currentVersionName: String): Int {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists() || !updatesDir.isDirectory) return 0

        val files = updatesDir.listFiles() ?: return 0
        var deletedCount = 0
        val pm = context.packageManager
        val cleanCurrent = AppUpdateInfo.cleanVersion(currentVersionName)

        for (file in files) {
            try {
                if (!file.isFile) continue

                if (file.name.endsWith(".apk", ignoreCase = true)) {
                    val archiveInfo = try {
                        pm.getPackageArchiveInfo(file.absolutePath, 0)
                    } catch (_: Exception) {
                        null
                    }

                    val archiveVersion = archiveInfo?.versionName?.let { AppUpdateInfo.cleanVersion(it) }
                    val nameVersion = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(file.name)?.value?.let {
                        AppUpdateInfo.cleanVersion(it)
                    }
                    val detectedVersion = archiveVersion ?: nameVersion

                    if (detectedVersion != null && detectedVersion.isNotBlank()) {
                        // If the APK version is <= current version, it has already been installed or superseded
                        if (!AppUpdateInfo.isNewerVersion(detectedVersion, cleanCurrent)) {
                            Log.d(TAG, "Deleting obsolete update APK: ${file.name} (detected=$detectedVersion, current=$cleanCurrent)")
                            if (file.delete()) deletedCount++
                        }
                    } else {
                        // Unparseable APK older than 24 hours
                        val ageMs = System.currentTimeMillis() - file.lastModified()
                        if (ageMs > 24 * 60 * 60 * 1000L) {
                            Log.d(TAG, "Deleting stale unparseable APK: ${file.name}")
                            if (file.delete()) deletedCount++
                        }
                    }
                } else {
                    // Non-APK temporary files in updates folder (e.g. .patch, .tmp, .part)
                    val ageMs = System.currentTimeMillis() - file.lastModified()
                    if (file.name.endsWith(".patch", ignoreCase = true) ||
                        file.name.contains(".tmp") ||
                        file.name.contains(".part") ||
                        ageMs > 24 * 60 * 60 * 1000L
                    ) {
                        if (file.delete()) deletedCount++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning update file: ${file.name}", e)
            }
        }
        return deletedCount
    }
}
