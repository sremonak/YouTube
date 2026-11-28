package com.vidora.app.ui.screens

import com.vidora.app.ui.theme.GlassAlertDialog
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
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
import com.vidora.app.ui.icons.ArrowBack
import com.vidora.app.ui.icons.Delete
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.Edit
import com.vidora.app.ui.icons.KeyboardArrowDown
import com.vidora.app.ui.icons.KeyboardArrowUp
import com.vidora.app.ui.icons.PlayArrow
import com.vidora.app.ui.icons.RemoveCircleOutline
import com.vidora.app.ui.icons.Share
import com.vidora.app.ui.icons.Shuffle
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
import androidx.compose.runtime.saveable.rememberSaveable
import com.vidora.app.data.CollectionTools
import com.vidora.app.data.CollectionOrder
import com.vidora.app.data.CollectionLength
import com.vidora.app.ui.components.CollectionControls
import com.vidora.app.ui.components.ChoiceMenu
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
    var duplicating by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(CollectionOrder.SAVED) }
    var length by rememberSaveable { mutableStateOf(CollectionLength.ALL) }
    val context = LocalContext.current

    if (playlist == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    val visible = CollectionTools.videos(playlist.videos, query, order, length)
    val canReorder = query.isBlank() && order == CollectionOrder.SAVED && length == CollectionLength.ALL
    LaunchedEffect(canReorder) { if (!canReorder) editing = false }
    com.vidora.app.ui.theme.GlassPage(header = {
        TopAppBar(
            title = { Text(playlist.name) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                ChoiceMenu("Playlist tools", listOf("Rename", "Share playlist", "Download all", "Duplicate playlist", "Apply sort to playlist", "Delete playlist")) {
                    when (it) {
                        0 -> renaming = true
                        1 -> {
                            val text = playlist.name + "\n" + playlist.videos.joinToString("\n") { v -> "${v.title} — ${v.url}" }
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share playlist"))
                        }
                        2 -> if (playlist.videos.isNotEmpty()) batch = true
                        3 -> duplicating = true
                        4 -> { Graph.library.reorderPlaylist(id, order); order = CollectionOrder.SAVED }
                        5 -> deleting = true
                    }
                }
            },
            colors = com.vidora.app.ui.theme.glassBarColors()
        )
        CollectionControls(query, { query = it }, order, { order = it }, length, { length = it }, visible.size)
        if (visible.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { Graph.player.play(visible.first(), visible); nav.navigate(Routes.PLAYER) }) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ui_play_all))
            }
            FilledTonalButton(onClick = {
                val shuffled = visible.shuffled()
                Graph.player.play(shuffled.first(), shuffled); nav.navigate(Routes.PLAYER)
            }) {
                Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ui_shuffle))
            }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = canReorder, onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
        }
        if (playlist.videos.isNotEmpty()) Text("${playlist.videos.size} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
    }) {
        if (visible.isEmpty()) {
            Column {
                com.vidora.app.ui.theme.TopChromeSpacer()
                EmptyState(if (playlist.videos.isEmpty()) stringResource(R.string.ui_empty_playlist) else "No matching videos", "Change your search or reset filters. Saved playlist items are kept.")
            }
        } else LazyColumn(contentPadding = PaddingValues(top = com.vidora.app.ui.theme.topChromePadding(), bottom = com.vidora.app.ui.theme.bottomChromePadding())) {
            itemsIndexed(visible, key = { _, v -> v.url }) { index, v ->
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
                    VideoCard(v, actions = VideoActions({ Graph.player.play(it, visible); nav.navigate(Routes.PLAYER) }, actions.openChannel, actions.openPlaylist, actions.download), compact = true)
                }
            }
        }
    }

    if (duplicating) NameDialog("Duplicate playlist", playlist.name + " copy", "Create", { duplicating = false }) {
        val copyId = Graph.library.createPlaylistFrom(it, playlist.videos)
        duplicating = false
        nav.navigate(Routes.localPlaylist(copyId))
    }
    if (batch) com.vidora.app.ui.components.BatchDownloadDialog(playlist.videos) { batch = false }
    if (renaming) {
        NameDialog("Rename playlist", playlist.name, "Save", onDismiss = { renaming = false }) {
            Graph.library.renamePlaylist(id, it); renaming = false
        }
    }
    if (deleting) {
        GlassAlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${playlist.name}”?") },
            text = { Text(stringResource(R.string.ui_the_videos_themselves_are_not_affected)) },
            confirmButton = { TextButton(onClick = { deleting = false; Graph.library.deletePlaylist(id) }) { Text(stringResource(R.string.ui_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}
