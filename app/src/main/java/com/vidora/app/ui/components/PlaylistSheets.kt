package com.vidora.app.ui.components

import com.vidora.app.ui.theme.GlassBottomSheet
import com.vidora.app.ui.theme.GlassAlertDialog
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.PlaylistAdd
import com.vidora.app.ui.icons.Add
import com.vidora.app.ui.icons.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vidora.app.Graph
import com.vidora.app.data.VideoItem
import com.vidora.app.account.RemotePlaylist
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** Pick (or create) a local playlist to add [video] to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(video: VideoItem, onDismiss: () -> Unit) {
    val library by Graph.library.data.collectAsState()
    val session by Graph.account.session.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var creatingRemote by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val videoId = remember(video.url) { Graph.repository.videoId(video.url) }
    var remote by remember { mutableStateOf<List<RemotePlaylist>?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session != null, videoId) {
        if (session != null && videoId != null) {
            runCatching { Graph.accountApi.playlistsFor(videoId) }
                .onSuccess { remote = it }
                .onFailure { remoteError = it.message ?: "Couldn't load your YouTube playlists" }
        }
    }
    fun toggleRemote(p: RemotePlaylist) {
        val id = videoId ?: return
        remote = remote?.map { if (it.id == p.id) it.copy(containsVideo = !p.containsVideo) else it }
        scope.launch {
            runCatching {
                if (p.containsVideo) Graph.accountApi.removeFromPlaylist(p.id, id) else Graph.accountApi.addToPlaylist(p.id, id)
            }.onSuccess {
                Toast.makeText(context, if (p.containsVideo) "Removed from ${p.title}" else "Saved to ${p.title}", Toast.LENGTH_SHORT).show()
            }.onFailure {
                remote = remote?.map { r -> if (r.id == p.id) p else r }
                Toast.makeText(context, "YouTube: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_save_to_playlist), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ui_new_playlist)) },
                    leadingContent = { Icon(Icons.Rounded.Add, null) },
                    modifier = Modifier.clickable { creating = true }
                )
            }
            if (library.playlists.isNotEmpty() || session != null) {
                item { Text(stringResource(R.string.ui_on_this_device), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            }
            items(library.playlists, key = { it.id }) { p ->
                val contains = p.videos.any { it.url == video.url }
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.videos.size} videos") },
                    leadingContent = { Icon(if (contains) Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                    modifier = Modifier.clickable {
                        if (contains) Graph.library.removeFromPlaylist(p.id, video.url)
                        else {
                            Graph.library.addToPlaylist(p.id, listOf(video))
                            onDismiss()
                        }
                    }
                )
            }
            if (session != null && videoId != null) {
                item {
                    Text(stringResource(R.string.ui_youtube_account), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp))
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.ui_new_youtube_playlist)) },
                        supportingContent = { Text(stringResource(R.string.ui_private)) },
                        leadingContent = { Icon(Icons.Rounded.Add, null) },
                        modifier = Modifier.clickable { creatingRemote = true }
                    )
                }
                val list = remote
                when {
                    list == null && remoteError == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) }
                    remoteError != null -> item { Text(remoteError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp)) }
                    list != null -> items(list, key = { "yt:" + it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title) },
                            supportingContent = p.privacy?.let { pr -> { Text(pr.lowercase().replaceFirstChar { it.uppercase() }) } },
                            leadingContent = { Icon(if (p.containsVideo) Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                            modifier = Modifier.clickable { toggleRemote(p) }
                        )
                    }
                }
            }
        }
    }
    if (creatingRemote && videoId != null) {
        NameDialog(title = "New YouTube playlist", initial = "", confirm = "Create", onDismiss = { creatingRemote = false }) { name ->
            creatingRemote = false
            scope.launch {
                runCatching { Graph.accountApi.createPlaylist(name, videoId) }
                    .onSuccess { Toast.makeText(context, "Created “$name” on YouTube", Toast.LENGTH_SHORT).show(); onDismiss() }
                    .onFailure { Toast.makeText(context, "YouTube: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    if (creating) {
        NameDialog(title = "New playlist", initial = "", confirm = "Create", onDismiss = { creating = false }) { name ->
            Graph.library.createPlaylist(name, video)
            creating = false
            onDismiss()
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.ui_name)) }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}
