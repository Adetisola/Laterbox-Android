package com.laterbox.app.data.reader

import com.laterbox.app.domain.util.UrlNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max

sealed class ArticleBlock {
    data class Heading(val level: Int, val text: String) : ArticleBlock()
    data class Paragraph(val text: String) : ArticleBlock()
    data class Quote(val text: String) : ArticleBlock()
    data class CodeBlock(val text: String) : ArticleBlock()
    data class BulletItem(val text: String) : ArticleBlock()
}

data class ExtractedArticle(
    val url: String,
    val title: String,
    val byline: String?,
    val siteName: String?,
    val leadImageUrl: String?,
    val blocks: List<ArticleBlock>,
    val wordCount: Int,
    val estimatedReadMinutes: Int,
    val fetchedAt: Long,
    val fromCache: Boolean = false
)

/**
 * Local-first HTML article extractor and disk cache for Laterbox's distraction-free reader mode.
 */
object ArticleContentExtractor {

    const val STARTER_GUIDE_URL = "https://example.com/laterbox-guide"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT_MILLIS = 10_000
    private const val MAX_BODY_BYTES = 1536 * 1024 // 1.5 MB
    private const val CACHE_DIR_NAME = "reader_cache"
    private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    private val BOILERPLATE_EXACT = setOf(
        "subscribe",
        "sign up",
        "log in",
        "sign in",
        "read more",
        "share this article",
        "cookie policy",
        "privacy policy",
        "terms of service",
        "advertisement",
        "sponsored"
    )

    suspend fun fetchOrLoadCached(
        url: String,
        cacheDir: File?,
        forceRefresh: Boolean = false
    ): Result<ExtractedArticle> = withContext(Dispatchers.IO) {
        val normalizedInput = ensureHttpScheme(url)
        if (normalizedInput.equals(STARTER_GUIDE_URL, ignoreCase = true)) {
            return@withContext Result.success(buildStarterGuideArticle(normalizedInput))
        }
        val cacheFile = cacheDir?.let { cacheFileFor(it, normalizedInput) }
        val cached = if (cacheFile != null && cacheFile.exists()) {
            readCache(cacheFile)
        } else {
            null
        }

        if (!forceRefresh && cached != null && (System.currentTimeMillis() - cached.fetchedAt) <= CACHE_TTL_MS) {
            return@withContext Result.success(cached.copy(fromCache = true))
        }

        val liveResult = try {
            val response = Jsoup.connect(normalizedInput)
                .userAgent(BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(TIMEOUT_MILLIS)
                .maxBodySize(MAX_BODY_BYTES)
                .followRedirects(true)
                .ignoreHttpErrors(false)
                .execute()

            val finalUrl = response.url()?.toString() ?: normalizedInput
            parseHtml(response.body(), finalUrl)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }

        liveResult.onSuccess { extracted ->
            if (cacheFile != null) {
                writeCache(cacheFile, extracted)
            }
        }

        if (liveResult.isFailure && cached != null) {
            return@withContext Result.success(cached.copy(fromCache = true))
        }

        liveResult
    }

    fun parseHtml(
        html: String,
        baseUrl: String,
        fetchedAt: Long = System.currentTimeMillis()
    ): Result<ExtractedArticle> {
        val doc: Document = Jsoup.parse(html, baseUrl)
        val domain = UrlNormalizer.extractDomain(baseUrl)

        val title = sequenceOf(
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:title]")?.attr("content"),
            doc.selectFirst("article h1")?.text(),
            doc.selectFirst("main h1")?.text(),
            doc.selectFirst("h1")?.text(),
            doc.title()
        ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .firstOrNull()
            ?: domain

        val byline = sequenceOf(
            doc.selectFirst("meta[name=author]")?.attr("content"),
            doc.selectFirst("meta[property=article:author]")?.attr("content"),
            doc.selectFirst("[rel=author]")?.text(),
            doc.selectFirst(".byline")?.text(),
            doc.selectFirst(".author")?.text()
        ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() && s.length <= 100 } }
            .firstOrNull()

        val siteName = sequenceOf(
            doc.selectFirst("meta[property=og:site_name]")?.attr("content"),
            doc.selectFirst("meta[name=application-name]")?.attr("content")
        ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .firstOrNull()
            ?: domain

        val leadImageUrl = sequenceOf(
            doc.selectFirst("meta[property=og:image]")?.absUrl("content"),
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:image]")?.absUrl("content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("content")
        ).mapNotNull { raw ->
            raw?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.firstOrNull()

        // Strip non-content boilerplate before scoring & walking blocks
        val cleanDoc = doc.clone()
        cleanDoc.select(
            "script, style, noscript, svg, iframe, nav, header, footer, aside, form, button, input, select, textarea, " +
                "[role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true], " +
                ".advertisement, .ad, .ads, .promo, .cookie-banner, .cookie-notice, .newsletter, .comments, #comments, .related-posts, .share-buttons"
        ).remove()

        val rootContainer = selectPrimaryContainer(cleanDoc)
        val rawBlocks = extractBlocks(rootContainer, title)

        val words = rawBlocks.sumOf { block ->
            val text = when (block) {
                is ArticleBlock.Heading -> block.text
                is ArticleBlock.Paragraph -> block.text
                is ArticleBlock.Quote -> block.text
                is ArticleBlock.CodeBlock -> block.text
                is ArticleBlock.BulletItem -> block.text
            }
            text.split(Regex("\\s+")).count { it.isNotBlank() }
        }

        if (rawBlocks.size < 2 || words < 30) {
            return Result.failure(
                IllegalStateException("Reader mode couldn't extract enough readable article text for this page.")
            )
        }

        val readMinutes = max(1, ceil(words / 220.0).toInt())

        return Result.success(
            ExtractedArticle(
                url = baseUrl,
                title = title,
                byline = byline,
                siteName = siteName,
                leadImageUrl = leadImageUrl,
                blocks = rawBlocks,
                wordCount = words,
                estimatedReadMinutes = readMinutes,
                fetchedAt = fetchedAt,
                fromCache = false
            )
        )
    }

    private fun selectPrimaryContainer(doc: Document): Element {
        val prioritySelectors = listOf(
            "article",
            "main",
            "[role=main]",
            ".post-content",
            ".article-content",
            ".entry-content",
            ".article-body",
            "#article-body",
            ".story-body"
        )

        var bestCandidate: Element? = null
        var bestScore = -1

        for (selector in prioritySelectors) {
            val matches = doc.select(selector)
            for (el in matches) {
                val pTextLen = el.select("p").sumOf { it.text().trim().length }
                val linkTextLen = el.select("a").sumOf { it.text().trim().length }
                val totalLen = el.text().trim().length.coerceAtLeast(1)
                val linkDensity = linkTextLen.toDouble() / totalLen.toDouble()
                val score = if (linkDensity > 0.5) pTextLen / 3 else pTextLen
                if (score > bestScore && pTextLen >= 120) {
                    bestScore = score
                    bestCandidate = el
                }
            }
            if (bestCandidate != null && bestScore >= 300) {
                return bestCandidate
            }
        }

        if (bestCandidate != null) return bestCandidate

        // Fallback: score divs/sections with paragraphs
        val containers = doc.select("section, div")
        var fallbackBest: Element? = null
        var fallbackScore = 0
        for (el in containers) {
            val directP = el.children().filter { it.tagName() == "p" }.sumOf { it.text().trim().length }
            if (directP > fallbackScore) {
                fallbackScore = directP
                fallbackBest = el
            }
        }
        return fallbackBest ?: doc.body()
    }

    private fun extractBlocks(container: Element, pageTitle: String): List<ArticleBlock> {
        val blocks = mutableListOf<ArticleBlock>()
        val seen = HashSet<String>()
        val elements = container.select("h1, h2, h3, h4, p, blockquote, pre, li")

        for (el in elements) {
            val tag = el.tagName().lowercase()
            // Avoid duplicating <p> or <li> nested inside blockquote or pre
            if (tag == "p" && (el.parents().any { it.tagName().equals("blockquote", true) || it.tagName().equals("li", true) })) {
                continue
            }

            val rawText = if (tag == "pre") el.wholeText() else el.text()
            val text = if (tag == "pre") rawText.trimEnd() else rawText.replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) continue
            if (text.lowercase() in BOILERPLATE_EXACT) continue
            if (tag == "h1" && text.equals(pageTitle, ignoreCase = true) && blocks.isEmpty()) continue

            val block: ArticleBlock? = when (tag) {
                "h1" -> ArticleBlock.Heading(1, text).takeIf { text.length >= 3 }
                "h2" -> ArticleBlock.Heading(2, text).takeIf { text.length >= 3 }
                "h3", "h4" -> ArticleBlock.Heading(3, text).takeIf { text.length >= 3 }
                "blockquote" -> ArticleBlock.Quote(text).takeIf { text.length >= 12 }
                "pre" -> ArticleBlock.CodeBlock(text).takeIf { text.length >= 6 }
                "li" -> ArticleBlock.BulletItem(text).takeIf { text.length >= 8 }
                "p" -> ArticleBlock.Paragraph(text).takeIf { text.length >= 20 }
                else -> null
            } ?: continue

            val dedupeKey = "${block!!::class.simpleName}:${text.take(180)}"
            if (seen.add(dedupeKey)) {
                blocks.add(block)
            }
        }
        return blocks
    }

    internal fun serializeToJson(article: ExtractedArticle): String {
        val blocksJson = JSONArray()
        article.blocks.forEach { block ->
            val obj = JSONObject()
            when (block) {
                is ArticleBlock.Heading -> {
                    obj.put("type", "heading")
                    obj.put("level", block.level)
                    obj.put("text", block.text)
                }
                is ArticleBlock.Paragraph -> {
                    obj.put("type", "paragraph")
                    obj.put("text", block.text)
                }
                is ArticleBlock.Quote -> {
                    obj.put("type", "quote")
                    obj.put("text", block.text)
                }
                is ArticleBlock.CodeBlock -> {
                    obj.put("type", "code")
                    obj.put("text", block.text)
                }
                is ArticleBlock.BulletItem -> {
                    obj.put("type", "bullet")
                    obj.put("text", block.text)
                }
            }
            blocksJson.put(obj)
        }

        return JSONObject().apply {
            put("url", article.url)
            put("title", article.title)
            put("byline", article.byline)
            put("siteName", article.siteName)
            put("leadImageUrl", article.leadImageUrl)
            put("wordCount", article.wordCount)
            put("estimatedReadMinutes", article.estimatedReadMinutes)
            put("fetchedAt", article.fetchedAt)
            put("blocks", blocksJson)
        }.toString()
    }

    internal fun deserializeFromJson(raw: String): ExtractedArticle? {
        return try {
            val obj = JSONObject(raw)
            val blocksJson = obj.getJSONArray("blocks")
            val blocks = mutableListOf<ArticleBlock>()
            for (i in 0 until blocksJson.length()) {
                val item = blocksJson.getJSONObject(i)
                val text = item.optString("text", "")
                if (text.isBlank()) continue
                when (item.optString("type")) {
                    "heading" -> blocks.add(ArticleBlock.Heading(item.optInt("level", 2), text))
                    "paragraph" -> blocks.add(ArticleBlock.Paragraph(text))
                    "quote" -> blocks.add(ArticleBlock.Quote(text))
                    "code" -> blocks.add(ArticleBlock.CodeBlock(text))
                    "bullet" -> blocks.add(ArticleBlock.BulletItem(text))
                }
            }
            ExtractedArticle(
                url = obj.getString("url"),
                title = obj.getString("title"),
                byline = obj.optString("byline", "").takeIf { it.isNotBlank() },
                siteName = obj.optString("siteName", "").takeIf { it.isNotBlank() },
                leadImageUrl = obj.optString("leadImageUrl", "").takeIf { it.isNotBlank() },
                blocks = blocks,
                wordCount = obj.optInt("wordCount", 0),
                estimatedReadMinutes = obj.optInt("estimatedReadMinutes", 1).coerceAtLeast(1),
                fetchedAt = obj.optLong("fetchedAt", 0L),
                fromCache = true
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheFileFor(baseCacheDir: File, url: String): File {
        val dir = File(baseCacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$hex.json")
    }

    private fun readCache(file: File): ExtractedArticle? {
        return try {
            deserializeFromJson(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(file: File, article: ExtractedArticle) {
        try {
            file.writeText(serializeToJson(article), Charsets.UTF_8)
        } catch (_: Exception) {
            // Non-fatal cache write error
        }
    }

    private fun ensureHttpScheme(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun buildStarterGuideArticle(url: String): ExtractedArticle {
        val blocks = listOf(
            ArticleBlock.Paragraph(
                "Laterbox is your calm, local-first personal library. Every article, video, tutorial, or discussion you save stays on your device in SQLite with full-text search and zero tracking."
            ),
            ArticleBlock.Heading(2, "1. Capture from anywhere in 1 tap"),
            ArticleBlock.BulletItem("Share from any browser or social app using Android's Share Sheet and choose Laterbox."),
            ArticleBlock.BulletItem("Use the Save to Laterbox Quick Settings tile or the + Save dock button right inside your Inbox."),
            ArticleBlock.Heading(2, "2. Read distraction-free or watch offline"),
            ArticleBlock.Paragraph(
                "Open readable web articles right here in Reader Mode, extract YouTube video or playlist captions as Markdown, Text, or PDF, or inspect and download video and audio for offline playback."
            ),
            ArticleBlock.Quote(
                "You can set Reader Mode as your default card tap action anytime under Settings -> Reading & Behaviour."
            ),
            ArticleBlock.Heading(2, "3. Triage effortlessly"),
            ArticleBlock.BulletItem("Switch between Hero Cards, Compact List, and Editorial views with the layout button next to Search."),
            ArticleBlock.BulletItem("Use Unread, Media Ready, and Scheduled chips to focus, or Archive Read to clear finished items in one tap with Undo.")
        )
        val words = blocks.sumOf { block ->
            val text = when (block) {
                is ArticleBlock.Heading -> block.text
                is ArticleBlock.Paragraph -> block.text
                is ArticleBlock.Quote -> block.text
                is ArticleBlock.CodeBlock -> block.text
                is ArticleBlock.BulletItem -> block.text
            }
            text.split(Regex("\\s+")).count { it.isNotBlank() }
        }
        return ExtractedArticle(
            url = url,
            title = "Welcome to Laterbox: Read Offline, Organize & Remember",
            byline = "Laterbox Offline Guide",
            siteName = "Laterbox",
            leadImageUrl = null,
            blocks = blocks,
            wordCount = words,
            estimatedReadMinutes = 1,
            fetchedAt = System.currentTimeMillis(),
            fromCache = true
        )
    }
}
