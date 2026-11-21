package com.vidora.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.EmptyState
import com.vidora.app.ui.components.NameDialog
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.components.VideoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistScreen(nav: NavHostController, actions: VideoActions, id: String) {
    val library by Graph.library.data.collectAsState()
    val playlist = library.playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (playlist == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(playlist.name) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename") }
                if (playlist.videos.isNotEmpty()) {
                    IconButton(onClick = {
                        val text = playlist.name + "\n" + playlist.videos.joinToString("\n") { "${it.title} — ${it.url}" }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share playlist"))
                    }) { Icon(Icons.Rounded.Share, "Share") }
                }
                if (playlist.videos.isNotEmpty()) IconButton(onClick = { batch = true }) { Icon(Icons.Rounded.Download, "Download all") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Delete") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (playlist.videos.isEmpty()) {
            EmptyState("Empty playlist", "Use “Save to playlist” on any video to add it here.")
            return@Column
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { Graph.player.play(playlist.videos.first(), playlist.videos); nav.navigate(Routes.PLAYER) }) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play all")
            }
            FilledTonalButton(onClick = {
                val shuffled = playlist.videos.shuffled()
                Graph.player.play(shuffled.first(), shuffled); nav.navigate(Routes.PLAYER)
            }) {
                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
        }
        Text("${playlist.videos.size} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            itemsIndexed(playlist.videos, key = { _, v -> v.url }) { index, v ->
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { VideoCard(v, actions, compact = true) }
                        Column {
                            IconButton(onClick = { Graph.library.movePlaylistItem(id, index, index - 1) }, enabled = index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { Graph.library.movePlaylistItem(id, index, index + 1) }, enabled = index < playlist.videos.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                        }
                        IconButton(onClick = { Graph.library.removeFromPlaylist(id, v.url) }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove") }
                    }
                } else {
                    VideoCard(v, actions = VideoActions({ Graph.player.play(it, playlist.videos); nav.navigate(Routes.PLAYER) }, actions.openChannel, actions.openPlaylist, actions.download), compact = true)
                }
            }
        }
    }

    if (batch) com.vidora.app.ui.components.BatchDownloadDialog(playlist.videos) { batch = false }
    if (renaming) {
        NameDialog("Rename playlist", playlist.name, "Save", onDismiss = { renaming = false }) {
            Graph.library.renamePlaylist(id, it); renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${playlist.name}”?") },
            text = { Text("The videos themselves are not affected.") },
            confirmButton = { TextButton(onClick = { deleting = false; Graph.library.deletePlaylist(id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
