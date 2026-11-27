package com.laterbox.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalizationPrivacyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inspectorPauseTuneAndResetModelControlsWork() {
        var personalizationEnabled = true
        var assistantContextEnabled = false
        var resetCalled = false
        var suppressedKey: String? = null
        var likedGenre: String? = null

        val sampleFacets = listOf(
            UserTasteFacetEntity(
                facetKey = "genre:sci_fi",
                facetType = TasteFacetType.ENDURING_INTEREST.name,
                surfaceScope = IntelligenceSurfaceScope.MOVIES.name,
                displayLabel = "Sci-Fi",
                score = 0.82,
                confidence = 0.90,
                provenance = "Watched Arrival",
                evidenceSummary = "Watch history",
                includeInAssistantContext = false,
                isSuppressedByUser = false,
                firstObservedAtMs = 1000L,
                lastReinforcedAtMs = 2000L
            )
        )

        composeRule.setContent {
            LaterboxTheme {
                PersonalizationPrivacyScreen(
                    preferences = UserPreferences(
                        personalizationEnabled = personalizationEnabled,
                        assistantPersonalContextEnabled = assistantContextEnabled
                    ),
                    facets = sampleFacets,
                    signalCount = 14,
                    onPersonalizationEnabledChange = { personalizationEnabled = it },
                    onAssistantPersonalContextEnabledChange = { assistantContextEnabled = it },
                    onSetExplicitPreference = { _, label, _, _ -> likedGenre = label },
                    onToggleSuppressFacet = { key, _ -> suppressedKey = key },
                    onToggleIncludeInAssistant = { _, _ -> },
                    onDeleteFacet = {},
                    onRebuildFromLibrary = {},
                    onResetModel = { resetCalled = true },
                    onBackClick = {}
                )
            }
        }

        composeRule.onNodeWithTag("personalization_privacy_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("personalization_signal_count").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodes(androidx.compose.ui.test.hasText("Sci-Fi"))[1].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Why: Watched Arrival").performScrollTo().assertIsDisplayed()

        // Toggle Pause switch
        composeRule.onNodeWithTag("personalization_enabled_switch").performScrollTo().performClick()
        assertEquals(false, personalizationEnabled)

        // Pin a quick genre
        composeRule.onNodeWithText("Documentary").performScrollTo().performClick()
        assertEquals("Documentary", likedGenre)

        // Mute facet
        composeRule.onNodeWithText("Mute").performScrollTo().performClick()
        assertEquals("genre:sci_fi", suppressedKey)

        // Reset model requires confirmation dialog
        composeRule.onNodeWithTag("personalization_reset_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("personalization_confirm_reset_button").performClick()
        assertTrue(resetCalled)
    }
}
