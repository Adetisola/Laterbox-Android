package com.laterbox.app.update

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Distinct possible outcomes when checking for application updates.
 */
@Keep
sealed class UpdateCheckResult {
    @Keep data class UpdateAvailable(val updateInfo: AppUpdateInfo) : UpdateCheckResult()
    @Keep data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    @Keep data class NetworkError(val message: String, val cause: Throwable? = null) : UpdateCheckResult()
    @Keep data class ApiError(val statusCode: Int, val message: String) : UpdateCheckResult()
    @Keep data class AuthError(val statusCode: Int, val message: String) : UpdateCheckResult()
    @Keep data class RateLimited(val retryAfterSeconds: Int?, val message: String) : UpdateCheckResult()
    @Keep data class InvalidRelease(val message: String) : UpdateCheckResult()
    @Keep data class NoCompatibleApk(val releaseVersion: String, val deviceAbis: List<String>) : UpdateCheckResult()

    val isUpdateAvailable: Boolean
        get() = this is UpdateAvailable

    val updateInfoOrNull: AppUpdateInfo?
        get() = (this as? UpdateAvailable)?.updateInfo
}

/**
 * Checks for published updates via Laterbox's authenticated backend update endpoint
 * (`/updates/laterbox/latest`) so both public and private GitHub repositories are
 * supported securely without embedding GitHub credentials in the Android app.
 * Also preserves compatibility with direct release/manifest JSON payloads in tests.
 */
object GitHubUpdateChecker {

    private const val TAG = "GitHubUpdateChecker"
    private const val REPO_BASE_URL = "https://github.com/Adetisola/Laterbox/releases"
    const val DEFAULT_BACKEND_UPDATE_PATH = "/updates/laterbox/latest"

    fun buildBackendUpdateUrl(
        baseUrl: String = com.laterbox.app.BuildConfig.MOVIEBOX_API_BASE_URL,
        forceRefresh: Boolean = false
    ): String {
        val cleanBase = baseUrl.trim()
            .ifBlank { com.laterbox.app.BuildConfig.MOVIEBOX_API_BASE_URL }
            .trim()
            .trimEnd('/')
        val path = "$cleanBase$DEFAULT_BACKEND_UPDATE_PATH"
        return if (forceRefresh) "$path?refresh=1" else path
    }

    private fun withRefreshQueryIfApplicable(url: String, forceRefresh: Boolean): String {
        if (!forceRefresh) return url
        if (!url.contains(DEFAULT_BACKEND_UPDATE_PATH) || url.contains("refresh=")) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}refresh=1"
    }

    private fun resolveRelativeUrl(baseRequestUrl: String, pathOrUrl: String): String {
        return try {
            URL(URL(baseRequestUrl), pathOrUrl).toString()
        } catch (_: Exception) {
            pathOrUrl
        }
    }

    private data class ParsedErrorBody(
        val code: String?,
        val message: String?,
        val retryAfterSeconds: Int?
    )

    private fun parseErrorBody(rawBody: String?): ParsedErrorBody {
        if (rawBody.isNullOrBlank()) return ParsedErrorBody(null, null, null)
        return try {
            val root = JSONObject(rawBody)
            val detailObj = root.optJSONObject("detail")
            if (detailObj != null) {
                val code = detailObj.optString("code", "").trim().ifBlank { null }
                val message = detailObj.optString("message", "").trim().ifBlank { null }
                val retryAfter = if (detailObj.has("retry_after_seconds")) {
                    detailObj.optInt("retry_after_seconds", -1).takeIf { it > 0 }
                } else {
                    null
                }
                ParsedErrorBody(code, message, retryAfter)
            } else {
                val detailStr = root.optString("detail", "").trim().ifBlank { null }
                val msgStr = root.optString("message", "").trim().ifBlank { null }
                ParsedErrorBody(null, detailStr ?: msgStr, null)
            }
        } catch (_: Exception) {
            ParsedErrorBody(null, rawBody.trim().take(240).ifBlank { null }, null)
        }
    }

    private fun parseRetryAfterSeconds(connection: HttpURLConnection, bodyRetryAfter: Int?): Int? {
        if (bodyRetryAfter != null && bodyRetryAfter > 0) return bodyRetryAfter
        val headerRetry = connection.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
        if (headerRetry != null && headerRetry > 0) return headerRetry
        val resetEpoch = connection.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()
        if (resetEpoch != null) {
            val delta = (resetEpoch - (System.currentTimeMillis() / 1000L)).toInt()
            if (delta > 0) return delta
        }
        return null
    }

    private fun readErrorStreamText(connection: HttpURLConnection): String? {
        return try {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long
    )

    data class SelectedApkAsset(
        val asset: ReleaseAsset,
        val targetAbiLabel: String
    )

    /**
     * Inspects device supported ABIs and selects the best matching APK asset from the release.
     */
    fun selectBestApkAsset(
        assets: List<ReleaseAsset>,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray()
    ): SelectedApkAsset? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        val is64BitSupported = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        val isArm32Supported = supportedAbis.any { it.startsWith("armeabi", ignoreCase = true) }

        // Find candidate APKs
        val arm64Apk = apkAssets.firstOrNull {
            it.name.contains("arm64", ignoreCase = true)
        }
        val arm32Apk = apkAssets.firstOrNull {
            it.name.contains("arm32", ignoreCase = true) || it.name.contains("armeabi-v7a", ignoreCase = true)
        }
        val universalApk = apkAssets.firstOrNull {
            it.name.contains("universal", ignoreCase = true) || it.name.equals("app-release.apk", ignoreCase = true)
        }

        return when {
            // 64-bit device: Prefer ARM64 split -> Universal -> Fallback to any non-conflicting
            is64BitSupported && arm64Apk != null ->
                SelectedApkAsset(arm64Apk, "ARM64 (arm64-v8a)")

            // 32-bit only device (e.g., Galaxy A02s): Prefer ARM32 split -> Universal -> any non-arm64
            !is64BitSupported && isArm32Supported && arm32Apk != null ->
                SelectedApkAsset(arm32Apk, "ARM32 (armeabi-v7a)")

            // Universal fallback
            universalApk != null ->
                SelectedApkAsset(universalApk, "Universal")

            // If 64-bit device and no specific split name matched, accept first available apk
            is64BitSupported ->
                SelectedApkAsset(apkAssets.first(), "Universal")

            // If 32-bit device: only accept non-arm64 APKs; NEVER return an arm64-only APK to a 32-bit device
            else -> {
                val non64Apk = apkAssets.firstOrNull { !it.name.contains("arm64", ignoreCase = true) }
                if (non64Apk != null) {
                    SelectedApkAsset(non64Apk, "Universal")
                } else {
                    // No 32-bit or universal APK available in this release
                    null
                }
            }
        }
    }

    private suspend fun fetchDeltaManifest(
        manifestAsset: ReleaseAsset,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null
    ): DeltaManifest? {
        var conn: HttpURLConnection? = null
        return try {
            val targetUrl = if (ApkInstallerHelper.isBackendAssetResolverUrl(manifestAsset.downloadUrl)) {
                val resolved = ApkInstallerHelper.resolveSignedAssetDownloadUrl(
                    resolverUrl = manifestAsset.downloadUrl,
                    appCheckTokenProvider = appCheckTokenProvider
                )
                resolved.downloadUrl ?: return null
            } else {
                manifestAsset.downloadUrl
            }
            val url = URL(targetUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Laterbox-App")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                DeltaManifest.fromJson(text)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun checkForUpdate(
        currentVersionName: String,
        releasesUrl: String = buildBackendUpdateUrl(),
        deviceSupportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray(),
        context: Context? = null,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
        forceRefresh: Boolean = false
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val effectiveUrl = withRefreshQueryIfApplicable(releasesUrl, forceRefresh)
        try {
            var responseText: String? = null

            for (authAttempt in 0..1) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL(effectiveUrl)
                    val appCheckToken = if (appCheckTokenProvider != null) {
                        appCheckTokenProvider(authAttempt > 0)
                    } else {
                        null
                    }
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12_000
                        readTimeout = 15_000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "Laterbox-App")
                        if (!appCheckToken.isNullOrBlank()) {
                            setRequestProperty("X-Firebase-AppCheck", appCheckToken)
                        }
                        instanceFollowRedirects = true
                    }

                    val responseCode = connection.responseCode
                    if (responseCode in 200..299) {
                        responseText = connection.inputStream.bufferedReader().use { it.readText() }
                        break
                    }

                    val rawError = readErrorStreamText(connection)
                    val parsedError = parseErrorBody(rawError)

                    if (responseCode == 401) {
                        if (authAttempt == 0 && appCheckTokenProvider != null) {
                            continue
                        }
                        return@withContext UpdateCheckResult.AuthError(
                            statusCode = 401,
                            message = parsedError.message
                                ?: "App authentication failed while contacting update service."
                        )
                    }

                    if (parsedError.code == "github_auth_failed") {
                        return@withContext UpdateCheckResult.AuthError(
                            statusCode = responseCode,
                            message = parsedError.message
                                ?: "Update server could not authenticate with GitHub Releases."
                        )
                    }

                    val isRateLimit403 = responseCode == 403 && (
                        connection.getHeaderField("X-RateLimit-Remaining")?.trim() == "0" ||
                            connection.getHeaderField("Retry-After") != null ||
                            (parsedError.message ?: "").contains("rate limit", ignoreCase = true) ||
                            (parsedError.message ?: "").contains("abuse", ignoreCase = true) ||
                            (parsedError.message ?: "").contains("too many requests", ignoreCase = true)
                        )

                    if (responseCode == 429 || parsedError.code == "github_rate_limited" || isRateLimit403) {
                        val retryAfter = parseRetryAfterSeconds(connection, parsedError.retryAfterSeconds)
                        return@withContext UpdateCheckResult.RateLimited(
                            retryAfterSeconds = retryAfter,
                            message = parsedError.message
                                ?: "Update server rate limit reached. Please try again later."
                        )
                    }

                    if (responseCode == 403) {
                        return@withContext UpdateCheckResult.AuthError(
                            statusCode = 403,
                            message = parsedError.message
                                ?: "Access to update service or repository was denied."
                        )
                    }

                    if (responseCode == 404) {
                        return@withContext UpdateCheckResult.ApiError(
                            statusCode = 404,
                            message = parsedError.message ?: "No published releases found."
                        )
                    }

                    if (parsedError.code == "invalid_release_metadata") {
                        return@withContext UpdateCheckResult.InvalidRelease(
                            parsedError.message ?: "Release metadata returned by update server is malformed."
                        )
                    }

                    return@withContext UpdateCheckResult.ApiError(
                        statusCode = responseCode,
                        message = parsedError.message ?: "Update server returned HTTP $responseCode"
                    )
                } finally {
                    connection?.disconnect()
                }
            }

            val body = responseText
                ?: return@withContext UpdateCheckResult.AuthError(401, "App authentication failed while contacting update service.")

            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                return@withContext UpdateCheckResult.InvalidRelease("Could not parse release JSON from update server: ${e.message}")
            }

            if (root.has("targetVersion") && root.has("patches")) {
                return@withContext parseFromDeltaManifest(
                    responseText = body,
                    currentVersionName = currentVersionName,
                    deviceSupportedAbis = deviceSupportedAbis,
                    context = context
                )
            }

            val isDraft = root.optBoolean("draft", false)
            val isPrerelease = root.optBoolean("prerelease", false)
            if (isDraft || isPrerelease) {
                return@withContext UpdateCheckResult.UpToDate(currentVersionName)
            }

            val tagName = root.optString("tag_name", "").trim()
            if (tagName.isBlank()) {
                return@withContext UpdateCheckResult.InvalidRelease("Release metadata is missing tag_name.")
            }

            val cleanVersion = AppUpdateInfo.cleanVersion(tagName)
            val isNewer = AppUpdateInfo.isNewerVersion(cleanVersion, currentVersionName)
            if (!isNewer) {
                return@withContext UpdateCheckResult.UpToDate(currentVersionName)
            }

            val releaseTitle = root.optString("name", tagName)
            val releaseNotes = root.optString("body", "")
            val releasePageUrl = root.optString("html_url", "https://github.com/Adetisola/Laterbox/releases")

            val jsonAssets = root.optJSONArray("assets") ?: org.json.JSONArray()
            val parsedAssets = mutableListOf<ReleaseAsset>()

            for (i in 0 until jsonAssets.length()) {
                val assetObj = jsonAssets.optJSONObject(i) ?: continue
                val name = assetObj.optString("name", "").trim()
                val downloadPath = assetObj.optString("download_path", "").trim()
                val browserUrl = assetObj.optString("browser_download_url", "").trim()
                val downloadUrl = when {
                    downloadPath.isNotBlank() -> resolveRelativeUrl(effectiveUrl, downloadPath)
                    browserUrl.isNotBlank() -> browserUrl
                    else -> ""
                }
                val size = assetObj.optLong("size", 0L)
                if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                    parsedAssets.add(ReleaseAsset(name, downloadUrl, size))
                }
            }

            val selected = selectBestApkAsset(parsedAssets, deviceSupportedAbis)
            if (selected == null) {
                return@withContext UpdateCheckResult.NoCompatibleApk(cleanVersion, deviceSupportedAbis.toList())
            }

            // Inspect inline delta_manifest from backend (or fallback to manifest asset) when context is available
            var deltaPatchInfo: DeltaPatchInfo? = null
            if (context != null) {
                try {
                    val inlineManifestJson = root.optJSONObject("delta_manifest")
                    val manifest = if (inlineManifestJson != null) {
                        DeltaManifest.fromJson(inlineManifestJson.toString())
                    } else {
                        val manifestAsset = parsedAssets.firstOrNull {
                            it.name.equals(DeltaManifest.MANIFEST_FILENAME, ignoreCase = true)
                        }
                        manifestAsset?.let { fetchDeltaManifest(it, appCheckTokenProvider) }
                    }

                    if (manifest != null) {
                        val installedSha = InstalledApkHelper.getInstalledApkSha256(context)
                        if (!installedSha.isNullOrBlank()) {
                            val compatiblePatch = manifest.findCompatiblePatch(installedSha, deviceSupportedAbis)
                            if (compatiblePatch != null) {
                                val patchAsset = parsedAssets.firstOrNull {
                                    it.name.equals(compatiblePatch.patchFilename, ignoreCase = true)
                                }
                                if (patchAsset != null && patchAsset.downloadUrl.isNotBlank()) {
                                    deltaPatchInfo = compatiblePatch.copy(downloadUrl = patchAsset.downloadUrl)
                                    Log.i(TAG, "Compatible delta patch selected: ${compatiblePatch.patchFilename} (${compatiblePatch.patchSize} bytes)")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Delta patch resolution encountered an error, falling back to full APK: ${e.message}")
                }
            }

            val apkDownloadUrl = selected.asset.downloadUrl.ifBlank { releasePageUrl }
            val info = AppUpdateInfo(
                versionName = cleanVersion,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = apkDownloadUrl,
                releasePageUrl = releasePageUrl,
                apkSize = selected.asset.size,
                isNewer = true,
                targetAbiLabel = selected.targetAbiLabel,
                assetName = selected.asset.name,
                deltaPatch = deltaPatchInfo
            )

            UpdateCheckResult.UpdateAvailable(info)
        } catch (e: UnknownHostException) {
            UpdateCheckResult.NetworkError("Could not resolve host. Please check your internet connection.", e)
        } catch (e: SocketTimeoutException) {
            UpdateCheckResult.NetworkError("Update check timed out while connecting to update server.", e)
        } catch (e: ConnectException) {
            UpdateCheckResult.NetworkError("Failed to connect to update server.", e)
        } catch (e: IOException) {
            UpdateCheckResult.NetworkError(e.message ?: "Network error during update check.", e)
        } catch (e: Exception) {
            UpdateCheckResult.InvalidRelease("Unexpected error: ${e.message}")
        }
    }

    private fun parseFromDeltaManifest(
        responseText: String,
        currentVersionName: String,
        deviceSupportedAbis: Array<String>,
        context: Context?
    ): UpdateCheckResult {
        val manifest = DeltaManifest.fromJson(responseText)
            ?: return UpdateCheckResult.InvalidRelease("Could not parse delta-manifest.json from GitHub.")

        val cleanVersion = AppUpdateInfo.cleanVersion(manifest.targetVersion)
        if (cleanVersion.isBlank()) {
            return UpdateCheckResult.InvalidRelease("delta-manifest.json is missing targetVersion.")
        }

        if (!AppUpdateInfo.isNewerVersion(cleanVersion, currentVersionName)) {
            return UpdateCheckResult.UpToDate(currentVersionName)
        }

        val tag = "v$cleanVersion"
        val downloadBase = "$REPO_BASE_URL/download/$tag"
        val releasePageUrl = "$REPO_BASE_URL/tag/$tag"

        val parsedAssets = manifest.patches
            .filter { it.targetApkFilename.isNotBlank() }
            .distinctBy { it.targetApkFilename }
            .map { patch ->
                ReleaseAsset(
                    name = patch.targetApkFilename,
                    downloadUrl = "$downloadBase/${patch.targetApkFilename}",
                    size = patch.targetApkSize
                )
            }

        val selected = selectBestApkAsset(parsedAssets, deviceSupportedAbis)
            ?: return UpdateCheckResult.NoCompatibleApk(cleanVersion, deviceSupportedAbis.toList())

        var deltaPatchInfo: DeltaPatchInfo? = null
        if (context != null) {
            try {
                val installedSha = InstalledApkHelper.getInstalledApkSha256(context)
                if (!installedSha.isNullOrBlank()) {
                    val compatiblePatch = manifest.findCompatiblePatch(installedSha, deviceSupportedAbis)
                    if (compatiblePatch != null && compatiblePatch.patchFilename.isNotBlank()) {
                        deltaPatchInfo = compatiblePatch.copy(
                            downloadUrl = "$downloadBase/${compatiblePatch.patchFilename}"
                        )
                        Log.i(
                            "GitHubUpdateChecker",
                            "Compatible delta patch selected: ${compatiblePatch.patchFilename} (${compatiblePatch.patchSize} bytes)"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    "GitHubUpdateChecker",
                    "Delta patch resolution encountered an error, falling back to full APK: ${e.message}"
                )
            }
        }

        val info = AppUpdateInfo(
            versionName = cleanVersion,
            releaseTitle = "Laterbox $tag",
            releaseNotes = "",
            downloadUrl = selected.asset.downloadUrl.ifBlank { releasePageUrl },
            releasePageUrl = releasePageUrl,
            apkSize = selected.asset.size,
            isNewer = true,
            targetAbiLabel = selected.targetAbiLabel,
            assetName = selected.asset.name,
            deltaPatch = deltaPatchInfo
        )
        return UpdateCheckResult.UpdateAvailable(info)
    }
}
