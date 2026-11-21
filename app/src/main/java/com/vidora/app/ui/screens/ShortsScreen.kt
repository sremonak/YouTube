package com.vidora.app.ui.screens

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
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
import coil.compose.AsyncImage
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
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
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
    // Extend the list with related shorts as details arrive.
    LaunchedEffect(state.details?.item?.url) {
        val related = state.details?.relatedVideos.orEmpty()
        val shorts = related.filter { it.isShort || (it.duration in 1..75) }.ifEmpty { if (items.size < 3) related.take(6) else emptyList() }
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
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                keepScreenOn = true
                                player = Graph.player.sessionPlayer
                            }
                        },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize().clickable { Graph.player.togglePlay() }
                    )
                    if (!state.isPlaying && !state.loading) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center).size(72.dp))
                    }
                    if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    state.error?.let {
                        Text(stringResource(R.string.ui_couldn_t_play_this_short), color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    AsyncImage(item.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                ShortOverlay(item, if (active) state.details?.likes ?: -1 else -1, actions, Modifier.align(Alignment.BottomStart))
            }
        }
        IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
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
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
