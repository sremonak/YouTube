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
import com.vidora.app.ui.icons.AccountCircle
import com.vidora.app.ui.icons.PlayArrow
import com.vidora.app.ui.icons.FileDownload
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.vidora.app.ui.icons.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import com.vidora.app.util.Updater
import com.vidora.app.ui.icons.IosGlyphs
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.vidora.app.account.FeedChip
import com.vidora.app.data.PageResult
import com.vidora.app.ui.Paged
import com.vidora.app.ui.UiCache
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.EntryGrid
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.theme.BrandTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, actions: VideoActions, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updateAvailable by Updater.available.collectAsStateWithLifecycle()
    val checkingUpdate by Updater.checking.collectAsStateWithLifecycle()
    val region by Graph.region.state.collectAsStateWithLifecycle()
    LaunchedEffect(settings.contentCountry, settings.autoRegionByIp) {
        Graph.region.resolve(settings)
    }
    val session by Graph.account.session.collectAsStateWithLifecycle()
    val signedIn = session != null && !settings.incognito
    // Account recommendations remain optional; Explore stays the default for every user.
    var accountChips by remember(signedIn) { mutableStateOf(UiCache.get<List<FeedChip>>("home:chips") ?: emptyList()) }
    val tabs = remember(signedIn, accountChips, region.code) {
        buildList {
            add(HomeTab(HomeSection.FOR_YOU.name, HomeSection.FOR_YOU.label) { loadSection(HomeSection.FOR_YOU, region.code) })
            if (signedIn) {
                add(HomeTab("acc", "YouTube account") {
                    val first = Graph.accountApi.feedWithChips(AccountFeed.RECOMMENDED)
                    if (first.chips.isNotEmpty()) { accountChips = first.chips; UiCache.put("home:chips", first.chips) }
                    first.page
                })
                accountChips.filter { it.token != null && !it.label.equals("All", true) }.forEach { chip ->
                    add(HomeTab("chip:" + chip.label, chip.label) { Graph.accountApi.chip(chip.token!!) })
                }
                add(HomeTab("ytmusic", "YouTube Music") { Graph.accountApi.musicHome() })
            }
            val taken = map { it.label.lowercase() }.toSet()
            HomeSection.entries
                .filter { it != HomeSection.ACCOUNT && it != HomeSection.FOR_YOU }
                .filter { it.label.lowercase() !in taken }
                .forEach { sec -> add(HomeTab(sec.name, sec.label) { loadSection(sec, region.code) }) }
            add(HomeTab("peertube", "PeerTube") { Graph.openVideos.peerSearch(null) })
            add(HomeTab("commons", "Commons") { Graph.openVideos.commonsSearch(com.vidora.app.data.GuestSources.countryName(region.code)) })
        }
    }
    var tabKey by rememberSaveable(signedIn) { mutableStateOf(if (signedIn) "acc" else HomeSection.FOR_YOU.name) }
    val tab = tabs.firstOrNull { it.key == tabKey } ?: tabs.first()
    // Cached across navigation: coming back from Settings / the player shows the same feed instantly.
    val rankingKey = "${settings.homeLanguage}:${settings.homeInterests.sorted()}:${settings.lessLikeVideos.hashCode()}:${settings.incognito}"
    val paged = remember(tab.key, signedIn, region.code, rankingKey) {
        if (tab.key == HomeSection.FOR_YOU.name) UiCache.progressive("home:public:${region.code}:$rankingKey", persist = !settings.incognito) {
            Graph.discovery.homeUpdates(region.code, settings, if (settings.incognito) emptySet() else Graph.library.data.value.history.map { it.video.url }.toSet())
        } else UiCache.paged("home:$signedIn:${region.code}:${tab.key}") { tab.load() }
    }
    androidx.compose.runtime.DisposableEffect(paged) { onDispose { paged.cancelPending() } }
    androidx.activity.compose.ReportDrawnWhen { paged.items.isNotEmpty() || paged.error != null }
    val gridState = rememberLazyGridState()
    var homeOptions by remember { mutableStateOf(false) }
    var regionDetails by remember { mutableStateOf(false) }
    var showContinue by rememberSaveable { mutableStateOf(true) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(paged, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                // Revalidate old Home on return, but never jump a user who is reading down the feed.
                if (gridState.firstVisibleItemIndex == 0) paged.ensureLoaded(com.vidora.app.data.FeedFreshness.REVALIDATE_MS)
                delay(60_000)
            }
        }
    }
    if (regionDetails) com.vidora.app.ui.theme.GlassAlertDialog(
        onDismissRequest = { regionDetails = false },
        title = { Text("Home region") },
        text = { Text("${com.vidora.app.data.GuestSources.countryName(region.code)} · ${region.method}. Public Explore uses regional topics, not official regional charts. Search is independent. Signed-in YouTube recommendations use your account.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { regionDetails = false }) { Text("Done") } },
    )

    var shownTab by rememberSaveable { mutableStateOf(tab.key) }
    LaunchedEffect(tab.key, signedIn, region.code, paged) {
        paged.ensureLoaded(com.vidora.app.data.FeedFreshness.REVALIDATE_MS)
        // Only jump to the top when the user switched chips — not when returning to Home.
        if (shownTab != tab.key) { shownTab = tab.key; gridState.scrollToItem(0) }
    }

    com.vidora.app.ui.theme.GlassPage(header = {
        TopAppBar(
            title = { BrandLogo(settings.displayName.ifBlank { "Vidora" }) },
            actions = {
                IconButton(onClick = { nav.navigate(Routes.search()) }) { Icon(Icons.Rounded.Search, "Search") }
                IconButton(
                    enabled = !checkingUpdate,
                    onClick = { scope.launch { Updater.checkForUser(context.applicationContext) } },
                ) {
                    if (checkingUpdate) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else BadgedBox(badge = { if (updateAvailable != null) Badge() }) {
                        Icon(IosGlyphs.arrowDownCircleFill, if (updateAvailable != null) "Update available" else "Check for updates")
                    }
                }
                Box {
                    IconButton(onClick = { homeOptions = true }) { Icon(Icons.Rounded.AccountCircle, "Home options") }
                    androidx.compose.material3.DropdownMenu(expanded = homeOptions, onDismissRequest = { homeOptions = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Downloads") }, onClick = { homeOptions = false; nav.navigate(Routes.DOWNLOADS) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Your library") }, onClick = { homeOptions = false; nav.navigate(Routes.LIBRARY) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Home preferences") }, onClick = { homeOptions = false; nav.navigate(Routes.SETTINGS) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(if (showContinue) "Hide Continue watching" else "Show Continue watching") }, onClick = { showContinue = !showContinue; homeOptions = false })
                    }
                }
            },
            colors = com.vidora.app.ui.theme.glassBarColors()
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = { regionDetails = true }, modifier = Modifier.weight(1f)) {
                Text(if (signedIn && tab.key == "acc") "Your YouTube recommendations" else "Explore · ${region.code}", maxLines = 2)
            }
            androidx.compose.material3.TextButton(enabled = !paged.loading && !paged.refreshing, onClick = {
                paged.load(refresh = true)
                scope.launch { gridState.scrollToItem(0) }
            }) { Text(if (paged.refreshing) "Refreshing…" else "Refresh feed") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            items(tabs, key = { it.key }) { t ->
                YtChip(selected = t.key == tab.key, onClick = { tabKey = t.key }) {
                    Text(t.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }) {
        com.vidora.app.ui.theme.GlassRefreshBox(isRefreshing = paged.refreshing, onRefresh = { paged.load(refresh = true) }, modifier = Modifier.fillMaxSize()) {
            val library by Graph.library.data.collectAsStateWithLifecycle()
            val resume = remember(library.history) { com.vidora.app.data.ContinueWatching.pick(library.history) }
            EntryGrid(
                paged = paged, actions = actions, state = gridState,
                explainHome = tab.key == HomeSection.FOR_YOU.name,
                filter = { e -> !(e is Entry.Video && ((settings.hideShorts && e.video.isShort) || (tab.key == HomeSection.FOR_YOU.name && e.video.url in settings.lessLikeVideos))) },
                header = {
                    Column {
                        com.vidora.app.ui.components.ProviderStatus(paged)
                        if (showContinue && resume.isNotEmpty() && tab.key == HomeSection.FOR_YOU.name) ContinueWatchingRow(resume, actions)
                    }
                }
            )
        }
    }
}

/** One chip of the Home feed. */
private class HomeTab(val key: String, val label: String, val load: suspend () -> PageResult)

/** Home is region-biased; typed search is kept separate and unmodified. */
private suspend fun loadSection(section: HomeSection, country: String): PageResult {
    if (section == HomeSection.FOR_YOU) return Graph.discovery.home(country)
    section.kioskId?.let { kiosk ->
        try { return Graph.repository.kiosk(kiosk) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return Graph.discovery.search(com.vidora.app.data.GuestSources.regionalQuery(country, section.label)) }
    }
    return Graph.discovery.home(country)
}

@Composable
private fun ContinueWatchingRow(entries: List<com.vidora.app.data.HistoryEntry>, actions: VideoActions) {
    Column(Modifier.padding(bottom = 8.dp)) {
        com.vidora.app.ui.components.SectionHeader("Continue watching")
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.video.url }) { h ->
                Column(Modifier.width(176.dp).clickable { actions.open(h.video) }) {
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
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
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
        color = when {
            selected -> MaterialTheme.colorScheme.onSurface
            com.vidora.app.ui.theme.LocalGlass.current.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
