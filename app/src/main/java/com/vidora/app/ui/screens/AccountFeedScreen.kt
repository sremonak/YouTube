package com.vidora.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.account.AccountFeed
import com.vidora.app.data.Entry
import com.vidora.app.ui.Paged
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.EntryGrid
import com.vidora.app.ui.components.VideoActions

/** Recommendations / subscriptions / liked / watch later / history from the signed-in account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFeedScreen(nav: NavHostController, actions: VideoActions, feedName: String) {
    val feed = AccountFeed.entries.firstOrNull { it.name == feedName } ?: AccountFeed.RECOMMENDED
    val scope = rememberCoroutineScope()
    val paged = remember(feed) { Paged(scope) { Graph.accountApi.feed(feed) } }
    LaunchedEffect(paged) { paged.load() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(feed.label) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                val videos = paged.items.filterIsInstance<Entry.Video>().map { it.video }
                if (videos.isNotEmpty()) {
                    TextButton(onClick = { Graph.player.play(videos.first(), videos); nav.navigate(Routes.PLAYER) }) { Text("Play all") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        PullToRefreshBox(isRefreshing = paged.refreshing, onRefresh = { paged.load(refresh = true) }, modifier = Modifier.fillMaxSize()) {
            EntryGrid(paged = paged, actions = actions)
        }
    }
}
