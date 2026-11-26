package com.vidora.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import com.vidora.app.account.FeedChip
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
    // YouTube's own filter chips for this feed (e.g. History: All / Videos / Shorts / Music).
    var chips by remember(feed) { mutableStateOf(com.vidora.app.ui.UiCache.get<List<FeedChip>>("acc:${feed.name}:chips") ?: emptyList()) }
    var chipLabel by androidx.compose.runtime.saveable.rememberSaveable(feed) { mutableStateOf<String?>(null) }
    val chip = chips.firstOrNull { it.label == chipLabel && it.token != null }
    val paged = remember(feed, chip?.label) {
        com.vidora.app.ui.UiCache.paged("acc:${feed.name}:${chip?.label}") {
            if (chip != null) Graph.accountApi.chip(chip.token!!)
            else Graph.accountApi.feedWithChips(feed).also {
                if (it.chips.isNotEmpty()) { chips = it.chips; com.vidora.app.ui.UiCache.put("acc:${feed.name}:chips", it.chips) }
            }.page
        }
    }
    LaunchedEffect(paged) { paged.ensureLoaded() }

    com.vidora.app.ui.theme.GlassPage(header = {
        TopAppBar(
            title = { Text(feed.label) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                val videos = paged.items.filterIsInstance<Entry.Video>().map { it.video }
                if (videos.isNotEmpty()) {
                    TextButton(onClick = { Graph.player.play(videos.first(), videos); nav.navigate(Routes.PLAYER) }) { Text(stringResource(R.string.ui_play_all)) }
                }
            },
            colors = com.vidora.app.ui.theme.glassBarColors()
        )
        if (chips.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                items(chips, key = { it.label }) { c ->
                    val selected = if (chipLabel == null) c.token == null || c.selected else c.label == chipLabel
                    YtChip(selected = selected, onClick = { chipLabel = if (c.token == null) null else c.label }) {
                        Text(c.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }) {
        com.vidora.app.ui.theme.GlassRefreshBox(isRefreshing = paged.refreshing, onRefresh = { paged.load(refresh = true) }, modifier = Modifier.fillMaxSize()) {
            EntryGrid(paged = paged, actions = actions)
        }
    }
}
