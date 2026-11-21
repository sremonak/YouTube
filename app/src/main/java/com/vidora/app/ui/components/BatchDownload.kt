package com.vidora.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vidora.app.Graph
import com.vidora.app.data.VideoItem
import com.vidora.app.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Download all" confirmation: queues every video as video or audio with the default quality. */
@Composable
fun BatchDownloadDialog(videos: List<VideoItem>, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    fun start(audioOnly: Boolean) {
        onDismiss()
        Toast.makeText(context, "Queuing ${videos.size} downloads…", Toast.LENGTH_SHORT).show()
        Graph.appScope.launch {
            val n = Downloads.enqueueAll(context, videos, audioOnly)
            withContext(Dispatchers.Main) { Toast.makeText(context, "$n downloads queued", Toast.LENGTH_SHORT).show() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download ${videos.size} videos?") },
        text = { Text("Each video is saved with your default download quality. Videos already in Downloads are skipped.") },
        confirmButton = {
            Column {
                TextButton(onClick = { start(audioOnly = false) }) { Text("Video") }
                TextButton(onClick = { start(audioOnly = true) }) { Text("Audio only") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
