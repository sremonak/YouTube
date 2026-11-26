package com.vidora.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.data.Entry
import com.vidora.app.data.PlaylistDetails
import com.vidora.app.ui.Paged
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.EntryGrid
import com.vidora.app.ui.components.ErrorState
import com.vidora.app.ui.components.VideoActions

@Composable
fun PlaylistScreen(nav: NavHostController, actions: VideoActions, url: String) {
    val scope = rememberCoroutineScope()
    var details by remember(url) { mutableStateOf(com.vidora.app.ui.UiCache.get<PlaylistDetails>("playlist-d:$url")) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(url, reload) {
        error = null
        runCatching { if (reload == 0 && details != null) details!! else Graph.repository.playlist(url).also { com.vidora.app.ui.UiCache.put("playlist-d:$url", it) } }
            .onSuccess { details = it }
            .onFailure { error = it.message ?: "Failed to load playlist" }
    }

    val d = details
    when {
        error != null && d == null -> Column(Modifier.statusBarsPadding()) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            ErrorState(error ?: "") { reload++ }
        }
        d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> {
            val paged = remember(d) { com.vidora.app.ui.UiCache.paged("playlist:$url") { d.first } }
            LaunchedEffect(paged) { paged.ensureLoaded() }
            EntryGrid(paged = paged, actions = actions, header = {
                Column(Modifier.statusBarsPadding().padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Text(stringResource(R.string.ui_playlist), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(d.playlist.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(
                        listOfNotNull(d.playlist.uploader, d.playlist.count.takeIf { it >= 0 }?.let { "$it videos" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    if (d.description.isNotBlank()) {
                        Text(d.description, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val videos = paged.items.filterIsInstance<Entry.Video>().map { it.video }
                        Button(onClick = {
                            videos.firstOrNull()?.let { Graph.player.play(it, videos); nav.navigate(Routes.PLAYER) }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ui_play_all))
                        }
                        FilledTonalButton(onClick = {
                            val shuffled = videos.shuffled()
                            shuffled.firstOrNull()?.let { Graph.player.play(it, shuffled); nav.navigate(Routes.PLAYER) }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ui_shuffle))
                        }
                        var batch by remember { mutableStateOf(false) }
                        IconButton(onClick = { batch = true }, enabled = videos.isNotEmpty()) { Icon(Icons.Rounded.Download, "Download all") }
                        if (batch) com.vidora.app.ui.components.BatchDownloadDialog(videos) { batch = false }
                    }
                }
            })
        }
    }
}
