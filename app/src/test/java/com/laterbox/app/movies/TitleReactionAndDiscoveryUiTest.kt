package com.laterbox.app.movies

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.TitleReaction
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.intelligence.IntelligenceSignalLogger
import com.laterbox.app.intelligence.UserUnderstandingRepository
import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.repository.MoviesRepositoryImpl
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.ui.MovieDetailScreen
import com.laterbox.app.movies.ui.MovieDetailUiState
import com.laterbox.app.movies.ui.MoviePosterCard
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleReactionAndDiscoveryUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun setTitleReaction_persistsCanonicalIdentityAcrossProviderDuplicatesAndLogsSignals() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val prefs = UserPreferencesRepository(context)
            val understandingRepo = UserUnderstandingRepository(
                intelligenceDao = db.intelligenceDao(),
                userPreferencesRepository = prefs,
                moviesDao = db.moviesDao(),
                savedLinkDao = db.savedLinkDao()
            )
            val signalLogger = IntelligenceSignalLogger(
                intelligenceDao = db.intelligenceDao(),
                userUnderstandingRepository = understandingRepo,
                userPreferencesRepository = prefs
            )
            val client = OkHttpClient()
            val repo = MoviesRepositoryImpl(
                moviesDao = db.moviesDao(),
                internetArchiveProvider = InternetArchiveMoviesProvider(client, "http://127.0.0.1:1"),
                tvMazeProvider = TvMazeSeriesProvider(client, "http://127.0.0.1:1"),
                tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }, baseUrl = "http://127.0.0.1:1"),
                fixtureProvider = DevelopmentMoviesFixtureProvider(),
                intelligenceSignalLogger = signalLogger,
                userUnderstandingRepository = understandingRepo
            )

            val movieBoxCopy = MovieTitleSummary(
                id = "mb_inception",
                providerId = "moviebox",
                kind = MediaKind.MOVIE,
                title = "Inception",
                releaseYear = 2010,
                imdbId = "tt1375666",
                genres = listOf("Sci-Fi", "Thriller"),
                summary = "A thief enters shared dreams to plant an idea.",
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                isPlayableHint = true
            )
            val tmdbDuplicate = MovieTitleSummary(
                id = "27205",
                providerId = "tmdb",
                kind = MediaKind.MOVIE,
                title = "Inception",
                releaseYear = 2010,
                imdbId = "tt1375666",
                genres = listOf("Sci-Fi", "Thriller"),
                sourceBadge = DataSourceBadge.LIVE_TMDB,
                isPlayableHint = false
            )

            // Resolve both provider entries into the local identity table (as catalogue/detail does)
            repo.recordRecentlyViewed(tmdbDuplicate)

            // React LIKE on MovieBox copy -> TMDB duplicate sharing tt1375666 resolves identically
            repo.setTitleReaction(movieBoxCopy, TitleReaction.LIKE)
            assertEquals(TitleReaction.LIKE, repo.observeTitleReaction("moviebox", "mb_inception").first())
            assertEquals(TitleReaction.LIKE, repo.observeTitleReaction("tmdb", "27205").first())

            // Replace with DISLIKE (mutually exclusive)
            repo.setTitleReaction(tmdbDuplicate, TitleReaction.DISLIKE)
            assertEquals(TitleReaction.DISLIKE, repo.observeTitleReaction("moviebox", "mb_inception").first())
            assertEquals(TitleReaction.DISLIKE, repo.observeTitleReaction("tmdb", "27205").first())

            val recentSignals = db.intelligenceDao().getRecentSignalsSnapshot(20)
            assertTrue(recentSignals.any { it.signalType == IntelligenceSignalLogger.SIGNAL_TITLE_LIKE })
            assertTrue(recentSignals.any { it.signalType == IntelligenceSignalLogger.SIGNAL_TITLE_DISLIKE })

            // Toggle same reaction to null clears reaction cleanly
            repo.setTitleReaction(movieBoxCopy, null)
            assertNull(repo.observeTitleReaction("moviebox", "mb_inception").first())
            assertNull(repo.observeTitleReaction("tmdb", "27205").first())
        } finally {
            db.close()
        }
    }

    @Test
    fun posterCardAndDetailScreenMoreMenu_renderSharedInsetFeedbackAndSyncMutuallyExclusiveState() {
        val sample = MovieTitleSummary(
            id = "mov_ui_1",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Arrival",
            releaseYear = 2016,
            rating = 7.9,
            genres = listOf("Sci-Fi", "Mystery"),
            summary = "A linguist works with alien visitors while processing grief.",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        var currentReaction by mutableStateOf<TitleReaction?>(null)
        var showDetail by mutableStateOf(false)

        composeTestRule.setContent {
            LaterboxTheme {
                if (!showDetail) {
                    MoviePosterCard(
                        item = sample,
                        onClick = {},
                        onToggleWatchlist = {},
                        titleReaction = currentReaction,
                        onSetTitleReaction = { _, next -> currentReaction = next }
                    )
                } else {
                    MovieDetailScreen(
                        uiState = MovieDetailUiState(
                            isLoading = false,
                            details = MediaDetails(
                                summary = sample,
                                fullDescription = "A linguist deciphers an alien language."
                            ),
                            playbackResolution = PlaybackResolution.Available(
                                title = "Arrival",
                                sources = listOf(
                                    PlaybackSource("s1", "https://example.com/arrival.mp4", "MP4", "720p", 720, providerName = "MovieBox")
                                )
                            ),
                            titleReaction = currentReaction
                        ),
                        onBackClick = {},
                        onRetry = {},
                        onToggleWatchlist = {},
                        onPlayClick = { _, _ -> },
                        onSelectSeason = {},
                        onOpenRelated = { _, _ -> },
                        onOpenExternalUrl = {},
                        onSetTitleReaction = { next -> currentReaction = next }
                    )
                }
            }
        }

        // Open card overflow menu and check inset divider + centered thumbs
        composeTestRule.onNodeWithTag("poster_actions_mov_ui_1").performClick()
        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_divider").assertIsDisplayed()
        val sectionBounds = composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_section").getBoundsInRoot()
        val dividerBounds = composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_divider").getBoundsInRoot()
        assertTrue("Expected inset divider on left", dividerBounds.left > sectionBounds.left)
        assertTrue("Expected inset divider on right", dividerBounds.right < sectionBounds.right)

        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_like").assertIsNotSelected()
        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_dislike").assertIsNotSelected()
        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_like").performClick()
        assertEquals(TitleReaction.LIKE, currentReaction)

        // Reopen menu -> Like shows selected; click Dislike -> switches mutually exclusively
        composeTestRule.onNodeWithTag("poster_actions_mov_ui_1").performClick()
        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_like").assertIsSelected()
        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_dislike").assertIsNotSelected()
        composeTestRule.onNodeWithTag("poster_feedback_mov_ui_1_dislike").performClick()
        assertEquals(TitleReaction.DISLIKE, currentReaction)

        // Switch to MovieDetailScreen -> shared reaction state is synced in detail More menu
        composeTestRule.runOnUiThread { showDetail = true }
        composeTestRule.onNodeWithTag("detail_more_actions_button").performClick()
        composeTestRule.onNodeWithTag("detail_feedback_mov_ui_1_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_feedback_mov_ui_1_divider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_feedback_mov_ui_1_like").assertIsNotSelected()
        composeTestRule.onNodeWithTag("detail_feedback_mov_ui_1_dislike").assertIsSelected()

        // Click Dislike again -> clears reaction to null
        composeTestRule.onNodeWithTag("detail_feedback_mov_ui_1_dislike").performClick()
        assertNull(currentReaction)
    }
}
