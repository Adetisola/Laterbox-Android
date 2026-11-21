package com.laterbox.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.data.local.entity.ChatConversationEntity
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.ui.navigation.LaterboxDestination
import com.laterbox.app.ui.theme.LaterboxTheme

enum class DrawerPrimarySection {
    INBOX,
    COLLECTIONS,
    MOVIES,
    DOWNLOADS,
    ARCHIVE,
    NOTIFICATIONS,
    SETTINGS,
    CHAT
}

@Composable
fun LaterboxModalDrawerSheet(
    currentSection: DrawerPrimarySection,
    activeConversationId: String?,
    recentConversations: List<ChatConversationEntity>,
    unreadNotificationCount: Int,
    userName: String,
    onNavigateInbox: () -> Unit,
    onNavigateCollections: () -> Unit,
    onNavigateMovies: () -> Unit = {},
    onNavigateDownloads: () -> Unit,
    onNavigateArchive: () -> Unit,
    onNavigateNotifications: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNewChatClick: () -> Unit,
    onConversationClick: (ChatConversationEntity) -> Unit,
    onDeleteConversation: (String) -> Unit,
    currentRoute: String? = null,
    onNavigateMoviesSubRoute: (String) -> Unit = {},
    moviesTvEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    ModalDrawerSheet(
        modifier = modifier.width(304.dp),
        drawerContainerColor = colors.backgroundPrimary,
        drawerContentColor = colors.textPrimary
    ) {
        LaterboxDrawerContent(
            currentSection = currentSection,
            activeConversationId = activeConversationId,
            recentConversations = recentConversations,
            unreadNotificationCount = unreadNotificationCount,
            userName = userName,
            onNavigateInbox = onNavigateInbox,
            onNavigateCollections = onNavigateCollections,
            onNavigateMovies = onNavigateMovies,
            onNavigateDownloads = onNavigateDownloads,
            onNavigateArchive = onNavigateArchive,
            onNavigateNotifications = onNavigateNotifications,
            onNavigateSettings = onNavigateSettings,
            onNewChatClick = onNewChatClick,
            onConversationClick = onConversationClick,
            onDeleteConversation = onDeleteConversation,
            currentRoute = currentRoute,
            onNavigateMoviesSubRoute = onNavigateMoviesSubRoute,
            moviesTvEnabled = moviesTvEnabled
        )
    }
}

@Composable
fun LaterboxPersistentSidePanel(
    currentSection: DrawerPrimarySection,
    activeConversationId: String?,
    recentConversations: List<ChatConversationEntity>,
    unreadNotificationCount: Int,
    userName: String,
    onNavigateInbox: () -> Unit,
    onNavigateCollections: () -> Unit,
    onNavigateMovies: () -> Unit = {},
    onNavigateDownloads: () -> Unit,
    onNavigateArchive: () -> Unit,
    onNavigateNotifications: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNewChatClick: () -> Unit,
    onConversationClick: (ChatConversationEntity) -> Unit,
    onDeleteConversation: (String) -> Unit,
    currentRoute: String? = null,
    onNavigateMoviesSubRoute: (String) -> Unit = {},
    moviesTvEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing
    Surface(
        modifier = modifier
            .width(276.dp)
            .fillMaxHeight(),
        color = colors.backgroundPrimary
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            LaterboxDrawerContent(
                currentSection = currentSection,
                activeConversationId = activeConversationId,
                recentConversations = recentConversations,
                unreadNotificationCount = unreadNotificationCount,
                userName = userName,
                onNavigateInbox = onNavigateInbox,
                onNavigateCollections = onNavigateCollections,
                onNavigateMovies = onNavigateMovies,
                onNavigateDownloads = onNavigateDownloads,
                onNavigateArchive = onNavigateArchive,
                onNavigateNotifications = onNavigateNotifications,
                onNavigateSettings = onNavigateSettings,
                onNewChatClick = onNewChatClick,
                onConversationClick = onConversationClick,
                onDeleteConversation = onDeleteConversation,
                currentRoute = currentRoute,
                onNavigateMoviesSubRoute = onNavigateMoviesSubRoute,
                moviesTvEnabled = moviesTvEnabled,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(spacing.hairline)
                    .fillMaxHeight()
                    .background(colors.borderSubtle)
            )
        }
    }
}

@Composable
fun LaterboxDrawerContent(
    currentSection: DrawerPrimarySection,
    activeConversationId: String?,
    recentConversations: List<ChatConversationEntity>,
    unreadNotificationCount: Int,
    userName: String,
    onNavigateInbox: () -> Unit,
    onNavigateCollections: () -> Unit,
    onNavigateMovies: () -> Unit = {},
    onNavigateDownloads: () -> Unit,
    onNavigateArchive: () -> Unit,
    onNavigateNotifications: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNewChatClick: () -> Unit,
    onConversationClick: (ChatConversationEntity) -> Unit,
    onDeleteConversation: (String) -> Unit,
    currentRoute: String? = null,
    onNavigateMoviesSubRoute: (String) -> Unit = {},
    moviesTvEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    var conversationPendingDelete by remember { mutableStateOf<ChatConversationEntity?>(null) }

    val isOnMoviesRoute = currentSection == DrawerPrimarySection.MOVIES ||
        currentRoute?.startsWith("movies") == true
    var isMoviesExpanded by rememberSaveable { mutableStateOf(isOnMoviesRoute) }
    LaunchedEffect(isOnMoviesRoute) {
        if (isOnMoviesRoute) {
            isMoviesExpanded = true
        }
    }

    conversationPendingDelete?.let { conv ->
        LaterboxConfirmDialog(
            title = "Delete conversation?",
            message = "\"${conv.title}\" will be removed from this device.",
            confirmText = "Delete",
            onConfirm = {
                conversationPendingDelete = null
                onDeleteConversation(conv.id)
            },
            onDismiss = { conversationPendingDelete = null }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            .testTag("laterbox_drawer_list"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "drawer_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.sm, vertical = spacing.sm)
            ) {
                Text(
                    text = "Laterbox",
                    style = typography.titleLarge,
                    color = colors.textPrimary
                )
                Text(
                    text = if (userName.isNotBlank()) "Welcome back, $userName" else "Your calm reading library",
                    style = typography.metaText,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(spacing.xs))
        }

        item(key = "drawer_new_chat") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LaterboxShapeTokens.Button)
                    .background(colors.surfacePrimary)
                    .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Button)
                    .clickable(role = Role.Button, onClick = onNewChatClick)
                    .padding(horizontal = spacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.Chat),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.accentPrimary
                )
                Text(
                    text = "New chat",
                    style = typography.uiMedium.copy(fontSize = 14.sp),
                    color = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }

        item(key = "drawer_nav_items") {
            Column {
                DrawerNavItem(
                    label = "Inbox",
                    iconRes = LaterboxIcons.Inbox,
                    selected = currentSection == DrawerPrimarySection.INBOX,
                    onClick = onNavigateInbox
                )
                DrawerNavItem(
                    label = "Collections",
                    iconRes = LaterboxIcons.Folder,
                    selected = currentSection == DrawerPrimarySection.COLLECTIONS,
                    onClick = onNavigateCollections
                )
                if (moviesTvEnabled) {
                    MoviesAccordionNavSection(
                        selected = isOnMoviesRoute,
                        expanded = isMoviesExpanded,
                        currentRoute = currentRoute,
                        onToggleExpanded = { isMoviesExpanded = !isMoviesExpanded },
                        onNavigateLanding = onNavigateMovies,
                        onNavigateSubRoute = onNavigateMoviesSubRoute
                    )
                }
                DrawerNavItem(
                    label = "Downloads",
                    iconRes = LaterboxIcons.Download,
                    selected = currentSection == DrawerPrimarySection.DOWNLOADS,
                    onClick = onNavigateDownloads
                )
                DrawerNavItem(
                    label = "Archive",
                    iconRes = LaterboxIcons.Archive,
                    selected = currentSection == DrawerPrimarySection.ARCHIVE,
                    onClick = onNavigateArchive
                )
                DrawerNavItem(
                    label = "Notifications",
                    iconRes = LaterboxIcons.Bell,
                    selected = currentSection == DrawerPrimarySection.NOTIFICATIONS,
                    badgeCount = unreadNotificationCount,
                    onClick = onNavigateNotifications
                )
                DrawerNavItem(
                    label = "Settings",
                    iconRes = LaterboxIcons.Settings,
                    selected = currentSection == DrawerPrimarySection.SETTINGS,
                    onClick = onNavigateSettings
                )
            }
        }

        item(key = "drawer_conversations_header") {
            Spacer(modifier = Modifier.height(spacing.md))
            HorizontalDivider(thickness = spacing.hairline, color = colors.borderSubtle)
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = "Recent conversations",
                style = typography.metaText.copy(fontWeight = FontWeight.Medium),
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = spacing.sm)
            )
            Spacer(modifier = Modifier.height(spacing.xs))
        }

        if (recentConversations.isEmpty()) {
            item(key = "drawer_conversations_empty") {
                Text(
                    text = "No conversations yet. Message the assistant from Inbox or tap New chat.",
                    style = typography.bodySecondary.copy(fontSize = 12.sp),
                    color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs)
                )
            }
        } else {
            items(recentConversations, key = { it.id }) { conversation ->
                val isSelected = currentSection == DrawerPrimarySection.CHAT && conversation.id == activeConversationId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) colors.accentContainer else colors.backgroundPrimary)
                        .clickable(role = Role.Button) {
                            onConversationClick(conversation)
                        }
                        .padding(start = spacing.sm, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.title,
                        style = typography.bodySecondary.copy(
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Delete conversation ${conversation.title}",
                                onClick = { conversationPendingDelete = conversation }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = LaterboxIcons.Delete),
                            contentDescription = "Delete conversation",
                            modifier = Modifier.size(14.dp),
                            tint = colors.textTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerNavItem(
    label: String,
    @DrawableRes iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentContainer else colors.backgroundPrimary)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = spacing.sm, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = if (selected) colors.accentPrimary else colors.textSecondary
        )
        Spacer(modifier = Modifier.width(spacing.md))
        Text(
            text = label,
            style = typography.uiMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) colors.accentPrimary else colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.accentPrimary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                    style = typography.metaText.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.onAccentPrimary
                )
            }
        }
    }
}

@Composable
private fun MoviesAccordionNavSection(
    selected: Boolean,
    expanded: Boolean,
    currentRoute: String?,
    onToggleExpanded: () -> Unit,
    onNavigateLanding: () -> Unit,
    onNavigateSubRoute: (String) -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) colors.accentContainer else colors.backgroundPrimary),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Tab, onClick = onNavigateLanding)
                    .padding(start = spacing.sm, end = spacing.xs, top = 10.dp, bottom = 10.dp)
                    .testTag("drawer_movies_row"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.Movies),
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = if (selected) colors.accentPrimary else colors.textSecondary
                )
                Spacer(modifier = Modifier.width(spacing.md))
                Text(
                    text = "Movies & TV",
                    style = typography.uiMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (selected) colors.accentPrimary else colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse Movies & TV menu" else "Expand Movies & TV menu",
                        onClick = onToggleExpanded
                    )
                    .testTag("drawer_movies_chevron"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = LaterboxIcons.ChevronRight),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if (expanded) 90f else 0f),
                    tint = if (selected) colors.accentPrimary else colors.textSecondary
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 4.dp, bottom = 4.dp)
                    .testTag("drawer_movies_submenu"),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DrawerSubGroupLabel("Library")
                DrawerSubNavItem(
                    label = "Search",
                    iconRes = LaterboxIcons.Search,
                    selected = currentRoute?.contains("focusSearch=true") == true,
                    testTag = "drawer_movies_sub_search",
                    onClick = {
                        onNavigateSubRoute(LaterboxDestination.MoviesCatalogue.createRoute(focusSearch = true))
                    }
                )
                DrawerSubNavItem(
                    label = "Watch History",
                    iconRes = LaterboxIcons.Clock,
                    selected = currentRoute == LaterboxDestination.MoviesViewingHistory.route,
                    testTag = "drawer_movies_sub_history",
                    onClick = {
                        onNavigateSubRoute(LaterboxDestination.MoviesViewingHistory.route)
                    }
                )
                DrawerSubNavItem(
                    label = "Watchlist",
                    iconRes = LaterboxIcons.Bookmark,
                    selected = currentRoute == LaterboxDestination.MoviesWatchlist.route,
                    testTag = "drawer_movies_sub_watchlist",
                    onClick = {
                        onNavigateSubRoute(LaterboxDestination.MoviesWatchlist.route)
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))
                DrawerSubGroupLabel("Discovery")
                DrawerSubNavItem(
                    label = "Popular Movies",
                    iconRes = LaterboxIcons.Movies,
                    selected = currentRoute?.contains("kind=movie") == true &&
                        (currentRoute.contains("sort=popular") || !currentRoute.contains("sort=")),
                    testTag = "drawer_movies_sub_popular_movies",
                    onClick = {
                        onNavigateSubRoute(
                            LaterboxDestination.MoviesCatalogue.createRoute(
                                kind = MediaKind.MOVIE.wireValue,
                                sort = CatalogueSort.POPULAR.wireValue
                            )
                        )
                    }
                )
                DrawerSubNavItem(
                    label = "Popular Series",
                    iconRes = LaterboxIcons.Play,
                    selected = currentRoute?.contains("kind=tv") == true,
                    testTag = "drawer_movies_sub_popular_series",
                    onClick = {
                        onNavigateSubRoute(
                            LaterboxDestination.MoviesCatalogue.createRoute(
                                kind = MediaKind.TV_SERIES.wireValue,
                                sort = CatalogueSort.POPULAR.wireValue
                            )
                        )
                    }
                )
                DrawerSubNavItem(
                    label = "Newest",
                    iconRes = LaterboxIcons.Sparkles,
                    selected = currentRoute?.contains("sort=newest") == true,
                    testTag = "drawer_movies_sub_newest",
                    onClick = {
                        onNavigateSubRoute(
                            LaterboxDestination.MoviesCatalogue.createRoute(
                                sort = CatalogueSort.NEWEST.wireValue
                            )
                        )
                    }
                )
                DrawerSubNavItem(
                    label = "Top Rated",
                    iconRes = LaterboxIcons.Sparkles,
                    selected = currentRoute?.contains("sort=top_rated") == true,
                    testTag = "drawer_movies_sub_top_rated",
                    onClick = {
                        onNavigateSubRoute(
                            LaterboxDestination.MoviesCatalogue.createRoute(
                                sort = CatalogueSort.TOP_RATED.wireValue
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun DrawerSubGroupLabel(text: String) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    Text(
        text = text.uppercase(),
        style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        color = colors.textTertiary,
        modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun DrawerSubNavItem(
    label: String,
    @DrawableRes iconRes: Int,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accentContainer.copy(alpha = 0.7f) else colors.backgroundPrimary)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (selected) colors.accentPrimary else colors.textSecondary
        )
        Text(
            text = label,
            style = typography.uiMedium.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) colors.accentPrimary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun sectionForRoute(route: String?): DrawerPrimarySection {
    return when {
        route == LaterboxDestination.Collections.route || route?.startsWith("collection/") == true ->
            DrawerPrimarySection.COLLECTIONS
        route?.startsWith("movies") == true -> DrawerPrimarySection.MOVIES
        route == LaterboxDestination.Downloads.route -> DrawerPrimarySection.DOWNLOADS
        route == LaterboxDestination.Archive.route -> DrawerPrimarySection.ARCHIVE
        route == LaterboxDestination.NotificationHistory.route -> DrawerPrimarySection.NOTIFICATIONS
        route?.startsWith("settings") == true -> DrawerPrimarySection.SETTINGS
        route?.startsWith("chat") == true -> DrawerPrimarySection.CHAT
        else -> DrawerPrimarySection.INBOX
    }
}
