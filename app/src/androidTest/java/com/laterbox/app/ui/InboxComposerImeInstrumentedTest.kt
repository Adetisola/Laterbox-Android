package com.laterbox.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laterbox.app.ui.components.BottomBarDestination
import com.laterbox.app.ui.components.FloatingChatComposer
import com.laterbox.app.ui.components.InboxActionDock
import com.laterbox.app.ui.components.LaterboxBottomBar
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Android instrumentation test verifying real WindowInsets.ime visibility and
 * BasicTextField focus retention when LaterboxBottomBar hides upon IME opening.
 */
@RunWith(AndroidJUnit4::class)
class InboxComposerImeInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun expandedInboxComposer_retainsFocusAndRealImeWhenBottomBarHides_andClosesOnX() {
        var text by mutableStateOf("")
        var expanded by mutableStateOf(false)

        composeTestRule.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        composeTestRule.setContent {
            LaterboxTheme {
                val colors = LaterboxTheme.colors
                val density = LocalDensity.current
                val focusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current
                val isImeVisible = WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
                val showHandsetBottomBar = !(expanded && isImeVisible)

                val collapseInboxComposer = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    expanded = false
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            if (expanded) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                .clip(CircleShape)
                                                .clickable(
                                                    role = Role.Button,
                                                    onClickLabel = "Hide assistant composer",
                                                    onClick = collapseInboxComposer
                                                )
                                                .testTag("inbox_composer_hide_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = colors.surfaceElevated.copy(alpha = 0.94f),
                                                border = BorderStroke(1.dp, colors.borderSubtle),
                                                shadowElevation = 2.dp,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        painter = painterResource(id = LaterboxIcons.Close),
                                                        contentDescription = "Hide assistant composer",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = colors.textSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    FloatingChatComposer(
                                        text = text,
                                        onTextChange = { text = it },
                                        onSend = {
                                            text = ""
                                            collapseInboxComposer()
                                        }
                                    )
                                }
                            } else {
                                InboxActionDock(
                                    assistantEnabled = true,
                                    onAskClick = { expanded = true },
                                    onSaveClick = {}
                                )
                            }
                        }

                        if (showHandsetBottomBar) {
                            LaterboxBottomBar(
                                currentDestination = BottomBarDestination.INBOX,
                                onDestinationSelected = {}
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(
                                        WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)
                                    )
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("inbox_dock_ask_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("floating_chat_input").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            val insets = ViewCompat.getRootWindowInsets(composeTestRule.activity.window.decorView)
            insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        composeTestRule.onNodeWithTag("floating_chat_input").assertIsFocused()
        composeTestRule.onNodeWithTag("floating_chat_input").performTextInput("Hello IME")
        composeTestRule.waitForIdle()
        assertTrue(text == "Hello IME")

        composeTestRule.onNodeWithTag("inbox_composer_hide_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(expanded)
        composeTestRule.onNodeWithTag("laterbox_bottom_bar").assertIsDisplayed()
    }
}
