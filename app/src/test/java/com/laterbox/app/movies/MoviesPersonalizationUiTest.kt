package com.laterbox.app.movies

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PersonalizedTitleRecommendation
import com.laterbox.app.movies.domain.model.SurprisePickResult
import com.laterbox.app.movies.ui.MoviesHomeUiState
import com.laterbox.app.movies.ui.MoviesScreen
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp")
class MoviesPersonalizationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pickedForYou_hidesWhyThisRationale_andOmitsTuneTastesButtons() {
        val samplePick = MovieTitleSummary(
            id = "42",
            providerId = "ia",
            kind = MediaKind.MOVIE,
            title = "Orbital Horizon",
            releaseYear = 2024,
            rating = 8.3,
            genres = listOf("Sci-Fi"),
            sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
            isPlayableHint = true
        )
        val state = MoviesHomeUiState(
            isLoading = false,
            feed = MoviesHomeFeed(
                sections = listOf(BrowseSection(id = "trending", title = "Trending", items = listOf(samplePick)))
            ),
            isPersonalizationActive = true,
            personalizedForYouRail = listOf(
                PersonalizedTitleRecommendation(
                    item = samplePick,
                    score = 9.2,
                    rationale = "Sci-Fi"
                )
            ),
            surprisePick = SurprisePickResult(
                pick = samplePick,
                rationale = "Something different to explore",
                signalTags = listOf("Sci-Fi")
            )
        )

        composeRule.setContent {
            LaterboxTheme {
                MoviesScreen(
                    uiState = state,
                    onRefresh = {},
                    onOpenCatalogue = { _, _ -> },
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onToggleWatchlist = {},
                    onToggleSampleFixtures = {}
                )
            }
        }

        composeRule.onNodeWithTag("movies_home_list")
            .performScrollToNode(hasTestTag("movies_home_for_you_rail"))
        composeRule.onNodeWithText("Picked For You").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("movies_for_you_rationale_42").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("movies_tune_tastes_button").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("movies_home_list")
            .performScrollToNode(hasTestTag("movies_home_surprise_card"))
        composeRule.onNodeWithTag("movies_surprise_why_badge", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("movies_surprise_tune_tastes").fetchSemanticsNodes().size)
    }
}
