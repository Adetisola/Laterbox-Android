package com.laterbox.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.laterbox.app.domain.model.Collection
import com.laterbox.app.domain.model.CollectionWithCount
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.intelligence.LinkCollectionSuggestion
import com.laterbox.app.intelligence.ResurfacedLinkSuggestion
import com.laterbox.app.ui.collections.CollectionsScreen
import com.laterbox.app.ui.collections.CollectionsUiState
import com.laterbox.app.ui.inbox.InboxScreen
import com.laterbox.app.ui.inbox.InboxUiState
import com.laterbox.app.ui.sheets.SavedItemDetailSheet
import com.laterbox.app.ui.sheets.SavedItemDetailUiState
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Phase3UiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun link(id: Long, title: String, domain: String = "example.com") = SavedLink(
        id = id,
        originalUrl = "https://$domain/$id",
        normalizedUrl = "https://$domain/$id",
        title = title,
        domain = domain
    )

    @Test
    fun inboxScreen_rendersResurfacerStripAndCollectionBannerCallbacks() {
        val col = Collection(10L, "Design Systems")
        val targetLink = link(42L, "Figma Tokens Guide", domain = "figma.com")
        val resurfaced = ResurfacedLinkSuggestion(
            link = targetLink,
            rationale = "Matches your active Design focus"
        )
        val colSuggestion = LinkCollectionSuggestion(
            link = targetLink,
            collection = col,
            rationale = "Same domain (figma.com) as \"Design Systems\"",
            score = 5.0
        )

        var openedResurfaceId: Long? = null
        var dismissedResurfaceId: Long? = null
        var acceptedSuggestion: LinkCollectionSuggestion? = null
        var dismissedSuggestion: LinkCollectionSuggestion? = null

        composeRule.setContent {
            LaterboxTheme {
                InboxScreen(
                    uiState = InboxUiState(
                        links = listOf(targetLink),
                        isLoading = false,
                        resurfacedLinks = listOf(resurfaced),
                        collectionSuggestions = listOf(colSuggestion)
                    ),
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onFilterContentType = {},
                    onFilterDateRange = {},
                    onLinkClick = {},
                    onQuickOpenClick = {},
                    onAddLinkClick = {},
                    onSettingsClick = {},
                    onToggleViewMode = {},
                    onRefresh = {},
                    onCompleteOnboarding = {},
                    onToggleRead = {},
                    onArchive = {},
                    onDelete = {},
                    onOpenResurfacedLink = { openedResurfaceId = it.link.id },
                    onDismissResurfacedLink = { dismissedResurfaceId = it.id },
                    onAcceptCollectionSuggestion = { acceptedSuggestion = it },
                    onDismissCollectionSuggestion = { dismissedSuggestion = it }
                )
            }
        }

        composeRule.onNodeWithTag("inbox_resurfacer_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("inbox_resurface_card_42", useUnmergedTree = true).assertIsDisplayed().performClick()
        assertEquals(42L, openedResurfaceId)

        composeRule.onNodeWithTag("inbox_resurface_dismiss_42", useUnmergedTree = true).performClick()
        assertEquals(42L, dismissedResurfaceId)

        composeRule.onNodeWithTag("inbox_collection_suggestion_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("inbox_collection_accept_42", useUnmergedTree = true).performClick()
        assertEquals(col.id, acceptedSuggestion?.collection?.id)

        composeRule.onNodeWithTag("inbox_collection_dismiss_42", useUnmergedTree = true).performClick()
        assertEquals(targetLink.id, dismissedSuggestion?.link?.id)
    }

    @Test
    fun collectionsScreen_rendersSearchFieldAndEmptySearchState() {
        var latestQuery = ""
        var clearClicked = false
        composeRule.setContent {
            LaterboxTheme {
                CollectionsScreen(
                    uiState = CollectionsUiState(
                        collections = emptyList(),
                        isLoading = false,
                        searchQuery = "missing"
                    ),
                    onCollectionClick = {},
                    onOpenCreateDialog = {},
                    onCloseCreateDialog = {},
                    onCreateCollection = {},
                    onOpenRenameDialog = {},
                    onCloseRenameDialog = {},
                    onRenameCollection = { _, _ -> },
                    onOpenDeleteDialog = {},
                    onCloseDeleteDialog = {},
                    onDeleteCollection = {},
                    onSearchQueryChange = { latestQuery = it },
                    onClearSearch = { clearClicked = true }
                )
            }
        }

        composeRule.onNodeWithTag("collections_search_field").assertIsDisplayed()
        composeRule.onNodeWithText("No matching collections").assertIsDisplayed()
        composeRule.onNodeWithText("Clear search").performClick()
        assertTrue(clearClicked)

        composeRule.onNodeWithTag("laterbox_search_input", useUnmergedTree = true).performTextReplacement("Design")
        assertEquals("Design", latestQuery)
    }

    @Test
    fun savedItemDetailSheet_rendersAndTriggersCollectionSuggestionChip() {
        val target = link(7L, "Kotlin Flows Deep Dive", domain = "kotlinlang.org")
        val suggestedCol = Collection(5L, "Android Engineering")
        val suggestion = LinkCollectionSuggestion(
            link = target,
            collection = suggestedCol,
            rationale = "Same domain (kotlinlang.org)",
            score = 4.5
        )
        var accepted: LinkCollectionSuggestion? = null
        var dismissed: LinkCollectionSuggestion? = null

        composeRule.setContent {
            val density = LocalDensity.current
            val sheetState = remember(density) {
                SheetState(
                    skipPartiallyExpanded = true,
                    density = density,
                    initialValue = SheetValue.Expanded
                )
            }
            LaterboxTheme {
                SavedItemDetailSheet(
                    uiState = SavedItemDetailUiState(
                        link = target,
                        editedTitle = target.title,
                        collections = listOf(suggestedCol),
                        suggestedCollection = suggestion
                    ),
                    sheetState = sheetState,
                    onTitleChange = {},
                    onNoteChange = {},
                    onToggleCollection = {},
                    onAcceptCollectionSuggestion = { accepted = it },
                    onDismissCollectionSuggestion = { dismissed = it },
                    onCreateCollection = {},
                    onSaveEdits = {},
                    onToggleRead = {},
                    onToggleArchive = {},
                    onDelete = {},
                    onQuickOpen = {},
                    onRetryMetadata = {},
                    onDismissRequest = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail_collection_suggestion_chip", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(suggestedCol.id, accepted?.collection?.id)

        composeRule.onNodeWithTag("detail_collection_suggestion_dismiss", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(target.id, dismissed?.link?.id)
    }
}
