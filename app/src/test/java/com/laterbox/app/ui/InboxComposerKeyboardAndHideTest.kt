package com.laterbox.app.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.laterbox.app.ui.components.BottomBarDestination
import com.laterbox.app.ui.components.FloatingChatComposer
import com.laterbox.app.ui.components.InboxActionDock
import com.laterbox.app.ui.components.LaterboxBottomBar
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxComposerKeyboardAndHideTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeKeyboardController : SoftwareKeyboardController {
        var showCalls = 0
        var hideCalls = 0
        override fun show() {
            showCalls++
        }
        override fun hide() {
            hideCalls++
        }
    }

    @Composable
    private fun InboxBottomChromeHarness(
        text: String,
        onTextChange: (String) -> Unit,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        isImeVisible: Boolean,
        onSend: (String) -> Unit,
        onAddLinkClick: () -> Unit = {}
    ) {
        val colors = LaterboxTheme.colors
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val collapseInboxComposer = {
            focusManager.clearFocus()
            keyboardController?.hide()
            onExpandedChange(false)
        }
        val showHandsetBottomBar = !(expanded && isImeVisible)

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Single fixed composition slot matching LaterboxNavGraph:
                // FloatingChatComposer never moves between branches when showHandsetBottomBar changes.
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
                                onTextChange = onTextChange,
                                onSend = {
                                    val prompt = text.trim()
                                    if (prompt.isNotBlank()) {
                                        onTextChange("")
                                        collapseInboxComposer()
                                        onSend(prompt)
                                    }
                                },
                                onAddLinkClick = onAddLinkClick
                            )
                        }
                    } else {
                        InboxActionDock(
                            assistantEnabled = true,
                            onAskClick = { onExpandedChange(true) },
                            onSaveClick = onAddLinkClick
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

    @Test
    fun inboxComposer_keepsFocusAndDraft_whenBottomBarHidesForIme_andCollapsesViaSimpleX() {
        var draftText by mutableStateOf("")
        var isExpanded by mutableStateOf(false)
        var isImeVisible by mutableStateOf(false)
        val sentPrompts = mutableListOf<String>()
        var addLinkClicks = 0
        val fakeKeyboard = FakeKeyboardController()

        composeTestRule.setContent {
            LaterboxTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides fakeKeyboard) {
                    InboxBottomChromeHarness(
                        text = draftText,
                        onTextChange = { draftText = it },
                        expanded = isExpanded,
                        onExpandedChange = { isExpanded = it },
                        isImeVisible = isImeVisible,
                        onSend = { sentPrompts.add(it) },
                        onAddLinkClick = { addLinkClicks++ }
                    )
                }
            }
        }

        // 1. Collapsed state shows dock and bottom bar
        composeTestRule.onNodeWithTag("inbox_action_dock").assertIsDisplayed()
        composeTestRule.onNodeWithTag("laterbox_bottom_bar").assertIsDisplayed()

        // 2. Tap Ask Laterbox -> expanded composer + simple accessible X button (no "Hide" text pill)
        composeTestRule.onNodeWithTag("inbox_dock_ask_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(isExpanded)
        composeTestRule.onNodeWithTag("floating_chat_composer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("inbox_composer_hide_button")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Hide assistant composer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide").assertDoesNotExist()

        // 3. Focus input, type before IME transition
        val inputNode = composeTestRule.onNodeWithTag("floating_chat_input")
        val initialSemanticsId = inputNode.fetchSemanticsNode().id
        inputNode.performClick()
        composeTestRule.waitForIdle()
        inputNode.assertIsFocused()

        inputNode.performTextInput("Find my ")
        composeTestRule.waitForIdle()
        assertEquals("Find my ", draftText)

        // 4. Simulate Android IME becoming visible, hiding LaterboxBottomBar
        isImeVisible = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("laterbox_bottom_bar").assertDoesNotExist()

        // BasicTextField node, focus, and text are preserved cleanly without disposal!
        val nodeWhileImeOpen = composeTestRule.onNodeWithTag("floating_chat_input")
        assertEquals(initialSemanticsId, nodeWhileImeOpen.fetchSemanticsNode().id)
        nodeWhileImeOpen.assertIsFocused()

        // Continuing to type while bottom bar is hidden appends smoothly
        nodeWhileImeOpen.performTextInput("saved articles")
        composeTestRule.waitForIdle()
        assertEquals("Find my saved articles", draftText)
        assertEquals(
            "Find my saved articles",
            nodeWhileImeOpen.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        )

        // 5. Simulate IME closing without collapsing composer -> bottom bar returns cleanly, draft stays
        isImeVisible = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("laterbox_bottom_bar").assertIsDisplayed()
        assertEquals("Find my saved articles", draftText)

        // 6. Tapping X hides keyboard, clears focus, collapses composer to dock, and preserves draft
        composeTestRule.onNodeWithTag("inbox_composer_hide_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(isExpanded)
        assertTrue("Expected hide() to be invoked when collapsing composer", fakeKeyboard.hideCalls >= 1)
        composeTestRule.onNodeWithTag("inbox_action_dock").assertIsDisplayed()
        composeTestRule.onNodeWithTag("floating_chat_composer").assertDoesNotExist()

        // Re-opening restores preserved draft
        composeTestRule.onNodeWithTag("inbox_dock_ask_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(isExpanded)
        assertEquals(
            "Find my saved articles",
            composeTestRule.onNodeWithTag("floating_chat_input")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text
        )

        // "+ Add link" action inside expanded composer still triggers
        composeTestRule.onNodeWithTag("composer_add_link_button").performClick()
        assertEquals(1, addLinkClicks)

        // Sending clears draft and collapses
        composeTestRule.onNodeWithTag("composer_send_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("Find my saved articles"), sentPrompts)
        assertEquals("", draftText)
        assertFalse(isExpanded)
    }

    @Test
    fun inboxComposer_backHandlerClearFocusPattern_resetsFocusWithoutLosingDraft() {
        var draftText by mutableStateOf("Existing query")
        var isExpanded by mutableStateOf(true)
        val fakeKeyboard = FakeKeyboardController()

        composeTestRule.setContent {
            LaterboxTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides fakeKeyboard) {
                    InboxBottomChromeHarness(
                        text = draftText,
                        onTextChange = { draftText = it },
                        expanded = isExpanded,
                        onExpandedChange = { isExpanded = it },
                        isImeVisible = true,
                        onSend = {}
                    )
                }
            }
        }

        val inputNode = composeTestRule.onNodeWithTag("floating_chat_input")
        inputNode.performClick()
        composeTestRule.waitForIdle()
        inputNode.assertIsFocused()

        composeTestRule.onNodeWithTag("inbox_composer_hide_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(isExpanded)
        assertEquals("Existing query", draftText)

        composeTestRule.onNodeWithTag("inbox_dock_ask_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("floating_chat_input").assertIsNotFocused()
    }
}
