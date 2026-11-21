package com.vidora.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.VideoLibrary
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
    const val PLAYER = "player"
    const val CHANNEL = "channel/{url}"
    const val PLAYLIST = "playlist/{url}"
    const val SETTINGS = "settings"
    const val LIST = "list/{kind}"
    const val LOGIN = "login"
    const val ACCOUNT_FEED = "account/{feed}"
    const val LOCAL_PLAYLIST = "local/{id}"
    const val FILTERS = "filters"

    fun search(q: String = "") = "search?q=" + Uri.encode(q)
    fun channel(url: String) = "channel/" + Uri.encode(url)
    fun playlist(url: String) = "playlist/" + Uri.encode(url)
    fun list(kind: String) = "list/$kind"
    fun accountFeed(feed: String) = "account/$feed"
    fun localPlaylist(id: String) = "local/$id"
}

private data class TopDest(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val topDestinations = listOf(
    TopDest(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    TopDest(Routes.SUBS, "Subscriptions", Icons.Outlined.Subscriptions, Icons.Rounded.Subscriptions),
    TopDest(Routes.DOWNLOADS, "Downloads", Icons.Outlined.Download, Icons.Rounded.Download),
    TopDest(Routes.LIBRARY, "Library", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
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
                Graph.player.play(item)
                nav.navigate(Routes.PLAYER) { launchSingleTop = true }
            },
            openChannel = { url -> nav.navigate(Routes.channel(url)) },
            openPlaylist = { url -> nav.navigate(Routes.playlist(url)) },
            download = { item -> downloadTarget = item },
        )
    }

    // Player events (SponsorBlock skips, sleep timer, queue changes) → snackbar
    LaunchedEffect(Unit) { Graph.player.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { activity.openPlayerRequests.collect { if (Graph.player.state.value.current != null) nav.navigate(Routes.PLAYER) { launchSingleTop = true } } }
    LaunchedEffect(Unit) {
        activity.incomingLinks.collect { link ->
            activity.incomingLinks.resetReplayCache()
            openLink(link, nav, actions)
        }
    }

    // While in picture-in-picture only the video surface is shown.
    if (activity.isInPip) {
        PlayerScreen(nav = nav, actions = actions, activity = activity, pipMode = true)
        return
    }

    val isTop = topDestinations.any { it.route == route }
    val showMini = playerState.current != null && route != Routes.PLAYER && route != Routes.LOGIN
    val showNav = route != Routes.PLAYER && route != Routes.LOGIN

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!wide && showNav) {
                Column {
                    AnimatedVisibility(showMini, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        MiniPlayer(onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } })
                    }
                    if (isTop || route == null) {
                        NavigationBar {
                            topDestinations.forEach { d ->
                                NavigationBarItem(
                                    selected = route == d.route,
                                    onClick = { nav.navigateTop(d.route) },
                                    icon = { NavIcon(d, route == d.route, if (d.route == Routes.DOWNLOADS) activeDownloads else 0) },
                                    label = { Text(d.label, maxLines = 1) }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide && showNav) {
                NavigationRail(Modifier.statusBarsPadding()) {
                    topDestinations.forEach { d ->
                        NavigationRailItem(
                            selected = route == d.route,
                            onClick = { nav.navigateTop(d.route) },
                            icon = { NavIcon(d, route == d.route, if (d.route == Routes.DOWNLOADS) activeDownloads else 0) },
                            label = { Text(d.label, maxLines = 1) }
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
                    composable(Routes.HOME) { HomeScreen(nav, actions, settings) }
                    composable(Routes.SUBS) { SubscriptionsScreen(nav, actions) }
                    composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
                    composable(Routes.LIBRARY) { LibraryScreen(nav, actions) }
                    composable(Routes.SETTINGS) { SettingsScreen(nav, settings) }
                    composable(
                        Routes.SEARCH,
                        arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })
                    ) { entry -> SearchScreen(nav, actions, initialQuery = entry.arguments?.getString("q").orEmpty()) }
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
                    composable(Routes.LIST, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
                        VideoListScreen(nav, actions, entry.arguments?.getString("kind").orEmpty())
                    }
                }
                if (wide && showMini) {
                    Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        MiniPlayer(onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } }, floating = true)
                    }
                }
            }
        }
    }

    downloadTarget?.let { video -> DownloadSheet(video = video, onDismiss = { downloadTarget = null }) }
}

@Composable
private fun NavIcon(d: TopDest, selected: Boolean, badge: Int) {
    BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
        Icon(if (selected) d.selectedIcon else d.icon, contentDescription = d.label)
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
