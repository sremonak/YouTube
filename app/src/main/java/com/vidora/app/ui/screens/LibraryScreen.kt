package com.vidora.app.ui.screens

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.WatchLater
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
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
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Library") },
            actions = { IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Settings") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                SectionHeader("Recently watched") {
                    TextButton(onClick = { nav.navigate(Routes.list("history")) }) { Text("See all") }
                }
            }
            item {
                val recent = library.history.take(12)
                if (recent.isEmpty()) {
                    Text("Videos you watch will show up here.", style = MaterialTheme.typography.bodyMedium,
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
            item { LibraryRow(Icons.Rounded.History, "History", "${library.history.size} videos") { nav.navigate(Routes.list("history")) } }
            item { LibraryRow(Icons.Rounded.Favorite, "Liked videos", "${library.favorites.size} videos") { nav.navigate(Routes.list("liked")) } }
            item { LibraryRow(Icons.Rounded.WatchLater, "Watch later", "${library.watchLater.size} videos") { nav.navigate(Routes.list("later")) } }
            item { LibraryRow(Icons.Rounded.Subscriptions, "Subscriptions", "${library.subscriptions.size} channels") { nav.navigate(Routes.SUBS) } }
        }
    }
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
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (videos.isNotEmpty()) {
                    TextButton(onClick = { Graph.player.play(videos.first(), videos); nav.navigate(Routes.PLAYER) }) { Text("Play all") }
                }
                if (kind == "history" && videos.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear history") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (videos.isEmpty()) {
            EmptyState("Nothing here yet", "Videos you add will appear here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                items(videos, key = { it.url }) { v -> VideoCard(v, actions, compact = true) }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("This removes all watched videos and resume positions.") },
            confirmButton = { TextButton(onClick = { Graph.library.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}
