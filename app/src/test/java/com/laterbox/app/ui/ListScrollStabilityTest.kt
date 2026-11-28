package com.laterbox.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.preferences.InboxViewMode
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.ui.components.SavedLinkCard
import com.laterbox.app.ui.components.savedLinkCardContentType
import com.laterbox.app.ui.inbox.InboxScreen
import com.laterbox.app.ui.inbox.InboxUiState
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListScrollStabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun savedLinkCardContentType_mapsViewModesAndImageAvailability() {
        val withImage = SavedLink(
            id = 1L,
            originalUrl = "https://example.com/1",
            normalizedUrl = "https://example.com/1",
            title = "With Image",
            domain = "example.com",
            previewImageUrl = "https://example.com/cover.jpg",
            contentType = ContentType.ARTICLE
        )
        val withoutImage = withImage.copy(id = 2L, previewImageUrl = null)
        val blankImage = withImage.copy(id = 3L, previewImageUrl = "   ")

        assertEquals("link_hero", savedLinkCardContentType(withImage, InboxViewMode.HERO))
        assertEquals("link_editorial", savedLinkCardContentType(withImage, InboxViewMode.EDITORIAL))
        assertEquals("link_compact", savedLinkCardContentType(withImage, InboxViewMode.COMPACT))

        assertEquals("link_text", savedLinkCardContentType(withoutImage, InboxViewMode.HERO))
        assertEquals("link_editorial", savedLinkCardContentType(withoutImage, InboxViewMode.EDITORIAL))
        assertEquals("link_text", savedLinkCardContentType(withoutImage, InboxViewMode.COMPACT))
        assertEquals("link_text", savedLinkCardContentType(blankImage, InboxViewMode.HERO))
    }

    @Test
    fun savedLinkCard_heroModeReservesFixed16x9ImageHeightWithoutLayoutCollapse() {
        val heroCardOne = SavedLink(
            id = 10L,
            originalUrl = "https://example.com/a",
            normalizedUrl = "https://example.com/a",
            title = "Fixed Ratio Hero Article",
            domain = "example.com",
            previewImageUrl = "https://example.com/cover-a.jpg",
            contentType = ContentType.ARTICLE,
            createdAt = 1_700_000_000_000L
        )
        val heroCardTwo = heroCardOne.copy(
            id = 11L,
            previewImageUrl = "https://example.com/cover-b.jpg"
        )
        val textOnlyCard = heroCardOne.copy(
            id = 12L,
            previewImageUrl = null
        )

        composeTestRule.setContent {
            LaterboxTheme {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SavedLinkCard(
                        link = heroCardOne,
                        onClick = {},
                        onQuickOpenClick = {},
                        viewMode = InboxViewMode.HERO,
                        modifier = Modifier.testTag("hero_card_1")
                    )
                    SavedLinkCard(
                        link = heroCardTwo,
                        onClick = {},
                        onQuickOpenClick = {},
                        viewMode = InboxViewMode.HERO,
                        modifier = Modifier.testTag("hero_card_2")
                    )
                    SavedLinkCard(
                        link = textOnlyCard,
                        onClick = {},
                        onQuickOpenClick = {},
                        viewMode = InboxViewMode.HERO,
                        modifier = Modifier.testTag("text_card")
                    )
                }
            }
        }

        val boundsOne = composeTestRule.onNodeWithTag("hero_card_1").getUnclippedBoundsInRoot()
        val boundsTwo = composeTestRule.onNodeWithTag("hero_card_2").getUnclippedBoundsInRoot()
        val boundsText = composeTestRule.onNodeWithTag("text_card").getUnclippedBoundsInRoot()

        val heightOne = (boundsOne.bottom - boundsOne.top).value
        val heightTwo = (boundsTwo.bottom - boundsTwo.top).value
        val heightText = (boundsText.bottom - boundsText.top).value

        assertEquals(heightOne, heightTwo, 1f)
        // A 320.dp wide 16:9 image slot adds 180.dp of fixed vertical height over a text-only card.
        val addedHeight = heightOne - heightText
        assertTrue(
            "Expected fixed 16:9 hero media slot height (~180dp) even before decode, added=$addedHeight",
            addedHeight >= 170f
        )
    }

    @Test
    fun linkRepository_inboxLinks_suppressesDuplicateEmissionsForTimestampOnlyUpdates() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = Executor { runnable -> runnable.run() }
        val db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()

        try {
            val dao = db.savedLinkDao()
            val repo = LinkRepository(dao)

            repo.inboxLinks.test {
                val initial = awaitItem()
                assertTrue(initial.isEmpty())

                val insertedId = dao.insertLink(
                    SavedLinkEntity(
                        originalUrl = "https://example.com/scroll-test",
                        normalizedUrl = "https://example.com/scroll-test",
                        title = "Initial Title",
                        domain = "example.com",
                        createdAt = 1_000L,
                        updatedAt = 1_000L
                    )
                )

                val firstWithItem = awaitItem()
                assertEquals(1, firstWithItem.size)
                assertEquals("Initial Title", firstWithItem.first().title)

                // Touching only internal updatedAt must not emit a duplicate UI list update.
                val stored = dao.getEntityById(insertedId)!!
                dao.updateLink(stored.copy(updatedAt = 9_999L))
                expectNoEvents()

                // Changing a visible card field must emit immediately.
                dao.updateLink(dao.getEntityById(insertedId)!!.copy(title = "Updated Visible Title", updatedAt = 10_500L))
                val afterTitleChange = awaitItem()
                assertEquals("Updated Visible Title", afterTitleChange.first().title)

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun inboxScreen_viewModeToggle_preservesScrolledIndexAndRendersMetadataBannerInList() {
        val sampleLinks = (1..25).map { idx ->
            SavedLink(
                id = idx.toLong(),
                originalUrl = "https://example.com/$idx",
                normalizedUrl = "https://example.com/$idx",
                title = "Scroll Link $idx",
                domain = "example.com",
                previewImageUrl = "https://example.com/img-$idx.jpg",
                contentType = ContentType.ARTICLE,
                createdAt = 1_700_000_000_000L - (idx * 60_000L)
            )
        }
        val listState = LazyListState()
        var uiState by mutableStateOf(
            InboxUiState(
                links = sampleLinks,
                isLoading = false,
                hasCompletedOnboarding = true,
                viewMode = InboxViewMode.HERO,
                pendingMetadataCount = 2
            )
        )

        composeTestRule.setContent {
            LaterboxTheme {
                Box(modifier = Modifier.width(360.dp).height(640.dp)) {
                    InboxScreen(
                        uiState = uiState,
                        listState = listState,
                        onSearchQueryChange = {},
                        onClearSearch = {},
                        onFilterContentType = {},
                        onFilterDateRange = {},
                        onLinkClick = {},
                        onQuickOpenClick = {},
                        onAddLinkClick = {},
                        onSettingsClick = {},
                        onToggleViewMode = {
                            val nextMode = when (uiState.viewMode) {
                                InboxViewMode.HERO -> InboxViewMode.COMPACT
                                InboxViewMode.COMPACT -> InboxViewMode.EDITORIAL
                                InboxViewMode.EDITORIAL -> InboxViewMode.HERO
                            }
                            uiState = uiState.copy(viewMode = nextMode)
                        },
                        onRefresh = {},
                        onCompleteOnboarding = {},
                        onToggleRead = {},
                        onArchive = {},
                        onDelete = {},
                        onMenuClick = {},
                        bottomComposerHeightPx = 180
                    )
                }
            }
        }

        // Metadata sync banner lives inside LazyColumn as a keyed item rather than the collapsible header.
        composeTestRule.onNodeWithText("Enriching 2 links in the background…").assertIsDisplayed()

        composeTestRule.runOnUiThread {
            runBlocking {
                listState.scrollToItem(10, 24)
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(
            "Expected scrolled index >= 8 before toggling view mode, was ${listState.firstVisibleItemIndex}",
            listState.firstVisibleItemIndex >= 8
        )

        composeTestRule.onNodeWithContentDescription("Hero view (tap for compact)").performClick()
        composeTestRule.waitForIdle()

        assertEquals(InboxViewMode.COMPACT, uiState.viewMode)
        assertTrue(
            "Expected scroll anchor preserved after view mode toggle, was ${listState.firstVisibleItemIndex}",
            listState.firstVisibleItemIndex >= 8
        )

        // Clearing background enrichment banner while scrolled must keep scroll near anchor cleanly.
        uiState = uiState.copy(pendingMetadataCount = 0)
        composeTestRule.waitForIdle()
        assertTrue(
            "Expected scroll preserved after background banner dismissal, was ${listState.firstVisibleItemIndex}",
            listState.firstVisibleItemIndex >= 7
        )
    }
}
