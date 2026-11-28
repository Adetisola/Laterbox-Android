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

sealed class HeroCarouselEntry<out T> {
    abstract val stableKey: String

    data class CatalogueBanner<T>(
        val item: T,
        override val stableKey: String,
    ) : HeroCarouselEntry<T>()

    data class CustomSlide(
        val slide: RemoteHeroSlide,
    ) : HeroCarouselEntry<Nothing>() {
        override val stableKey: String = "custom_hero_${slide.id}"
    }
}

object RemoteConfigLayoutHelper {

    fun <T> buildCombinedHeroEntries(
        defaultBanners: List<T>,
        customSlides: List<RemoteHeroSlide>,
        keyForBanner: (index: Int, item: T) -> String = { index, _ -> "default_hero_$index" },
    ): List<HeroCarouselEntry<T>> {
        val defaultEntries = defaultBanners.mapIndexed { index, item ->
            HeroCarouselEntry.CatalogueBanner(
                item = item,
                stableKey = keyForBanner(index, item),
            )
        }
        val customEntries = customSlides
            .asSequence()
            .filter { it.enabled }
            .sortedBy { it.order }
            .map { HeroCarouselEntry.CustomSlide(it) }
            .toList()
        return defaultEntries + customEntries
    }

    private val defaultMoviesOrder: List<Pair<String, MoviesHomeSlotKind>> = listOf(
        "hero_carousel" to MoviesHomeSlotKind.HERO_CAROUSEL,
        "continue_watching" to MoviesHomeSlotKind.CONTENT_RAIL,
        "watchlist" to MoviesHomeSlotKind.CONTENT_RAIL,
        "recently_viewed" to MoviesHomeSlotKind.CONTENT_RAIL,
        "because_you_watched" to MoviesHomeSlotKind.CONTENT_RAIL,
        "recently_added" to MoviesHomeSlotKind.CONTENT_RAIL,
        "top_rated" to MoviesHomeSlotKind.CONTENT_RAIL,
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
            if (!recommendationsEnabled && id == "because_you_watched") {
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

    enum class AdaptiveUpdateModalMode {
        AVAILABLE,
        REQUIRED,
    }

    data class AdaptiveUpdatePromptPolicy(
        val mode: AdaptiveUpdateModalMode,
        val targetVersionCode: Int,
        val targetTag: String,
        val minimumSupportedVersionCode: Int,
        val minimumSupportedTag: String,
        val customMessage: String,
        val fallbackUrl: String = VersionEnforcementBlockState.DEFAULT_UPDATE_URL,
    ) {
        val isBlocking: Boolean
            get() = mode == AdaptiveUpdateModalMode.REQUIRED
    }

    fun shouldShowBlockingUpdateDialog(
        versionPolicy: RemoteVersionPolicy,
        currentVersionCode: Int,
    ): Boolean {
        if (!versionPolicy.isEnforceModeActive) return false
        val belowMinimum = versionPolicy.minimumSupportedVersionCode > 0 &&
            currentVersionCode < versionPolicy.minimumSupportedVersionCode
        val belowRecommended = versionPolicy.recommendedVersionCode > 0 &&
            currentVersionCode < versionPolicy.recommendedVersionCode
        return belowMinimum || belowRecommended
    }

    fun shouldShowRecommendedUpdateBanner(
        versionPolicy: RemoteVersionPolicy,
        currentVersionCode: Int,
        dismissedInSession: Boolean,
    ): Boolean {
        if (dismissedInSession) return false
        if (versionPolicy.normalizedEnforcementMode != RemoteVersionPolicy.MODE_MONITOR) return false
        return versionPolicy.recommendedVersionCode > 0 &&
            currentVersionCode < versionPolicy.recommendedVersionCode
    }

    fun resolveAdaptiveUpdatePrompt(
        versionPolicy: RemoteVersionPolicy,
        currentVersionCode: Int,
        dismissedInSession: Boolean,
        serverBlock: VersionEnforcementBlockState? = null,
    ): AdaptiveUpdatePromptPolicy? {
        if (serverBlock != null &&
            serverBlock.triggeredByServer426 &&
            serverBlock.minimumSupportedVersionCode > 0 &&
            currentVersionCode < serverBlock.minimumSupportedVersionCode
        ) {
            val targetTag = versionPolicy.recommendedTag.takeIf { it.isNotBlank() }
                ?: serverBlock.recommendedTag.takeIf { it.isNotBlank() }
                ?: serverBlock.latestStableTag
            val targetCode = versionPolicy.recommendedVersionCode.takeIf { it > 0 }
                ?: serverBlock.recommendedVersionCode.takeIf { it > 0 }
                ?: serverBlock.minimumSupportedVersionCode
            val msg = versionPolicy.updateMessage.ifBlank { serverBlock.message }
            val fallbackUrl = targetTag.takeIf { it.isNotBlank() }
                ?.let { "https://laterbox-website.vercel.app/releases/$it" }
                ?: serverBlock.updateUrl
            return AdaptiveUpdatePromptPolicy(
                mode = AdaptiveUpdateModalMode.REQUIRED,
                targetVersionCode = targetCode,
                targetTag = targetTag,
                minimumSupportedVersionCode = serverBlock.minimumSupportedVersionCode,
                minimumSupportedTag = serverBlock.minimumSupportedTag.ifBlank { versionPolicy.minimumSupportedTag },
                customMessage = msg,
                fallbackUrl = fallbackUrl,
            )
        }

        if (shouldShowBlockingUpdateDialog(versionPolicy, currentVersionCode)) {
            val targetTag = versionPolicy.recommendedTag.ifBlank { versionPolicy.minimumSupportedTag }
            val targetCode = versionPolicy.recommendedVersionCode.takeIf { it > 0 }
                ?: versionPolicy.minimumSupportedVersionCode
            val fallbackUrl = targetTag.takeIf { it.isNotBlank() }
                ?.let { "https://laterbox-website.vercel.app/releases/$it" }
                ?: VersionEnforcementBlockState.DEFAULT_UPDATE_URL
            return AdaptiveUpdatePromptPolicy(
                mode = AdaptiveUpdateModalMode.REQUIRED,
                targetVersionCode = targetCode,
                targetTag = targetTag,
                minimumSupportedVersionCode = versionPolicy.minimumSupportedVersionCode,
                minimumSupportedTag = versionPolicy.minimumSupportedTag,
                customMessage = versionPolicy.updateMessage,
                fallbackUrl = fallbackUrl,
            )
        }

        if (shouldShowRecommendedUpdateBanner(versionPolicy, currentVersionCode, dismissedInSession)) {
            val targetTag = versionPolicy.recommendedTag
            val fallbackUrl = targetTag.takeIf { it.isNotBlank() }
                ?.let { "https://laterbox-website.vercel.app/releases/$it" }
                ?: VersionEnforcementBlockState.DEFAULT_UPDATE_URL
            return AdaptiveUpdatePromptPolicy(
                mode = AdaptiveUpdateModalMode.AVAILABLE,
                targetVersionCode = versionPolicy.recommendedVersionCode,
                targetTag = targetTag,
                minimumSupportedVersionCode = versionPolicy.minimumSupportedVersionCode,
                minimumSupportedTag = versionPolicy.minimumSupportedTag,
                customMessage = versionPolicy.updateMessage,
                fallbackUrl = fallbackUrl,
            )
        }

        return null
    }

    enum class BottomFloatingOverlayChoice {
        NONE,
        NOTICE_DECK,
    }

    /**
     * Floating bottom overlay deck excludes version-policy update prompts,
     * which render directly via the single adaptive update modal.
     */
    fun selectBottomFloatingOverlay(
        versionPolicy: RemoteVersionPolicy,
        currentVersionCode: Int,
        dismissedUpdateInSession: Boolean,
        activeSurfaceNotices: List<RemoteAnnouncementItem>,
    ): BottomFloatingOverlayChoice {
        val _dismissed = dismissedUpdateInSession
        if (shouldShowBlockingUpdateDialog(versionPolicy, currentVersionCode)) {
            return BottomFloatingOverlayChoice.NONE
        }
        if (activeSurfaceNotices.isNotEmpty()) {
            return BottomFloatingOverlayChoice.NOTICE_DECK
        }
        return BottomFloatingOverlayChoice.NONE
    }
}
