package com.laterbox.app.ui.inbox

import com.laterbox.app.capture.ClipboardSuggestion
import com.laterbox.app.data.preferences.InboxViewMode
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.intelligence.LinkCollectionSuggestion
import com.laterbox.app.intelligence.ResurfacedLinkSuggestion

enum class InboxQuickFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    MEDIA_READY("Media Ready"),
    SCHEDULED("Scheduled")
}

data class InboxUiState(
    val links: List<SavedLink> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val activeFilterType: ContentType? = null,
    val activeDateRangeFilter: DateRangeFilter = DateRangeFilter.ALL,
    val activeQuickFilter: InboxQuickFilter = InboxQuickFilter.ALL,
    val readInboxCount: Int = 0,
    val pendingMetadataCount: Int = 0,
    val viewMode: InboxViewMode = InboxViewMode.HERO,
    val hasCompletedOnboarding: Boolean = true,
    val clipboardSuggestion: ClipboardSuggestion? = null,
    val unreadNotificationCount: Int = 0,
    val mediaReadyLinkIds: Set<Long> = emptySet(),
    val selectedLinkIds: Set<Long> = emptySet(),
    val resurfacedLinks: List<ResurfacedLinkSuggestion> = emptyList(),
    val collectionSuggestions: List<LinkCollectionSuggestion> = emptyList()
) {
    val isSelectionMode: Boolean
        get() = selectedLinkIds.isNotEmpty()

    val selectedCount: Int
        get() = selectedLinkIds.size

    val isEmpty: Boolean
        get() = !isLoading &&
            links.isEmpty() &&
            searchQuery.isBlank() &&
            activeFilterType == null &&
            activeDateRangeFilter == DateRangeFilter.ALL &&
            activeQuickFilter == InboxQuickFilter.ALL

    val isSearchEmpty: Boolean
        get() = !isLoading &&
            links.isEmpty() &&
            (searchQuery.isNotBlank() ||
                activeFilterType != null ||
                activeDateRangeFilter != DateRangeFilter.ALL ||
                activeQuickFilter != InboxQuickFilter.ALL)
}
