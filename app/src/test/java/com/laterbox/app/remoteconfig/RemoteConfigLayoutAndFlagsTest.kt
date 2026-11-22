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
        assertTrue(slots.any { it.railId == "newest" })
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
    fun versionPolicy_evaluatesBlockingAndRecommendedUpdateThresholdsCorrectly() {
        val advisoryPolicy = RemoteVersionPolicy(
            minimumSupportedVersionCode = 60,
            recommendedVersionCode = 65,
            enforcementEnabled = true,
            recommendedTag = "v1.0.65",
        )
        // Build 62 meets min (60) but is below recommended (65)
        assertFalse(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(advisoryPolicy, 62))
        assertTrue(RemoteConfigLayoutHelper.shouldShowRecommendedUpdateBanner(advisoryPolicy, 62, dismissedInSession = false))
        assertFalse(RemoteConfigLayoutHelper.shouldShowRecommendedUpdateBanner(advisoryPolicy, 62, dismissedInSession = true))

        // Build 59 is below minimumSupportedVersionCode with enforcementEnabled=true -> blocking dialog
        assertTrue(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(advisoryPolicy, 59))
        assertFalse(RemoteConfigLayoutHelper.shouldShowRecommendedUpdateBanner(advisoryPolicy, 59, dismissedInSession = false))

        // Build 66 satisfies both -> neither banner nor dialog
        assertFalse(RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(advisoryPolicy, 66))
        assertFalse(RemoteConfigLayoutHelper.shouldShowRecommendedUpdateBanner(advisoryPolicy, 66, dismissedInSession = false))
    }
}
