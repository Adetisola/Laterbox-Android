package com.laterbox.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapabilityRegistryTest {

    @Test
    fun `manifest hash matches backend canonical SHA-256`() {
        assertEquals(2, AppCapabilityRegistry.MANIFEST_VERSION)
        assertEquals("2026.11.21-v2", AppCapabilityRegistry.MANIFEST_REVISION)
        assertEquals(
            "a46b4edd0cb5bf822c633913d6986e732179b0b88db3255638c202a6220c1eed",
            AppCapabilityRegistry.MANIFEST_HASH
        )
        assertTrue(AppCapabilityRegistry.productManifestItems.size >= 10)
    }

    @Test
    fun `evaluateCapabilities accurately reflects build version, feature toggles, and engine state`() {
        val snapshot = CapabilitySnapshot(
            clientVersionCode = 61,
            clientVersionName = "1.0.61",
            ytDlpState = "FAILED",
            youtubeTranscriptsReady = true,
            assistantEnabled = false,
            smartRemindersEnabled = true,
            configuredExportFolderName = "LaterboxExports",
            configuredExportFolderWritable = true
        )
        val evaluated = AppCapabilityRegistry.evaluateCapabilities(snapshot).associateBy { it.id }

        assertEquals(
            CapabilityAvailability.DISABLED,
            evaluated["assistant.chat_and_actions"]?.status
        )
        assertEquals(
            CapabilityAvailability.UNAVAILABLE,
            evaluated["movies.pip_and_mini_player"]?.status
        )
        assertEquals(
            CapabilityAvailability.UNAVAILABLE,
            evaluated["sharing.deep_links_and_files"]?.status
        )
        assertEquals(
            CapabilityAvailability.CONDITIONALLY_AVAILABLE,
            evaluated["downloads.youtube_transcripts"]?.status
        )
        assertEquals(
            CapabilityAvailability.SUPPORTED,
            evaluated["downloads.saf_export"]?.status
        )
    }

    @Test
    fun `toRuntimeSnapshotJson includes manifest hash and runtime metrics`() {
        val json = AppCapabilityRegistry.toRuntimeSnapshotJson(
            CapabilitySnapshot(
                clientVersionCode = 62,
                moviesWatchlistCount = 5,
                collectionsCount = 2
            )
        )
        assertEquals(AppCapabilityRegistry.MANIFEST_HASH, json.getString("manifestHash"))
        assertEquals(62, json.getInt("clientVersionCode"))
        assertEquals(5, json.getInt("moviesWatchlistCount"))
        assertEquals(2, json.getInt("collectionsCount"))
    }
}
