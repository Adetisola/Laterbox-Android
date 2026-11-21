package com.laterbox.app.ui.components

import com.laterbox.app.ui.navigation.LaterboxDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualTabDockResolverTest {

    @Test
    fun resolveForRoute_returnsContextualSpecOnlyForInboxCollectionsAndMovies() {
        val inbox = ContextualTabDockResolver.resolveForRoute(LaterboxDestination.Inbox.route)
        assertNotNull(inbox)
        assertEquals(TabQuickActionKind.SAVE_LINK, inbox!!.kind)
        assertEquals("Save Link", inbox.primaryLabel)
        assertEquals(LaterboxIcons.Plus, inbox.primaryIconRes)
        assertEquals("inbox_dock_save_button", inbox.primaryButtonTestTag)

        val collections = ContextualTabDockResolver.resolveForRoute(LaterboxDestination.Collections.route)
        assertNotNull(collections)
        assertEquals(TabQuickActionKind.NEW_COLLECTION, collections!!.kind)
        assertEquals("New Collection", collections.primaryLabel)
        assertEquals(LaterboxIcons.FolderAdd, collections.primaryIconRes)

        val movies = ContextualTabDockResolver.resolveForRoute(LaterboxDestination.Movies.route)
        assertNotNull(movies)
        assertEquals(TabQuickActionKind.WATCHLIST, movies!!.kind)
        assertEquals("Watchlist", movies.primaryLabel)
        assertEquals(LaterboxIcons.Bookmark, movies.primaryIconRes)

        // Downloads and Archive must have no quick-action dock
        assertNull(ContextualTabDockResolver.resolveForRoute(LaterboxDestination.Downloads.route))
        assertNull(ContextualTabDockResolver.resolveForRoute(LaterboxDestination.Archive.route))
        assertNull(ContextualTabDockResolver.resolveForRoute(LaterboxDestination.Settings.route))
    }
}
