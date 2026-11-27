package com.vidora.app.ui

import com.vidora.app.ui.theme.glass
import com.vidora.app.ui.theme.glassSource
import androidx.compose.ui.layout.onSizeChanged
import com.vidora.app.R
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.Home
import com.vidora.app.ui.icons.Subscriptions
import com.vidora.app.ui.icons.VideoLibrary
import com.vidora.app.ui.icons.AccountCircle
import com.vidora.app.ui.icons.SlowMotionVideo
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import com.vidora.app.data.AppSettings
import com.vidora.app.data.VideoItem
import com.vidora.app.download.DownloadStatus
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.screens.AccountFeedScreen
import com.vidora.app.ui.screens.FiltersScreen
import com.vidora.app.ui.screens.LocalPlaylistScreen
import com.vidora.app.ui.screens.ShortsScreen
import com.vidora.app.ui.screens.ShortsSession
import com.vidora.app.ui.screens.ChannelScreen
import com.vidora.app.ui.screens.LoginScreen
import com.vidora.app.ui.screens.DownloadsScreen
import com.vidora.app.ui.screens.HomeScreen
import com.vidora.app.ui.screens.LibraryScreen
import com.vidora.app.ui.screens.MiniPlayer
import com.vidora.app.ui.screens.PlayerScreen
import com.vidora.app.ui.screens.PlaylistScreen
import com.vidora.app.ui.screens.SearchScreen
import com.vidora.app.ui.screens.SettingsScreen
import com.vidora.app.ui.screens.SubscriptionsScreen
import com.vidora.app.ui.screens.VideoListScreen

object Routes {
    const val HOME = "home"
    const val SUBS = "subscriptions"
    const val DOWNLOADS = "downloads"
    const val LIBRARY = "library"
    const val SEARCH = "search?q={q}"
    const val PUBLIC_SEARCH = "publicsearch?q={q}"
    const val PLAYER = "player"
    const val CHANNEL = "channel/{url}"
    const val PLAYLIST = "playlist/{url}"
    const val SETTINGS = "settings"
    const val LIST = "list/{kind}"
    const val LOGIN = "login"
    const val ACCOUNT_FEED = "account/{feed}"
    const val LOCAL_PLAYLIST = "local/{id}"
    const val FILTERS = "filters"
    const val SHORTS = "shorts"
    const val ABOUT = "about"

    fun search(q: String = "") = "search?q=" + Uri.encode(q)
    fun publicSearch(q: String) = "publicsearch?q=" + Uri.encode(q)
    fun channel(url: String) = "channel/" + Uri.encode(url)
    fun playlist(url: String) = "playlist/" + Uri.encode(url)
    fun list(kind: String) = "list/$kind"
    fun accountFeed(feed: String) = "account/$feed"
    fun localPlaylist(id: String) = "local/$id"
}

private data class TopDest(val route: String, @androidx.annotation.StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

/** YouTube's bottom bar: Home · Shorts · Subscriptions · You (downloads live under You). */
private val topDestinations = listOf(
    TopDest(Routes.HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Rounded.Home),
    TopDest(Routes.SHORTS, R.string.nav_shorts, Icons.Outlined.SlowMotionVideo, Icons.Rounded.SlowMotionVideo),
    TopDest(Routes.SUBS, R.string.nav_subscriptions, Icons.Outlined.Subscriptions, Icons.Rounded.Subscriptions),
    TopDest(Routes.LIBRARY, R.string.nav_you, Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle),
)

@Composable
fun AppRoot(activity: MainActivity, settings: AppSettings) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playerState by Graph.player.state.collectAsState()
    val downloads by Graph.downloads.items.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var downloadTarget by remember { mutableStateOf<VideoItem?>(null) }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val activeDownloads = downloads.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.MUXING || it.status == DownloadStatus.QUEUED }

    val actions = remember(nav) {
        VideoActions(
            open = { item ->
                if (item.isShort && Graph.settingsState.value.shortsPlayer) {
                    ShortsSession.seed = listOf(item)
                    nav.navigate(Routes.SHORTS) { launchSingleTop = true }
                } else {
                    Graph.player.play(item)
                    nav.navigate(Routes.PLAYER) { launchSingleTop = true }
                }
            },
            openChannel = { url -> nav.navigate(Routes.channel(url)) },
            openPlaylist = { url -> nav.navigate(Routes.playlist(url)) },
            download = { item -> downloadTarget = item },
        )
    }

    // Player events (SponsorBlock skips, sleep timer, queue changes) → snackbar
    LaunchedEffect(Unit) { Graph.player.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        activity.openAboutRequests.collect {
            activity.openAboutRequests.resetReplayCache()
            nav.navigate(Routes.ABOUT) { launchSingleTop = true }
        }
    }
    LaunchedEffect(Unit) { activity.openPlayerRequests.collect { if (Graph.player.state.value.current != null) nav.navigate(Routes.PLAYER) { launchSingleTop = true } } }
    LaunchedEffect(Unit) {
        activity.incomingLinks.collect { link ->
            activity.incomingLinks.resetReplayCache()
            openLink(link, nav, actions)
        }
    }

    // Back on Home (the root) → floating "Exit Vidora?" card instead of closing straight away.
    // Registered before the NavHost, so screens' own back handlers still win.
    var askExit by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = route == Routes.HOME && !activity.isInPip) { askExit = true }
    if (askExit) com.vidora.app.ui.components.ExitCard(activity) { askExit = false }

    // While in picture-in-picture only the video surface is shown.
    if (activity.isInPip) {
        PlayerScreen(nav = nav, actions = actions, activity = activity, pipMode = true)
        return
    }

    val isTop = topDestinations.any { it.route == route }
    val showMini = playerState.current != null && route != Routes.PLAYER && route != Routes.LOGIN && route != Routes.SHORTS
    val showNav = route != Routes.PLAYER && route != Routes.LOGIN

    val glassState = com.vidora.app.ui.theme.rememberGlassState()
    val lowRam = androidx.compose.runtime.remember {
        (activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
    }
    val glassOn = settings.glassUi && !lowRam
    androidx.compose.runtime.SideEffect { com.vidora.app.ui.icons.IconStyle.ios = settings.iosIcons }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var chromePx by remember { mutableStateOf(0) }
    val bottomChrome = when {
        !wide && showNav -> with(density) { chromePx.toDp() }
        wide && showMini -> 96.dp
        else -> 0.dp
    }
    // Content scrolls *under* the glass bars; Shorts (full-bleed video) and the plain style keep the old layout.
    val padContent = !glassOn || route == Routes.SHORTS

    androidx.compose.runtime.CompositionLocalProvider(
        com.vidora.app.ui.theme.LocalGlass provides com.vidora.app.ui.theme.GlassPrefs(glassOn, glassState),
        com.vidora.app.ui.theme.LocalBottomChrome provides if (padContent) 0.dp else bottomChrome,
    ) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxSize()) {
            if (wide && showNav) {
                NavigationRail(Modifier.statusBarsPadding()) {
                    topDestinations.forEach { d ->
                        NavigationRailItem(
                            selected = route == d.route,
                            onClick = { if (route == d.route) TabReselect.events.tryEmit(d.route) else nav.navigateTop(d.route) },
                            icon = { NavIcon(d, route == d.route, if (d.route == Routes.LIBRARY) activeDownloads else 0) },
                            label = { Text(androidx.compose.ui.res.stringResource(d.label), maxLines = 1) }
                        )
                    }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxSize()
                    .padding(bottom = if (padContent && !wide) bottomChrome else 0.dp)
                    .glassSource(glassState, glassOn && !padContent)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Inside the captured content glass can't sample itself → frosted tint only.
                androidx.compose.runtime.CompositionLocalProvider(
                    com.vidora.app.ui.theme.LocalGlass provides com.vidora.app.ui.theme.GlassPrefs(glassOn, null)
                ) {
                NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
                    composable(Routes.HOME) { HomeScreen(nav, actions, settings) }
                    composable(Routes.SUBS) { SubscriptionsScreen(nav, actions) }
                    composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
                    composable(Routes.LIBRARY) { LibraryScreen(nav, actions) }
                    composable(Routes.SETTINGS) { SettingsScreen(nav, settings) }
                    composable(Routes.ABOUT) { com.vidora.app.ui.screens.AboutScreen(nav) }
                    composable(
                        Routes.SEARCH,
                        arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })
                    ) { entry -> SearchScreen(nav, actions, initialQuery = entry.arguments?.getString("q").orEmpty()) }
                    composable(Routes.PUBLIC_SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { entry ->
                        SearchScreen(nav, actions, entry.arguments?.getString("q").orEmpty(), com.vidora.app.data.VideoSource.PUBLIC)
                    }
                    composable(Routes.PLAYER) { PlayerScreen(nav = nav, actions = actions, activity = activity, pipMode = false, onDownload = { downloadTarget = it }) }
                    composable(Routes.CHANNEL, arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
                        ChannelScreen(nav, actions, Uri.decode(entry.arguments?.getString("url").orEmpty()))
                    }
                    composable(Routes.PLAYLIST, arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
                        PlaylistScreen(nav, actions, Uri.decode(entry.arguments?.getString("url").orEmpty()))
                    }
                    composable(Routes.LOGIN) { LoginScreen(nav) }
                    composable(Routes.ACCOUNT_FEED, arguments = listOf(navArgument("feed") { type = NavType.StringType })) { entry ->
                        AccountFeedScreen(nav, actions, entry.arguments?.getString("feed").orEmpty())
                    }
                    composable(Routes.LOCAL_PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                        LocalPlaylistScreen(nav, actions, entry.arguments?.getString("id").orEmpty())
                    }
                    composable(Routes.FILTERS) { FiltersScreen(nav) }
                    composable(Routes.SHORTS) { ShortsScreen(nav, actions) }
                    composable(Routes.LIST, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
                        VideoListScreen(nav, actions, entry.arguments?.getString("kind").orEmpty())
                    }
                }
                }
            }
        }
        if (wide && showMini) {
            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                MiniPlayer(onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } }, floating = true)
            }
        }
        if (!wide && showNav) {
            Column(
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { chromePx = it.height }
            ) {
                AnimatedVisibility(showMini, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    MiniPlayer(onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } }, floating = glassOn)
                }
                if (isTop || route == null) {
                    val onShorts = route == Routes.SHORTS
                    val glassNav = glassOn && !onShorts
                    if (!glassNav) {
                        androidx.compose.material3.HorizontalDivider(
                            thickness = 0.5.dp,
                            color = if (onShorts) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    NavigationBar(
                        containerColor = when {
                            onShorts -> Color.Black
                            glassNav -> Color.Transparent
                            else -> MaterialTheme.colorScheme.background
                        },
                        tonalElevation = 0.dp,
                        modifier = if (glassNav) Modifier.glass(
                            androidx.compose.foundation.shape.RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                        ) else Modifier,
                    ) {
                        val fg = if (onShorts) Color.White else MaterialTheme.colorScheme.onSurface
                        topDestinations.forEach { d ->
                            val selected = route == d.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { if (route == d.route) TabReselect.events.tryEmit(d.route) else nav.navigateTop(d.route) },
                                icon = { NavIcon(d, selected, if (d.route == Routes.LIBRARY) activeDownloads else 0) },
                                label = { Text(androidx.compose.ui.res.stringResource(d.label), maxLines = 1, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null) },
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = fg, selectedTextColor = fg,
                                    unselectedIconColor = fg.copy(alpha = if (glassNav) 0.75f else 1f), unselectedTextColor = fg.copy(alpha = if (glassNav) 0.75f else 1f),
                                    // Glass: a soft luminous pill behind the selected tab
                                    indicatorColor = if (glassNav) fg.copy(alpha = 0.10f) else Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = bottomChrome + 8.dp)
        )
    }
    }

    downloadTarget?.let { video -> DownloadSheet(video = video, onDismiss = { downloadTarget = null }) }
    com.vidora.app.ui.components.StartupDialogs(onSignIn = { nav.navigate(Routes.LOGIN) { launchSingleTop = true } }, showUpdates = route != Routes.LOGIN)
}

@Composable
private fun NavIcon(d: TopDest, selected: Boolean, badge: Int) {
    BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
        Icon(if (selected) d.selectedIcon else d.icon, contentDescription = androidx.compose.ui.res.stringResource(d.label))
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun openLink(link: String, nav: NavHostController, actions: VideoActions) {
    val repo = Graph.repository
    when {
        repo.isStreamUrl(link) -> actions.open(VideoItem(url = link, title = "Loading…"))
        repo.isPlaylistUrl(link) -> nav.navigate(Routes.playlist(link))
        repo.isChannelUrl(link) -> nav.navigate(Routes.channel(link))
        else -> nav.navigate(Routes.search(link))
    }
}

@Composable
fun brandColor() = MaterialTheme.colorScheme.primary
