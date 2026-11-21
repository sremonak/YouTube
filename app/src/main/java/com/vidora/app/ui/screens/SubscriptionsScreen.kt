package com.vidora.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.data.Entry
import com.vidora.app.data.PageResult
import com.vidora.app.ui.Paged
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.Avatar
import com.vidora.app.ui.components.EmptyState
import com.vidora.app.ui.components.EntryGrid
import com.vidora.app.ui.components.VideoActions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(nav: NavHostController, actions: VideoActions) {
    val library by Graph.library.data.collectAsState()
    val scope = rememberCoroutineScope()
    val subs = library.subscriptions
    val paged = remember(subs.map { it.url }) { Paged(scope) { loadFeed() } }
    LaunchedEffect(paged) { if (subs.isNotEmpty()) paged.load() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Subscriptions") },
            actions = { IconButton(onClick = { nav.navigate(Routes.search()) }) { Icon(Icons.Rounded.Search, "Search") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (subs.isEmpty()) {
            EmptyState(
                "No subscriptions yet",
                "Subscribe to channels — they are stored privately on this device, no account needed.",
                Icons.Rounded.Subscriptions
            )
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(subs, key = { it.url }) { c ->
                Column(
                    Modifier.width(68.dp).clickable { nav.navigate(Routes.channel(c.url)) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Avatar(c.avatar, 56)
                    Text(c.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        PullToRefreshBox(isRefreshing = paged.refreshing, onRefresh = { paged.load(refresh = true) }, modifier = Modifier.fillMaxSize()) {
            EntryGrid(paged = paged, actions = actions)
        }
    }
}

/** Fetches the latest uploads of every subscribed channel in parallel (max 6 at once). */
private suspend fun loadFeed(): PageResult = coroutineScope {
    val subs = Graph.library.data.value.subscriptions
    val gate = Semaphore(6)
    val videos = subs.map { c ->
        async { gate.withPermit { runCatching { Graph.repository.latestUploads(c.url).take(15) }.getOrDefault(emptyList()) } }
    }.awaitAll().flatten()
    val sorted = videos.sortedByDescending { it.uploadedEpoch }.distinctBy { it.url }
    PageResult(sorted.map { Entry.Video(it) })
}
