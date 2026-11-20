package com.laterbox.app.media.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.media.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MediaStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storageManager: MediaStorageManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = MediaStorageManager(context)
    }

    @Test
    fun sniffMimeType_mapsFtypContainerToAudioMp4_whenMediaTypeIsAudio() {
        val file = tempFolder.newFile("sample_audio.m4a")
        // Write standard ISO MP4/M4A header: 4-byte box size + "ftyp" + "isom"
        file.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D))

        val mime = storageManager.sniffMimeType(file, mediaType = MediaType.AUDIO)
        assertEquals("audio/mp4", mime)
    }

    @Test
    fun sniffMimeType_mapsFtypContainerToVideoMp4_whenMediaTypeIsVideo() {
        val file = tempFolder.newFile("sample_video.mp4")
        file.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D))

        val mime = storageManager.sniffMimeType(file, mediaType = MediaType.VIDEO)
        assertEquals("video/mp4", mime)
    }

    @Test
    fun sniffMimeType_identifiesJpegAndPng() {
        val jpg = tempFolder.newFile("test.jpg")
        jpg.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        assertEquals("image/jpeg", storageManager.sniffMimeType(jpg, mediaType = MediaType.IMAGE))

        val png = tempFolder.newFile("test.png")
        png.writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()))
        assertEquals("image/png", storageManager.sniffMimeType(png, mediaType = MediaType.IMAGE))
    }

    @Test
    fun resolveExtension_ignoresComplexFormatSelectors() {
        // Complex yt-dlp selector should NOT be accepted as extension
        val complexSelector = "bestaudio[ext=m4a]/bestaudio/best"
        val ext = storageManager.resolveExtension("audio/mp4", complexSelector, MediaType.AUDIO)
        assertEquals("m4a", ext)
    }

    @Test
    fun resolveExtension_acceptsCleanExtensionHint() {
        val ext = storageManager.resolveExtension("audio/mp4", "m4a", MediaType.AUDIO)
        assertEquals("m4a", ext)

        val extMp4 = storageManager.resolveExtension("video/mp4", "mp4", MediaType.VIDEO)
        assertEquals("mp4", extMp4)
    }

    @Test
    fun sniffMimeType_mapsMatroskaContainerToAudioWebm_andResolvesWebmExtension() {
        val file = tempFolder.newFile("sample_opus.part")
        file.writeBytes(byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x00, 0x00))

        val mime = storageManager.sniffMimeType(file, mediaType = MediaType.AUDIO)
        assertEquals("audio/webm", mime)

        val ext = storageManager.resolveExtension(mime, null, MediaType.AUDIO)
        assertTrue("WebM audio must resolve to webm or opus, not m4a", ext == "webm" || ext == "opus")
    }

    @Test
    fun partFiles_storedUnderFilesDirDownloadPartsAndMigrateLegacySafely() {
        val activeId = "task_active_1"
        val inProgressId = "task_downloading_2"
        val orphanId = "task_orphan_3"

        val legacyDir = File(context.cacheDir, "media_parts").apply { mkdirs() }
        val legacyActive = File(legacyDir, "$activeId.part").apply { writeText("active-bytes-123") }
        val legacyInProgress = File(legacyDir, "$inProgressId.part").apply { writeText("inflight-bytes") }
        val legacyOrphan = File(legacyDir, "$orphanId.part").apply { writeText("orphan-bytes") }

        val migrated = storageManager.migrateLegacyPartFilesIfNeeded(
            activeTaskIds = setOf(activeId, inProgressId),
            inProgressTaskIds = setOf(inProgressId)
        )
        assertEquals(1, migrated)

        // Active/paused migrated to filesDir/download_parts
        val expectedNew = File(File(context.filesDir, "download_parts"), "$activeId.part")
        assertTrue(expectedNew.exists())
        assertEquals("active-bytes-123", expectedNew.readText())
        assertTrue(!legacyActive.exists())

        // In-progress task locked & untouched during migration
        assertTrue(legacyInProgress.exists())

        // Orphan deleted
        assertTrue(!legacyOrphan.exists())

        // Cleanup
        storageManager.deletePartFile(activeId)
        storageManager.deletePartFile(inProgressId)
    }

    @Test
    fun sweepOrphanPartFiles_protectsActiveAndInProgressTasks() {
        val activeFile = storageManager.getPartFile("keep_active").apply { writeText("a") }
        val inProgressFile = storageManager.getPartFile("keep_running").apply { writeText("b") }
        val orphanFile = storageManager.getPartFile("drop_orphan").apply { writeText("c") }

        val deleted = storageManager.sweepOrphanPartFiles(
            activeTaskIds = setOf("keep_active"),
            inProgressTaskIds = setOf("keep_running")
        )
        assertEquals(1, deleted)
        assertTrue(activeFile.exists())
        assertTrue(inProgressFile.exists())
        assertTrue(!orphanFile.exists())

        storageManager.deletePartFile("keep_active")
        storageManager.deletePartFile("keep_running")
    }
}
