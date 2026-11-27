package com.laterbox.app.movies

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PersonalizedTitleRecommendation
import com.laterbox.app.movies.domain.model.SurprisePickResult
import com.laterbox.app.movies.ui.MoviesHomeUiState
import com.laterbox.app.movies.ui.MoviesScreen
import com.laterbox.app.ui.navigation.LaterboxDestination
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertTrue
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
    fun pickedForYou_rendersWhyThisRationale_andTuneTastesNavigatesToPersonalizationPrivacy() {
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
                    rationale = "Pinned taste: Sci-Fi"
                )
            ),
            surprisePick = SurprisePickResult(
                pick = samplePick,
                rationale = "Pinned taste: Sci-Fi",
                signalTags = listOf("Sci-Fi")
            )
        )

        var navigatedToPersonalization = false

        composeRule.setContent {
            LaterboxTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = LaterboxDestination.Movies.route
                ) {
                    composable(LaterboxDestination.Movies.route) {
                        MoviesScreen(
                            uiState = state,
                            onRefresh = {},
                            onOpenCatalogue = { _, _ -> },
                            onOpenDetail = { _, _ -> },
                            onOpenPlayer = { _, _, _, _ -> },
                            onToggleWatchlist = {},
                            onToggleSampleFixtures = {},
                            onOpenPersonalizationPrivacy = {
                                navController.navigate(LaterboxDestination.SettingsPersonalizationPrivacy.route)
                            }
                        )
                    }
                    composable(LaterboxDestination.SettingsPersonalizationPrivacy.route) {
                        navigatedToPersonalization = true
                        androidx.compose.material3.Text("Personalization & Privacy")
                    }
                }
            }
        }

        composeRule.onNodeWithTag("movies_home_list")
            .performScrollToNode(hasTestTag("movies_home_for_you_rail"))
        composeRule.onNodeWithText("Picked For You").assertIsDisplayed()
        composeRule.onNodeWithTag("movies_for_you_rationale_42").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("movies_home_list")
            .performScrollToNode(hasTestTag("movies_home_surprise_card"))
        composeRule.onNodeWithTag("movies_surprise_why_badge", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("movies_home_list")
            .performScrollToNode(hasTestTag("movies_home_for_you_rail"))
        composeRule.onNodeWithTag("movies_tune_tastes_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Personalization & Privacy").assertIsDisplayed()
        assertTrue(navigatedToPersonalization)
    }
}
