package com.laterbox.app.intelligence

import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.CollectionWithCount
import com.laterbox.app.domain.model.SavedLink
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTieBreakerTest {

    private val nowMs = 1_800_000_000_000L

    private fun link(
        id: Long,
        title: String,
        url: String = "https://example.com/$id",
        domain: String = "example.com",
        description: String? = null,
        isRead: Boolean = false,
        createdAt: Long = nowMs - 1_000L
    ) = SavedLink(
        id = id,
        originalUrl = url,
        normalizedUrl = url,
        title = title,
        description = description,
        domain = domain,
        isRead = isRead,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun designLikeFacet() = UserTasteFacetEntity(
        facetKey = "topic:design",
        facetType = TasteFacetType.EXPLICIT_LIKE.name,
        surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
        displayLabel = "Design",
        score = 0.9,
        confidence = 1.0,
        provenance = "Pinned",
        evidenceSummary = "Pinned",
        isSuppressedByUser = false,
        firstObservedAtMs = nowMs - 5000L,
        lastReinforcedAtMs = nowMs - 1000L
    )

    @Test
    fun rankLinks_lexicalBucketStrictlyBeatsTasteFacetScore() {
        val exactTitleLink = link(
            id = 1,
            title = "Compose",
            domain = "example.com",
            isRead = true,
            createdAt = nowMs - 50_000L
        )
        val descOnlyDesignLink = link(
            id = 2,
            title = "Typography Showcase",
            domain = "figma.com",
            description = "Built with Compose tooling",
            isRead = false,
            createdAt = nowMs
        )
        val domainMatchLink = link(
            id = 3,
            title = "Weekly Notes",
            domain = "compose.example.org",
            createdAt = nowMs - 10_000L
        )

        val ranked = SearchTieBreaker.rankLinks(
            links = listOf(descOnlyDesignLink, domainMatchLink, exactTitleLink),
            query = "Compose",
            facets = listOf(designLikeFacet()),
            applyTieBreaker = true,
            nowMs = nowMs
        )

        // Tier 4 exact title > Tier 2 domain > Tier 1 description, even though id=2 matches EXPLICIT_LIKE Design + unread + newest
        assertEquals(listOf(exactTitleLink.id, domainMatchLink.id, descOnlyDesignLink.id), ranked.map { it.id })
    }

    @Test
    fun rankLinks_equalLexicalBucketUsesFacetThenUnreadThenRecency() {
        val readNoFacet = link(
            id = 1,
            title = "Guide to Layouts",
            domain = "example.org",
            isRead = true,
            createdAt = nowMs
        )
        val unreadNoFacetOlder = link(
            id = 2,
            title = "Guide to Layouts",
            domain = "example.net",
            isRead = false,
            createdAt = nowMs - 10_000L
        )
        val unreadWithDesignFacet = link(
            id = 3,
            title = "Guide to Layouts",
            domain = "figma.com",
            description = "Typography and product design layout tokens",
            isRead = false,
            createdAt = nowMs - 20_000L
        )

        val rankedEnabled = SearchTieBreaker.rankLinks(
            links = listOf(readNoFacet, unreadNoFacetOlder, unreadWithDesignFacet),
            query = "Guide to Layouts",
            facets = listOf(designLikeFacet()),
            applyTieBreaker = true,
            nowMs = nowMs
        )
        assertEquals(listOf(unreadWithDesignFacet.id, unreadNoFacetOlder.id, readNoFacet.id), rankedEnabled.map { it.id })

        // When applyTieBreaker = false (paused/kill-switched), falls back to lexical tier + recency
        val rankedDisabled = SearchTieBreaker.rankLinks(
            links = listOf(unreadWithDesignFacet, unreadNoFacetOlder, readNoFacet),
            query = "Guide to Layouts",
            facets = listOf(designLikeFacet()),
            applyTieBreaker = false,
            nowMs = nowMs
        )
        assertEquals(listOf(readNoFacet.id, unreadNoFacetOlder.id, unreadWithDesignFacet.id), rankedDisabled.map { it.id })
    }

    @Test
    fun rankCollections_lexicalMatchBeatsFacetAndOrdersEqualMatches() {
        val prefix = CollectionWithCount(Collection(1, "Design Systems", nowMs, nowMs - 5_000L), itemCount = 1)
        val exact = CollectionWithCount(Collection(2, "Design", nowMs, nowMs - 20_000L), itemCount = 1)
        val substring = CollectionWithCount(Collection(3, "ProductDesign Hub", nowMs, nowMs), itemCount = 10)

        val ranked = SearchTieBreaker.rankCollections(
            collections = listOf(substring, prefix, exact),
            query = "Design",
            facets = listOf(designLikeFacet()),
            applyTieBreaker = true,
            nowMs = nowMs
        )
        assertEquals(listOf(exact.collection.id, prefix.collection.id, substring.collection.id), ranked.map { it.collection.id })
    }
}
