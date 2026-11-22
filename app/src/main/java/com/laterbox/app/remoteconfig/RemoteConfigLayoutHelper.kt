package com.laterbox.app.remoteconfig

import com.laterbox.app.ai.RemoteCapabilities

enum class MoviesHomeSlotKind {
    ANNOUNCEMENT_BANNER,
    FEATURED_CALLOUT_CARD,
    HERO_CAROUSEL,
    CONTENT_RAIL,
}

data class MoviesHomeRenderSlot(
    val key: String,
    val kind: MoviesHomeSlotKind,
    val order: Int,
    val railId: String? = null,
    val customTitle: String = "",
    val maxItems: Int = 15,
    val component: RemoteUiComponent? = null,
)

object RemoteConfigLayoutHelper {

    private val defaultMoviesOrder: List<Pair<String, MoviesHomeSlotKind>> = listOf(
        "hero_carousel" to MoviesHomeSlotKind.HERO_CAROUSEL,
        "continue_watching" to MoviesHomeSlotKind.CONTENT_RAIL,
        "watchlist" to MoviesHomeSlotKind.CONTENT_RAIL,
        "recently_viewed" to MoviesHomeSlotKind.CONTENT_RAIL,
        "because_you_watched" to MoviesHomeSlotKind.CONTENT_RAIL,
        "quick_picks" to MoviesHomeSlotKind.CONTENT_RAIL,
        "recently_added" to MoviesHomeSlotKind.CONTENT_RAIL,
        "top_rated" to MoviesHomeSlotKind.CONTENT_RAIL,
        "newest" to MoviesHomeSlotKind.CONTENT_RAIL,
        "discovery_sections" to MoviesHomeSlotKind.CONTENT_RAIL,
    )

    fun buildMoviesHomeSlots(
        snapshot: RemoteConfigSnapshot,
        recommendationsEnabled: Boolean = snapshot.isFeatureEnabled(RemoteCapabilities.KEY_MOVIES_RECOMMENDATIONS),
    ): List<MoviesHomeRenderSlot> {
        val moviesComponents = snapshot.componentsForSurface(RemoteUiSurface.MOVIES)
        val heroConfig = moviesComponents.firstOrNull { it.type == RemoteCapabilities.TYPE_HERO_CAROUSEL }
        val featuredConfig = moviesComponents.firstOrNull {
            it.type == RemoteCapabilities.TYPE_FEATURED_CALLOUT_CARD && it.visible
        }

        val slots = mutableListOf<MoviesHomeRenderSlot>()
        val includeHero = heroConfig?.visible ?: true
        if (includeHero) {
            slots += MoviesHomeRenderSlot(
                key = "hero_carousel",
                kind = MoviesHomeSlotKind.HERO_CAROUSEL,
                order = heroConfig?.order ?: 10,
                maxItems = heroConfig?.maxItems ?: 8,
                component = heroConfig,
            )
        }
        if (featuredConfig != null) {
            slots += MoviesHomeRenderSlot(
                key = "ui_callout_${featuredConfig.id}",
                kind = MoviesHomeSlotKind.FEATURED_CALLOUT_CARD,
                order = featuredConfig.order,
                component = featuredConfig,
            )
        }

        // Core catalogue rails stay consistently accessible in their native sequence
        var railOrder = 100
        for ((id, kind) in defaultMoviesOrder) {
            if (kind != MoviesHomeSlotKind.CONTENT_RAIL) continue
            if (!recommendationsEnabled && (id == "because_you_watched" || id == "quick_picks")) {
                continue
            }
            railOrder += 10
            slots += MoviesHomeRenderSlot(
                key = "rail_$id",
                kind = MoviesHomeSlotKind.CONTENT_RAIL,
                order = railOrder,
                railId = id,
                maxItems = 15,
            )
        }

        return slots.sortedBy { it.order }
    }

    fun shouldShowBlockingUpdateDialog(
        versionPolicy: RemoteVersionPolicy,
        currentVersionCode: Int,
    ): Boolean {
        return versionPolicy.enforcementEnabled &&
            versionPolicy.minimumSupportedVersionCode > 0 &&
            currentVersionCode < versionPolicy.minimumSupportedVersionCode
    }

    fun shouldShowRecommendedUpdateBanner(
        versionPolicy: RemoteVersionPolicy,
        currentVersionCode: Int,
        dismissedInSession: Boolean,
    ): Boolean {
        if (dismissedInSession) return false
        if (shouldShowBlockingUpdateDialog(versionPolicy, currentVersionCode)) return false
        return versionPolicy.recommendedVersionCode > currentVersionCode
    }

    enum class BottomFloatingOverlayChoice {
        NONE,
        NOTICE_DECK,
        RECOMMENDED_UPDATE,
    }

    /**
     * Enforces UI notification priority hierarchy:
     * Blocking Dialog > Maintenance Status Strip > Critical/Warning Notice > Recommended Update > Info Notice.
     */
    fun selectBottomFloatingOverlay(
        versionPolicy: RemoteVersionPolicy,
        currentVersionCode: Int,
        dismissedUpdateInSession: Boolean,
        activeSurfaceNotices: List<RemoteAnnouncementItem>,
    ): BottomFloatingOverlayChoice {
        if (shouldShowBlockingUpdateDialog(versionPolicy, currentVersionCode)) {
            return BottomFloatingOverlayChoice.NONE
        }
        val hasHighSeverityNotice = activeSurfaceNotices.any { notice ->
            notice.severity == RemoteAnnouncementSeverity.CRITICAL ||
                notice.severity == RemoteAnnouncementSeverity.WARNING
        }
        if (hasHighSeverityNotice) {
            return BottomFloatingOverlayChoice.NOTICE_DECK
        }
        if (shouldShowRecommendedUpdateBanner(versionPolicy, currentVersionCode, dismissedUpdateInSession)) {
            return BottomFloatingOverlayChoice.RECOMMENDED_UPDATE
        }
        if (activeSurfaceNotices.isNotEmpty()) {
            return BottomFloatingOverlayChoice.NOTICE_DECK
        }
        return BottomFloatingOverlayChoice.NONE
    }
}
