package com.laterbox.app.media.data.inbox

import com.laterbox.app.media.domain.adapter.MediaPlatformAdapter
import com.laterbox.app.media.domain.model.InspectionResult

/**
 * Routes X/Twitter, Instagram, and Spotify URLs directly through the Laterbox backend
 * `/api/inbox/resolve/{twitter,instagram,spotify}` extraction layer instead of client scraping.
 * Non-matching hosts (YouTube, TikTok, etc.) fall through to the existing adapter chain.
 */
class BackendInboxMediaAdapter(
    private val inboxResolverClient: LaterboxInboxResolverClient
) : MediaPlatformAdapter {

    override val id: String = "backend_inbox_resolver"
    override val inspectionTimeoutMs: Long = 15_000L

    override fun canHandle(url: String): Boolean {
        return InboxLinkRouter.routeForUrl(url) != null
    }

    override suspend fun inspect(url: String): InspectionResult {
        return inboxResolverClient.resolveToInspectionResult(url)
    }
}
