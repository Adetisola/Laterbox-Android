package com.laterbox.app.intelligence

import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.SavedLink
import java.util.Locale

data class LinkCollectionSuggestion(
    val link: SavedLink,
    val collection: Collection,
    val rationale: String,
    val score: Double
)

/**
 * Deterministic on-device suggester matching uncategorized saved links against existing
 * collections using domain overlap, token similarity with links already in each collection,
 * and allowlisted [TopicExtractor] tags. Never moves links automatically.
 */
object CollectionSuggester {

    private const val MIN_SUGGESTION_SCORE = 2.0

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "your", "have", "about",
        "how", "what", "why", "when", "where", "are", "was", "were", "will", "can", "all",
        "com", "org", "net", "www", "http", "https", "html", "link", "page", "article"
    )

    private data class CollectionProfile(
        val collection: Collection,
        val domains: Set<String>,
        val tokens: Set<String>,
        val nameTokens: Set<String>,
        val topicKeys: Set<String>,
        val nameTopicKeys: Set<String>
    )

    fun suggestForLinks(
        links: List<SavedLink>,
        collections: List<Collection>,
        categorizedLinks: List<SavedLink> = links.filter { it.collectionIds.isNotEmpty() || it.collectionId != null },
        dismissedPairs: Set<Pair<Long, Long>> = emptySet(),
        maxSuggestions: Int = 3
    ): List<LinkCollectionSuggestion> {
        if (maxSuggestions <= 0 || collections.isEmpty() || links.isEmpty()) return emptyList()

        val profiles = buildProfiles(collections, categorizedLinks)
        val uncategorized = links.filter {
            !it.isArchived && it.collectionIds.isEmpty() && it.collectionId == null
        }
        if (uncategorized.isEmpty()) return emptyList()

        return uncategorized
            .mapNotNull { link -> bestMatchForLink(link, profiles, dismissedPairs) }
            .sortedWith(
                compareByDescending<LinkCollectionSuggestion> { it.score }
                    .thenByDescending { it.link.createdAt }
                    .thenBy { it.link.id }
            )
            .take(maxSuggestions)
    }

    fun suggestForLink(
        targetLink: SavedLink,
        collections: List<Collection>,
        allLinks: List<SavedLink>,
        dismissedPairs: Set<Pair<Long, Long>> = emptySet()
    ): LinkCollectionSuggestion? {
        if (collections.isEmpty()) return null
        if (targetLink.collectionIds.isNotEmpty() || targetLink.collectionId != null) return null
        val profiles = buildProfiles(
            collections = collections,
            categorizedLinks = allLinks.filter { it.id != targetLink.id && (it.collectionIds.isNotEmpty() || it.collectionId != null) }
        )
        return bestMatchForLink(targetLink, profiles, dismissedPairs)
    }

    private fun buildProfiles(
        collections: List<Collection>,
        categorizedLinks: List<SavedLink>
    ): List<CollectionProfile> {
        val linksByCollectionId = mutableMapOf<Long, MutableList<SavedLink>>()
        for (link in categorizedLinks) {
            val ids = (link.collectionIds + listOfNotNull(link.collectionId)).distinct()
            for (cid in ids) {
                linksByCollectionId.getOrPut(cid) { mutableListOf() }.add(link)
            }
        }

        return collections.map { col ->
            val colLinks = linksByCollectionId[col.id].orEmpty()
            val domains = colLinks.map { normalizeDomain(it.domain) }.filter { it.isNotBlank() }.toSet()
            val nameTokens = tokenize(col.name)
            val contentTokens = colLinks.flatMap { tokenize("${it.title} ${it.description.orEmpty()} ${it.siteName.orEmpty()}") }.toSet()
            val nameTopicKeys = TopicExtractor.extractLibraryTopics(title = col.name).map { it.facetKey }.toSet()
            val linkTopicKeys = colLinks.flatMap {
                TopicExtractor.extractLibraryTopics(
                    url = it.originalUrl,
                    domain = it.domain,
                    title = it.title,
                    description = it.description
                ).map { topic -> topic.facetKey }
            }.toSet()
            CollectionProfile(
                collection = col,
                domains = domains,
                tokens = nameTokens + contentTokens,
                nameTokens = nameTokens,
                topicKeys = nameTopicKeys + linkTopicKeys,
                nameTopicKeys = nameTopicKeys
            )
        }
    }

    private fun bestMatchForLink(
        link: SavedLink,
        profiles: List<CollectionProfile>,
        dismissedPairs: Set<Pair<Long, Long>>
    ): LinkCollectionSuggestion? {
        val linkDomain = normalizeDomain(link.domain)
        val linkTokens = tokenize("${link.title} ${link.description.orEmpty()} ${link.siteName.orEmpty()} $linkDomain")
        val linkTopics = TopicExtractor.extractLibraryTopics(
            url = link.originalUrl,
            domain = link.domain,
            title = link.title,
            description = link.description
        )
        val linkTopicKeys = linkTopics.map { it.facetKey }.toSet()

        var best: LinkCollectionSuggestion? = null
        for (profile in profiles) {
            if ((link.id to profile.collection.id) in dismissedPairs) continue

            var score = 0.0
            val domainMatched = linkDomain.isNotBlank() && linkDomain in profile.domains
            if (domainMatched) score += 3.5

            val sharedTopics = linkTopics.filter { it.facetKey in profile.topicKeys }
            if (sharedTopics.isNotEmpty()) {
                score += sharedTopics.size * 2.5
            }

            val sharedNameTokens = linkTokens.intersect(profile.nameTokens)
            val sharedAllTokens = linkTokens.intersect(profile.tokens)
            if (sharedNameTokens.isNotEmpty()) {
                score += minOf(sharedNameTokens.size, 3) * 1.8
            } else if (sharedAllTokens.size >= 2) {
                score += minOf(sharedAllTokens.size, 4) * 1.1
            }

            // Explicit media cue fallback for watch-intent collection names
            if (link.contentType == com.laterbox.app.domain.model.ContentType.VIDEO &&
                profile.nameTopicKeys.contains("topic:cinema_tv") &&
                linkTopicKeys.contains("topic:cinema_tv")
            ) {
                score += 1.0
            }

            if (score < MIN_SUGGESTION_SCORE) continue

            val rationale = when {
                domainMatched ->
                    "Same domain ($linkDomain) as \"${profile.collection.name}\""
                sharedTopics.isNotEmpty() ->
                    "Matches ${sharedTopics.first().displayLabel} topic for \"${profile.collection.name}\""
                sharedNameTokens.isNotEmpty() ->
                    "Matches collection name \"${profile.collection.name}\""
                else ->
                    "Similar keywords to links in \"${profile.collection.name}\""
            }

            val candidate = LinkCollectionSuggestion(
                link = link,
                collection = profile.collection,
                rationale = rationale,
                score = score
            )
            if (best == null || candidate.score > best.score ||
                (candidate.score == best.score && candidate.collection.id < best.collection.id)
            ) {
                best = candidate
            }
        }
        return best
    }

    private fun normalizeDomain(domain: String): String =
        domain.trim().lowercase(Locale.US).removePrefix("www.")

    private fun tokenize(text: String): Set<String> =
        text.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
}
