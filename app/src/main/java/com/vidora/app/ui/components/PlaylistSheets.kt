package com.vidora.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
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

/** Pick (or create) a local playlist to add [video] to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(video: VideoItem, onDismiss: () -> Unit) {
    val library by Graph.library.data.collectAsState()
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Save to playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("New playlist") },
                    leadingContent = { Icon(Icons.Rounded.Add, null) },
                    modifier = Modifier.clickable { creating = true }
                )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
