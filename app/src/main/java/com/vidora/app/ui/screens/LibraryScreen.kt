package com.vidora.app.ui.screens

import com.vidora.app.ui.theme.GlassAlertDialog
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.Visibility
import com.vidora.app.ui.icons.VisibilityOff
import kotlinx.coroutines.launch
import com.vidora.app.ui.icons.PlaylistPlay
import com.vidora.app.ui.icons.ArrowBack
import com.vidora.app.ui.icons.KeyboardArrowRight
import com.vidora.app.ui.icons.AccountCircle
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.Search
import com.vidora.app.ui.icons.AutoAwesome
import com.vidora.app.ui.icons.DeleteSweep
import com.vidora.app.ui.icons.ThumbUp
import com.vidora.app.ui.icons.Favorite
import com.vidora.app.ui.icons.History
import com.vidora.app.ui.icons.Person
import com.vidora.app.ui.icons.Album
import com.vidora.app.ui.icons.LibraryMusic
import com.vidora.app.ui.icons.Settings
import com.vidora.app.ui.icons.Subscriptions
import com.vidora.app.ui.icons.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.account.AccountFeed
import com.vidora.app.data.VideoItem
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.EmptyState
import com.vidora.app.ui.components.SectionHeader
import com.vidora.app.ui.components.Thumbnail
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.components.VideoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavHostController, actions: VideoActions) {
    val library by Graph.library.data.collectAsState()
    val session by Graph.account.session.collectAsState()
    com.vidora.app.ui.theme.GlassPage(header = {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_you)) },
            actions = {
                val incognito = Graph.settingsState.collectAsState().value.incognito
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                IconButton(onClick = { scope.launch { Graph.settings.update { it.copy(incognito = !it.incognito) } } }) {
                    Icon(
                        if (incognito) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (incognito) "Turn off incognito" else "Turn on incognito",
                        tint = if (incognito) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { nav.navigate(Routes.search()) }) { Icon(Icons.Rounded.Search, "Search") }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Settings") }
            },
            colors = com.vidora.app.ui.theme.glassBarColors()
        )
    }) {
        LazyColumn(contentPadding = PaddingValues(top = com.vidora.app.ui.theme.topChromePadding(), bottom = com.vidora.app.ui.theme.bottomChromePadding())) {
            // YouTube-style profile header
            item {
                val s = session
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate(if (s != null) Routes.SETTINGS else Routes.LOGIN) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (s?.avatar != null) com.vidora.app.ui.components.Avatar(s.avatar, 64)
                    else Icon(Icons.Rounded.AccountCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s?.name ?: "Not signed in", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s?.handle ?: "Sign in to sync subscriptions, likes and history",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
                        )
                    }
                }
            }
            item {
                val downloads by Graph.downloads.items.collectAsState()
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { YouPill(Icons.Rounded.Download, "Downloads${if (downloads.isNotEmpty()) " · ${downloads.size}" else ""}") { nav.navigate(Routes.DOWNLOADS) } }
                    item { YouPill(Icons.Rounded.History, "History") { nav.navigate(if (session != null) Routes.accountFeed(AccountFeed.HISTORY.name) else Routes.list("history")) } }
                    item { YouPill(Icons.Rounded.Settings, "Settings") { nav.navigate(Routes.SETTINGS) } }
                    if (session == null) item { YouPill(Icons.Rounded.AccountCircle, "Sign in") { nav.navigate(Routes.LOGIN) } }
                }
            }
            if (Graph.settingsState.value.incognito) {
                item {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.ui_incognito_is_on_nothing_you_watch_or_sea),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            item {
                SectionHeader(if (session != null) "History" else stringResource(R.string.ui_recently_watched)) {
                    TextButton(onClick = {
                        nav.navigate(if (session != null) Routes.accountFeed(AccountFeed.HISTORY.name) else Routes.list("history"))
                    }) { Text(stringResource(R.string.ui_see_all)) }
                }
            }
            item {
                // Signed in: the account's real YouTube watch history (falls back to this device's history).
                var accountHistory by remember(session != null) { mutableStateOf(com.vidora.app.ui.UiCache.get<List<VideoItem>>("you:history", 5 * 60_000L)) }
                var historyError by remember(session != null) { mutableStateOf<String?>(null) }
                LaunchedEffect(session != null) {
                    if (session != null && accountHistory == null) {
                        runCatching { Graph.accountApi.feed(AccountFeed.HISTORY) }
                            .onSuccess { p ->
                                accountHistory = p.items.filterIsInstance<com.vidora.app.data.Entry.Video>().map { it.video }.take(20)
                                    .also { com.vidora.app.ui.UiCache.put("you:history", it) }
                            }
                            .onFailure { historyError = it.message }
                    }
                }
                historyError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                val recent = accountHistory?.takeIf { it.isNotEmpty() }?.map { v ->
                    com.vidora.app.data.HistoryEntry(v, 0L, library.history.firstOrNull { it.video.url == v.url }?.positionMs ?: 0L)
                }
                    ?: library.history.take(12)
                if (recent.isEmpty()) {
                    Text(stringResource(R.string.ui_videos_you_watch_will_show_up_here), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.video.url }) { h ->
                            Column(Modifier.width(180.dp).clickable { actions.open(h.video) }) {
                                val progress = if (h.video.duration > 0) h.positionMs / 1000f / h.video.duration else 0f
                                Thumbnail(h.video, Modifier.fillMaxWidth(), progress)
                                Text(h.video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(h.video.uploader.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                val downloads by Graph.downloads.items.collectAsState()
                LibraryRow(Icons.Rounded.Download, "Downloads", "${downloads.size} files · watch offline") { nav.navigate(Routes.DOWNLOADS) }
            }
            if (session != null) {
                item { LibraryRow(Icons.Rounded.History, "History", "Your YouTube watch history") { nav.navigate(Routes.accountFeed(AccountFeed.HISTORY.name)) } }
                item { LibraryRow(Icons.Rounded.ThumbUp, "Liked videos", "From your YouTube account") { nav.navigate(Routes.accountFeed(AccountFeed.LIKED.name)) } }
                item { LibraryRow(Icons.Rounded.WatchLater, "Watch later", "From your YouTube account") { nav.navigate(Routes.accountFeed(AccountFeed.WATCH_LATER.name)) } }
                item { LibraryRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Your playlists", "Playlists on your YouTube account") { nav.navigate(Routes.accountFeed(AccountFeed.PLAYLISTS.name)) } }
                item { SectionHeader("On this device") }
            }
            item { LibraryRow(Icons.Rounded.History, if (session != null) "Vidora history" else "History", "${library.history.size} videos") { nav.navigate(Routes.list("history")) } }
            item { LibraryRow(Icons.Rounded.Favorite, if (session != null) "Favourites" else "Liked videos", "${library.favorites.size} videos") { nav.navigate(Routes.list("liked")) } }
            item { LibraryRow(Icons.Rounded.WatchLater, if (session != null) "Saved for later" else "Watch later", "${library.watchLater.size} videos") { nav.navigate(Routes.list("later")) } }
            item { LibraryRow(Icons.Rounded.Subscriptions, "Subscriptions", "${library.subscriptions.size} channels") { nav.navigate(Routes.SUBS) } }

            // ---------------------------------------------------------- local playlists
            item {
                var creating by remember { mutableStateOf(false) }
                SectionHeader(stringResource(R.string.ui_playlists)) {
                    TextButton(onClick = { creating = true }) { Text(stringResource(R.string.ui_new)) }
                }
                if (creating) {
                    com.vidora.app.ui.components.NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
                        creating = false
                        nav.navigate(Routes.localPlaylist(Graph.library.createPlaylist(name)))
                    }
                }
            }
            if (library.playlists.isEmpty()) {
                item {
                    Text(stringResource(R.string.ui_create_playlists_from_any_video_s_menu_s), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            items(library.playlists, key = { "pl:" + it.id }) { p ->
                LibraryRow(Icons.AutoMirrored.Rounded.PlaylistPlay, p.name, "${p.videos.size} videos") { nav.navigate(Routes.localPlaylist(p.id)) }
            }

            // ---------------------------------------------------------- YouTube account
            item { SectionHeader(if (session != null) "YouTube account · ${session?.name ?: "signed in"}" else "YouTube account") }
            if (session == null) {
                item {
                    LibraryRow(Icons.Rounded.AccountCircle, "Sign in with YouTube", "Unlock restricted videos and your own feeds") {
                        nav.navigate(Routes.LOGIN)
                    }
                }
            } else {
                items(AccountFeed.entries, key = { "acc:" + it.name }) { feed ->
                    LibraryRow(feed.icon(), feed.label, "From your YouTube account") { nav.navigate(Routes.accountFeed(feed.name)) }
                }
            }
        }
    }
}

private fun AccountFeed.icon(): ImageVector = when (this) {
    AccountFeed.RECOMMENDED -> Icons.Rounded.AutoAwesome
    AccountFeed.SUBSCRIPTIONS -> Icons.Rounded.Subscriptions
    AccountFeed.LIKED -> Icons.Rounded.ThumbUp
    AccountFeed.WATCH_LATER -> Icons.Rounded.WatchLater
    AccountFeed.HISTORY -> Icons.Rounded.History
    AccountFeed.PLAYLISTS -> Icons.AutoMirrored.Rounded.PlaylistPlay
    AccountFeed.MUSIC_LIKED -> Icons.Rounded.Favorite
    AccountFeed.MUSIC_PLAYLISTS -> Icons.Rounded.LibraryMusic
    AccountFeed.MUSIC_ALBUMS -> Icons.Rounded.Album
    AccountFeed.MUSIC_ARTISTS -> Icons.Rounded.Person
}

@Composable
private fun LibraryRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Full-screen list for history / liked / watch later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(nav: NavHostController, actions: VideoActions, kind: String) {
    val library by Graph.library.data.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val (title, videos: List<VideoItem>) = when (kind) {
        "liked" -> "Liked videos" to library.favorites
        "later" -> "Watch later" to library.watchLater
        else -> "History" to library.history.map { it.video }
    }
    com.vidora.app.ui.theme.GlassPage(header = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (videos.isNotEmpty()) {
                    TextButton(onClick = { Graph.player.play(videos.first(), videos); nav.navigate(Routes.PLAYER) }) { Text(stringResource(R.string.ui_play_all)) }
                }
                if (kind == "history" && videos.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear history") }
                }
            },
            colors = com.vidora.app.ui.theme.glassBarColors()
        )
    }) {
        if (videos.isEmpty()) {
            Column {
                com.vidora.app.ui.theme.TopChromeSpacer()
                EmptyState(stringResource(R.string.ui_nothing_here_yet), stringResource(R.string.ui_videos_you_add_will_appear_here))
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = com.vidora.app.ui.theme.topChromePadding(), bottom = com.vidora.app.ui.theme.bottomChromePadding())) {
                items(videos, key = { it.url }) { v -> VideoCard(v, actions, compact = true) }
            }
        }
    }
    if (confirmClear) {
        GlassAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ui_clear_watch_history)) },
            text = { Text(stringResource(R.string.ui_this_removes_all_watched_videos_and_resu)) },
            confirmButton = { TextButton(onClick = { Graph.library.clearHistory(); confirmClear = false }) { Text(stringResource(R.string.ui_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
}

@Composable
private fun YouPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
