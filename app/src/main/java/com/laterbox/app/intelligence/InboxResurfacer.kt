package com.laterbox.app.intelligence

import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.domain.model.SavedLink
import kotlin.math.max

data class ResurfacedLinkSuggestion(
    val link: SavedLink,
    val rationale: String,
    val matchedFacetKeys: List<String> = emptyList(),
    val score: Double = 0.0
)

/**
 * Deterministic, purely on-device resurfacer for meaningful older/unread Inbox links.
 *
 * Strictly never mutates or reorders the main chronological Inbox feed; outputs compact
 * suggestions for a dismissible resurfacing strip when Inbox has enough saves.
 */
object InboxResurfacer {

    const val MIN_INBOX_LINKS = 4
    const val DAY_MS = 24L * 60L * 60L * 1000L
    const val MIN_AGE_MS = 2L * DAY_MS

    fun selectSuggestions(
        inboxLinks: List<SavedLink>,
        facets: List<UserTasteFacetEntity> = emptyList(),
        dismissedLinkIds: Set<Long> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        maxItems: Int = 3,
        minInboxLinks: Int = MIN_INBOX_LINKS
    ): List<ResurfacedLinkSuggestion> {
        if (maxItems <= 0 || inboxLinks.size < minInboxLinks) return emptyList()

        val activeFacets = facets.filter { facet ->
            !facet.isSuppressedByUser &&
                (facet.surfaceScope == IntelligenceSurfaceScope.LIBRARY.name ||
                    facet.surfaceScope == IntelligenceSurfaceScope.ALL.name) &&
                (facet.expiresAtMs == null || facet.expiresAtMs > nowMs)
        }
        val dislikedKeys = activeFacets
            .filter { it.facetType == TasteFacetType.EXPLICIT_DISLIKE.name }
            .map { it.facetKey }
            .toSet()
        val positiveFacetByKey = activeFacets
            .filter { it.facetType != TasteFacetType.EXPLICIT_DISLIKE.name }
            .associateBy { it.facetKey }

        val candidates = inboxLinks.filter { link ->
            !link.isArchived &&
                !link.isRead &&
                link.id !in dismissedLinkIds &&
                (nowMs - link.createdAt) >= MIN_AGE_MS
        }
        if (candidates.isEmpty()) return emptyList()

        val scored = candidates.mapNotNull { link ->
            val linkTopics = TopicExtractor.extractLibraryTopics(
                url = link.originalUrl,
                domain = link.domain,
                title = link.title,
                description = link.description
            )
            if (linkTopics.any { it.facetKey in dislikedKeys }) {
                return@mapNotNull null
            }

            var score = 0.0
            var topFacet: UserTasteFacetEntity? = null
            val matchedKeys = mutableListOf<String>()

            for (topic in linkTopics) {
                val facet = positiveFacetByKey[topic.facetKey] ?: continue
                val typeMultiplier = when (facet.facetType) {
                    TasteFacetType.ACTIVE_GOAL.name -> 4.0
                    TasteFacetType.EXPLICIT_LIKE.name -> 3.6
                    TasteFacetType.SHORT_TERM_INTENT.name -> 3.2
                    else -> 2.4
                }
                val facetPoints = typeMultiplier * facet.score.coerceIn(0.15, 1.0)
                score += facetPoints
                matchedKeys.add(facet.facetKey)
                if (topFacet == null || facetPoints > topFacet.score) {
                    topFacet = facet
                }
            }

            val hasNote = !link.personalNote.isNullOrBlank()
            val hasFutureReminder = link.reminderAt != null && link.reminderAt > nowMs
            val ageDays = max(2L, (nowMs - link.createdAt) / DAY_MS).toInt()

            if (hasNote) score += 1.6
            if (hasFutureReminder) score += 0.8
            if (link.collectionIds.isEmpty() && link.collectionId == null) score += 0.25

            // Mild sweet-spot recency decay over 2..45 days so very stale links slowly cool down
            val ageScore = when {
                ageDays in 2..14 -> 1.4
                ageDays in 15..30 -> 1.0
                else -> 0.5
            }
            score += ageScore

            // Require at least a facet match, a user note/high-intent save, or age >= 3 days
            if (matchedKeys.isEmpty() && !hasNote && ageDays < 3) {
                return@mapNotNull null
            }

            val rationale = when {
                topFacet != null && topFacet.facetType == TasteFacetType.ACTIVE_GOAL.name ->
                    "Matches your active ${topFacet.displayLabel} focus"
                topFacet != null && topFacet.facetType == TasteFacetType.EXPLICIT_LIKE.name ->
                    "Matches your pinned ${topFacet.displayLabel} interest"
                topFacet != null ->
                    "Matches your ${topFacet.displayLabel} interest • ${ageDays}d ago"
                hasNote ->
                    "Saved with your note • ${ageDays}d ago"
                else ->
                    "Saved $ageDays days ago • Unread"
            }

            ResurfacedLinkSuggestion(
                link = link,
                rationale = rationale,
                matchedFacetKeys = matchedKeys.distinct(),
                score = score
            )
        }

        return scored
            .sortedWith(
                compareByDescending<ResurfacedLinkSuggestion> { it.score }
                    .thenByDescending { it.link.createdAt }
                    .thenBy { it.link.id }
            )
            .take(maxItems)
    }
}
