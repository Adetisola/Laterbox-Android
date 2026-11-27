package com.laterbox.app.intelligence

import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.SavedLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSuggesterTest {

    private fun collection(id: Long, name: String) = Collection(id = id, name = name, createdAt = 100L, updatedAt = 100L)

    private fun link(
        id: Long,
        title: String,
        url: String,
        domain: String,
        description: String? = null,
        collectionIds: List<Long> = emptyList(),
        isArchived: Boolean = false
    ) = SavedLink(
        id = id,
        originalUrl = url,
        normalizedUrl = url,
        title = title,
        description = description,
        domain = domain,
        collectionId = collectionIds.firstOrNull(),
        collectionIds = collectionIds,
        isArchived = isArchived
    )

    @Test
    fun suggestForLinks_matchesDomainAndTopicOverlapAndNeverSuggestsCategorizedLinks() {
        val designCol = collection(10L, "UI Inspiration")
        val cookingCol = collection(20L, "Weeknight Recipes")

        val existingDesignLink = link(
            id = 1L,
            title = "Figma Autolayout Deep Dive",
            url = "https://figma.com/blog/autolayout",
            domain = "figma.com",
            collectionIds = listOf(designCol.id)
        )
        val uncategorizedFigma = link(
            id = 2L,
            title = "Variables in Design Tokens",
            url = "https://figma.com/blog/variables",
            domain = "figma.com"
        )
        val uncategorizedCookingTopic = link(
            id = 3L,
            title = "Sourdough Baking Recipe Guide",
            url = "https://cooking.example.com/sourdough",
            domain = "cooking.example.com",
            description = "Simple dinner pasta and kitchen baking tips"
        )
        val alreadyCategorizedCopy = link(
            id = 4L,
            title = "Figma Kit",
            url = "https://figma.com/kit",
            domain = "figma.com",
            collectionIds = listOf(designCol.id)
        )

        val suggestions = CollectionSuggester.suggestForLinks(
            links = listOf(existingDesignLink, uncategorizedFigma, uncategorizedCookingTopic, alreadyCategorizedCopy),
            collections = listOf(designCol, cookingCol)
        )

        val byLinkId = suggestions.associateBy { it.link.id }
        assertEquals(designCol.id, byLinkId[uncategorizedFigma.id]?.collection?.id)
        assertTrue(byLinkId[uncategorizedFigma.id]?.rationale?.contains("figma.com") == true)
        assertEquals(cookingCol.id, byLinkId[uncategorizedCookingTopic.id]?.collection?.id)
        assertNull(byLinkId[alreadyCategorizedCopy.id])
        assertNull(byLinkId[existingDesignLink.id])
    }

    @Test
    fun suggestForLink_respectsDismissedPairs() {
        val techCol = collection(1L, "Engineering")
        val target = link(
            id = 99L,
            title = "Kotlin Coroutines Internals",
            url = "https://kotlinlang.org/coroutines",
            domain = "kotlinlang.org"
        )
        val existingTech = link(
            id = 2L,
            title = "Android Kotlin Flows",
            url = "https://kotlinlang.org/flow",
            domain = "kotlinlang.org",
            collectionIds = listOf(techCol.id)
        )

        val suggestion = CollectionSuggester.suggestForLink(
            targetLink = target,
            collections = listOf(techCol),
            allLinks = listOf(existingTech, target),
            dismissedPairs = emptySet()
        )
        assertNotNull(suggestion)
        assertEquals(techCol.id, suggestion!!.collection.id)

        val dismissedResult = CollectionSuggester.suggestForLink(
            targetLink = target,
            collections = listOf(techCol),
            allLinks = listOf(existingTech, target),
            dismissedPairs = setOf(target.id to techCol.id)
        )
        assertNull(dismissedResult)
    }
}
