package com.laterbox.app.ui.collections

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: LaterboxDatabase
    private lateinit var collectionRepository: CollectionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        collectionRepository = CollectionRepository(database.collectionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun collectionsViewModel_createRenameDeleteWorkflow() = runTest {
        val viewModel = CollectionsViewModel(collectionRepository)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }
            assertTrue(state.isEmpty)

            // Create collection
            viewModel.onCreateCollection("Design Ideas")
            state = awaitItem()
            while (state.collections.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(1, state.collections.size)
            assertEquals("Design Ideas", state.collections[0].collection.name)

            val collectionId = state.collections[0].collection.id

            // Rename collection
            viewModel.onRenameCollection(collectionId, "Design Inspiration")
            state = awaitItem()
            while (state.collections[0].collection.name != "Design Inspiration") {
                state = awaitItem()
            }
            assertEquals("Design Inspiration", state.collections[0].collection.name)

            // Delete collection
            viewModel.onDeleteCollection(collectionId)
            state = awaitItem()
            while (state.collections.isNotEmpty()) {
                state = awaitItem()
            }
            assertEquals(0, state.collections.size)
            assertTrue(state.isEmpty)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun collectionsViewModel_searchQueryFiltersLexicallyAndClears() = runTest {
        collectionRepository.createCollection("Design")
        collectionRepository.createCollection("Design Systems")
        collectionRepository.createCollection("Product Design Notes")
        collectionRepository.createCollection("Weekend Recipes")

        val prefsRepo = com.laterbox.app.data.preferences.UserPreferencesRepository(
            ApplicationProvider.getApplicationContext()
        )
        val viewModel = CollectionsViewModel(
            collectionRepository = collectionRepository,
            userUnderstandingRepository = com.laterbox.app.intelligence.UserUnderstandingRepository(
                intelligenceDao = database.intelligenceDao(),
                userPreferencesRepository = prefsRepo
            ),
            userPreferencesRepository = prefsRepo
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.collections.size < 4) {
                state = awaitItem()
            }

            viewModel.onSearchQueryChange("Design")
            state = awaitItem()
            while (state.searchQuery != "Design" || state.collections.size != 3) {
                state = awaitItem()
            }
            assertEquals(
                listOf("Design", "Design Systems", "Product Design Notes"),
                state.collections.map { it.collection.name }
            )

            viewModel.onSearchQueryChange("NoSuchShelf")
            state = awaitItem()
            while (state.searchQuery != "NoSuchShelf") {
                state = awaitItem()
            }
            assertTrue(state.collections.isEmpty())
            assertTrue(state.isSearchEmpty)

            viewModel.onClearSearch()
            state = awaitItem()
            while (state.searchQuery.isNotEmpty() || state.collections.size != 4) {
                state = awaitItem()
            }
            assertEquals(4, state.collections.size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
