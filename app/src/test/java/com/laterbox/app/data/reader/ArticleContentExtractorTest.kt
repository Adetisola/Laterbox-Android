package com.laterbox.app.data.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArticleContentExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseHtml_extractsCleanArticleBlocksAndMetadata_andStripsBoilerplate() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Fallback Title</title>
                <meta property="og:title" content="Deep Focus on Mobile UI" />
                <meta property="og:site_name" content="Design Weekly" />
                <meta name="author" content="Ada Lovelace" />
                <meta property="og:image" content="https://example.com/hero.jpg" />
            </head>
            <body>
                <nav><a href="/home">Home</a><a href="/about">About</a></nav>
                <div class="cookie-banner"><p>Cookie Policy</p></div>
                <article>
                    <h1>Deep Focus on Mobile UI</h1>
                    <p>Mobile reading interfaces thrive when visual noise steps out of the way and lets clear typography breathe across small screens.</p>
                    <h2>Typography Matters</h2>
                    <p>Generous line height, balanced column widths, and predictable contrast reduce eye fatigue during longer evening reading sessions.</p>
                    <blockquote>Good typography is invisible when reading feels effortless.</blockquote>
                    <ul>
                        <li>Keep primary actions obvious and above the fold</li>
                        <li>Strip sticky banners, trackers, and cookie prompts</li>
                    </ul>
                    <pre>val spacing = 16.dp</pre>
                </article>
                <footer><p>Copyright 2026 Design Weekly</p></footer>
                <script>console.log("tracking")</script>
            </body>
            </html>
        """.trimIndent()

        val result = ArticleContentExtractor.parseHtml(html, "https://example.com/posts/deep-focus")
        assertTrue(result.isSuccess)
        val article = result.getOrThrow()

        assertEquals("Deep Focus on Mobile UI", article.title)
        assertEquals("Design Weekly", article.siteName)
        assertEquals("Ada Lovelace", article.byline)
        assertEquals("https://example.com/hero.jpg", article.leadImageUrl)
        assertFalse(article.blocks.any { it.toString().contains("Cookie Policy") })
        assertFalse(article.blocks.any { it.toString().contains("Copyright 2026") })
        assertTrue(article.blocks.any { it is ArticleBlock.Heading && it.text == "Typography Matters" })
        assertTrue(article.blocks.any { it is ArticleBlock.Quote })
        assertTrue(article.blocks.any { it is ArticleBlock.BulletItem })
        assertTrue(article.blocks.any { it is ArticleBlock.CodeBlock })
        assertTrue(article.wordCount >= 40)
        assertEquals(1, article.estimatedReadMinutes)
    }

    @Test
    fun parseHtml_rejectsThinOrJsShellPages() {
        val thinHtml = """
            <html><head><title>App Shell</title></head>
            <body><div id="root"></div><noscript>Please enable JavaScript.</noscript></body></html>
        """.trimIndent()

        val result = ArticleContentExtractor.parseHtml(thinHtml, "https://example.com/app")
        assertTrue(result.isFailure)
    }

    @Test
    fun serializeAndDeserialize_preservesAllBlockTypesAndMetadata() {
        val original = ExtractedArticle(
            url = "https://example.com/read",
            title = "Cached Article",
            byline = "Tim Gabe",
            siteName = "Laterbox Journal",
            leadImageUrl = "https://example.com/lead.png",
            blocks = listOf(
                ArticleBlock.Heading(2, "Section One"),
                ArticleBlock.Paragraph("This is a sample paragraph saved offline for instant distraction-free reading."),
                ArticleBlock.Quote("Simplicity scales better than clutter."),
                ArticleBlock.BulletItem("Local-first storage"),
                ArticleBlock.CodeBlock("fun readOffline() = true")
            ),
            wordCount = 250,
            estimatedReadMinutes = 2,
            fetchedAt = 1_700_000_000_000L,
            fromCache = false
        )

        val json = ArticleContentExtractor.serializeToJson(original)
        val restored = ArticleContentExtractor.deserializeFromJson(json)

        assertNotNull(restored)
        assertEquals(original.url, restored?.url)
        assertEquals(original.title, restored?.title)
        assertEquals(original.byline, restored?.byline)
        assertEquals(original.siteName, restored?.siteName)
        assertEquals(original.leadImageUrl, restored?.leadImageUrl)
        assertEquals(original.blocks, restored?.blocks)
        assertEquals(2, restored?.estimatedReadMinutes)
        assertTrue(restored?.fromCache == true)
    }
}
