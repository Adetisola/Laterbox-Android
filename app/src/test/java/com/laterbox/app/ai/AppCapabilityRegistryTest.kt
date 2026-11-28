package com.laterbox.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapabilityRegistryTest {

    @Test
    fun `manifest hash matches backend canonical SHA-256`() {
        assertEquals(3, AppCapabilityRegistry.MANIFEST_VERSION)
        assertEquals("2026.11.27-v3", AppCapabilityRegistry.MANIFEST_REVISION)
        assertEquals(
            "d50bbef9a31c3815ab9e2d3085ffbc8b17fcf9a309ea44881a27ff0612a5fd96",
            AppCapabilityRegistry.MANIFEST_HASH
        )
        assertEquals(13, AppCapabilityRegistry.productManifestItems.size)
        val byId = AppCapabilityRegistry.productManifestItems.associateBy { it.id }
        val assistant = byId["assistant.chat_and_actions"]!!
        assertTrue(assistant.requiresBackend)
        assertTrue(assistant.aiCopyRequiresBackend)
        assertFalse(assistant.reversible)
        assertTrue(assistant.requiresConfirmation)
        assertTrue(assistant.availableActions.contains("DELETE_CONVERSATION"))
        assertTrue(assistant.failureRecoveryPaths.any { it.condition == "online_maintenance" })
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
            configuredExportFolderWritable = true,
            playerIntroSkipEnabled = false
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
        assertEquals(
            CapabilityAvailability.DISABLED,
            evaluated["movies.intro_skip"]?.status
        )
    }

    @Test
    fun `evaluateCapabilities enforces onlineServicesPaused and provider flags`() {
        val pausedSnapshot = CapabilitySnapshot(
            clientVersionCode = 84,
            onlineServicesPaused = true
        )
        val pausedEval = AppCapabilityRegistry.evaluateCapabilities(pausedSnapshot).associateBy { it.id }

        for (blockedId in AppCapabilityRegistry.ONLINE_BLOCKED_IDS) {
            assertEquals(
                "Expected $blockedId to be DISABLED during onlineServicesPaused",
                CapabilityAvailability.DISABLED,
                pausedEval[blockedId]?.status
            )
        }
        assertEquals(
            CapabilityAvailability.CONDITIONALLY_AVAILABLE,
            pausedEval["downloads.media_extraction"]?.status
        )
        assertEquals(
            CapabilityAvailability.SUPPORTED,
            pausedEval["inbox.save_organize"]?.status
        )

        val partialProviderSnapshot = CapabilitySnapshot(
            clientVersionCode = 84,
            providerMovieboxEnabled = false,
            providerInternetArchiveEnabled = true,
            providerTvmazeEnabled = true
        )
        val partialEval = AppCapabilityRegistry.evaluateCapabilities(partialProviderSnapshot).associateBy { it.id }
        val discovery = partialEval["movies.discovery_and_library"]!!
        assertEquals(CapabilityAvailability.CONDITIONALLY_AVAILABLE, discovery.status)
        assertTrue(discovery.reason.orEmpty().contains("Disabled: MovieBox"))
        assertTrue(discovery.reason.orEmpty().contains("Internet Archive, TVMaze"))

        val allProvidersOffSnapshot = CapabilitySnapshot(
            clientVersionCode = 84,
            providerMovieboxEnabled = false,
            providerInternetArchiveEnabled = false,
            providerTvmazeEnabled = false
        )
        val allOffEval = AppCapabilityRegistry.evaluateCapabilities(allProvidersOffSnapshot).associateBy { it.id }
        assertEquals(
            CapabilityAvailability.CONDITIONALLY_AVAILABLE,
            allOffEval["movies.discovery_and_library"]?.status
        )
        assertTrue(
            allOffEval["movies.discovery_and_library"]?.reason.orEmpty()
                .contains("All online catalogue providers are currently disabled")
        )

        val moviesDisabledSnapshot = CapabilitySnapshot(
            clientVersionCode = 84,
            moviesTvEnabled = false
        )
        val moviesOffEval = AppCapabilityRegistry.evaluateCapabilities(moviesDisabledSnapshot).associateBy { it.id }
        assertEquals(CapabilityAvailability.DISABLED, moviesOffEval["movies.discovery_and_library"]?.status)
        assertEquals(CapabilityAvailability.DISABLED, moviesOffEval["movies.pip_and_mini_player"]?.status)
        assertEquals(CapabilityAvailability.DISABLED, moviesOffEval["movies.recommendations"]?.status)
        assertEquals("Movies & TV section is currently disabled.", moviesOffEval["movies.recommendations"]?.reason)
        assertEquals(CapabilityAvailability.DISABLED, moviesOffEval["movies.intro_skip"]?.status)
        assertEquals("Movies & TV section is currently disabled.", moviesOffEval["movies.intro_skip"]?.reason)

        val bothDisabledSnapshot = CapabilitySnapshot(
            clientVersionCode = 84,
            moviesTvEnabled = false,
            moviesRecommendationsEnabled = false,
            playerIntroSkipEnabled = false
        )
        val bothOffEval = AppCapabilityRegistry.evaluateCapabilities(bothDisabledSnapshot).associateBy { it.id }
        assertEquals("Personalized recommendations are turned off.", bothOffEval["movies.recommendations"]?.reason)
        assertEquals("Skip intro controls are turned off.", bothOffEval["movies.intro_skip"]?.reason)
    }

    @Test
    fun `toRuntimeSnapshotJson and formatEvaluatedCapabilitiesBlock include v3 metadata`() {
        val snapshot = CapabilitySnapshot(
            clientVersionCode = 84,
            onlineServicesPaused = true,
            moviesWatchlistCount = 5,
            collectionsCount = 2
        )
        val json = AppCapabilityRegistry.toRuntimeSnapshotJson(snapshot)
        assertEquals(AppCapabilityRegistry.MANIFEST_HASH, json.getString("manifestHash"))
        assertEquals(3, json.getInt("manifestVersion"))
        assertEquals("2026.11.27-v3", json.getString("manifestRevision"))
        assertTrue(json.getBoolean("onlineServicesPaused"))
        assertEquals(84, json.getInt("clientVersionCode"))
        assertEquals(5, json.getInt("moviesWatchlistCount"))
        assertEquals(2, json.getInt("collectionsCount"))

        val formatted = AppCapabilityRegistry.formatEvaluatedCapabilitiesBlock(
            AppCapabilityRegistry.evaluateCapabilities(snapshot)
        )
        assertTrue(formatted.contains("Evaluated Capability States (Manifest v3):"))
        assertTrue(formatted.contains("assistant.chat_and_actions): DISABLED"))
        assertTrue(formatted.contains("downloads.media_extraction): CONDITIONALLY_AVAILABLE"))
    }

    @Test(expected = IllegalStateException::class)
    fun `parseAndVerifyManifestJson rejects tampered manifest hash`() {
        val raw = AppCapabilityRegistry::class.java.classLoader!!
            .getResourceAsStream("capability_manifest_v3.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val tampered = raw.replace(
            AppCapabilityRegistry.MANIFEST_HASH,
            "0000000000000000000000000000000000000000000000000000000000000000"
        )
        AppCapabilityRegistry.parseAndVerifyManifestJson(tampered)
    }
}
