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

    private data class ManifestFetchOutcome(
        val manifest: DeltaManifest? = null,
        val errorDetail: String? = null
    )

    private suspend fun fetchDeltaManifest(
        manifestAsset: ReleaseAsset,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null
    ): ManifestFetchOutcome {
        var conn: HttpURLConnection? = null
        return try {
            val targetUrl = if (ApkInstallerHelper.isBackendAssetResolverUrl(manifestAsset.downloadUrl)) {
                val resolved = ApkInstallerHelper.resolveSignedAssetDownloadUrl(
                    resolverUrl = manifestAsset.downloadUrl,
                    appCheckTokenProvider = appCheckTokenProvider
                )
                if (resolved.downloadUrl == null) {
                    return ManifestFetchOutcome(
                        errorDetail = resolved.errorMessage ?: "Failed to resolve download URL for delta-manifest.json"
                    )
                }
                resolved.downloadUrl
            } else {
                manifestAsset.downloadUrl
            }
            val url = URL(targetUrl)
            conn = UpdateNetworkSupport.connectionFactory(url).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Laterbox-App")
                setRequestProperty("Accept", "application/json")
            }
            val statusCode = conn.responseCode
            if (statusCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = DeltaManifest.fromJson(text)
                if (parsed != null) {
                    ManifestFetchOutcome(manifest = parsed)
                } else {
                    ManifestFetchOutcome(errorDetail = "delta-manifest.json failed schema validation or JSON parsing")
                }
            } else {
                ManifestFetchOutcome(errorDetail = "delta-manifest.json download returned HTTP $statusCode")
            }
        } catch (e: Exception) {
            ManifestFetchOutcome(errorDetail = "Exception fetching delta-manifest.json: ${e.message ?: e::class.java.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    private const val FALLBACK_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val FALLBACK_FORCE_COOLDOWN_MS = 60 * 1000L

    private data class FallbackCacheEntry(
        val body: UpdateCheckResult,
        val currentVersionName: String,
        val storedAtMs: Long
    )

    @Volatile
    private var fallbackCache: FallbackCacheEntry? = null

    internal var clock: () -> Long = { System.currentTimeMillis() }

    internal fun resetFallbackCache() {
        fallbackCache = null
    }

    private fun isBackendLatestUrl(url: String): Boolean = try {
        (java.net.URI(url).path ?: "").trimEnd('/').endsWith("/updates/laterbox/latest")
    } catch (_: Exception) {
        false
    }

    private fun isNetworkLevelFailure(cause: Throwable?): Boolean =
        cause is UnknownHostException || cause is ConnectException ||
            cause is java.net.NoRouteToHostException || cause is SocketTimeoutException

    /**
     * Checks the backend first. Only when the backend is unreachable at the network level
     * (DNS / connect / timeout) does it consult the unauthenticated public GitHub Releases API.
     * Auth, rate-limit, API and malformed-data failures never trigger the fallback.
     */
    suspend fun checkForUpdate(
        currentVersionName: String,
        releasesUrl: String = buildBackendUpdateUrl(),
        deviceSupportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray(),
        context: Context? = null,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
        forceRefresh: Boolean = false,
        publicFallbackUrl: String? = UpdateNetworkSupport.PUBLIC_RELEASES_LATEST_URL,
        currentVersionCode: Int = 0,
    ): UpdateCheckResult {
        val primary = checkForUpdateAt(
            currentVersionName,
            releasesUrl,
            deviceSupportedAbis,
            context,
            appCheckTokenProvider,
            forceRefresh,
            currentVersionCode = currentVersionCode,
        )
        if (primary !is UpdateCheckResult.NetworkError ||
            publicFallbackUrl == null ||
            !isNetworkLevelFailure(primary.cause) ||
            !isBackendLatestUrl(releasesUrl)
        ) {
            return primary
        }
        val host = UpdateNetworkSupport.safeHost(releasesUrl)
        Log.w(TAG, "Backend unreachable host=$host cause=${primary.cause?.javaClass?.simpleName}; trying public GitHub release metadata")
        val now = clock()
        val cached = fallbackCache
        if (cached != null && cached.currentVersionName == currentVersionName) {
            val age = now - cached.storedAtMs
            val ttl = if (forceRefresh) FALLBACK_FORCE_COOLDOWN_MS else FALLBACK_CACHE_TTL_MS
            if (age in 0 until ttl) return cached.body
        }
        val fallback = checkForUpdateAt(
            currentVersionName,
            publicFallbackUrl,
            deviceSupportedAbis,
            context,
            null,
            false,
            currentVersionCode = currentVersionCode,
        )
        return when (fallback) {
            is UpdateCheckResult.UpdateAvailable, is UpdateCheckResult.UpToDate -> {
                fallbackCache = FallbackCacheEntry(fallback, currentVersionName, now)
                fallback
            }
            is UpdateCheckResult.RateLimited -> fallback
            else -> primary
        }
    }

    private suspend fun checkForUpdateAt(
        currentVersionName: String,
        releasesUrl: String,
        deviceSupportedAbis: Array<String>,
        context: Context?,
        appCheckTokenProvider: (suspend (Boolean) -> String?)?,
        forceRefresh: Boolean,
        currentVersionCode: Int = 0,
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
                    connection = UpdateNetworkSupport.connectionFactory(url).apply {
                        requestMethod = "GET"
                        connectTimeout = 12_000
                        readTimeout = 15_000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "Laterbox-App")
                        if (isBackendLatestUrl(effectiveUrl)) {
                            val effectiveVCode = if (currentVersionCode > 0) currentVersionCode else com.laterbox.app.BuildConfig.VERSION_CODE
                            setRequestProperty("X-Laterbox-Client", "android")
                            setRequestProperty("X-Laterbox-Version-Code", effectiveVCode.toString())
                            setRequestProperty("X-Laterbox-Version-Name", com.laterbox.app.BuildConfig.VERSION_NAME)
                        }
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

            val resolvedVersionCode = root.optInt("versionCode", 0).takeIf { it > 0 }
                ?: root.optJSONObject("delta_manifest")?.optInt("targetVersionCode", 0)?.takeIf { it > 0 }

            val cleanVersion = AppUpdateInfo.cleanVersion(tagName)
            val isNewerByName = AppUpdateInfo.isNewerVersion(cleanVersion, currentVersionName)
            val isNewerByCode = currentVersionCode > 0 && resolvedVersionCode != null && resolvedVersionCode > currentVersionCode
            if (!isNewerByName && !isNewerByCode) {
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

            val deltaOutcome = resolveDeltaPatch(
                context = context,
                deviceSupportedAbis = deviceSupportedAbis,
                parsedAssets = parsedAssets,
                appCheckTokenProvider = appCheckTokenProvider,
                manifestProvider = {
                    val inlineManifestJson = root.optJSONObject("delta_manifest")
                    if (inlineManifestJson != null) {
                        val parsed = DeltaManifest.fromJson(inlineManifestJson.toString())
                        if (parsed != null) {
                            ManifestFetchOutcome(manifest = parsed)
                        } else {
                            ManifestFetchOutcome(errorDetail = "Inline delta_manifest failed schema validation or JSON parsing")
                        }
                    } else {
                        val manifestAsset = parsedAssets.firstOrNull {
                            it.name.equals(DeltaManifest.MANIFEST_FILENAME, ignoreCase = true)
                        }
                        if (manifestAsset != null) {
                            fetchDeltaManifest(manifestAsset, appCheckTokenProvider)
                        } else {
                            val backendError = root.optString("delta_manifest_error", "").trim().ifBlank { null }
                            ManifestFetchOutcome(
                                errorDetail = backendError ?: "Release does not include ${DeltaManifest.MANIFEST_FILENAME}"
                            )
                        }
                    }
                },
                patchUrlResolver = { patch ->
                    parsedAssets.firstOrNull { it.name.equals(patch.patchFilename, ignoreCase = true) }
                        ?.downloadUrl
                        ?.takeIf { it.isNotBlank() }
                }
            )

            logDeltaOutcome(deltaOutcome)

            val deltaPatch = deltaOutcome.patch
            val matchedTargetAsset = if (deltaPatch != null) {
                parsedAssets.firstOrNull { it.name.equals(deltaPatch.targetApkFilename, ignoreCase = true) }
            } else {
                null
            }
            val apkDownloadUrl = matchedTargetAsset?.downloadUrl?.takeIf { it.isNotBlank() }
                ?: selected.asset.downloadUrl.ifBlank { releasePageUrl }
            val effectiveApkSize = matchedTargetAsset?.size?.takeIf { it > 0L }
                ?: deltaPatch?.targetApkSize?.takeIf { it > 0L }
                ?: selected.asset.size
            val effectiveAbiLabel = deltaPatch?.architectureLabel?.ifBlank { selected.targetAbiLabel }
                ?: selected.targetAbiLabel
            val effectiveAssetName = matchedTargetAsset?.name
                ?: deltaPatch?.targetApkFilename?.ifBlank { selected.asset.name }
                ?: selected.asset.name

            val info = AppUpdateInfo(
                versionName = cleanVersion,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = apkDownloadUrl,
                releasePageUrl = releasePageUrl,
                apkSize = effectiveApkSize,
                isNewer = true,
                targetAbiLabel = effectiveAbiLabel,
                assetName = effectiveAssetName,
                deltaPatch = deltaPatch,
                deltaDiagnosticReason = deltaOutcome.reason,
                deltaDiagnosticDetail = deltaOutcome.detail,
                versionCode = resolvedVersionCode,
            )

            UpdateCheckResult.UpdateAvailable(info)
        } catch (e: UnknownHostException) {
            UpdateCheckResult.NetworkError(UpdateNetworkSupport.userMessage(UpdateFailureKind.DNS), e)
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

    private data class DeltaResolutionOutcome(
        val patch: DeltaPatchInfo?,
        val reason: DeltaDiagnosticReason,
        val detail: String
    )

    private fun logDeltaOutcome(outcome: DeltaResolutionOutcome) {
        if (outcome.patch != null && outcome.reason == DeltaDiagnosticReason.SELECTED) {
            Log.i(TAG, "Delta [${outcome.reason.logCode}]: ${outcome.detail}")
        } else {
            Log.w(TAG, "Delta unavailable [${outcome.reason.logCode}]: ${outcome.detail}")
        }
    }

    private suspend fun resolveDeltaPatch(
        context: Context?,
        deviceSupportedAbis: Array<String>,
        parsedAssets: List<ReleaseAsset>,
        appCheckTokenProvider: (suspend (Boolean) -> String?)? = null,
        manifestProvider: suspend () -> ManifestFetchOutcome,
        patchUrlResolver: (DeltaPatchInfo) -> String?
    ): DeltaResolutionOutcome {
        if (context == null) {
            return DeltaResolutionOutcome(
                patch = null,
                reason = DeltaDiagnosticReason.INSTALLED_APK_CONTEXT_UNAVAILABLE,
                detail = DeltaDiagnosticReason.INSTALLED_APK_CONTEXT_UNAVAILABLE.description
            )
        }
        return try {
            when (val inspection = InstalledApkHelper.inspectInstalledApk(context)) {
                is InstalledApkHelper.InstalledApkInspection.ApkFileInaccessible -> {
                    DeltaResolutionOutcome(
                        patch = null,
                        reason = DeltaDiagnosticReason.INSTALLED_APK_INACCESSIBLE,
                        detail = DeltaDiagnosticReason.INSTALLED_APK_INACCESSIBLE.description
                    )
                }
                is InstalledApkHelper.InstalledApkInspection.Sha256CalculationFailed -> {
                    DeltaResolutionOutcome(
                        patch = null,
                        reason = DeltaDiagnosticReason.INSTALLED_APK_SHA256_FAILED,
                        detail = inspection.errorMessage ?: DeltaDiagnosticReason.INSTALLED_APK_SHA256_FAILED.description
                    )
                }
                is InstalledApkHelper.InstalledApkInspection.Ready -> {
                    val manifestOutcome = manifestProvider()
                    val manifest = manifestOutcome.manifest
                    if (manifest == null) {
                        DeltaResolutionOutcome(
                            patch = null,
                            reason = DeltaDiagnosticReason.INVALID_OR_UNAVAILABLE_MANIFEST,
                            detail = manifestOutcome.errorDetail ?: DeltaDiagnosticReason.INVALID_OR_UNAVAILABLE_MANIFEST.description
                        )
                    } else {
                        val match = manifest.findCompatiblePatchDetailed(
                            installedApkSha256 = inspection.sha256,
                            deviceSupportedAbis = deviceSupportedAbis,
                            installedApkAbis = inspection.nativeAbis
                        )
                        val compatiblePatch = match.patch
                        if (compatiblePatch == null) {
                            DeltaResolutionOutcome(
                                patch = null,
                                reason = match.reason,
                                detail = match.detail
                            )
                        } else {
                            val patchUrl = patchUrlResolver(compatiblePatch)
                            if (patchUrl.isNullOrBlank()) {
                                DeltaResolutionOutcome(
                                    patch = null,
                                    reason = DeltaDiagnosticReason.PATCH_ASSET_UNAVAILABLE,
                                    detail = "Manifest references '${compatiblePatch.patchFilename}' (base=${compatiblePatch.baseVersion}, arch=${compatiblePatch.architecture}), which is missing from release assets"
                                )
                            } else {
                                DeltaResolutionOutcome(
                                    patch = compatiblePatch.copy(downloadUrl = patchUrl),
                                    reason = DeltaDiagnosticReason.SELECTED,
                                    detail = "${match.detail} (${compatiblePatch.patchSize} bytes)"
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DeltaResolutionOutcome(
                patch = null,
                reason = DeltaDiagnosticReason.INVALID_OR_UNAVAILABLE_MANIFEST,
                detail = "Unexpected error resolving delta patch: ${e.message ?: e::class.java.simpleName}"
            )
        }
    }

    private suspend fun parseFromDeltaManifest(
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

        val deltaOutcome = resolveDeltaPatch(
            context = context,
            deviceSupportedAbis = deviceSupportedAbis,
            parsedAssets = parsedAssets,
            manifestProvider = { ManifestFetchOutcome(manifest = manifest) },
            patchUrlResolver = { patch ->
                patch.patchFilename.takeIf { it.isNotBlank() }?.let { "$downloadBase/$it" }
            }
        )
        logDeltaOutcome(deltaOutcome)

        val deltaPatch = deltaOutcome.patch
        val matchedTargetAsset = if (deltaPatch != null) {
            parsedAssets.firstOrNull { it.name.equals(deltaPatch.targetApkFilename, ignoreCase = true) }
        } else {
            null
        }

        val info = AppUpdateInfo(
            versionName = cleanVersion,
            releaseTitle = "Laterbox $tag",
            releaseNotes = "",
            downloadUrl = matchedTargetAsset?.downloadUrl ?: selected.asset.downloadUrl.ifBlank { releasePageUrl },
            releasePageUrl = releasePageUrl,
            apkSize = matchedTargetAsset?.size ?: deltaPatch?.targetApkSize ?: selected.asset.size,
            isNewer = true,
            targetAbiLabel = deltaPatch?.architectureLabel?.ifBlank { selected.targetAbiLabel } ?: selected.targetAbiLabel,
            assetName = matchedTargetAsset?.name ?: deltaPatch?.targetApkFilename ?: selected.asset.name,
            deltaPatch = deltaPatch,
            deltaDiagnosticReason = deltaOutcome.reason,
            deltaDiagnosticDetail = deltaOutcome.detail
        )
        return UpdateCheckResult.UpdateAvailable(info)
    }
}
