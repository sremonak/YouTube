package com.vidora.app.ui.screens

import com.vidora.app.ui.theme.glassOverlay
import androidx.compose.foundation.border
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.ArrowBack
import com.vidora.app.ui.icons.Refresh
import com.vidora.app.data.PageResult
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.Favorite
import com.vidora.app.ui.icons.FavoriteBorder
import com.vidora.app.ui.icons.PlayArrow
import com.vidora.app.ui.icons.Share
import com.vidora.app.ui.icons.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.vidora.app.Graph
import com.vidora.app.data.Community
import com.vidora.app.data.Votes
import com.vidora.app.data.VideoItem
import com.vidora.app.ui.components.Avatar
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.util.Format
import kotlinx.coroutines.flow.distinctUntilChanged

/** Videos queued for the Shorts player; the first one is where the pager starts. */
object ShortsSession {
    var seed: List<VideoItem> = emptyList()
}

/**
 * Full-screen vertical Shorts player. Swiping moves to the next short; the list keeps
 * extending with related shorts as each one loads. Uses the shared app player, so
 * background play / notification controls keep working.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(nav: NavHostController, actions: VideoActions) {
    val items = remember { mutableStateListOf<VideoItem>().apply { addAll(ShortsSession.seed.distinctBy { it.url }) } }
    var next by remember { mutableStateOf<(suspend () -> PageResult)?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pageRetry by remember { mutableStateOf(0) }
    val settings by Graph.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (items.isEmpty()) {
        // Opened from the bottom bar: build a Shorts feed (like YouTube's Shorts tab).
        var failed by remember { mutableStateOf<String?>(null) }
        var attempt by remember { mutableStateOf(0) }
        LaunchedEffect(attempt) {
            try {
                val page = Graph.shorts.load()
                next = page.next
                val videos = page.items.filterIsInstance<com.vidora.app.data.Entry.Video>().map { it.video }
                if (videos.isEmpty()) failed = "No Shorts on this page · retry" else items.addAll(videos)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failed = e.message ?: "Couldn't load Shorts" }
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val f = failed
            if (f == null) CircularProgressIndicator(color = Color.White)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(f, color = Color.White)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(onClick = { failed = null; attempt++ }) { Text(stringResource(R.string.ui_retry), color = Color.White) }
            }
        }
        return
    }
    // Leaving Shorts pauses it, like YouTube (instead of continuing in the mini player).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val cur = Graph.player.state.value.current
            if (cur != null && items.any { it.url == cur.url }) Graph.player.exoPlayer.pause()
        }
    }
    val pager = rememberPagerState { items.size }
    val state by Graph.player.state.collectAsState()
    BackHandler { nav.popBackStack() }

    // Play the settled page; loop each short like YouTube does.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            items.getOrNull(page)?.let { item ->
                if (Graph.player.state.value.current?.url != item.url) Graph.player.play(item, items.toList())
                Graph.player.exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            }
        }
    }
    LaunchedEffect(pager.settledPage, items.size, pageRetry) {
        val load = next
        if (pager.settledPage >= items.size - 3 && load != null) {
            try {
                val page = load()
                next = page.next
                val known = items.map { it.url }.toHashSet()
                items.addAll(page.items.filterIsInstance<com.vidora.app.data.Entry.Video>().map { it.video }.filter { known.add(it.url) })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { pageError = e.message ?: "Shorts page failed" }
        }
    }
    // Extend the list with related shorts as details arrive.
    LaunchedEffect(state.details?.item?.url) {
        val related = state.details?.relatedVideos.orEmpty()
        val shorts = related.filter { it.isShort || (it.duration in 1..75) }
        val known = items.map { it.url }.toHashSet()
        items.addAll(shorts.filter { known.add(it.url) })
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { Graph.player.exoPlayer.repeatMode = if (Graph.player.state.value.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items[it].url }) { page ->
            val item = items[page]
            val active = page == pager.settledPage && state.current?.url == item.url
            Box(Modifier.fillMaxSize()) {
                if (active) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                useController = false
                                resizeMode = if (settings.shortsFit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                keepScreenOn = true
                                player = Graph.player.sessionPlayer
                            }
                        },
                        update = { it.resizeMode = if (settings.shortsFit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize().clickable(role = Role.Button,
                            onClickLabel = if (state.isPlaying) "Pause Short" else "Play Short") { Graph.player.togglePlay() }
                    )
                    if (!state.isPlaying && !state.loading) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center).size(72.dp))
                    }
                    if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    state.error?.let {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.ui_couldn_t_play_this_short), color = Color.White)
                            androidx.compose.material3.TextButton(onClick = { Graph.player.retry() }) { Text("Retry Short", color = Color.White) }
                        }
                    }
                } else {
                    AsyncImage(item.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                ShortOverlay(item, if (active) state.details?.likes ?: -1 else -1, actions, Modifier.align(Alignment.BottomStart))
            }
        }
        IconButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier.statusBarsPadding().padding(8.dp).size(48.dp)
                .glassOverlay(androidx.compose.foundation.shape.CircleShape, alpha = 0.32f)
                .border(0.7.dp, Color.White.copy(alpha = 0.22f), androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
        }
        IconButton(onClick = { next = null; pageError = null; items.clear(); ShortsSession.seed = emptyList() },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(48.dp)) {
            Icon(Icons.Rounded.Refresh, "Refresh Shorts", tint = Color.White)
        }
        androidx.compose.material3.TextButton(onClick = { scope.launch { Graph.settings.update { it.copy(shortsFit = !it.shortsFit) } } },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)) {
            Text(if (settings.shortsFit) "Fit video" else "Fill screen", color = Color.White)
        }
        pageError?.let { message ->
            androidx.compose.material3.TextButton(onClick = { pageError = null; pageRetry++ }, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)) {
                Text("$message · Retry", color = Color.White, maxLines = 2)
            }
        }
        // Progress line
        var progress by remember { mutableStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                val p = Graph.player.exoPlayer
                progress = if (p.duration > 0) (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
                kotlinx.coroutines.delay(200)
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .fillMaxWidth(progress).height(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun ShortOverlay(item: VideoItem, likes: Long, actions: VideoActions, modifier: Modifier) {
    val library by Graph.library.data.collectAsState()
    val settings by Graph.settingsState.collectAsState()
    val liked = library.favorites.any { it.url == item.url }
    val context = LocalContext.current
    var votes by remember(item.url) { mutableStateOf<Votes?>(null) }
    LaunchedEffect(item.url, settings.returnDislike) {
        if (settings.returnDislike) votes = Graph.repository.videoId(item.url)?.let { Community.votes(it) }
    }
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .navigationBarsPadding().padding(start = 16.dp, end = 8.dp, bottom = 20.dp, top = 48.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { item.uploaderUrl?.let(actions.openChannel) }) {
                Avatar(item.uploaderAvatar, 32)
                Spacer(Modifier.width(8.dp))
                Text(item.uploader.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text(item.title, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ShortAction(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (likes >= 0) Format.count(likes) else "Like") { Graph.library.toggleFavorite(item) }
            ShortAction(Icons.Rounded.ThumbDown, votes?.let { Format.count(it.dislikes) } ?: "Dislike") { com.vidora.app.account.AccountSync.disliked(item.url) }
            ShortAction(Icons.Rounded.Download, "Save") { actions.download(item) }
            ShortAction(Icons.Rounded.Share, "Share") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, item.url)
                context.startActivity(android.content.Intent.createChooser(send, "Share"))
            }
        }
    }
}

@Composable
private fun ShortAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        // Frosted-glass bubble behind each action, like iOS controls over video.
        Box(
            Modifier.size(50.dp)
                .glassOverlay(androidx.compose.foundation.shape.CircleShape, alpha = 0.30f)
                .border(0.7.dp, Color.White.copy(alpha = 0.22f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** Shorts feed for the bottom-bar tab: account recommendations when signed in, otherwise a search. */
