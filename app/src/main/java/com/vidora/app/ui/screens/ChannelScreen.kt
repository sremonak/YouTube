package com.vidora.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.vidora.app.Graph
import com.vidora.app.data.ChannelDetails
import com.vidora.app.ui.Paged
import com.vidora.app.ui.components.Avatar
import com.vidora.app.ui.components.EntryGrid
import com.vidora.app.ui.components.ErrorState
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.util.Format

@Composable
fun ChannelScreen(nav: NavHostController, actions: VideoActions, url: String) {
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<ChannelDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(url, reload) {
        error = null
        runCatching { Graph.repository.channel(url) }
            .onSuccess { details = it }
            .onFailure { error = it.message ?: "Failed to load channel" }
    }

    val d = details
    Column(Modifier.fillMaxSize()) {
        when {
            error != null && d == null -> {
                Row(Modifier.statusBarsPadding()) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
                ErrorState(error ?: "") { reload++ }
            }
            d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val tabs = d.tabs
                val selected = tab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
                val paged = remember(d, selected) {
                    tabs.getOrNull(selected)?.let { t -> Paged(scope) { t.load() } }
                }
                LaunchedEffect(paged) { paged?.load() }
                if (paged != null) {
                    EntryGrid(
                        paged = paged,
                        actions = actions,
                        header = {
                            Column {
                                ChannelHeader(d, onBack = { nav.popBackStack() })
                                if (tabs.size > 1) {
                                    PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 12.dp) {
                                        tabs.forEachIndexed { i, t ->
                                            Tab(selected = i == selected, onClick = { tab = i }, text = { Text(t.name) })
                                        }
                                    }
                                }
                                val loadedVideos = paged.items.filterIsInstance<com.vidora.app.data.Entry.Video>().map { it.video }
                                if (loadedVideos.isNotEmpty()) {
                                    var batch by remember { mutableStateOf(false) }
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.AssistChip(
                                            onClick = { Graph.player.play(loadedVideos.first(), loadedVideos); nav.navigate(com.vidora.app.ui.Routes.PLAYER) },
                                            label = { Text("Play all") },
                                            leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)) }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        androidx.compose.material3.AssistChip(
                                            onClick = { batch = true },
                                            label = { Text("Download ${loadedVideos.size}") },
                                            leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) }
                                        )
                                    }
                                    if (batch) com.vidora.app.ui.components.BatchDownloadDialog(loadedVideos) { batch = false }
                                }
                            }
                        }
                    )
                } else {
                    ChannelHeader(d, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun ChannelHeader(d: ChannelDetails, onBack: () -> Unit) {
    val library by Graph.library.data.collectAsState()
    val subscribed = library.subscriptions.any { it.url == d.channel.url }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(4.2f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (d.banner != null) AsyncImage(d.banner, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
            }
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(d.channel.avatar, 72)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.channel.verified) Icon(Icons.Rounded.Verified, null, Modifier.padding(start = 6.dp).size(18.dp))
                }
                Text(Format.subscribers(d.channel.subscribers), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        d.channel.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(12.dp))
        val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        if (subscribed) FilledTonalButton(onClick = { Graph.library.toggleSubscription(d.channel) }, modifier = modifier) { Text("Subscribed") }
        else Button(onClick = { Graph.library.toggleSubscription(d.channel) }, modifier = modifier) { Text("Subscribe") }
        Spacer(Modifier.height(8.dp))
    }
}
