package com.laterbox.app.remoteconfig

import com.laterbox.app.ai.RemoteCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigLayoutAndFlagsTest {

    @Test
    fun buildMoviesHomeSlots_reordersRails_hidesDisabledRails_andHonorsRecommendationsFlag() {
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
                        id = "rail_watchlist_custom",
                        type = RemoteCapabilities.TYPE_CONTENT_RAIL,
                        surface = RemoteUiSurface.MOVIES,
                        visible = true,
                        order = 15,
                        railId = "watchlist",
                        title = "Pinned Queue",
                        maxItems = 8,
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
                    ),
                )
            )
        )

        val slots = RemoteConfigLayoutHelper.buildMoviesHomeSlots(snapshot)

        assertEquals("ui_callout_callout_top", slots[0].key)
        assertEquals("rail_watchlist", slots[1].key)
        assertEquals("Pinned Queue", slots[1].customTitle)
        assertEquals(8, slots[1].maxItems)
        assertEquals("hero_carousel", slots[2].key)
        assertEquals(4, slots[2].maxItems)

        // Hidden newest rail and disabled recommendation rails must not appear
        assertFalse(slots.any { it.railId == "newest" })
        assertFalse(slots.any { it.railId == "because_you_watched" })
        assertFalse(slots.any { it.railId == "quick_picks" })

        // Unconfigured non-recommendation core rails remain safely available
        assertTrue(slots.any { it.railId == "continue_watching" })
        assertTrue(slots.any { it.railId == "top_rated" })
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
