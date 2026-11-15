package com.laterbox.app.media.data.adapters.ytdlp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YtDlpOptimizerTest {

    private lateinit var context: Context
    private lateinit var testWorkDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testWorkDir = File(context.cacheDir, "optimizer_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testWorkDir.deleteRecursively()
    }

    private fun createSampleZip(zipFile: File, includeEjs: Boolean = true, flagValue: String = "False") {
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // __main__.py
            zos.putNextEntry(ZipEntry("__main__.py"))
            zos.write("print('yt-dlp')".toByteArray())
            zos.closeEntry()

            // yt_dlp/__init__.py
            zos.putNextEntry(ZipEntry("yt_dlp/__init__.py"))
            zos.write("# init".toByteArray())
            zos.closeEntry()

            // yt_dlp/YoutubeDL.py
            zos.putNextEntry(ZipEntry("yt_dlp/YoutubeDL.py"))
            zos.write("class YoutubeDL: pass".toByteArray())
            zos.closeEntry()

            // yt_dlp/extractor/youtube/jsc/_builtin/ejs.py
            if (includeEjs) {
                zos.putNextEntry(ZipEntry(YtDlpOptimizer.EJS_RELATIVE_PATH))
                val content = """
                    class EJS:
                        _ENABLE_PREPROCESSED_PLAYER_CACHE = $flagValue
                """.trimIndent()
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun getCacheDir_createsAndReturnsWritableDirectory() {
        val cacheDir = YtDlpOptimizer.getCacheDir(context)
        assertTrue(cacheDir.exists())
        assertTrue(cacheDir.isDirectory)
        assertTrue(cacheDir.canWrite())
    }

    @Test
    fun validateUnpackedDir_returnsFalseForEmptyOrIncompleteDirectory() {
        val emptyDir = File(testWorkDir, "empty").apply { mkdirs() }
        assertFalse(YtDlpOptimizer.validateUnpackedDir(emptyDir))

        val incompleteDir = File(testWorkDir, "incomplete").apply { mkdirs() }
        File(incompleteDir, "__main__.py").writeText("content")
        assertFalse(YtDlpOptimizer.validateUnpackedDir(incompleteDir))
    }

    @Test
    fun validateUnpackedDir_returnsTrueWhenAllEssentialFilesExist() {
        val validDir = File(testWorkDir, "valid").apply { mkdirs() }
        File(validDir, "__main__.py").writeText("print('main')")
        File(validDir, "yt_dlp").mkdirs()
        File(validDir, "yt_dlp/__init__.py").writeText("# init")
        File(validDir, "yt_dlp/YoutubeDL.py").writeText("class YoutubeDL: pass")

        assertTrue(YtDlpOptimizer.validateUnpackedDir(validDir))
    }

    @Test
    fun unzip_extractsAllEntriesCorrectly() {
        val zipFile = File(testWorkDir, "bundle.zip")
        createSampleZip(zipFile)

        val targetDir = File(testWorkDir, "unpacked")
        YtDlpOptimizer.unzip(zipFile, targetDir)

        assertTrue(File(targetDir, "__main__.py").exists())
        assertTrue(File(targetDir, "yt_dlp/__init__.py").exists())
        assertTrue(File(targetDir, "yt_dlp/YoutubeDL.py").exists())
        assertTrue(File(targetDir, YtDlpOptimizer.EJS_RELATIVE_PATH).exists())
    }

    @Test
    fun patchPreprocessedPlayerCache_patchesFlagWhenFound() {
        val dir = File(testWorkDir, "pkg").apply { mkdirs() }
        val ejsFile = File(dir, YtDlpOptimizer.EJS_RELATIVE_PATH).apply {
            parentFile?.mkdirs()
            writeText("prefix\n_ENABLE_PREPROCESSED_PLAYER_CACHE = False\nsuffix")
        }

        val patched = YtDlpOptimizer.patchPreprocessedPlayerCache(dir)
        assertTrue(patched)

        val updated = ejsFile.readText()
        assertTrue(updated.contains("_ENABLE_PREPROCESSED_PLAYER_CACHE = True"))
        assertFalse(updated.contains("_ENABLE_PREPROCESSED_PLAYER_CACHE = False"))

        // Idempotent second run
        val patchedAgain = YtDlpOptimizer.patchPreprocessedPlayerCache(dir)
        assertTrue(patchedAgain)
    }

    @Test
    fun cleanCacheIfVersionChanged_purgesStaleEntriesOnVersionMismatch() {
        val cacheDir = YtDlpOptimizer.getCacheDir(context)
        val dummyFile = File(cacheDir, "old_cached_player.js").apply { writeText("cached js") }
        assertTrue(dummyFile.exists())

        // Record initial version
        YtDlpOptimizer.cleanCacheIfVersionChanged(context, "2025.10.01")

        // New version triggers cache purge
        YtDlpOptimizer.cleanCacheIfVersionChanged(context, "2025.11.12")
        assertFalse(dummyFile.exists())
    }

    @Test
    fun ensureOptimized_extractsArchive_patchesFlag_andSetsPath() = runTest {
        val noBackup = context.noBackupFilesDir ?: File(context.filesDir, "no_backup").apply { mkdirs() }
        val archiveFile = File(noBackup, "youtubedl-android/yt-dlp/yt-dlp")
        createSampleZip(archiveFile)

        val result = YtDlpOptimizer.ensureOptimized(context, forceReextract = true)

        assertTrue(result.isOptimized)
        assertFalse(result.fallbackToArchive)
        assertTrue(result.isPlayerCachePatched)
        assertNotNull(result.activePath)
        assertTrue(result.activePath?.exists() == true)
        assertTrue(result.activePath?.isDirectory == true)

        val active = YtDlpOptimizer.getYtDlpBinaryPath()
        assertEquals(result.activePath?.absolutePath, active?.absolutePath)

        val ejs = File(result.activePath, YtDlpOptimizer.EJS_RELATIVE_PATH)
        assertTrue(ejs.readText().contains("_ENABLE_PREPROCESSED_PLAYER_CACHE = True"))
    }

    private fun createShebangZip(file: File, shebang: String = "#!/usr/bin/env python3\n") {
        file.parentFile?.mkdirs()
        file.writeBytes(shebang.toByteArray(Charsets.UTF_8))
        val tempZip = File(testWorkDir, "temp_${System.currentTimeMillis()}.zip")
        createSampleZip(tempZip)
        file.appendBytes(tempZip.readBytes())
        tempZip.delete()
    }

    @Test
    fun detectArchiveFormat_identifiesConventionalAndShebangZip() {
        val conventionalZip = File(testWorkDir, "conventional.zip")
        createSampleZip(conventionalZip)
        val format1 = YtDlpOptimizer.detectArchiveFormat(conventionalZip)
        assertEquals(YtDlpOptimizer.ArchiveFormat.ConventionalZip, format1)

        val shebangZip = File(testWorkDir, "shebang_ytdlp")
        createShebangZip(shebangZip)
        val format2 = YtDlpOptimizer.detectArchiveFormat(shebangZip)
        assertTrue(format2 is YtDlpOptimizer.ArchiveFormat.ShebangZip)
        assertEquals(23L, (format2 as YtDlpOptimizer.ArchiveFormat.ShebangZip).zipOffset)

        val invalidFile = File(testWorkDir, "invalid.txt").apply { writeText("just text") }
        val format3 = YtDlpOptimizer.detectArchiveFormat(invalidFile)
        assertEquals(YtDlpOptimizer.ArchiveFormat.Invalid, format3)
    }

    @Test
    fun unzip_withShebangZip_extractsAllEntriesCorrectly() {
        val shebangZip = File(testWorkDir, "shebang_bundle")
        createShebangZip(shebangZip)

        val targetDir = File(testWorkDir, "shebang_unpacked")
        YtDlpOptimizer.unzip(shebangZip, targetDir)

        assertTrue(File(targetDir, "__main__.py").exists())
        assertTrue(File(targetDir, "yt_dlp/__init__.py").exists())
        assertTrue(File(targetDir, "yt_dlp/YoutubeDL.py").exists())
        assertTrue(File(targetDir, YtDlpOptimizer.EJS_RELATIVE_PATH).exists())
    }

    @Test
    fun ensureOptimized_withShebangZip_extractsArchiveAndPatches() = runTest {
        val noBackup = context.noBackupFilesDir ?: File(context.filesDir, "no_backup").apply { mkdirs() }
        val archiveFile = File(noBackup, "youtubedl-android/yt-dlp/yt-dlp")
        createShebangZip(archiveFile)

        val result = YtDlpOptimizer.ensureOptimized(context, forceReextract = true)

        assertTrue(result.isOptimized)
        assertFalse(result.fallbackToArchive)
        assertTrue(result.isPlayerCachePatched)
        assertNotNull(result.activePath)
        assertTrue(result.activePath?.exists() == true)

        val ejs = File(result.activePath, YtDlpOptimizer.EJS_RELATIVE_PATH)
        assertTrue(ejs.readText().contains("_ENABLE_PREPROCESSED_PLAYER_CACHE = True"))
    }

    @Test
    fun ensureOptimized_withInvalidArchive_doesNotExtract() = runTest {
        val noBackup = context.noBackupFilesDir ?: File(context.filesDir, "no_backup").apply { mkdirs() }
        val archiveFile = File(noBackup, "youtubedl-android/yt-dlp/yt-dlp")
        archiveFile.parentFile?.mkdirs()
        archiveFile.writeText("corrupt non-zip content")

        val result = YtDlpOptimizer.ensureOptimized(context, forceReextract = true)

        assertFalse(result.isOptimized)
        assertTrue(result.fallbackToArchive)
        assertFalse(result.isPlayerCachePatched)
    }

    @Test
    fun ensureOptimized_reusingExistingDirectory_reappliesEjsPatch() = runTest {
        val noBackup = context.noBackupFilesDir ?: File(context.filesDir, "no_backup").apply { mkdirs() }
        val archiveFile = File(noBackup, "youtubedl-android/yt-dlp/yt-dlp")
        createSampleZip(archiveFile)

        val result1 = YtDlpOptimizer.ensureOptimized(context, forceReextract = true)
        assertTrue(result1.isOptimized)

        // Simulate ejs.py losing patch
        val ejs = File(result1.activePath, YtDlpOptimizer.EJS_RELATIVE_PATH)
        ejs.writeText("class EJS:\n    _ENABLE_PREPROCESSED_PLAYER_CACHE = False\n")

        // Reuse directory without force reextract
        val result2 = YtDlpOptimizer.ensureOptimized(context, forceReextract = false)
        assertTrue(result2.isOptimized)
        assertTrue(result2.isPlayerCachePatched)
        assertTrue(ejs.readText().contains("_ENABLE_PREPROCESSED_PLAYER_CACHE = True"))
    }

    @Test
    fun getMinimumPythonRequirement_parsesFromDirectoryAndArchive() {
        val dir = File(testWorkDir, "py_req_dir").apply { mkdirs() }
        val compatDir = File(dir, "yt_dlp/compat").apply { mkdirs() }
        File(compatDir, "__init__.py").writeText("if sys.version_info < (3, 12):\n    raise RuntimeError()")

        val (major, minor) = YtDlpOptimizer.getMinimumPythonRequirement(dir)
        assertEquals(3, major)
        assertEquals(12, minor)
    }

    @Test
    fun ensureOptimized_concurrentInvocations_protectedByMutex() = runTest {
        val noBackup = context.noBackupFilesDir ?: File(context.filesDir, "no_backup").apply { mkdirs() }
        val archiveFile = File(noBackup, "youtubedl-android/yt-dlp/yt-dlp")
        createSampleZip(archiveFile)

        val results = listOf(
            async { YtDlpOptimizer.ensureOptimized(context) },
            async { YtDlpOptimizer.ensureOptimized(context) },
            async { YtDlpOptimizer.ensureOptimized(context) },
            async { YtDlpOptimizer.ensureOptimized(context) },
            async { YtDlpOptimizer.ensureOptimized(context) }
        ).awaitAll()

        assertEquals(5, results.size)
        results.forEach { res ->
            assertTrue(res.isOptimized)
            assertTrue(res.isPlayerCachePatched)
        }
    }

    @Test
    fun ensureOptimized_missingPlayerCacheFlag_keepsOptimizedWithPatchFalse() = runTest {
        val noBackup = context.noBackupFilesDir ?: File(context.filesDir, "no_backup").apply { mkdirs() }
        val archiveFile = File(noBackup, "youtubedl-android/yt-dlp/yt-dlp")
        archiveFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(archiveFile)).use { zos ->
            zos.putNextEntry(ZipEntry("__main__.py"))
            zos.write("print('yt-dlp')".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("yt_dlp/__init__.py"))
            zos.write("# init".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("yt_dlp/YoutubeDL.py"))
            zos.write("class YoutubeDL: pass".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(YtDlpOptimizer.EJS_RELATIVE_PATH))
            zos.write("class EJS:\n    pass\n".toByteArray())
            zos.closeEntry()
        }

        val result = YtDlpOptimizer.ensureOptimized(context, forceReextract = true)

        assertTrue("Optimization must stay active even if upstream removes _ENABLE_PREPROCESSED_PLAYER_CACHE", result.isOptimized)
        assertFalse(result.fallbackToArchive)
        assertFalse(result.isPlayerCachePatched)
    }

    @Test
    fun inspectEjsSolver_detectsVersionAndSolversFromDirAndZip() {
        val dir = File(testWorkDir, "ejs_unpacked").apply { mkdirs() }
        File(dir, YtDlpOptimizer.EJS_VERSION_RELATIVE_PATH).apply {
            parentFile?.mkdirs()
            writeText("__version__ = '0.8.0'\n")
        }
        File(dir, YtDlpOptimizer.EJS_CORE_SOLVER_PATH).apply {
            parentFile?.mkdirs()
            writeText("// core")
        }
        File(dir, YtDlpOptimizer.EJS_LIB_SOLVER_PATH).apply {
            parentFile?.mkdirs()
            writeText("// lib")
        }
        File(dir, YtDlpOptimizer.EJS_RELATIVE_PATH).apply {
            parentFile?.mkdirs()
            writeText("_ENABLE_PREPROCESSED_PLAYER_CACHE = True\n")
        }

        val dirStatus = YtDlpOptimizer.inspectEjsSolver(dir)
        assertTrue(dirStatus.isAvailable)
        assertEquals("0.8.0", dirStatus.version)
        assertTrue(dirStatus.hasCoreSolver)
        assertTrue(dirStatus.hasLibSolver)
        assertTrue(dirStatus.isPlayerCachePatched)

        val zip = File(testWorkDir, "ejs_archive.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            listOf(
                YtDlpOptimizer.EJS_VERSION_RELATIVE_PATH to "__version__ = '0.8.1'\n",
                YtDlpOptimizer.EJS_CORE_SOLVER_PATH to "// core",
                YtDlpOptimizer.EJS_LIB_SOLVER_PATH to "// lib"
            ).forEach { (path, body) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        val zipStatus = YtDlpOptimizer.inspectEjsSolver(zip)
        assertTrue(zipStatus.isAvailable)
        assertEquals("0.8.1", zipStatus.version)
        assertTrue(zipStatus.hasCoreSolver)
        assertTrue(zipStatus.hasLibSolver)
    }

    @Test
    fun quickJsHelper_parseHelpOutput_identifiesBellardAndNgCompatibility() {
        val modernBellard = QuickJsHelper.parseHelpOutput("QuickJS version 2025-04-26\nusage: qjs [options]")
        assertEquals("quickjs", modernBellard.identity)
        assertEquals("2025-04-26", modernBellard.version)
        assertTrue(modernBellard.isCompatible)
        assertTrue(modernBellard.meetsRecommendedBaseline)

        val oldBellard = QuickJsHelper.parseHelpOutput("QuickJS version 2021-03-27\nusage: qjs")
        assertEquals("quickjs", oldBellard.identity)
        assertEquals("2021-03-27", oldBellard.version)
        assertFalse(oldBellard.isCompatible)

        val modernNg = QuickJsHelper.parseHelpOutput("QuickJS-ng version 0.12.0\nusage: qjs")
        assertEquals("quickjs-ng", modernNg.identity)
        assertEquals("0.12.0", modernNg.version)
        assertTrue(modernNg.isCompatible)
        assertTrue(modernNg.meetsRecommendedBaseline)
    }
}
