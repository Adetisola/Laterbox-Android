package com.laterbox.app.intelligence

import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.domain.model.CollectionWithCount
import com.laterbox.app.domain.model.SavedLink
import java.util.Locale

/**
 * Lexical-first search ranker where exact lexical relevance buckets are 100% authoritative,
 * and on-device taste facets, unread state, and recency act strictly as tie-breakers inside
 * identical lexical match buckets.
 */
object SearchTieBreaker {

    fun rankLinks(
        links: List<SavedLink>,
        query: String,
        facets: List<UserTasteFacetEntity> = emptyList(),
        applyTieBreaker: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): List<SavedLink> {
        val cleanQuery = query.trim().lowercase(Locale.US)
        if (cleanQuery.isBlank() || links.size <= 1) return links

        val queryTokens = cleanQuery.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val activeFacetWeights = if (applyTieBreaker) {
            buildLibraryFacetWeights(facets, nowMs)
        } else {
            emptyMap()
        }

        data class RankedLink(
            val link: SavedLink,
            val lexicalTier: Int,
            val facetScore: Double,
            val unreadScore: Int
        )

        return links.map { link ->
            val tier = lexicalTierForLink(link, cleanQuery, queryTokens)
            val facetScore = if (applyTieBreaker && activeFacetWeights.isNotEmpty()) {
                val topics = TopicExtractor.extractLibraryTopics(
                    url = link.originalUrl,
                    domain = link.domain,
                    title = link.title,
                    description = link.description
                )
                topics.sumOf { activeFacetWeights[it.facetKey] ?: 0.0 }
            } else {
                0.0
            }
            val unread = if (applyTieBreaker && !link.isRead) 1 else 0
            RankedLink(link, tier, facetScore, unread)
        }.sortedWith(
            compareByDescending<RankedLink> { it.lexicalTier }
                .thenByDescending { it.facetScore }
                .thenByDescending { it.unreadScore }
                .thenByDescending { it.link.createdAt }
                .thenBy { it.link.id }
        ).map { it.link }
    }

    fun rankCollections(
        collections: List<CollectionWithCount>,
        query: String,
        facets: List<UserTasteFacetEntity> = emptyList(),
        applyTieBreaker: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): List<CollectionWithCount> {
        val cleanQuery = query.trim().lowercase(Locale.US)
        if (cleanQuery.isBlank() || collections.size <= 1) return collections

        val facetWeights = if (applyTieBreaker) {
            buildAllFacetWeights(facets, nowMs)
        } else {
            emptyMap()
        }

        data class RankedCollection(
            val item: CollectionWithCount,
            val lexicalTier: Int,
            val facetScore: Double
        )

        return collections.map { entry ->
            val nameLower = entry.collection.name.trim().lowercase(Locale.US)
            val tier = when {
                nameLower == cleanQuery -> 4
                nameLower.startsWith(cleanQuery) -> 3
                Regex("\\b${Regex.escape(cleanQuery)}\\b").containsMatchIn(nameLower) -> 2
                nameLower.contains(cleanQuery) -> 1
                else -> 0
            }
            val facetScore = if (applyTieBreaker && facetWeights.isNotEmpty()) {
                val topics = TopicExtractor.extractLibraryTopics(title = entry.collection.name)
                var score = topics.sumOf { facetWeights[it.facetKey] ?: 0.0 }
                val nameTokens = nameLower.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
                for ((key, weight) in facetWeights) {
                    val keyLabel = key.substringAfter(':').replace('_', ' ')
                    if (keyLabel in nameTokens) {
                        score += weight
                    }
                }
                score
            } else {
                0.0
            }
            RankedCollection(entry, tier, facetScore)
        }.sortedWith(
            compareByDescending<RankedCollection> { it.lexicalTier }
                .thenByDescending { it.facetScore }
                .thenByDescending { it.item.itemCount }
                .thenByDescending { it.item.collection.updatedAt }
                .thenBy { it.item.collection.name.lowercase(Locale.US) }
        ).map { it.item }
    }

    internal fun lexicalTierForLink(
        link: SavedLink,
        cleanQuery: String,
        queryTokens: List<String>
    ): Int {
        val titleLower = link.title.trim().lowercase(Locale.US)
        val domainLower = link.domain.trim().lowercase(Locale.US)
        val urlLower = link.originalUrl.trim().lowercase(Locale.US)
        val siteLower = link.siteName?.trim()?.lowercase(Locale.US).orEmpty()
        val descLower = link.description?.trim()?.lowercase(Locale.US).orEmpty()
        val noteLower = link.personalNote?.trim()?.lowercase(Locale.US).orEmpty()

        // Tier 4: exact title or full-word phrase match in title
        if (titleLower == cleanQuery ||
            (cleanQuery.length >= 2 && Regex("\\b${Regex.escape(cleanQuery)}\\b").containsMatchIn(titleLower))
        ) {
            return 4
        }
        // Tier 3: title contains query or all query tokens appear in title
        if (titleLower.contains(cleanQuery) ||
            (queryTokens.size > 1 && queryTokens.all { titleLower.contains(it) })
        ) {
            return 3
        }
        // Tier 2: domain / siteName / URL contains query
        if (domainLower.contains(cleanQuery) ||
            siteLower.contains(cleanQuery) ||
            urlLower.contains(cleanQuery)
        ) {
            return 2
        }
        // Tier 1: partial description / personal note match or cross-field token match
        val combined = "$titleLower $domainLower $siteLower $descLower $noteLower"
        if (descLower.contains(cleanQuery) ||
            noteLower.contains(cleanQuery) ||
            (queryTokens.isNotEmpty() && queryTokens.all { combined.contains(it) })
        ) {
            return 1
        }
        return 0
    }

    private fun buildLibraryFacetWeights(
        facets: List<UserTasteFacetEntity>,
        nowMs: Long
    ): Map<String, Double> = facets
        .filter {
            !it.isSuppressedByUser &&
                (it.surfaceScope == IntelligenceSurfaceScope.LIBRARY.name ||
                    it.surfaceScope == IntelligenceSurfaceScope.ALL.name) &&
                (it.expiresAtMs == null || it.expiresAtMs > nowMs)
        }
        .associate { facet ->
            val conf = facet.confidence.coerceIn(0.25, 1.0)
            val weight = when (facet.facetType) {
                TasteFacetType.EXPLICIT_DISLIKE.name -> -5.0
                TasteFacetType.EXPLICIT_LIKE.name -> 2.0 * facet.score
                TasteFacetType.ACTIVE_GOAL.name -> 1.8 * facet.score * conf
                else -> facet.score * conf
            }
            facet.facetKey to weight
        }

    private fun buildAllFacetWeights(
        facets: List<UserTasteFacetEntity>,
        nowMs: Long
    ): Map<String, Double> = facets
        .filter {
            !it.isSuppressedByUser &&
                (it.expiresAtMs == null || it.expiresAtMs > nowMs)
        }
        .associate { facet ->
            val conf = facet.confidence.coerceIn(0.25, 1.0)
            val weight = when (facet.facetType) {
                TasteFacetType.EXPLICIT_DISLIKE.name -> -5.0
                TasteFacetType.EXPLICIT_LIKE.name -> 2.0 * facet.score
                else -> facet.score * conf
            }
            facet.facetKey to weight
        }
}
