package com.laterbox.app.media.data.adapters.ytdlp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit

@Keep
data class QuickJsRuntimeInfo(
    val isAvailable: Boolean,
    val identity: String,
    val version: String,
    val isCompatible: Boolean,
    val meetsRecommendedBaseline: Boolean,
    val executablePath: String? = null,
    val isExecutable: Boolean = false,
    val detail: String? = null
)

object QuickJsHelper {

    private const val TAG = "QuickJsHelper"
    private val VERSION_LINE_REGEX = Regex("""^QuickJS(?:-ng)?\s+version\s+(\S+)""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val MIN_SUPPORTED_BELLARD = listOf(2023, 12, 9)
    private val MIN_RECOMMENDED_BELLARD = listOf(2025, 4, 26)
    private val MIN_RECOMMENDED_NG = listOf(0, 12, 0)

    @Volatile
    private var cachedSignature: String? = null

    @Volatile
    private var cachedInfo: QuickJsRuntimeInfo? = null

    fun getQuickJsExecutable(context: Context): File? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: return null
        val qjsFile = File(nativeLibDir, "libqjs.so")
        return if (qjsFile.exists() && qjsFile.canExecute()) {
            qjsFile
        } else if (qjsFile.exists()) {
            try {
                qjsFile.setExecutable(true, false)
            } catch (_: Throwable) {
            }
            if (qjsFile.canExecute()) qjsFile else null
        } else {
            null
        }
    }

    fun getJsRuntimeArgument(context: Context): String? {
        val qjs = getQuickJsExecutable(context) ?: return null
        return "quickjs:${qjs.absolutePath}"
    }

    /**
     * Inspects the installed QuickJS runtime binary (identity, version, compatibility).
     * Results are cached per binary path + lastModified timestamp so batch extractions never
     * repeatedly spawn probe processes.
     */
    fun inspectQuickJsRuntime(
        context: Context,
        forceRefresh: Boolean = false,
        probeHelpRunner: ((File) -> String?)? = null
    ): QuickJsRuntimeInfo {
        val nativeLibDir = context.applicationInfo?.nativeLibraryDir
        val rawFile = if (!nativeLibDir.isNullOrBlank()) File(nativeLibDir, "libqjs.so") else null
        val qjsFile = getQuickJsExecutable(context)

        if (rawFile == null || !rawFile.exists()) {
            if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true) && probeHelpRunner == null) {
                return QuickJsRuntimeInfo(
                    isAvailable = false,
                    identity = "missing",
                    version = "unknown",
                    isCompatible = false,
                    meetsRecommendedBaseline = false,
                    executablePath = rawFile?.absolutePath,
                    isExecutable = false,
                    detail = "libqjs.so absent in Robolectric sandbox"
                )
            }
            return QuickJsRuntimeInfo(
                isAvailable = false,
                identity = "missing",
                version = "unknown",
                isCompatible = false,
                meetsRecommendedBaseline = false,
                executablePath = rawFile?.absolutePath,
                isExecutable = false,
                detail = "libqjs.so not found in nativeLibraryDir"
            )
        }

        if (qjsFile == null) {
            return QuickJsRuntimeInfo(
                isAvailable = false,
                identity = "not_executable",
                version = "unknown",
                isCompatible = false,
                meetsRecommendedBaseline = false,
                executablePath = rawFile.absolutePath,
                isExecutable = false,
                detail = "libqjs.so exists but is not executable"
            )
        }

        val sig = "${qjsFile.absolutePath}:${qjsFile.length()}:${qjsFile.lastModified()}"
        if (!forceRefresh && probeHelpRunner == null && cachedSignature == sig) {
            cachedInfo?.let { return it }
        }

        val helpOut = if (probeHelpRunner != null) {
            probeHelpRunner(qjsFile)
        } else {
            runHelpProbe(qjsFile)
        }

        val info = if (helpOut.isNullOrBlank()) {
            QuickJsRuntimeInfo(
                isAvailable = true,
                identity = "unknown",
                version = "unknown",
                isCompatible = false,
                meetsRecommendedBaseline = false,
                executablePath = qjsFile.absolutePath,
                isExecutable = true,
                detail = "QuickJS binary did not return --help version output"
            )
        } else {
            parseHelpOutput(helpOut, executablePath = qjsFile.absolutePath, isExecutable = true)
        }

        if (probeHelpRunner == null) {
            cachedSignature = sig
            cachedInfo = info
        }
        return info
    }

    internal fun parseHelpOutput(
        helpOutput: String,
        executablePath: String? = null,
        isExecutable: Boolean = true
    ): QuickJsRuntimeInfo {
        val isNg = helpOutput.contains("QuickJS-ng", ignoreCase = true)
        val identity = if (isNg) "quickjs-ng" else if (helpOutput.contains("QuickJS", ignoreCase = true)) "quickjs" else "unknown"
        val version = VERSION_LINE_REGEX.find(helpOutput)?.groupValues?.getOrNull(1)?.trim() ?: "unknown"
        val tuple = parseVersionTuple(version)

        val (isCompatible, meetsRecommended) = when (identity) {
            "quickjs-ng" -> {
                val compat = tuple.isNotEmpty() && tuple[0] >= 0 && tuple.any { it > 0 }
                val rec = tuple.isNotEmpty() && compareVersionTuples(tuple, MIN_RECOMMENDED_NG) >= 0
                compat to rec
            }
            "quickjs" -> {
                val compat = tuple.isNotEmpty() && compareVersionTuples(tuple, MIN_SUPPORTED_BELLARD) >= 0
                val rec = tuple.isNotEmpty() && compareVersionTuples(tuple, MIN_RECOMMENDED_BELLARD) >= 0
                compat to rec
            }
            else -> false to false
        }

        return QuickJsRuntimeInfo(
            isAvailable = true,
            identity = identity,
            version = version,
            isCompatible = isCompatible,
            meetsRecommendedBaseline = meetsRecommended,
            executablePath = executablePath,
            isExecutable = isExecutable,
            detail = if (isCompatible) {
                "$identity $version"
            } else {
                "$identity $version below recommended baseline"
            }
        )
    }

    internal fun parseVersionTuple(version: String): List<Int> {
        if (version.isBlank() || version == "unknown") return emptyList()
        return Regex("""\d+""").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    private fun compareVersionTuples(a: List<Int>, b: List<Int>): Int {
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun runHelpProbe(qjsFile: File): String? {
        return try {
            val process = ProcessBuilder(qjsFile.absolutePath, "--help")
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(1024)
                var total = 0
                while (total < 8192) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                sb.toString()
            }
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
            }
            out.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "QuickJS --help probe failed: ${t.message}")
            null
        }
    }

    internal fun clearCacheForTest() {
        cachedSignature = null
        cachedInfo = null
    }
}

