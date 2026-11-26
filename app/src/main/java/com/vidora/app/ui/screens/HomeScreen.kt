package com.vidora.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.account.AccountFeed
import com.vidora.app.ui.components.Avatar
import com.vidora.app.data.AppSettings
import com.vidora.app.data.Entry
import com.vidora.app.data.HomeSection
import com.vidora.app.data.PageResult
import com.vidora.app.ui.Paged
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.EntryGrid
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.theme.BrandTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, actions: VideoActions, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val session by Graph.account.session.collectAsState()
    val signedIn = session != null
    var section by rememberSaveable { mutableStateOf(if (Graph.account.isSignedIn) HomeSection.ACCOUNT else HomeSection.FOR_YOU) }
    if (!signedIn && section == HomeSection.ACCOUNT) section = HomeSection.FOR_YOU
    val sections = remember(signedIn) { HomeSection.entries.filter { signedIn || it != HomeSection.ACCOUNT } }
    val pages = remember(signedIn) { HashMap<HomeSection, Paged>() }
    val paged = pages.getOrPut(section) { Paged(scope) { loadSection(section) } }
    val gridState = rememberLazyGridState()

    LaunchedEffect(section, signedIn) {
        if (paged.items.isEmpty() && !paged.loading) paged.load()
        gridState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { BrandLogo(settings.displayName.ifBlank { "Vidora" }) },
            actions = {
                IconButton(onClick = { nav.navigate(Routes.DOWNLOADS) }) { Icon(Icons.Outlined.FileDownload, "Downloads") }
                IconButton(onClick = { nav.navigate(Routes.search()) }) { Icon(Icons.Rounded.Search, "Search") }
                IconButton(onClick = { nav.navigate(if (signedIn) Routes.LIBRARY else Routes.LOGIN) }) {
                    val avatar = session?.avatar
                    if (avatar != null) Avatar(avatar, 28)
                    else Icon(Icons.Rounded.AccountCircle, if (signedIn) "Account" else "Sign in")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            items(sections) { s ->
                YtChip(selected = s == section, onClick = { section = s }) {
                    Text(s.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        PullToRefreshBox(isRefreshing = paged.refreshing, onRefresh = { paged.load(refresh = true) }, modifier = Modifier.fillMaxSize()) {
            val library by Graph.library.data.collectAsState()
            val resume = remember(library.history) { com.vidora.app.data.ContinueWatching.pick(library.history) }
            EntryGrid(
                paged = paged, actions = actions, state = gridState,
                filter = { e -> !(settings.hideShorts && e is Entry.Video && e.video.isShort) },
                header = if (resume.isEmpty() || section == HomeSection.LIVE) null else ({ ContinueWatchingRow(resume, actions) })
            )
        }
    }
}

/** "For you" is built locally from the user's own history & searches — no account, no tracking. */
private suspend fun loadSection(section: HomeSection): PageResult {
    section.kioskId?.let { return Graph.repository.kiosk(it) }
    if (section == HomeSection.ACCOUNT) return Graph.accountApi.feed(AccountFeed.RECOMMENDED)
    val lib = Graph.library.data.value
    val seeds = buildList {
        addAll(lib.searchHistory.take(5))
        addAll(lib.history.take(10).mapNotNull { it.video.uploader }.distinct().take(4))
        addAll(lib.subscriptions.shuffled().take(3).map { it.name })
    }
    val query = seeds.randomOrNull() ?: listOf("trending today", "new music", "technology news", "best of the week", "documentary").random()
    return runCatching { Graph.repository.search(query) }.getOrElse { Graph.repository.kiosk("trending_music") }
}

@Composable
private fun ContinueWatchingRow(entries: List<com.vidora.app.data.HistoryEntry>, actions: VideoActions) {
    Column(Modifier.padding(bottom = 8.dp)) {
        com.vidora.app.ui.components.SectionHeader("Continue watching")
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.video.url }) { h ->
                Column(Modifier.width(200.dp).clickable { actions.open(h.video) }) {
                    val progress = h.positionMs / 1000f / h.video.duration
                    com.vidora.app.ui.components.Thumbnail(h.video, Modifier.fillMaxWidth(), progress)
                    Text(
                        h.video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "${com.vidora.app.util.Format.duration((h.video.duration - h.positionMs / 1000).coerceAtLeast(0))} left",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                    )
                }
            }
        }
    }
}

/** YouTube-style wordmark: red rounded "play" badge + bold name. */
@Composable
fun BrandLogo(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 30.dp, height = 21.dp)
                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(name, style = BrandTitle.copy(fontSize = 20.sp, letterSpacing = (-0.8).sp), color = MaterialTheme.colorScheme.onBackground)
    }
}

/** YouTube's flat category chip: inverted colours when selected, 8 dp corners, no outline. */
@Composable
fun YtChip(selected: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
