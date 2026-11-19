package com.laterbox.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Utility for safely inspecting the currently installed application package, its APK file,
 * cryptographic signing certificate digests, and version metadata.
 */
object InstalledApkHelper {

    private const val TAG = "InstalledApkHelper"

    /**
     * Structured result of inspecting the installed APK file, SHA-256 digest, and native ABIs.
     */
    sealed class InstalledApkInspection {
        data object ApkFileInaccessible : InstalledApkInspection()
        data class Sha256CalculationFailed(val errorMessage: String?) : InstalledApkInspection()
        data class Ready(
            val file: File,
            val sha256: String,
            val nativeAbis: Set<String>,
            val variant: String?
        ) : InstalledApkInspection()
    }

    /**
     * Retrieves the File handle for the currently installed application APK.
     * Note: Android mounts the installed APK in read-only mode at [Context.getApplicationInfo().sourceDir].
     * The original APK is never modified or moved.
     */
    fun getInstalledApkFile(context: Context): File? {
        return try {
            val sourceDir = context.applicationInfo?.sourceDir
            if (!sourceDir.isNullOrBlank()) {
                val file = File(sourceDir)
                if (file.exists() && file.isFile && file.canRead()) {
                    file
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve installed APK file: ${e.message}")
            null
        }
    }

    /**
     * Extracts native library ABI directory names (`lib/<abi>/...`) from an APK archive.
     * Returns an empty set if the file is not a ZIP archive or contains no native libraries.
     */
    fun extractApkNativeAbis(apkFile: File): Set<String> {
        val abis = mutableSetOf<String>()
        try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("lib/") && !entry.isDirectory) {
                        val parts = entry.name.split("/")
                        if (parts.size >= 2 && parts[1].isNotBlank()) {
                            abis.add(parts[1])
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not inspect APK native ABIs for ${apkFile.name}: ${e.message}")
        }
        return abis
    }

    /**
     * Infers the Laterbox APK variant (`arm64-v8a`, `armeabi-v7a`, or `universal`) from packaged native ABIs.
     */
    fun inferVariantFromAbis(abis: Set<String>): String? {
        if (abis.isEmpty()) return null
        val hasArm64 = abis.any { it.equals("arm64-v8a", ignoreCase = true) || it.equals("arm64", ignoreCase = true) }
        val hasArm32 = abis.any { it.startsWith("armeabi", ignoreCase = true) || it.equals("arm32", ignoreCase = true) }
        return when {
            hasArm64 && hasArm32 -> "universal"
            hasArm64 -> "arm64-v8a"
            hasArm32 -> "armeabi-v7a"
            else -> null
        }
    }

    /**
     * Extracts all packaged native ABIs from the currently installed APK (and split APKs if present).
     */
    fun getInstalledApkAbis(context: Context): Set<String> {
        val collected = mutableSetOf<String>()
        getInstalledApkFile(context)?.let { collected.addAll(extractApkNativeAbis(it)) }
        val splitDirs = try {
            context.applicationInfo?.splitSourceDirs
        } catch (_: Exception) {
            null
        }
        if (splitDirs != null) {
            for (path in splitDirs) {
                if (path.isNullOrBlank()) continue
                val splitFile = File(path)
                if (splitFile.exists() && splitFile.isFile && splitFile.canRead()) {
                    collected.addAll(extractApkNativeAbis(splitFile))
                }
            }
        }
        return collected
    }

    /**
     * Inspects the installed APK file, computes its SHA-256 digest, and reads its packaged native ABIs.
     */
    fun inspectInstalledApk(context: Context): InstalledApkInspection {
        val file = getInstalledApkFile(context) ?: return InstalledApkInspection.ApkFileInaccessible
        val sha = try {
            computeSha256(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute installed APK SHA-256: ${e.message}")
            return InstalledApkInspection.Sha256CalculationFailed(e.message)
        }
        val abis = getInstalledApkAbis(context)
        return InstalledApkInspection.Ready(
            file = file,
            sha256 = sha,
            nativeAbis = abis,
            variant = inferVariantFromAbis(abis)
        )
    }

    /**
     * Computes the SHA-256 digest of a file in 64 KB streaming chunks to avoid loading
     * entire files into memory.
     */
    @Throws(IOException::class)
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.lowercase()
    }

    /**
     * Computes the SHA-256 digest of the currently installed APK, or null if unreadable.
     */
    fun getInstalledApkSha256(context: Context): String? {
        return (inspectInstalledApk(context) as? InstalledApkInspection.Ready)?.sha256
    }

    /**
     * Retrieves the installed application version code safely across all Android versions.
     */
    fun getInstalledVersionCode(context: Context): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Computes the cryptographic SHA-256 hex digest of an X.509 signing certificate byte array.
     */
    fun computeCertSha256(certBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(certBytes).joinToString("") { "%02x".format(it) }.lowercase()
    }

    /**
     * Extracts the set of cryptographic SHA-256 digests for all active signing certificates
     * of the currently running application.
     */
    fun getInstalledSignersSha256(context: Context): Set<String> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                pInfo.signingInfo?.apkContentsSigners?.map { computeCertSha256(it.toByteArray()) }?.toSet() ?: emptySet()
            } else {
                @Suppress("DEPRECATION")
                val pInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pInfo.signatures?.map { computeCertSha256(it.toByteArray()) }?.toSet() ?: emptySet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract installed signing certificates: ${e.message}")
            emptySet()
        }
    }

    /**
     * Extracts the set of cryptographic SHA-256 digests for all signing certificates of an uninstalled APK archive.
     */
    fun getArchiveSignersSha256(pm: PackageManager, archiveFile: File, archiveInfo: PackageInfo): Set<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && archiveInfo.signingInfo != null) {
                archiveInfo.signingInfo?.apkContentsSigners?.map { computeCertSha256(it.toByteArray()) }?.toSet() ?: emptySet()
            } else {
                @Suppress("DEPRECATION")
                val sigs = archiveInfo.signatures
                    ?: pm.getPackageArchiveInfo(archiveFile.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
                @Suppress("DEPRECATION")
                sigs?.map { computeCertSha256(it.toByteArray()) }?.toSet() ?: emptySet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract archive signing certificates: ${e.message}")
            emptySet()
        }
    }
}
