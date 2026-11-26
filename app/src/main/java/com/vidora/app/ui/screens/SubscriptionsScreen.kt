package com.vidora.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.vidora.app.data.ChannelItem
import com.vidora.app.data.SubscriptionGroup
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
import com.vidora.app.account.AccountFeed
import com.vidora.app.account.AccountSync
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
    val session by Graph.account.session.collectAsState()
    val signedIn = session != null
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    // Signed in + no group: YouTube's own subscriptions feed (FEsubscriptions), like the official app.
    val useAccountFeed = signedIn && groupId == null
    val paged = remember(useAccountFeed, if (useAccountFeed) emptyList() else subs.map { it.url }) {
        Paged(scope) { if (useAccountFeed) Graph.accountApi.feed(AccountFeed.SUBSCRIPTIONS) else loadFeed() }
    }
    LaunchedEffect(paged) { if (useAccountFeed || subs.isNotEmpty()) paged.load() }
    // Keep the channel row in sync with the account's subscriptions.
    LaunchedEffect(signedIn) { if (signedIn) AccountSync.importSubscriptionsIfStale() }
    var editing by remember { mutableStateOf<SubscriptionGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    val group = library.groups.firstOrNull { it.id == groupId }
    val groupSet = remember(group) { group?.channels?.toHashSet() }
    val shownSubs = if (groupSet == null) subs else subs.filter { it.url in groupSet }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.ui_subscriptions)) },
            actions = { IconButton(onClick = { nav.navigate(Routes.search()) }) { Icon(Icons.Rounded.Search, "Search") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (subs.isEmpty() && !signedIn) {
            EmptyState(
                stringResource(R.string.ui_no_subscriptions_yet),
                stringResource(R.string.ui_subscribe_to_channels_they_are_stored_pr),
                Icons.Rounded.Subscriptions
            )
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = group == null, onClick = { groupId = null }, label = { Text(stringResource(R.string.ui_all)) }) }
            items(library.groups, key = { "g:" + it.id }) { g ->
                FilterChip(
                    selected = g.id == groupId,
                    onClick = { groupId = if (groupId == g.id) null else g.id },
                    label = { Text("${g.name} · ${g.channels.size}") },
                    trailingIcon = if (g.id == groupId) ({ Icon(Icons.Rounded.Edit, "Edit group", Modifier.size(16.dp).clickable { editing = g }) }) else null,
                )
            }
            item { AssistChip(onClick = { creating = true }, label = { Text(stringResource(R.string.ui_new_group)) }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shownSubs, key = { it.url }) { c ->
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
            EntryGrid(paged = paged, actions = actions, filter = { e -> useAccountFeed || groupSet == null || (e is Entry.Video && e.video.uploaderUrl in groupSet) })
        }
    }
    if (creating || editing != null) {
        GroupEditor(editing, subs, onDismiss = { creating = false; editing = null }) { id ->
            creating = false; editing = null
            groupId = id
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditor(initial: SubscriptionGroup?, channels: List<ChannelItem>, onDismiss: () -> Unit, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial?.channels.orEmpty()) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(if (initial == null) "New group" else "Edit group", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ui_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null) TextButton(onClick = { Graph.library.deleteGroup(initial.id); onDone(null) }) { Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                Button(enabled = name.isNotBlank(), onClick = { onDone(Graph.library.saveGroup(initial?.id, name, selected.toList())) }) { Text("Save · ${selected.size}") }
            }
        }
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(channels, key = { it.url }) { c ->
                val checked = c.url in selected
                ListItem(
                    headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar(c.avatar, 36) },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier = Modifier.clickable { if (checked) selected.remove(c.url) else selected.add(c.url) }
                )
            }
        }
    }
}

/** Fetches the latest uploads of every subscribed channel in parallel (max 6 at once). */
private suspend fun loadFeed(): PageResult = coroutineScope {
    val subs = Graph.library.data.value.subscriptions
    val gate = Semaphore(6)
    val videos = subs.map { c ->
        async {
            gate.withPermit {
                // Tag with the subscription URL so group filters match regardless of URL form (/channel/UC… vs /@handle)
                runCatching { Graph.repository.latestUploads(c.url).take(15).map { it.copy(uploaderUrl = c.url) } }.getOrDefault(emptyList())
            }
        }
    }.awaitAll().flatten()
    val sorted = videos.sortedByDescending { it.uploadedEpoch }.distinctBy { it.url }
    PageResult(sorted.map { Entry.Video(it) })
}
