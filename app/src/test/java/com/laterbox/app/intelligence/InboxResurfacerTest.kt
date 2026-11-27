package com.laterbox.app.intelligence

import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxResurfacerTest {

    private val nowMs = 1_800_000_000_000L
    private val dayMs = InboxResurfacer.DAY_MS

    private fun link(
        id: Long,
        title: String,
        url: String = "https://example.com/$id",
        domain: String = "example.com",
        description: String? = null,
        personalNote: String? = null,
        isRead: Boolean = false,
        isArchived: Boolean = false,
        ageDays: Long = 5L
    ) = SavedLink(
        id = id,
        originalUrl = url,
        normalizedUrl = url,
        title = title,
        description = description,
        domain = domain,
        contentType = ContentType.ARTICLE,
        personalNote = personalNote,
        isRead = isRead,
        isArchived = isArchived,
        createdAt = nowMs - (ageDays * dayMs),
        updatedAt = nowMs - (ageDays * dayMs)
    )

    private fun facet(
        key: String,
        label: String,
        type: TasteFacetType,
        scope: IntelligenceSurfaceScope = IntelligenceSurfaceScope.LIBRARY,
        score: Double = 0.85,
        suppressed: Boolean = false
    ) = UserTasteFacetEntity(
        facetKey = key,
        facetType = type.name,
        surfaceScope = scope.name,
        displayLabel = label,
        score = score,
        confidence = 0.9,
        provenance = "Test",
        evidenceSummary = "Test",
        includeInAssistantContext = false,
        isSuppressedByUser = suppressed,
        firstObservedAtMs = nowMs - 10 * dayMs,
        lastReinforcedAtMs = nowMs - dayMs
    )

    @Test
    fun selectSuggestions_requiresMinimumInboxLinks() {
        val links = listOf(
            link(1, "Old Design Guide", domain = "figma.com", ageDays = 6),
            link(2, "Old Kotlin Post", domain = "kotlinlang.org", ageDays = 7),
            link(3, "Another Link", ageDays = 8)
        )
        val result = InboxResurfacer.selectSuggestions(links, nowMs = nowMs)
        assertTrue(result.isEmpty())
    }

    @Test
    fun selectSuggestions_prioritizesActiveGoalAndNoteAndExcludesReadArchivedDislikedDismissed() {
        val freshUnread = link(1, "Fresh Unread", ageDays = 0)
        val readOld = link(2, "Already Read Design", domain = "dribbble.com", isRead = true, ageDays = 8)
        val archivedOld = link(3, "Archived Design", domain = "figma.com", isArchived = true, ageDays = 8)
        val dislikedTech = link(4, "Kotlin Compiler Internals", domain = "kotlinlang.org", ageDays = 5)
        val designGoalMatch = link(5, "Typography Grid Systems", domain = "figma.com", ageDays = 4)
        val notedArticle = link(6, "Quiet Reading Essay", personalNote = "Finish chapter 2", ageDays = 2)
        val dismissedOld = link(7, "Science Discovery", domain = "nasa.gov", ageDays = 6)

        val facets = listOf(
            facet("topic:design", "Design", TasteFacetType.ACTIVE_GOAL),
            facet("topic:technology", "Technology", TasteFacetType.EXPLICIT_DISLIKE),
            facet("topic:science", "Science", TasteFacetType.EXPLICIT_LIKE)
        )

        val suggestions = InboxResurfacer.selectSuggestions(
            inboxLinks = listOf(
                freshUnread,
                readOld,
                archivedOld,
                dislikedTech,
                designGoalMatch,
                notedArticle,
                dismissedOld
            ),
            facets = facets,
            dismissedLinkIds = setOf(dismissedOld.id),
            nowMs = nowMs,
            maxItems = 3
        )

        val ids = suggestions.map { it.link.id }
        assertEquals(listOf(designGoalMatch.id, notedArticle.id), ids)
        assertEquals("Matches your active Design focus", suggestions[0].rationale)
        assertTrue(suggestions[1].rationale.startsWith("Saved with your note"))
        assertFalse(dislikedTech.id in ids)
        assertFalse(dismissedOld.id in ids)
        assertFalse(freshUnread.id in ids)
    }

    @Test
    fun selectSuggestions_ignoresSuppressedFacetAndCapsAtMaxItems() {
        val links = (1L..6L).map { idx ->
            link(
                id = idx,
                title = "Longform Essay $idx",
                ageDays = idx + 3L
            )
        }
        val suppressedDesign = facet("topic:design", "Design", TasteFacetType.ACTIVE_GOAL, suppressed = true)
        val suggestions = InboxResurfacer.selectSuggestions(
            inboxLinks = links + link(7L, "Figma Design", domain = "figma.com", ageDays = 5),
            facets = listOf(suppressedDesign),
            nowMs = nowMs,
            maxItems = 2
        )

        assertEquals(2, suggestions.size)
        assertTrue(suggestions.none { it.rationale.contains("Design") })
        assertTrue(suggestions.all { it.rationale.contains("Unread") })
    }
}
