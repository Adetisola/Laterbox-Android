package com.laterbox.app.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.laterbox.app.remoteconfig.RemoteConfigLayoutHelper
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
class AdaptiveAppUpdateModalTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun monitorRecommendationModal_showsLogoUpdateAvailableAndAllowsSessionDismissal() {
        var dismissed = false
        var postponedVersion: String? = null
        val policy = RemoteConfigLayoutHelper.AdaptiveUpdatePromptPolicy(
            mode = RemoteConfigLayoutHelper.AdaptiveUpdateModalMode.AVAILABLE,
            targetVersionCode = 205,
            targetTag = "v1.0.67",
            minimumSupportedVersionCode = 198,
            minimumSupportedTag = "v1.0.65",
            customMessage = "New improvements are ready.",
        )

        composeTestRule.setContent {
            LaterboxTheme(darkTheme = true) {
                AppUpdateDialog(
                    updateInfo = null,
                    policyPrompt = policy,
                    onResolvePolicyUpdate = { UpdateCheckResult.UpToDate("1.0.67") },
                    onDismiss = { dismissed = true },
                    onPostpone = { postponedVersion = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("adaptive_update_modal_available").assertIsDisplayed()
        composeTestRule.onNodeWithTag("adaptive_update_modal_logo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Update Available").assertIsDisplayed()
        composeTestRule.onNodeWithText("v1.0.67").assertIsDisplayed()
        composeTestRule.onNodeWithText("New improvements are ready.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("adaptive_update_modal_later_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("adaptive_update_modal_later_button").performClick()

        assertTrue(dismissed)
        assertEquals("1.0.67", postponedVersion)
    }

    @Test
    fun enforceBlockingModal_showsLogoUpdateRequiredHidesLaterAndResolvesOnUpdateNow() {
        var resolvedCalls = 0
        val policy = RemoteConfigLayoutHelper.AdaptiveUpdatePromptPolicy(
            mode = RemoteConfigLayoutHelper.AdaptiveUpdateModalMode.REQUIRED,
            targetVersionCode = 205,
            targetTag = "v1.0.67",
            minimumSupportedVersionCode = 198,
            minimumSupportedTag = "v1.0.65",
            customMessage = "",
        )

        composeTestRule.setContent {
            LaterboxTheme(darkTheme = true) {
                AppUpdateDialog(
                    updateInfo = null,
                    policyPrompt = policy,
                    onResolvePolicyUpdate = {
                        resolvedCalls++
                        UpdateCheckResult.NetworkError("Offline")
                    },
                    onDismiss = {},
                    onPostpone = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("adaptive_update_modal_required").assertIsDisplayed()
        composeTestRule.onNodeWithTag("adaptive_update_modal_logo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Update Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("v1.0.67").assertIsDisplayed()
        composeTestRule.onNodeWithTag("adaptive_update_modal_later_button").assertDoesNotExist()

        composeTestRule.onNodeWithTag("adaptive_update_modal_primary_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, resolvedCalls)
    }
}
