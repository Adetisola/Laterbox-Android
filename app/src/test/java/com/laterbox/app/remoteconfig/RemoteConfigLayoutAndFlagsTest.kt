package com.laterbox.app.remoteconfig

import com.laterbox.app.ai.RemoteCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigLayoutAndFlagsTest {

    @Test
    fun buildMoviesHomeSlots_preservesCoreRails_capsFeaturedCardAtOne_andHonorsRecommendationsFlag() {
        val snapshot = RemoteConfigSnapshot.default().copy(
            revision = 12,
            features = RemoteCapabilities.DEFAULT_FEATURES.toMutableMap().apply {
                put(RemoteCapabilities.KEY_MOVIES_RECOMMENDATIONS, false)
            },
            ui = RemoteUiConfiguration(
                components = listOf(
                    RemoteUiComponent(
                        id = "callout_top",
                        type = RemoteCapabilities.TYPE_FEATURED_CALLOUT_CARD,
                        surface = RemoteUiSurface.MOVIES,
                        visible = true,
                        order = 5,
                        title = "Saved Vault",
                        actionLabel = "Open",
                        actionDestination = "downloads",
                    ),
                    RemoteUiComponent(
                        id = "callout_second",
                        type = RemoteCapabilities.TYPE_FEATURED_CALLOUT_CARD,
                        surface = RemoteUiSurface.MOVIES,
                        visible = true,
                        order = 6,
                        title = "Second Card",
                        actionLabel = "Open",
                        actionDestination = "inbox",
                    ),
                    RemoteUiComponent(
                        id = "rail_newest_hidden",
                        type = RemoteCapabilities.TYPE_CONTENT_RAIL,
                        surface = RemoteUiSurface.MOVIES,
                        visible = false,
                        order = 20,
                        railId = "newest",
                    ),
                    RemoteUiComponent(
                        id = "hero_custom",
                        type = RemoteCapabilities.TYPE_HERO_CAROUSEL,
                        surface = RemoteUiSurface.MOVIES,
                        visible = true,
                        order = 25,
                        maxItems = 4,
                        autoAdvanceMs = 8000,
                        slides = listOf(
                            RemoteHeroSlide(
                                id = "slide_1",
                                imageUrl = "https://cdn.example.com/banner.png",
                                heading = "Featured Spotlight",
                                enabled = true,
                                order = 10,
                            )
                        ),
                    ),
                )
            )
        )

        val slots = RemoteConfigLayoutHelper.buildMoviesHomeSlots(snapshot)

        assertEquals("ui_callout_callout_top", slots[0].key)
        assertFalse(slots.any { it.key == "ui_callout_callout_second" })
        assertEquals("hero_carousel", slots[1].key)
        assertEquals(4, slots[1].maxItems)
        assertEquals(8000, slots[1].component?.autoAdvanceMs)
        assertEquals(1, slots[1].component?.slides?.size)

        // Legacy content_rail cannot hide core rails; recommendations flag still gates personalized shelves
        assertFalse(slots.any { it.railId == "newest" })
        assertTrue(slots.any { it.railId == "recently_added" })
        assertFalse(slots.any { it.railId == "because_you_watched" })
        assertFalse(slots.any { it.railId == "quick_picks" })
        assertTrue(slots.any { it.railId == "continue_watching" })
        assertTrue(slots.any { it.railId == "top_rated" })
    }

    @Test
    fun maintenanceStrip_andMultiSurfacePrioritySorting_behaveAccurately() {
        val normalHidden = RemoteMaintenanceConfig(enabled = false, mode = RemoteMaintenanceMode.NONE, message = "All good", showBannerInNormalMode = false)
        assertFalse(normalHidden.shouldShowStatusStrip)
        assertFalse(normalHidden.isOnlineServicesPaused)

        val normalShown = normalHidden.copy(showBannerInNormalMode = true)
        assertTrue(normalShown.shouldShowStatusStrip)
        assertFalse(normalShown.isOnlineServicesPaused)

        val partial = RemoteMaintenanceConfig(enabled = true, mode = RemoteMaintenanceMode.PARTIAL)
        assertTrue(partial.shouldShowStatusStrip)
        assertTrue(partial.isOnlineServicesPaused)

        val snap = RemoteConfigSnapshot.default().copy(
            announcements = listOf(
                RemoteAnnouncementItem(
                    id = "info_first",
                    message = "Low priority tip",
                    severity = RemoteAnnouncementSeverity.INFO,
                    surfaces = listOf(RemoteUiSurface.COLLECTIONS, RemoteUiSurface.DOWNLOADS),
                ),
                RemoteAnnouncementItem(
                    id = "critical_second",
                    message = "Urgent collections sync note",
                    severity = RemoteAnnouncementSeverity.CRITICAL,
                    surfaces = listOf(RemoteUiSurface.COLLECTIONS, RemoteUiSurface.ASSISTANT),
                ),
            )
        )
        val colNotices = snap.activeAnnouncementsForSurface(RemoteUiSurface.COLLECTIONS)
        assertEquals(listOf("critical_second", "info_first"), colNotices.map { it.id })
        assertEquals(listOf("critical_second"), snap.activeAnnouncementsForSurface(RemoteUiSurface.ASSISTANT).map { it.id })
        assertEquals(listOf("info_first"), snap.activeAnnouncementsForSurface(RemoteUiSurface.DOWNLOADS).map { it.id })
    }

    @Test
    fun versionPolicy_evaluatesAdaptiveUpdateModalAcrossDisabledMonitorAndEnforceModes() {
        // 1. Disabled -> never shows modal even if stale fields are passed
        val disabledPolicy = RemoteVersionPolicy(
            minimumSupportedVersionCode = 0,
            recommendedVersionCode = 0,
            enforcementEnabled = false,
            enforcementMode = RemoteVersionPolicy.MODE_DISABLED,
        )
        assertEquals(
            null,
            RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
                versionPolicy = disabledPolicy,
                currentVersionCode = 60,
                dismissedInSession = false,
            ),
        )

        // 2. Monitor with recommendation OFF -> no modal
        val monitorNoRec = RemoteVersionPolicy(
            minimumSupportedVersionCode = 64,
            recommendedVersionCode = 0,
            enforcementEnabled = false,
            enforcementMode = RemoteVersionPolicy.MODE_MONITOR,
            minimumSupportedTag = "v1.0.64",
        )
        assertEquals(
            null,
            RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
                versionPolicy = monitorNoRec,
                currentVersionCode = 62,
                dismissedInSession = false,
            ),
        )

        // 3. Monitor with recommendation ON -> dismissible Update Available when below recommended
        val monitorWithRec = RemoteVersionPolicy(
            minimumSupportedVersionCode = 60,
            recommendedVersionCode = 65,
            enforcementEnabled = false,
            enforcementMode = RemoteVersionPolicy.MODE_MONITOR,
            minimumSupportedTag = "v1.0.60",
            recommendedTag = "v1.0.65",
        )
        assertFalse(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(monitorWithRec, 62))
        val availablePrompt = RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
            versionPolicy = monitorWithRec,
            currentVersionCode = 62,
            dismissedInSession = false,
        )
        assertEquals(RemoteConfigLayoutHelper.AdaptiveUpdateModalMode.AVAILABLE, availablePrompt?.mode)
        assertEquals("v1.0.65", availablePrompt?.targetTag)
        assertFalse(availablePrompt?.isBlocking ?: true)
        assertEquals(
            null,
            RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
                versionPolicy = monitorWithRec,
                currentVersionCode = 62,
                dismissedInSession = true,
            ),
        )

        // 4. Enforce mode with min=64, rec=67: build 66 >= min yet < rec -> NON-DISMISSIBLE Update Required targeting v1.0.67
        val enforcePolicy = RemoteVersionPolicy(
            minimumSupportedVersionCode = 64,
            recommendedVersionCode = 67,
            enforcementEnabled = true,
            enforcementMode = RemoteVersionPolicy.MODE_ENFORCE,
            minimumSupportedTag = "v1.0.64",
            recommendedTag = "v1.0.67",
        )
        assertTrue(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(enforcePolicy, 66))
        val requiredAboveMinPrompt = RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
            versionPolicy = enforcePolicy,
            currentVersionCode = 66,
            dismissedInSession = true,
        )
        assertEquals(RemoteConfigLayoutHelper.AdaptiveUpdateModalMode.REQUIRED, requiredAboveMinPrompt?.mode)
        assertEquals("v1.0.67", requiredAboveMinPrompt?.targetTag)
        assertTrue(requiredAboveMinPrompt?.isBlocking == true)

        // 5. Build 67 satisfies both min and rec -> no modal
        assertFalse(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(enforcePolicy, 67))
        assertEquals(
            null,
            RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
                versionPolicy = enforcePolicy,
                currentVersionCode = 67,
                dismissedInSession = false,
            ),
        )
    }

    @Test
    fun floatingOverlayPriority_showsNoticeDeckUnlessBlockingUpdateModalIsActive() {
        val enforcePolicy = RemoteVersionPolicy(
            minimumSupportedVersionCode = 190,
            recommendedVersionCode = 198,
            enforcementEnabled = true,
            enforcementMode = RemoteVersionPolicy.MODE_ENFORCE,
            recommendedTag = "v1.0.65",
        )
        val monitorPolicy = RemoteVersionPolicy(
            minimumSupportedVersionCode = 190,
            recommendedVersionCode = 198,
            enforcementEnabled = false,
            enforcementMode = RemoteVersionPolicy.MODE_MONITOR,
            recommendedTag = "v1.0.65",
        )
        val infoNotice = RemoteAnnouncementItem(
            id = "info_tip",
            message = "Try collections",
            severity = RemoteAnnouncementSeverity.INFO,
            surfaces = listOf(RemoteUiSurface.INBOX),
        )

        // 1. Blocking Update Required modal suppresses bottom floating notice deck
        assertEquals(
            RemoteConfigLayoutHelper.BottomFloatingOverlayChoice.NONE,
            RemoteConfigLayoutHelper.selectBottomFloatingOverlay(
                versionPolicy = enforcePolicy,
                currentVersionCode = 195,
                dismissedUpdateInSession = false,
                activeSurfaceNotices = listOf(infoNotice),
            )
        )

        // 2. Monitor mode never uses floating update card; surface notices still render normally
        assertEquals(
            RemoteConfigLayoutHelper.BottomFloatingOverlayChoice.NOTICE_DECK,
            RemoteConfigLayoutHelper.selectBottomFloatingOverlay(
                versionPolicy = monitorPolicy,
                currentVersionCode = 195,
                dismissedUpdateInSession = false,
                activeSurfaceNotices = listOf(infoNotice),
            )
        )
    }

    @Test
    fun buildCombinedHeroEntries_alwaysPreservesDefaultsAndAppendsEnabledCustomSlides() {
        val defaults = listOf("Trend-1", "Trend-2", "Trend-3")

        // 1. Empty custom slide list -> preserves all default banners
        val defaultOnly = RemoteConfigLayoutHelper.buildCombinedHeroEntries(defaults, emptyList())
        assertEquals(3, defaultOnly.size)
        assertTrue(defaultOnly.all { it is HeroCarouselEntry.CatalogueBanner })
        assertEquals(
            defaults,
            defaultOnly.map { (it as HeroCarouselEntry.CatalogueBanner).item },
        )

        // 2. Adding 1 custom slide appends 1 additional slide after defaults
        val oneCustom = RemoteConfigLayoutHelper.buildCombinedHeroEntries(
            defaultBanners = defaults,
            customSlides = listOf(
                RemoteHeroSlide(id = "c1", heading = "Custom Spotlight", enabled = true, order = 10)
            ),
        )
        assertEquals(4, oneCustom.size)
        assertEquals("Trend-1", (oneCustom[0] as HeroCarouselEntry.CatalogueBanner).item)
        assertEquals("Trend-3", (oneCustom[2] as HeroCarouselEntry.CatalogueBanner).item)
        assertEquals("c1", (oneCustom[3] as HeroCarouselEntry.CustomSlide).slide.id)

        // 3. Adding 3 enabled slides (plus 1 disabled) appends exactly 3 slides sorted by order
        val threeCustom = RemoteConfigLayoutHelper.buildCombinedHeroEntries(
            defaultBanners = defaults,
            customSlides = listOf(
                RemoteHeroSlide(id = "c3", heading = "Third", enabled = true, order = 30),
                RemoteHeroSlide(id = "c_disabled", heading = "Hidden", enabled = false, order = 5),
                RemoteHeroSlide(id = "c1", heading = "First", enabled = true, order = 10),
                RemoteHeroSlide(id = "c2", heading = "Second", enabled = true, order = 20),
            ),
        )
        assertEquals(6, threeCustom.size)
        assertEquals(
            listOf("c1", "c2", "c3"),
            threeCustom.drop(3).map { (it as HeroCarouselEntry.CustomSlide).slide.id },
        )
    }

    @Test
    fun versionEnforcement_min63Rec72_boundaryMatrix_andNumericComparison() {
        val policy = RemoteVersionPolicy(
            minimumSupportedVersionCode = 63,
            recommendedVersionCode = 72,
            enforcementEnabled = true,
            enforcementMode = RemoteVersionPolicy.MODE_ENFORCE,
            minimumSupportedTag = "v1.0.63",
            recommendedTag = "v1.0.72",
            updateMessage = "Please update to build 72 to continue.",
        )

        // Boundary versions 62 (<min), 63 (==min, <rec), 64 (between), 71 (<rec), 72 (==rec), 73 (>rec)
        for (versionCode in listOf(62, 63, 64, 71)) {
            assertTrue(
                "Expected build $versionCode to show blocking dialog",
                RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(policy, versionCode),
            )
            val prompt = RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
                versionPolicy = policy,
                currentVersionCode = versionCode,
                dismissedInSession = true,
            )
            assertEquals(RemoteConfigLayoutHelper.AdaptiveUpdateModalMode.REQUIRED, prompt?.mode)
            assertTrue(prompt?.isBlocking == true)
            assertEquals("v1.0.72", prompt?.targetTag)
        }

        for (versionCode in listOf(72, 73, 206)) {
            assertFalse(
                "Expected build $versionCode to NOT be blocked",
                RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(policy, versionCode),
            )
            assertEquals(
                null,
                RemoteConfigLayoutHelper.resolveAdaptiveUpdatePrompt(
                    versionPolicy = policy,
                    currentVersionCode = versionCode,
                    dismissedInSession = false,
                ),
            )
        }

        // Numeric vs lexicographical edge cases ("9" < 10 and "72" < 100)
        val numericEdgePolicy = policy.copy(
            minimumSupportedVersionCode = 10,
            recommendedVersionCode = 100,
        )
        assertTrue(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(numericEdgePolicy, 9))
        assertTrue(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(numericEdgePolicy, 72))
        assertFalse(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(numericEdgePolicy, 100))
    }

    @Test
    fun remoteConfigValidator_rejectsMalformedAndExpiredPayloadsCleanly() {
        val malformed = RemoteConfigValidator.parseAndValidate(
            rawJson = "{not-valid-json",
            clientVersionCode = 72,
            source = RemoteConfigSource.NETWORK,
        )
        assertTrue(malformed is RemoteConfigParseResult.Rejected)

        val expiredJson = """
            {
              "schemaVersion": 1,
              "revision": 35,
              "publishedAt": "2026-11-23T00:00:00Z",
              "expiresAt": "2020-01-01T00:00:00Z",
              "general": { "minRefreshIntervalSeconds": 60, "staleCacheMaxAgeSeconds": 86400 },
              "versionPolicy": {
                "minimumSupportedVersionCode": 63,
                "recommendedVersionCode": 72,
                "enforcementEnabled": true,
                "enforcementMode": "enforce"
              }
            }
        """.trimIndent()
        val expired = RemoteConfigValidator.parseAndValidate(
            rawJson = expiredJson,
            clientVersionCode = 72,
            source = RemoteConfigSource.CACHE,
            nowEpochMs = 1_795_400_000_000L,
            enforceExpiry = true,
        )
        assertTrue(expired is RemoteConfigParseResult.Rejected)
    }
}

