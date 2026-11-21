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

        if (moviesComponents.isEmpty()) {
            return defaultMoviesOrder.mapIndexedNotNull { idx, (id, kind) ->
                if (!recommendationsEnabled && (id == "because_you_watched" || id == "quick_picks")) {
                    return@mapIndexedNotNull null
                }
                MoviesHomeRenderSlot(
                    key = if (kind == MoviesHomeSlotKind.HERO_CAROUSEL) "hero_carousel" else "rail_$id",
                    kind = kind,
                    order = (idx + 1) * 10,
                    railId = if (kind == MoviesHomeSlotKind.CONTENT_RAIL) id else null,
                    maxItems = if (kind == MoviesHomeSlotKind.HERO_CAROUSEL) 8 else 15,
                )
            }
        }

        val slots = mutableListOf<MoviesHomeRenderSlot>()
        val configuredRails = mutableSetOf<String>()
        var hasHero = false

        for (comp in moviesComponents) {
            when (comp.type) {
                RemoteCapabilities.TYPE_ANNOUNCEMENT_BANNER -> if (comp.visible) {
                    slots += MoviesHomeRenderSlot(
                        key = "ui_ann_${comp.id}",
                        kind = MoviesHomeSlotKind.ANNOUNCEMENT_BANNER,
                        order = comp.order,
                        component = comp,
                    )
                }

                RemoteCapabilities.TYPE_FEATURED_CALLOUT_CARD -> if (comp.visible) {
                    slots += MoviesHomeRenderSlot(
                        key = "ui_callout_${comp.id}",
                        kind = MoviesHomeSlotKind.FEATURED_CALLOUT_CARD,
                        order = comp.order,
                        component = comp,
                    )
                }

                RemoteCapabilities.TYPE_HERO_CAROUSEL -> {
                    hasHero = true
                    if (comp.visible) {
                        slots += MoviesHomeRenderSlot(
                            key = "hero_carousel",
                            kind = MoviesHomeSlotKind.HERO_CAROUSEL,
                            order = comp.order,
                            maxItems = comp.maxItems,
                            component = comp,
                        )
                    }
                }

                RemoteCapabilities.TYPE_CONTENT_RAIL -> {
                    val railId = comp.railId ?: continue
                    if (railId !in RemoteCapabilities.SUPPORTED_RAIL_IDS) continue
                    configuredRails += railId
                    if (!comp.visible) continue
                    if (!recommendationsEnabled && (railId == "because_you_watched" || railId == "quick_picks")) {
                        continue
                    }
                    slots += MoviesHomeRenderSlot(
                        key = "rail_$railId",
                        kind = MoviesHomeSlotKind.CONTENT_RAIL,
                        order = comp.order,
                        railId = railId,
                        customTitle = comp.title,
                        maxItems = comp.maxItems,
                        component = comp,
                    )
                }
            }
        }

        // Preserve unmentioned default rails safely at the tail so core functionality stays accessible
        var fallbackOrder = 500
        for ((id, kind) in defaultMoviesOrder) {
            if (kind == MoviesHomeSlotKind.HERO_CAROUSEL) {
                if (!hasHero) {
                    fallbackOrder += 10
                    slots += MoviesHomeRenderSlot(
                        key = "hero_carousel",
                        kind = kind,
                        order = fallbackOrder,
                        maxItems = 8,
                    )
                }
            } else if (id !in configuredRails) {
                if (!recommendationsEnabled && (id == "because_you_watched" || id == "quick_picks")) {
                    continue
                }
                fallbackOrder += 10
                slots += MoviesHomeRenderSlot(
                    key = "rail_$id",
                    kind = MoviesHomeSlotKind.CONTENT_RAIL,
                    order = fallbackOrder,
                    railId = id,
                    maxItems = 15,
                )
            }
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
}
