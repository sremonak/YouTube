package com.vidora.app.ui.components

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
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
        text = { Text(stringResource(R.string.ui_each_video_is_saved_with_your_default_do)) },
        confirmButton = {
            Column {
                TextButton(onClick = { start(audioOnly = false) }) { Text(stringResource(R.string.ui_video)) }
                TextButton(onClick = { start(audioOnly = true) }) { Text(stringResource(R.string.ui_audio_only)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}
