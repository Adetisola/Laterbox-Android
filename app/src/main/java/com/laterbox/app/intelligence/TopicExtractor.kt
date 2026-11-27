package com.laterbox.app.intelligence

import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import java.net.URI
import java.util.Locale

data class ExtractedFacetCandidate(
    val facetKey: String,
    val displayLabel: String,
    val surfaceScope: IntelligenceSurfaceScope,
    val isMediaCue: Boolean = false
)

data class MediaCueResult(
    val isExplicitMediaCue: Boolean,
    val genres: List<String> = emptyList(),
    val preferredKind: MediaKind? = null,
    val cueReason: String? = null
)

/**
 * Curated, allowlisted non-sensitive topic & media facet extractor.
 *
 * Strictly enforces:
 * - Zero health, finance, legal, political, religion, or relationship inferences.
 * - Media-only Inbox -> Movies cross-signals (explicit media URLs, trailers, or watch-intent collections only).
 */
object TopicExtractor {

    private data class TopicRule(
        val key: String,
        val label: String,
        val keywords: Set<String>,
        val domains: Set<String> = emptySet(),
        val defaultScope: IntelligenceSurfaceScope = IntelligenceSurfaceScope.LIBRARY
    )

    private val SAFE_TOPICS: List<TopicRule> = listOf(
        TopicRule(
            key = "topic:technology",
            label = "Technology",
            keywords = setOf(
                "kotlin", "android", "software", "programming", "developer", "engineering",
                "coding", "typescript", "javascript", "python", "rust", "golang", "linux",
                "database", "compiler", "opensource", "cloud", "kubernetes", "docker",
                "backend", "frontend", "machine learning", "artificial intelligence", "llm"
            ),
            domains = setOf("github.com", "gitlab.com", "stackoverflow.com", "news.ycombinator.com", "developer.android.com", "kotlinlang.org")
        ),
        TopicRule(
            key = "topic:design",
            label = "Design",
            keywords = setOf(
                "design", "typography", "typeface", "figma", "layout", "branding",
                "illustration", "editorial", "ui", "ux", "product design", "visual"
            ),
            domains = setOf("dribbble.com", "behance.net", "figma.com")
        ),
        TopicRule(
            key = "topic:science",
            label = "Science",
            keywords = setOf(
                "science", "physics", "astronomy", "space", "nasa", "biology",
                "chemistry", "neuroscience", "climate", "quantum", "mathematics", "arxiv"
            ),
            domains = setOf("arxiv.org", "nasa.gov", "nature.com", "scientificamerican.com")
        ),
        TopicRule(
            key = "topic:cinema_tv",
            label = "Cinema & TV",
            keywords = setOf(
                "movie", "movies", "film", "films", "cinema", "television", "tv series",
                "screenplay", "director", "trailer", "box office", "anime", "documentary"
            ),
            domains = setOf("imdb.com", "themoviedb.org", "letterboxd.com", "rottentomatoes.com", "tvmaze.com", "justwatch.com", "myanimelist.net", "anilist.co"),
            defaultScope = IntelligenceSurfaceScope.ALL
        ),
        TopicRule(
            key = "topic:music_audio",
            label = "Music & Audio",
            keywords = setOf(
                "music", "album", "soundtrack", "podcast", "playlist", "vinyl",
                "concert", "jazz", "synth", "guitar", "audio"
            ),
            domains = setOf("open.spotify.com", "spotify.com", "bandcamp.com", "soundcloud.com")
        ),
        TopicRule(
            key = "topic:gaming",
            label = "Gaming",
            keywords = setOf(
                "gaming", "videogame", "video game", "indie game", "steam", "nintendo",
                "playstation", "xbox", "esports", "rpg", "roguelike"
            ),
            domains = setOf("store.steampowered.com", "itch.io", "ign.com")
        ),
        TopicRule(
            key = "topic:books_writing",
            label = "Books & Writing",
            keywords = setOf(
                "book", "books", "essay", "essays", "newsletter", "literature",
                "novel", "author", "writing", "reading"
            ),
            domains = setOf("goodreads.com", "substack.com")
        ),
        TopicRule(
            key = "topic:productivity",
            label = "Productivity",
            keywords = setOf(
                "productivity", "workflow", "pkm", "note taking", "automation",
                "habits", "focus", "knowledge management"
            )
        ),
        TopicRule(
            key = "topic:food_cooking",
            label = "Food & Cooking",
            keywords = setOf(
                "recipe", "recipes", "cooking", "baking", "coffee", "espresso",
                "culinary", "kitchen", "sourdough"
            )
        ),
        TopicRule(
            key = "topic:travel_culture",
            label = "Travel & Culture",
            keywords = setOf(
                "travel", "museum", "photography", "architecture", "history",
                "culture", "city guide", "language learning"
            )
        ),
        TopicRule(
            key = "topic:sports",
            label = "Sports",
            keywords = setOf(
                "football", "soccer", "basketball", "nba", "formula 1", "f1",
                "tennis", "marathon", "athletics", "champions league"
            )
        )
    )

    private val SENSITIVE_EXCLUSION_TOKENS: Set<String> = setOf(
        // Health / medical
        "health", "medical", "hospital", "clinic", "doctor", "symptom", "symptoms",
        "diagnosis", "medication", "prescription", "therapy", "therapist", "mental",
        "depression", "anxiety", "pregnancy", "fertility", "cancer", "diabetes",
        "surgery", "pharmacy", "vaccine", "illness", "disease",
        // Finance / tax / banking / crypto
        "finance", "banking", "bank", "mortgage", "loan", "debt", "credit", "tax",
        "taxes", "irs", "salary", "payroll", "invoice", "bills", "insurance",
        "crypto", "bitcoin", "ethereum", "trading", "stock", "portfolio", "bankruptcy",
        // Legal
        "legal", "lawsuit", "attorney", "lawyer", "court", "divorce", "custody",
        "settlement", "subpoena", "contract", "immigration", "visa",
        // Politics
        "politics", "political", "election", "elections", "vote", "voting", "ballot",
        "campaign", "senate", "parliament", "congress", "president", "partisan",
        // Religion / faith
        "religion", "religious", "church", "mosque", "temple", "synagogue",
        "prayer", "sermon", "bible", "quran", "torah", "faith",
        // Relationships / private life
        "breakup", "dating", "tinder", "bumble", "hinge", "marriage", "wedding",
        "relationship", "couples", "affair"
    )

    private val MEDIA_CUE_DOMAINS: Set<String> = setOf(
        "imdb.com",
        "m.imdb.com",
        "themoviedb.org",
        "tmdb.org",
        "rottentomatoes.com",
        "letterboxd.com",
        "justwatch.com",
        "tvmaze.com",
        "trakt.tv",
        "myanimelist.net",
        "anilist.co"
    )

    private val VIDEO_TRAILER_HOSTS: Set<String> = setOf(
        "youtube.com",
        "m.youtube.com",
        "youtu.be",
        "vimeo.com"
    )

    private val TRAILER_CUE_REGEX = Regex(
        pattern = "\\b(?:official\\s+(?:trailer|teaser)|movie\\s+trailer|series\\s+trailer|teaser\\s+trailer|season\\s+\\d+\\s+trailer|full\\s+movie|official\\s+clip|trailer)\\b",
        option = RegexOption.IGNORE_CASE
    )

    private val SERIES_CUE_REGEX = Regex(
        pattern = "\\b(?:tv\\s+series|tv\\s+show|season\\s+\\d+|episode\\s+\\d+|tvmaze|series\\s+trailer)\\b",
        option = RegexOption.IGNORE_CASE
    )

    private val MOVIE_CUE_REGEX = Regex(
        pattern = "\\b(?:movie|film|cinema|feature\\s+film|full\\s+movie)\\b",
        option = RegexOption.IGNORE_CASE
    )

    private val WATCH_COLLECTION_NAMES: Set<String> = setOf(
        "watchlist",
        "movies",
        "movie",
        "tv",
        "tv shows",
        "tv series",
        "cinema",
        "films",
        "film",
        "anime",
        "to watch",
        "watch later",
        "trailers"
    )

    private val MEDIA_GENRE_ALIASES: Map<String, String> = mapOf(
        "sci-fi" to "Sci-Fi",
        "scifi" to "Sci-Fi",
        "science fiction" to "Sci-Fi",
        "action" to "Action",
        "adventure" to "Adventure",
        "animation" to "Animation",
        "anime" to "Anime",
        "comedy" to "Comedy",
        "crime" to "Crime",
        "documentary" to "Documentary",
        "drama" to "Drama",
        "fantasy" to "Fantasy",
        "horror" to "Horror",
        "mystery" to "Mystery",
        "romance" to "Romance",
        "thriller" to "Thriller"
    )

    fun containsSensitiveText(vararg texts: String?): Boolean {
        val combined = texts.filterNotNull().joinToString(" ").lowercase(Locale.US)
        if (combined.isBlank()) return false
        val tokens = combined.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        return tokens.any { it in SENSITIVE_EXCLUSION_TOKENS }
    }

    fun isSafeFacetLabel(label: String): Boolean {
        return !containsSensitiveText(label)
    }

    fun extractLibraryTopics(
        url: String? = null,
        domain: String? = null,
        title: String? = null,
        description: String? = null,
        query: String? = null
    ): List<ExtractedFacetCandidate> {
        if (containsSensitiveText(url, domain, title, description, query)) {
            return emptyList()
        }
        val normalizedDomain = normalizeHost(domain ?: extractHost(url))
        val text = listOfNotNull(title, description, query)
            .joinToString(" ")
            .lowercase(Locale.US)
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

        val matched = mutableListOf<ExtractedFacetCandidate>()
        for (rule in SAFE_TOPICS) {
            val domainMatch = normalizedDomain.isNotBlank() && rule.domains.any { d ->
                normalizedDomain == d || normalizedDomain.endsWith(".$d")
            }
            val keywordMatch = rule.keywords.any { kw ->
                if (' ' in kw) {
                    text.contains(kw)
                } else {
                    kw in tokens
                }
            }
            if (domainMatch || keywordMatch) {
                matched.add(
                    ExtractedFacetCandidate(
                        facetKey = rule.key,
                        displayLabel = rule.label,
                        surfaceScope = rule.defaultScope,
                        isMediaCue = rule.key == "topic:cinema_tv"
                    )
                )
            }
        }
        return matched.distinctBy { it.facetKey }
    }

    /**
     * Strictly extracts media cues for Inbox -> Movies cross-signals.
     * General articles or work links NEVER return isExplicitMediaCue = true.
     */
    fun extractMediaCueFromLink(
        url: String,
        domain: String,
        title: String,
        description: String? = null,
        contentType: ContentType = ContentType.WEBSITE,
        collectionNames: List<String> = emptyList()
    ): MediaCueResult {
        if (containsSensitiveText(url, domain, title, description)) {
            return MediaCueResult(isExplicitMediaCue = false)
        }
        val host = normalizeHost(domain.ifBlank { extractHost(url) })
        val isMediaDatabaseHost = host in MEDIA_CUE_DOMAINS ||
            MEDIA_CUE_DOMAINS.any { host.endsWith(".$it") }
        val isVideoHost = contentType == ContentType.VIDEO ||
            host in VIDEO_TRAILER_HOSTS ||
            VIDEO_TRAILER_HOSTS.any { host.endsWith(".$it") }

        val text = "$title ${description.orEmpty()}".lowercase(Locale.US)
        val hasTrailerCue = isVideoHost && (
            TRAILER_CUE_REGEX.containsMatchIn(text) ||
                url.contains("trailer", ignoreCase = true)
            )
        val hasWatchCollection = collectionNames.any { col ->
            col.trim().lowercase(Locale.US) in WATCH_COLLECTION_NAMES
        }

        if (!isMediaDatabaseHost && !hasTrailerCue && !hasWatchCollection) {
            return MediaCueResult(isExplicitMediaCue = false)
        }

        val genres = mutableSetOf<String>()
        for ((alias, canonical) in MEDIA_GENRE_ALIASES) {
            val matched = if (' ' in alias || '-' in alias) {
                text.contains(alias)
            } else {
                Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            }
            if (matched) genres.add(canonical)
        }
        for (col in collectionNames) {
            val cleanCol = col.trim().lowercase(Locale.US)
            if (cleanCol == "anime") genres.add("Anime")
        }

        val preferredKind = when {
            host.contains("tvmaze") || SERIES_CUE_REGEX.containsMatchIn(text) -> MediaKind.TV_SERIES
            MOVIE_CUE_REGEX.containsMatchIn(text) -> MediaKind.MOVIE
            else -> null
        }

        val reason = when {
            isMediaDatabaseHost -> "Saved $host media link"
            hasTrailerCue -> "Saved trailer video"
            else -> "Saved in watch collection"
        }

        return MediaCueResult(
            isExplicitMediaCue = true,
            genres = genres.toList(),
            preferredKind = preferredKind,
            cueReason = reason
        )
    }

    fun extractMediaFacets(item: MovieTitleSummary): List<ExtractedFacetCandidate> {
        val facets = mutableListOf<ExtractedFacetCandidate>()
        for (rawGenre in item.genres) {
            val clean = rawGenre.trim()
            if (clean.isBlank() || clean.equals("All", ignoreCase = true)) continue
            facets.add(
                ExtractedFacetCandidate(
                    facetKey = genreFacetKey(clean),
                    displayLabel = clean,
                    surfaceScope = IntelligenceSurfaceScope.MOVIES,
                    isMediaCue = true
                )
            )
        }
        facets.add(
            ExtractedFacetCandidate(
                facetKey = kindFacetKey(item.kind),
                displayLabel = item.kind.label,
                surfaceScope = IntelligenceSurfaceScope.MOVIES,
                isMediaCue = true
            )
        )
        return facets.distinctBy { it.facetKey }
    }

    fun genreFacetKey(genre: String): String {
        val slug = genre.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return "genre:$slug"
    }

    fun kindFacetKey(kind: MediaKind): String = "kind:${kind.wireValue}"

    private fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { URI(url).host.orEmpty() }.getOrDefault("")
    }

    private fun normalizeHost(host: String): String =
        host.trim().lowercase(Locale.US).removePrefix("www.")
}
