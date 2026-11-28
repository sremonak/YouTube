package com.vidora.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.data.PlaybackBookmark
import com.vidora.app.data.VideoItem
import com.vidora.app.download.DownloadKind
import com.vidora.app.download.DownloadStatus
import com.vidora.app.download.Storage
import com.vidora.app.player.PlayerUiState
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.*
import com.vidora.app.ui.icons.ArrowBack
import com.vidora.app.ui.theme.*
import com.vidora.app.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val settings by Graph.settingsState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<Pair<VideoItem, Long>?>(null) }
    val saved = library.bookmarks.filter { it.video.url == state.current?.url }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        SheetHeading("Saved moments")
        if (settings.incognito) Text("Bookmarks are hidden and cannot be saved in incognito.", Modifier.padding(16.dp))
        else {
            Text("Local timestamps · included in library backups", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = state.current != null && !state.loading && state.current?.isLive == false,
                onClick = { state.current?.let { draft = it to Graph.player.exoPlayer.currentPosition.coerceAtLeast(0) } }) { Text("Save current moment") }
            LazyColumn(Modifier.heightIn(max = 360.dp).navigationBarsPadding()) {
                items(saved, key = { it.id }) { bookmark ->
                    ListItem(headlineContent = { Text(bookmark.label) }, supportingContent = { Text(Format.duration(bookmark.positionMs / 1000).ifBlank { "0:00" }) },
                        modifier = Modifier.clickable { Graph.player.seekTo(bookmark.positionMs); onDismiss() },
                        trailingContent = { TextButton(onClick = { Graph.library.removeBookmark(bookmark.id) }) { Text("Remove") } })
                }
                if (saved.isEmpty()) item { Text("No moments saved for this video.", Modifier.padding(16.dp)) }
            }
        }
    }
    draft?.let { (video, position) ->
        NameDialog("Name this moment", Format.duration(position / 1000).ifBlank { "0:00" }, "Save", { draft = null }) {
            if (!Graph.settingsState.value.incognito) Graph.library.saveBookmark(video, position, it)
            draft = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(nav: NavHostController) {
    val context = LocalContext.current
    val library by Graph.library.data.collectAsStateWithLifecycle()
    val settings by Graph.settingsState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(0) }
    var renaming by remember { mutableStateOf<PlaybackBookmark?>(null) }
    var removing by remember { mutableStateOf<PlaybackBookmark?>(null) }
    val matches = if (settings.incognito) emptyList() else library.bookmarks.filter {
        query.trim().split(Regex("\\s+")).all { word -> "${it.label} ${it.video.title} ${it.video.uploader.orEmpty()}".contains(word, true) }
    }.let { when (sort) {
        1 -> it.sortedBy { b -> b.video.title.lowercase() }
        2 -> it.sortedBy { b -> b.positionMs }
        else -> it.sortedByDescending { b -> b.createdAt }
    } }
    GlassPage(header = {
        TopAppBar(title = { Text("Saved moments") }, navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }, colors = glassBarColors())
        if (!settings.incognito) {
            OutlinedTextField(query, { query = it }, label = { Text("Search saved moments") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row { ChoiceMenu(listOf("Newest first", "Video title", "Timestamp")[sort], listOf("Newest first", "Video title", "Timestamp")) { sort = it } }
        }
    }) {
        LazyColumn(contentPadding = PaddingValues(top = topChromePadding(), bottom = bottomChromePadding())) {
            if (matches.isEmpty()) item { EmptyState("No saved moments", if (settings.incognito) "Hidden in incognito mode." else "Open a video's player settings → Saved moments to bookmark a timestamp.") }
            items(matches, key = { it.id }) { bookmark ->
                ListItem(headlineContent = { Text(bookmark.label) },
                    supportingContent = { Text("${bookmark.video.title}\n${Format.duration(bookmark.positionMs / 1000).ifBlank { "0:00" }}") },
                    modifier = Modifier.clickable {
                        val current = Graph.player.state.value
                        val download = Graph.downloads.items.value.firstOrNull { it.video.url == bookmark.video.url && it.status == DownloadStatus.COMPLETED && it.uri != null }
                        when {
                            current.current?.url == bookmark.video.url && !current.loading && !current.restored -> {
                                Graph.player.seekTo(bookmark.positionMs); Graph.player.exoPlayer.play()
                            }
                            download != null && Storage.exists(context, download) -> {
                                Graph.player.playLocal(download.video, download.uri!!.toUri(), download.kind == DownloadKind.AUDIO)
                                Graph.player.seekTo(bookmark.positionMs)
                            }
                            else -> Graph.player.play(bookmark.video, startPositionMs = bookmark.positionMs)
                        }
                        nav.navigate(Routes.PLAYER)
                    },
                    trailingContent = {
                        ChoiceMenu("Moment actions", listOf("Rename", "Share timestamp", "Remove")) {
                            when (it) {
                                0 -> renaming = bookmark
                                1 -> {
                                    val original = bookmark.video.url.toUri()
                                    val link = if (original.isHierarchical && original.scheme in listOf("https", "http")) {
                                        val uri = original.buildUpon().clearQuery().fragment(null)
                                        original.queryParameterNames.filterNot { key -> key == "t" || key == "start" }.forEach { key ->
                                            original.getQueryParameters(key).forEach { value -> uri.appendQueryParameter(key, value) }
                                        }
                                        uri.appendQueryParameter("t", (bookmark.positionMs / 1000).toString()).build().toString()
                                    } else bookmark.video.url
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, "${bookmark.label}\n$link"), "Share moment"))
                                }
                                2 -> removing = bookmark
                            }
                        }
                    })
            }
        }
    }
    renaming?.let { b -> NameDialog("Rename moment", b.label, "Save", { renaming = null }) { Graph.library.renameBookmark(b.id, it); renaming = null } }
    removing?.let { b -> GlassAlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove saved moment?") },
        text = { Text(b.label) }, confirmButton = { TextButton(onClick = { Graph.library.removeBookmark(b.id); removing = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}
