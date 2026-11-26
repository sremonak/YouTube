package com.vidora.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.download.DownloadKind
import com.vidora.app.download.DownloadRecord
import com.vidora.app.download.DownloadStatus
import com.vidora.app.download.Downloads
import com.vidora.app.download.Storage
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.EmptyState
import com.vidora.app.ui.components.Thumbnail
import com.vidora.app.util.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val items by Graph.downloads.items.collectAsState()
    var filter by rememberSaveable { mutableStateOf(0) } // 0 all, 1 video, 2 audio
    var deleteTarget by remember { mutableStateOf<DownloadRecord?>(null) }

    // Storage permission is only needed on Android 9 and older.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            (context as? android.app.Activity)?.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
    }

    val visible = items.filter {
        when (filter) {
            1 -> it.kind == DownloadKind.VIDEO
            2 -> it.kind == DownloadKind.AUDIO
            else -> true
        }
    }
    val totalSize = items.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.totalBytes }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.ui_downloads))
                    Text("${items.count { it.status == DownloadStatus.COMPLETED }} files · ${Format.bytes(totalSize)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "Video", "Audio").withIndex().toList()) { (i, label) ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
            }
        }
        if (visible.isEmpty()) {
            EmptyState(
                stringResource(R.string.ui_no_downloads_yet),
                stringResource(R.string.ui_tap_download_on_any_video_to_save_it_as),
                Icons.Rounded.Download
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = com.vidora.app.ui.theme.bottomChromePadding())) {
                items(visible, key = { it.id }) { record ->
                    DownloadRow(record, onPlay = {
                        val uri = record.uri?.toUri() ?: return@DownloadRow
                        if (!Storage.exists(context, record)) {
                            Toast.makeText(context, "File was moved or deleted", Toast.LENGTH_SHORT).show()
                            return@DownloadRow
                        }
                        Graph.player.playLocal(record.video, uri, audioOnly = record.kind == DownloadKind.AUDIO)
                        nav.navigate(Routes.PLAYER)
                    }, onDelete = { deleteTarget = record })
                }
            }
        }
    }

    deleteTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.ui_delete_download)) },
            text = { Text(r.video.title) },
            confirmButton = {
                TextButton(onClick = { Downloads.delete(context, r.id, deleteFile = true); deleteTarget = null }) { Text(stringResource(R.string.ui_delete_file)) }
            },
            dismissButton = {
                TextButton(onClick = { Downloads.delete(context, r.id, deleteFile = false); deleteTarget = null }) { Text(stringResource(R.string.ui_remove_from_list)) }
            }
        )
    }
}

@Composable
private fun DownloadRow(record: DownloadRecord, onPlay: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val active = record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.QUEUED || record.status == DownloadStatus.MUXING

    Row(
        Modifier.fillMaxWidth().clickable(enabled = record.status == DownloadStatus.COMPLETED, onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(record.video.copy(duration = if (record.kind == DownloadKind.AUDIO) 0 else record.video.duration), Modifier.width(128.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val statusText = when (record.status) {
                DownloadStatus.QUEUED -> "Waiting for network…"
                DownloadStatus.RUNNING -> "${(record.progress * 100).toInt()}% · ${Format.bytes(record.downloadedBytes)} / ${Format.bytes(record.totalBytes)} · ${Format.speed(record.speedBps)}"
                DownloadStatus.MUXING -> "Merging audio + video…"
                DownloadStatus.COMPLETED -> "${if (record.kind == DownloadKind.AUDIO) "Audio" else "Video"} · ${record.qualityLabel} · ${Format.bytes(record.totalBytes)}"
                DownloadStatus.FAILED -> "Failed: ${record.error ?: "unknown error"}"
                DownloadStatus.CANCELLED -> "Cancelled"
            }
            Text(
                statusText, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                color = if (record.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (active) {
                Spacer(Modifier.height(6.dp))
                if (record.status == DownloadStatus.RUNNING && record.progress > 0f) {
                    LinearProgressIndicator(progress = { record.progress }, modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        when {
            active -> IconButton(onClick = { Downloads.cancel(context, record.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
            record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELLED ->
                IconButton(onClick = { scope.launch { Downloads.retry(context, record.id) } }) { Icon(Icons.Rounded.Refresh, "Retry") }
            else -> IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Play") }
        }
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (record.status == DownloadStatus.COMPLETED) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_open_with)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = {
                        menu = false
                        val uri = Storage.shareableUri(context, record) ?: return@DropdownMenuItem
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try { context.startActivity(Intent.createChooser(intent, "Open with")) } catch (_: ActivityNotFoundException) {}
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_share_file)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                        menu = false
                        val uri = Storage.shareableUri(context, record) ?: return@DropdownMenuItem
                        val intent = Intent(Intent.ACTION_SEND).setType(record.mime).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "Share"))
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
