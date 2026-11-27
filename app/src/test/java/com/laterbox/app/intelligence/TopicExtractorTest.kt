package com.laterbox.app.intelligence

import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicExtractorTest {

    @Test
    fun extractLibraryTopics_matchesCuratedNonSensitiveTaxonomy() {
        val techTopics = TopicExtractor.extractLibraryTopics(
            url = "https://kotlinlang.org/docs/coroutines-guide.html",
            domain = "kotlinlang.org",
            title = "Kotlin Coroutines & Android Architecture"
        )
        assertTrue(techTopics.any { it.facetKey == "topic:technology" && it.surfaceScope == IntelligenceSurfaceScope.LIBRARY })

        val designTopics = TopicExtractor.extractLibraryTopics(
            url = "https://figma.com/community/file/123",
            domain = "figma.com",
            title = "Editorial Typography & Layout Grid"
        )
        assertTrue(designTopics.any { it.facetKey == "topic:design" })

        val musicTopics = TopicExtractor.extractLibraryTopics(
            url = "https://open.spotify.com/album/abc",
            domain = "open.spotify.com",
            title = "Midnight Jazz Vinyl Sessions"
        )
        assertTrue(musicTopics.any { it.facetKey == "topic:music_audio" })
    }

    @Test
    fun extractLibraryTopics_blocksAllSensitiveCategories() {
        val sensitiveSamples = listOf(
            Triple("https://clinic.example.com/bill", "clinic.example.com", "Hospital Prescription & Diagnosis Notes"),
            Triple("https://irs.example.com/tax", "irs.example.com", "2026 Tax Returns & Salary Mortgage"),
            Triple("https://court.example.com/case", "court.example.com", "Divorce Custody Lawsuit Hearing"),
            Triple("https://vote.example.com/2026", "vote.example.com", "Senate Election Ballot Campaign"),
            Triple("https://church.example.com/sermon", "church.example.com", "Sunday Prayer & Bible Study"),
            Triple("https://dating.example.com/notes", "dating.example.com", "Breakup Reflection & Relationship Advice")
        )
        for ((url, domain, title) in sensitiveSamples) {
            val extracted = TopicExtractor.extractLibraryTopics(url = url, domain = domain, title = title)
            assertTrue("Expected zero facets for sensitive input '$title', got $extracted", extracted.isEmpty())
            val cue = TopicExtractor.extractMediaCueFromLink(url = url, domain = domain, title = title)
            assertFalse("Expected zero media cues for sensitive input '$title'", cue.isExplicitMediaCue)
        }
    }

    @Test
    fun extractMediaCueFromLink_isStrictlyMediaOnlyForInboxToMoviesCrossSignals() {
        // General articles, work links, or tech videos must NEVER leak into Movies cross-signals
        val articleCue = TopicExtractor.extractMediaCueFromLink(
            url = "https://martinfowler.com/articles/microservices.html",
            domain = "martinfowler.com",
            title = "Microservices Architecture & Engineering Action Guide",
            description = "Software systems drama and thriller debugging",
            contentType = ContentType.ARTICLE
        )
        assertFalse(articleCue.isExplicitMediaCue)

        val techVideoCue = TopicExtractor.extractMediaCueFromLink(
            url = "https://www.youtube.com/watch?v=xyz",
            domain = "youtube.com",
            title = "Kotlin Conf 2026 Keynote",
            description = "Deep dive into Android compiler engineering",
            contentType = ContentType.VIDEO
        )
        assertFalse(techVideoCue.isExplicitMediaCue)

        // Explicit IMDb/TMDB link IS a valid media cue
        val imdbCue = TopicExtractor.extractMediaCueFromLink(
            url = "https://www.imdb.com/title/tt1375666/",
            domain = "imdb.com",
            title = "Inception (2010) - Sci-Fi Thriller Movie",
            contentType = ContentType.WEBSITE
        )
        assertTrue(imdbCue.isExplicitMediaCue)
        assertTrue(imdbCue.genres.containsAll(listOf("Sci-Fi", "Thriller")))
        assertEquals(MediaKind.MOVIE, imdbCue.preferredKind)

        // Explicit YouTube trailer IS a valid media cue
        val trailerCue = TopicExtractor.extractMediaCueFromLink(
            url = "https://www.youtube.com/watch?v=trailer1",
            domain = "youtube.com",
            title = "Andor Season 2 Official Trailer (Sci-Fi TV Series)",
            contentType = ContentType.VIDEO
        )
        assertTrue(trailerCue.isExplicitMediaCue)
        assertTrue(trailerCue.genres.contains("Sci-Fi"))
        assertEquals(MediaKind.TV_SERIES, trailerCue.preferredKind)

        // Explicit watch-intent collection IS a valid media cue
        val watchCollectionCue = TopicExtractor.extractMediaCueFromLink(
            url = "https://example.com/show/solo-leveling",
            domain = "example.com",
            title = "Solo Leveling Action Fantasy",
            collectionNames = listOf("Watchlist", "Anime")
        )
        assertTrue(watchCollectionCue.isExplicitMediaCue)
        assertTrue(watchCollectionCue.genres.containsAll(listOf("Anime", "Action", "Fantasy")))
    }

    @Test
    fun extractMediaFacets_mapsMovieGenresAndKind() {
        val summary = MovieTitleSummary(
            id = "m1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            genres = listOf("Sci-Fi", "Thriller", "All")
        )
        val facets = TopicExtractor.extractMediaFacets(summary)
        assertEquals(
            setOf("genre:sci_fi", "genre:thriller", "kind:tv_series"),
            facets.map { it.facetKey }.toSet()
        )
        assertTrue(facets.all { it.isMediaCue && it.surfaceScope == IntelligenceSurfaceScope.MOVIES })
    }
}
