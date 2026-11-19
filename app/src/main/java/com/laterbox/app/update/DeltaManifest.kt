package com.laterbox.app.update

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Diagnostic reason explaining whether a delta patch was selected or why delta resolution fell back.
 */
@Keep
enum class DeltaDiagnosticReason(val logCode: String, val description: String) {
    SELECTED("selected", "Compatible delta patch selected"),
    INSTALLED_APK_CONTEXT_UNAVAILABLE("installed_apk_context_unavailable", "Installed APK context unavailable"),
    INSTALLED_APK_INACCESSIBLE("installed_apk_inaccessible", "Installed APK inaccessible on device"),
    INSTALLED_APK_SHA256_FAILED("installed_apk_sha256_failed", "Installed APK SHA-256 calculation failed"),
    INVALID_OR_UNAVAILABLE_MANIFEST("invalid_or_unavailable_manifest", "Invalid or unavailable delta manifest"),
    NO_MATCHING_BASE_HASH("no_matching_base_hash", "No matching base APK hash in delta manifest"),
    NO_ARCHITECTURE_COMPATIBLE_PATCH(
        "no_architecture_compatible_patch",
        "No architecture-compatible delta patch for installed variant or device ABIs"
    ),
    PATCH_ASSET_UNAVAILABLE(
        "patch_asset_unavailable",
        "Manifest entry found, but corresponding patch asset is unavailable in release"
    )
}

/**
 * Structured outcome of matching an installed APK against a [DeltaManifest].
 */
@Keep
data class DeltaMatchResult(
    val patch: DeltaPatchInfo?,
    val reason: DeltaDiagnosticReason,
    val installedVariant: String? = null,
    val detail: String = reason.description
)

/**
 * Metadata describing a differential binary patch between a base release APK and a target release APK.
 */
@Keep
data class DeltaPatchInfo(
    val architecture: String,
    val architectureLabel: String,
    val baseVersion: String,
    val baseVersionCode: Long,
    val baseApkSha256: String,
    val targetApkFilename: String,
    val targetApkSha256: String,
    val targetApkSize: Long,
    val patchFilename: String,
    val patchSha256: String,
    val patchSize: Long,
    val patchAlgorithm: String = "BSDIFF40",
    val downloadUrl: String = "",
    val baseArchitecture: String = ""
) {
    fun effectiveBaseArchitecture(): String {
        val raw = baseArchitecture.ifBlank { architecture }
        return normalizeArchitecture(raw)
    }

    fun normalizedArchitecture(): String = normalizeArchitecture(architecture)

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("architecture", architecture)
            if (baseArchitecture.isNotBlank()) {
                put("baseArchitecture", baseArchitecture)
            }
            put("architectureLabel", architectureLabel)
            put("baseVersion", baseVersion)
            put("baseVersionCode", baseVersionCode)
            put("baseApkSha256", baseApkSha256.lowercase())
            put("targetApkFilename", targetApkFilename)
            put("targetApkSha256", targetApkSha256.lowercase())
            put("targetApkSize", targetApkSize)
            put("patchFilename", patchFilename)
            put("patchSha256", patchSha256.lowercase())
            put("patchSize", patchSize)
            put("patchAlgorithm", patchAlgorithm)
        }
    }

    companion object {
        fun normalizeArchitecture(raw: String): String {
            val clean = raw.trim().lowercase()
            return when {
                clean == "arm64" || clean == "arm64-v8a" -> "arm64-v8a"
                clean == "arm32" || clean == "armeabi-v7a" || clean == "armeabi" -> "armeabi-v7a"
                clean == "universal" -> "universal"
                else -> clean
            }
        }

        fun fromJsonObject(obj: JSONObject, downloadUrl: String = ""): DeltaPatchInfo? {
            val arch = obj.optString("architecture").trim()
            val baseArch = obj.optString("baseArchitecture").trim()
            val baseVer = obj.optString("baseVersion").trim()
            val baseCode = obj.optLong("baseVersionCode", -1L)
            val baseSha = obj.optString("baseApkSha256").trim().lowercase()
            val targetName = obj.optString("targetApkFilename").trim()
            val targetSha = obj.optString("targetApkSha256").trim().lowercase()
            val targetSize = obj.optLong("targetApkSize", 0L)
            val patchName = obj.optString("patchFilename").trim()
            val patchSha = obj.optString("patchSha256").trim().lowercase()
            val patchSize = obj.optLong("patchSize", 0L)
            val algorithm = obj.optString("patchAlgorithm", "BSDIFF40").trim()
            val label = obj.optString("architectureLabel").ifBlank { arch }

            if (arch.isBlank() || baseVer.isBlank() || baseSha.isBlank() ||
                targetName.isBlank() || targetSha.isBlank() ||
                patchName.isBlank() || patchSha.isBlank() || patchSize <= 0L
            ) {
                return null
            }

            return DeltaPatchInfo(
                architecture = arch,
                architectureLabel = label,
                baseVersion = baseVer,
                baseVersionCode = baseCode,
                baseApkSha256 = baseSha,
                targetApkFilename = targetName,
                targetApkSha256 = targetSha,
                targetApkSize = targetSize,
                patchFilename = patchName,
                patchSha256 = patchSha,
                patchSize = patchSize,
                patchAlgorithm = algorithm,
                downloadUrl = downloadUrl,
                baseArchitecture = baseArch
            )
        }
    }
}

/**
 * Top-level delta update manifest published alongside release APKs in GitHub Releases.
 */
@Keep
data class DeltaManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val targetVersion: String,
    val targetVersionCode: Long,
    val generatedAt: Long = System.currentTimeMillis(),
    val patches: List<DeltaPatchInfo> = emptyList()
) {
    fun toJson(): String {
        val root = JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("targetVersion", targetVersion)
            put("targetVersionCode", targetVersionCode)
            put("generatedAt", generatedAt)
            val patchArray = JSONArray()
            for (patch in patches) {
                patchArray.put(patch.toJsonObject())
            }
            put("patches", patchArray)
        }
        return root.toString(2)
    }

    /**
     * Finds a compatible patch matching the installed APK's cryptographic SHA-256 hash,
     * installed APK variant (when available), and target device architecture.
     */
    @JvmOverloads
    fun findCompatiblePatch(
        installedApkSha256: String,
        deviceSupportedAbis: Array<String>,
        installedApkAbis: Set<String> = emptySet()
    ): DeltaPatchInfo? {
        return findCompatiblePatchDetailed(
            installedApkSha256 = installedApkSha256,
            deviceSupportedAbis = deviceSupportedAbis,
            installedApkAbis = installedApkAbis
        ).patch
    }

    /**
     * Finds a compatible patch and returns structured diagnostic details explaining the outcome.
     *
     * Matching guarantees:
     * 1. Exact installed APK SHA-256 match and supported algorithm (`BSDIFF40`).
     * 2. Device ABI compatibility check (ARM64-only patches are rejected on 32-bit-only devices).
     * 3. Installed APK variant match (`arm64-v8a`, `armeabi-v7a`, or `universal`) when native ABIs are known,
     *    so a 64-bit-capable device running an ARM32 installation selects its matching ARM32 patch.
     * 4. Deterministic ranking when multiple candidates match the installed APK hash.
     */
    @JvmOverloads
    fun findCompatiblePatchDetailed(
        installedApkSha256: String,
        deviceSupportedAbis: Array<String>,
        installedApkAbis: Set<String> = emptySet()
    ): DeltaMatchResult {
        val installedVariant = InstalledApkHelper.inferVariantFromAbis(installedApkAbis)
        if (installedApkSha256.isBlank()) {
            return DeltaMatchResult(
                patch = null,
                reason = DeltaDiagnosticReason.INSTALLED_APK_SHA256_FAILED,
                installedVariant = installedVariant,
                detail = "Installed APK SHA-256 digest is blank."
            )
        }
        val normalizedInstalledSha = installedApkSha256.trim().lowercase()

        // 1. Primary gate: exact base APK SHA-256 and supported algorithm
        val hashMatching = patches.filter { patch ->
            patch.patchAlgorithm.equals("BSDIFF40", ignoreCase = true) &&
                patch.baseApkSha256.trim().lowercase() == normalizedInstalledSha
        }
        if (hashMatching.isEmpty()) {
            return DeltaMatchResult(
                patch = null,
                reason = DeltaDiagnosticReason.NO_MATCHING_BASE_HASH,
                installedVariant = installedVariant,
                detail = "No manifest patch matches installed APK SHA-256 (${normalizedInstalledSha.take(12)}...)."
            )
        }

        val is64BitSupported = deviceSupportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        val isArm32Supported = deviceSupportedAbis.any { it.startsWith("armeabi", ignoreCase = true) }

        fun isSupportedByDevice(targetArch: String): Boolean {
            return when (DeltaPatchInfo.normalizeArchitecture(targetArch)) {
                "arm64-v8a" -> is64BitSupported
                "armeabi-v7a" -> isArm32Supported
                "universal" -> is64BitSupported || isArm32Supported
                else -> false
            }
        }

        // 2. Safety gate: never select a patch targeting an architecture the device cannot install
        val deviceCompatible = hashMatching.filter { isSupportedByDevice(it.architecture) }
        if (deviceCompatible.isEmpty()) {
            return DeltaMatchResult(
                patch = null,
                reason = DeltaDiagnosticReason.NO_ARCHITECTURE_COMPATIBLE_PATCH,
                installedVariant = installedVariant,
                detail = "Hash matched ${hashMatching.size} patch(es) for [${hashMatching.joinToString { it.architecture }}], none compatible with device ABIs [${deviceSupportedAbis.joinToString()}]."
            )
        }

        // 3. Variant gate: if installed APK ABIs clearly identify a variant, enforce variant compatibility
        val variantCandidates = if (installedVariant != null) {
            deviceCompatible.filter { it.effectiveBaseArchitecture() == installedVariant }
        } else {
            deviceCompatible
        }
        if (variantCandidates.isEmpty()) {
            return DeltaMatchResult(
                patch = null,
                reason = DeltaDiagnosticReason.NO_ARCHITECTURE_COMPATIBLE_PATCH,
                installedVariant = installedVariant,
                detail = "Installed APK variant is $installedVariant, but matching hash entry targets [${deviceCompatible.joinToString { it.effectiveBaseArchitecture() }}]."
            )
        }

        // 4. Deterministic selection strategy when multiple candidates match
        fun deviceAbiRank(arch: String): Int {
            val norm = DeltaPatchInfo.normalizeArchitecture(arch)
            return when {
                installedVariant != null && norm == installedVariant -> 0
                is64BitSupported -> when (norm) {
                    "arm64-v8a" -> 1
                    "armeabi-v7a" -> 2
                    "universal" -> 3
                    else -> 4
                }
                isArm32Supported -> when (norm) {
                    "armeabi-v7a" -> 1
                    "universal" -> 2
                    else -> 4
                }
                else -> 4
            }
        }

        val selected = variantCandidates.minWithOrNull(
            compareBy<DeltaPatchInfo>(
                { if (installedVariant != null && it.normalizedArchitecture() == installedVariant) 0 else 1 },
                { deviceAbiRank(it.architecture) },
                { it.patchSize },
                { -it.baseVersionCode },
                { it.patchFilename }
            )
        ) ?: return DeltaMatchResult(
            patch = null,
            reason = DeltaDiagnosticReason.NO_ARCHITECTURE_COMPATIBLE_PATCH,
            installedVariant = installedVariant
        )

        return DeltaMatchResult(
            patch = selected,
            reason = DeltaDiagnosticReason.SELECTED,
            installedVariant = installedVariant,
            detail = "Selected ${selected.patchFilename} (base=${selected.baseVersion}, arch=${selected.architecture}, installedVariant=${installedVariant ?: "unknown"})"
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MANIFEST_FILENAME = "delta-manifest.json"

        fun fromJson(jsonStr: String): DeltaManifest? {
            return try {
                val root = JSONObject(jsonStr)
                val schemaVer = root.optInt("schemaVersion", 1)
                if (schemaVer > CURRENT_SCHEMA_VERSION) {
                    // Fail closed on unsupported future schema version
                    return null
                }
                val targetVer = root.optString("targetVersion").trim()
                val targetCode = root.optLong("targetVersionCode", 0L)
                val generatedAt = root.optLong("generatedAt", 0L)

                val patchArray = root.optJSONArray("patches") ?: JSONArray()
                val patchList = mutableListOf<DeltaPatchInfo>()

                for (i in 0 until patchArray.length()) {
                    val pObj = patchArray.optJSONObject(i) ?: continue
                    val patch = DeltaPatchInfo.fromJsonObject(pObj)
                    if (patch != null) {
                        patchList.add(patch)
                    }
                }

                if (targetVer.isBlank()) null
                else DeltaManifest(
                    schemaVersion = schemaVer,
                    targetVersion = targetVer,
                    targetVersionCode = targetCode,
                    generatedAt = generatedAt,
                    patches = patchList
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
