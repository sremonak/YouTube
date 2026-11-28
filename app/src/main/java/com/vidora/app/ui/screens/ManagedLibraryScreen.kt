package com.vidora.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.data.*
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.*
import com.vidora.app.ui.icons.ArrowBack
import com.vidora.app.ui.icons.DeleteSweep
import com.vidora.app.ui.theme.GlassAlertDialog
import com.vidora.app.ui.theme.GlassPage

/** Multi-selection is local library management, never an implicit remote account mutation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagedLibraryScreen(nav: NavHostController, actions: VideoActions, kind: String) {
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val (title, videos) = when (kind) {
        "liked" -> "Liked videos" to library.favorites
        "later" -> "Watch later" to library.watchLater
        else -> "History" to library.history.map { it.video }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(CollectionOrder.SAVED) }
    var length by rememberSaveable { mutableStateOf(CollectionLength.ALL) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removal by remember { mutableStateOf<Set<String>?>(null) }
    var playlistItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    val visible = remember(videos, query, order, length) { CollectionTools.videos(videos, query, order, length) }
    val picked = videos.filter { it.url in selected }
    LaunchedEffect(videos) { selected = selected.filter { url -> videos.any { it.url == url } } }
    BackHandler(selecting) { selecting = false; selected = emptyList() }

    GlassPage(header = {
        TopAppBar(title = { Text(if (selecting) "${picked.size} selected" else title) },
            navigationIcon = { IconButton(onClick = {
                if (selecting) { selecting = false; selected = emptyList() } else nav.popBackStack()
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (!selecting && videos.isNotEmpty()) {
                    TextButton(onClick = { selecting = true }) { Text("Select") }
                    if (kind == "history") IconButton(onClick = { removal = videos.map { it.url }.toSet() }) {
                        Icon(Icons.Rounded.DeleteSweep, "Clear history")
                    }
                }
                if (selecting) TextButton(onClick = { selecting = false; selected = emptyList() }) { Text("Done") }
            }, colors = com.vidora.app.ui.theme.glassBarColors())
        CollectionControls(query, { query = it }, order, { order = it }, length, { length = it }, visible.size)
        if (selecting) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = (selected + visible.map { it.url }).distinct() }) { Text("Select matching") }
            TextButton(onClick = { selected = emptyList() }) { Text("Deselect") }
            if (picked.isNotEmpty()) ChoiceMenu("Actions", listOf("Play selected", "Queue selected", "Playlist from selection", "Remove locally")) {
                when (it) {
                    0 -> { Graph.player.play(picked.first(), picked); nav.navigate(Routes.PLAYER) }
                    1 -> Graph.player.enqueueAll(picked)
                    2 -> playlistItems = picked
                    3 -> removal = picked.map { v -> v.url }.toSet()
                }
            }
        } else if (visible.isNotEmpty()) Row {
            TextButton(onClick = { Graph.player.play(visible.first(), visible); nav.navigate(Routes.PLAYER) }) { Text("Play all") }
            TextButton(onClick = { val shuffled = visible.shuffled(); Graph.player.play(shuffled.first(), shuffled); nav.navigate(Routes.PLAYER) }) { Text("Shuffle") }
        }
    }) {
        if (visible.isEmpty()) Column {
            com.vidora.app.ui.theme.TopChromeSpacer()
            EmptyState(if (videos.isEmpty()) "Nothing here yet" else "No matching videos", "Search title or creator, or reset filters.")
        } else LazyColumn(contentPadding = PaddingValues(top = com.vidora.app.ui.theme.topChromePadding(), bottom = com.vidora.app.ui.theme.bottomChromePadding())) {
            items(visible, key = { it.url }) { video ->
                if (selecting) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(
                        value = video.url in selected, role = Role.Checkbox,
                        onValueChange = { checked -> selected = if (checked) (selected + video.url).distinct() else selected - video.url }
                    ).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = video.url in selected, onCheckedChange = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(video.uploader.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                } else VideoCard(video, actions, compact = true)
            }
        }
    }
    removal?.let { urls ->
        GlassAlertDialog(onDismissRequest = { removal = null }, title = { Text("Remove ${urls.size} local entries?") },
            text = { Text("Only this device's $title list is changed. Downloaded files, playlists and your YouTube account are not changed. Local history removal also clears saved resume positions.") },
            confirmButton = { TextButton(onClick = { Graph.library.removeLocalVideos(kind, urls); selected = selected - urls; removal = null }) { Text("Remove locally") } },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } })
    }
    playlistItems?.let { saved ->
        NameDialog("Playlist from selection", "", "Create", { playlistItems = null }) {
            val id = Graph.library.createPlaylistFrom(it, saved)
            playlistItems = null
            nav.navigate(Routes.localPlaylist(id))
        }
    }
}
