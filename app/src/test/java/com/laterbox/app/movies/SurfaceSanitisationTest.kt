package com.laterbox.app.movies

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.ai.AiFailure
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.media.data.adapters.ytdlp.FfmpegInitState
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpInitState
import com.laterbox.app.media.domain.model.FailureCode
import com.laterbox.app.media.share.LaterboxShareHelper
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.ui.formatPlayerErrorMessage
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.domain.YouTubeUrlParser
import com.laterbox.app.transcript.domain.YouTubeUrlTarget
import com.laterbox.app.ui.navigation.LaterboxDeepLinks
import com.laterbox.app.ui.settings.MediaDownloadsSettingsScreen
import com.laterbox.app.ui.theme.LaterboxTheme
import com.laterbox.app.update.UpdateFailureKind
import com.laterbox.app.update.UpdateNetworkSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurfaceSanitisationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val forbiddenUiTerms = listOf(
        "moviebox",
        "aoneroom",
        "hakunaymatata",
        "inmoviebox",
        "edgenext",
        "wefeed",
        "subject-api",
        "user-api",
        "play-info",
        "resource-position",
        "x-tr-signature",
        "x-client-token",
        "http ",
        "429",
        "403",
        "401",
        "428",
        "rate limit",
        "rate-limit",
        "rate limited",
        "throttle",
        "cooldown",
        "failover",
        "upstream",
        "cdn",
        "endpoint",
        "streaming server"
    )

    private fun assertCleanCopy(label: String, text: String) {
        val lower = text.lowercase()
        for (term in forbiddenUiTerms) {
            assertFalse("$label leaked forbidden term '$term': $text", lower.contains(term))
        }
    }

    @Test
    fun playerErrorFormatting_isCleanAcrossHttpStatuses() {
        val spec = DataSpec.Builder().setUri("https://bcdnxw.hakunaymatata.com/stream/1080.mp4").build()
        for (status in listOf(401, 403, 404, 410, 429, 500, 503)) {
            val cause = HttpDataSource.InvalidResponseCodeException(
                status,
                "Status $status",
                null,
                mapOf("Retry-After" to listOf("15")),
                spec,
                ByteArray(0)
            )
            val err = PlaybackException("HTTP $status", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            val msg = formatPlayerErrorMessage(err)
            assertCleanCopy("Player HTTP $status", msg)
        }
    }

    @Test
    fun failureCodesAndUpdateMessages_areCleanUserFacingCopy() {
        for (code in FailureCode.entries) {
            assertCleanCopy("FailureCode.${code.name}", code.userMessage)
        }
        for (kind in UpdateFailureKind.entries) {
            assertCleanCopy("UpdateFailureKind.${kind.name}", UpdateNetworkSupport.userMessage(kind, 429))
        }
        assertCleanCopy("AiFailure.RateLimited", AiFailure.RateLimited(429).userMessage)
        assertCleanCopy("AiFailure.AuthError", AiFailure.AuthError(401).userMessage)
        assertCleanCopy("AiFailure.ServerError", AiFailure.ServerError(500).userMessage)
        assertCleanCopy("Transcript.RATE_LIMITED", VideoTranscriptStatus.RATE_LIMITED.displayLabel)
    }

    @Test
    fun deepLinksAndShareHelpers_neverExposeMovieBox() {
        val movieUrl = LaterboxDeepLinks.buildTitleUrl("moviebox", "9168495427082149592", MediaKind.MOVIE)
        val seriesUrl = LaterboxDeepLinks.buildTitleUrl("moviebox", "123456", MediaKind.TV_SERIES)
        assertEquals("https://laterbox-website.vercel.app/movie/9168495427082149592", movieUrl)
        assertEquals("https://laterbox-website.vercel.app/series/123456", seriesUrl)
        assertCleanCopy("Movie deep link", movieUrl.orEmpty())
        assertCleanCopy("Series deep link", seriesUrl.orEmpty())

        val shareSummary = MovieTitleSummary(
            id = "9168495427082149592",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Inception",
            posterUrl = null,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        assertEquals("Laterbox", DataSourceBadge.LIVE_MOVIEBOX.displayLabel)
        val resolvedShare = LaterboxShareHelper.resolveTitleShareUrl(shareSummary)
        assertEquals(movieUrl, resolvedShare)
    }

    @Test
    fun mediaDownloadsSettingsScreen_hidesInternalTechnicalAndDiagnosticsEntries() {
        composeRule.setContent {
            LaterboxTheme {
                MediaDownloadsSettingsScreen(
                    preferences = UserPreferences(),
                    onMediaDownloadNetworkPolicyChange = {},
                    onAutoInspectMediaOnSaveChange = {},
                    onFastEngineEnabledChange = {},
                    isUpdatingYtDlp = false,
                    onUpdateYtDlpClick = {},
                    ytDlpState = YtDlpInitState.NotInitialized,
                    ffmpegState = FfmpegInitState.NotInitialized,
                    onSendPlaybackDiagnostics = {},
                    onRetryInitializeEngine = {},
                    onSetCustomExportDirectory = { _, _ -> },
                    onClearCustomExportDirectory = {},
                    onBackClick = {}
                )
            }
        }

        assertEquals(0, composeRule.onAllNodesWithText("Diagnostics").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Media Engine Diagnostics").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Send playback diagnostics to Laterbox").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Fast engine").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Media Extractor (yt-dlp)").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Media Stream Muxer (FFmpeg)").fetchSemanticsNodes().size)
    }

    @Test
    fun youtubeUrlParserAndContextCopy_neverExposeTechnicalHostTerms() {
        val invalid = YouTubeUrlParser.parse("https://")
        assertTrue(invalid is YouTubeUrlTarget.Invalid)
        assertCleanCopy("YouTubeUrlParser", (invalid as YouTubeUrlTarget.Invalid).reason)

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(ctx)
    }
}
