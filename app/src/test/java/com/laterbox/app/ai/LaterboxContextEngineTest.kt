package com.laterbox.app.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ActivityEventRepository
import com.laterbox.app.data.repository.ActivityEventType
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
import com.laterbox.app.media.data.db.entity.DownloadedAssetEntity
import com.laterbox.app.media.domain.model.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaterboxContextEngineTest {

    private lateinit var context: Context
    private lateinit var db: LaterboxDatabase
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var activityRepo: ActivityEventRepository
    private lateinit var engine: LaterboxContextEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefsRepo = UserPreferencesRepository(context)
        activityRepo = ActivityEventRepository(db.activityEventDao())
        engine = LaterboxContextEngine(
            database = db,
            activityEventRepository = activityRepo,
            userPreferencesRepository = prefsRepo,
            ytDlpRuntimeManager = YtDlpRuntimeManager(context),
            appContext = context
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun buildContextForPrompt_ranksRelevantLinksWithinBoundsAndRedactsSecrets() = runTest {
        prefsRepo.setGeminiApiKey("AIzaSySuperSecretKey1234567890123456")
        val linkDao = db.savedLinkDao()
        for (i in 1..15) {
            linkDao.insertLink(
                SavedLinkEntity(
                    originalUrl = "https://example.com/item/$i",
                    normalizedUrl = "https://example.com/item/$i",
                    title = if (i == 14) "Kotlin Coroutines Deep Dive" else "General Article $i",
                    domain = "example.com",
                    contentType = ContentType.ARTICLE
                )
            )
        }

        activityRepo.recordEvent(
            type = ActivityEventType.INSPECTION_FAILED,
            title = "Failed in /data/user/0/com.laterbox.app/files/secret",
            detail = "x-goog-api-key=AIzaSyAnotherSecretKey9999999999999"
        )

        val built = engine.buildContextForPrompt("Find my saved kotlin coroutines link")
        assertTrue(built.contextBlock.contains("Kotlin Coroutines Deep Dive"))
        val linkMatches = Regex("\\[linkId=\\d+\\]").findAll(built.contextBlock).count()
        assertTrue("Expected at most 10 links in context, got $linkMatches", linkMatches <= 10)
        assertFalse(built.contextBlock.contains("AIzaSy"))
        assertFalse(built.contextBlock.contains("/data/user/0/"))
    }

    @Test
    fun buildContextForPrompt_reportsLiveTranscriptAndDocumentLibraryCapabilitiesWithoutOutdatedClaims() = runTest {
        prefsRepo.setCustomExportDirectory("content://com.android.externalstorage.documents/tree/primary%3ATranscripts", "Transcripts")
        repeat(2) { idx ->
            db.downloadedAssetDao().insertAsset(
                DownloadedAssetEntity(
                    id = "doc_$idx",
                    savedLinkId = null,
                    sourcePlatform = "YouTube Transcript",
                    originalSourceUrl = "https://www.youtube.com/watch?v=vid$idx",
                    title = "Transcript_$idx.md",
                    mimeType = "text/markdown",
                    mediaType = MediaType.DOCUMENT.name,
                    localPath = "document/Transcript_$idx.md",
                    fileSize = 256L,
                    checksum = "hash_$idx",
                    createdAt = 1700000000000L + idx
                )
            )
        }

        val built = engine.buildContextForPrompt("Can you extract a YouTube video transcript and export PDF?")
        assertTrue(built.contextBlock.contains("Live App Capabilities & Runtime Status:"))
        assertTrue(built.contextBlock.contains("YouTube Transcript Extraction"))
        assertTrue(built.contextBlock.contains("Markdown (.md), Plain Text (.txt), PDF (.pdf)"))
        assertTrue(built.contextBlock.contains("Transcript clipboard copy"))
        assertTrue(built.contextBlock.contains("Downloads Library (Documents): Supported (2 saved documents)"))
        assertTrue(built.contextBlock.contains("Transcripts"))
        assertTrue(built.contextBlock.contains("CONTENT GROUNDING NOTE"))
        assertTrue(built.systemInstruction.contains("OPEN_YOUTUBE_TRANSCRIPTS"))
        assertFalse(built.systemInstruction.contains("does NOT transcribe videos"))
        assertFalse(built.contextBlock.contains("video transcript is not stored locally"))
    }
}
