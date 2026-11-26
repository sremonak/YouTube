package com.vidora.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.composed
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import com.vidora.app.data.Community
import com.vidora.app.data.ContentFilter
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidora.app.Graph
import com.vidora.app.data.ChannelItem
import com.vidora.app.data.Entry
import com.vidora.app.data.PlaylistItem
import com.vidora.app.data.VideoItem
import com.vidora.app.ui.Paged
import com.vidora.app.ui.theme.LocalDesign
import com.vidora.app.util.Format

/** Actions available from a video's overflow menu. */
class VideoActions(
    val open: (VideoItem) -> Unit,
    val openChannel: (String) -> Unit,
    val openPlaylist: (String) -> Unit,
    val download: (VideoItem) -> Unit,
)

@Composable
fun Thumbnail(item: VideoItem, modifier: Modifier = Modifier, progress: Float = 0f, cornerOverride: Int? = null) {
    val corner = cornerOverride ?: LocalDesign.current.corner
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        val badge = when {
            item.isLive -> "LIVE"
            item.duration > 0 -> Format.duration(item.duration)
            else -> null
        }
        if (badge != null) {
            Surface(
                color = if (item.isLive) Color(0xFFE53935) else Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
            ) {
                Text(
                    badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        if (progress > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f)))
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
fun Avatar(url: String?, size: Int, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(original: VideoItem, actions: VideoActions, modifier: Modifier = Modifier, compact: Boolean = LocalDesign.current.compact) {
    val item = rememberDeArrow(original)
    var menu by remember { mutableStateOf(false) }
    val history by Graph.library.data.collectAsStateCompat()
    val progress = remember(history, item.url) {
        val h = history.history.firstOrNull { it.video.url == item.url }
        if (h != null && item.duration > 0) h.positionMs / 1000f / item.duration
        else if (original.watchedPercent > 0) original.watchedPercent / 100f else 0f
    }
    // Dismissed through one of YouTube's menu actions ("Not interested"…): collapse like the app does.
    com.vidora.app.account.AccountSync.dismissed[original.url]?.let { msg ->
        Text(
            msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
        )
        return
    }
    val clickModifier = modifier.dpadFocusRing().combinedClickable(onClick = { actions.open(original) }, onLongClick = { menu = true })

    if (compact) {
        Row(clickModifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Thumbnail(item, Modifier.width(168.dp), progress)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                MetaLine(item)
            }
            Box { MoreButton(original, actions, menu) { menu = it } }
        }
    } else {
        // Phones: edge-to-edge thumbnails like the YouTube app; tablets keep rounded grid cards.
        val edge = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
        Column(clickModifier.padding(horizontal = if (edge) 0.dp else 12.dp, vertical = if (edge) 6.dp else 8.dp)) {
            Thumbnail(item, Modifier.fillMaxWidth(), progress, cornerOverride = if (edge) 0 else null)
            Row(Modifier.padding(start = if (edge) 12.dp else 0.dp, end = if (edge) 4.dp else 0.dp, top = 10.dp, bottom = if (edge) 10.dp else 0.dp), verticalAlignment = Alignment.Top) {
                if (LocalDesign.current.showAvatars && item.uploaderAvatar != null) {
                    Avatar(item.uploaderAvatar, 36)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    MetaLine(item)
                }
                Box { MoreButton(original, actions, menu) { menu = it } }
            }
        }
    }
}

@Composable
private fun MetaLine(item: VideoItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(
                item.uploader,
                Format.views(item.views).takeIf { it.isNotEmpty() },
                item.uploadedText?.takeIf { it.isNotBlank() } ?: Format.relative(item.uploadedEpoch).takeIf { it.isNotEmpty() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (item.verified) {
            Icon(Icons.Rounded.Verified, null, Modifier.padding(start = 3.dp).size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** DeArrow: community-sourced, non-clickbait titles and thumbnails (opt-in). */
@Composable
fun rememberDeArrow(item: VideoItem): VideoItem {
    val enabled = Graph.settingsState.collectAsState().value.deArrow
    if (!enabled) return item
    val id = remember(item.url) { Graph.repository.videoId(item.url) } ?: return item
    var branding by remember(id) { mutableStateOf(Community.cachedBranding(id)) }
    LaunchedEffect(id) { if (branding == null) branding = runCatching { Community.branding(id) }.getOrNull() }
    val b = branding ?: return item
    return item.copy(title = b.title ?: item.title, thumbnail = b.thumbnail ?: item.thumbnail)
}

@Composable
private fun MoreButton(item: VideoItem, actions: VideoActions, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    val library by Graph.library.data.collectAsStateCompat()
    var playlistSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (playlistSheet) AddToPlaylistSheet(item) { playlistSheet = false }
    IconButton(onClick = { onExpanded(true) }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Rounded.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_play_next)) }, onClick = { Graph.player.enqueue(item, playNextUp = true); onExpanded(false) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_add_to_queue)) }, onClick = { Graph.player.enqueue(item); onExpanded(false) })
        DropdownMenuItem(
            text = { Text(if (library.watchLater.any { it.url == item.url }) "Remove from Watch later" else "Save to Watch later") },
            onClick = { Graph.library.toggleWatchLater(item); onExpanded(false) })
        DropdownMenuItem(
            text = { Text(if (library.favorites.any { it.url == item.url }) "Remove from Liked" else "Add to Liked") },
            onClick = { Graph.library.toggleFavorite(item); onExpanded(false) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_save_to_playlist)) }, onClick = { playlistSheet = true; onExpanded(false) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_download)) }, leadingIcon = { Icon(Icons.Rounded.Download, null) },
            onClick = { actions.download(item); onExpanded(false) })
        // YouTube's own actions for this item (shown with YouTube's wording, sent to the account).
        item.feedback.forEach { f ->
            DropdownMenuItem(text = { Text(f.label) }, onClick = {
                onExpanded(false)
                com.vidora.app.account.AccountSync.feedback(item, f)
            })
        }
        item.uploaderUrl?.let { url ->
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_go_to_channel)) }, onClick = { actions.openChannel(url); onExpanded(false) })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_don_t_recommend_channel)) }, onClick = {
                onExpanded(false)
                scope.launch {
                    Graph.settings.update { it.copy(blockedChannels = it.blockedChannels + ContentFilter.encodeChannel(url, item.uploader.orEmpty())) }
                }
            })
        }
    }
}

@Composable
fun ChannelRow(channel: ChannelItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val library by Graph.library.data.collectAsStateCompat()
    val subscribed = library.subscriptions.any { it.url == channel.url }
    Row(
        modifier.fillMaxWidth().combinedClickableCompat(onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(channel.avatar, 56)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.verified) Icon(Icons.Rounded.Verified, null, Modifier.padding(start = 4.dp).size(15.dp))
            }
            Text(Format.subscribers(channel.subscribers), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = { Graph.library.toggleSubscription(channel) }) {
            Text(if (subscribed) "Subscribed" else "Subscribe")
        }
    }
}

@Composable
fun PlaylistCard(playlist: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val corner = LocalDesign.current.corner
    Column(modifier.dpadFocusRing().combinedClickableCompat(onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            AsyncImage(playlist.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (playlist.count >= 0) Text(" ${playlist.count}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        playlist.uploader?.let { Text("Playlist · $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Responsive grid of entries with infinite scroll, shimmer loading and error handling. */
@Composable
fun EntryGrid(
    paged: Paged,
    actions: VideoActions,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(bottom = com.vidora.app.ui.theme.bottomChromePadding()),
    header: (@Composable () -> Unit)? = null,
    filter: (Entry) -> Boolean = { true },
) {
    val compact = LocalDesign.current.compact
    val settings by Graph.settingsState.collectAsState()
    val library by Graph.library.data.collectAsStateCompat()
    val watched = remember(library.history, settings.hideWatched) {
        if (settings.hideWatched) library.history.filter { h -> h.video.duration > 0 && h.positionMs / 1000 >= h.video.duration * 0.9 }.map { it.video.url }.toHashSet() else emptySet()
    }
    val visible = remember(paged.items, filter, settings, watched) {
        paged.items.filter { filter(it) && ContentFilter.allows(it, settings, watched) }
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, paged.items.size) {
        if (shouldLoadMore && !paged.endReached && paged.items.isNotEmpty()) paged.loadMore()
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (compact) 420.dp else 300.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        when {
            paged.items.isEmpty() && paged.loading -> items(8) { ShimmerCard(compact) }
            paged.items.isEmpty() && paged.error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorState(paged.error ?: "", onRetry = { paged.load() })
            }
            paged.items.isEmpty() && !paged.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(stringResource(R.string.ui_nothing_here_yet), stringResource(R.string.ui_try_another_search_or_category))
            }
        }
        if (paged.items.isNotEmpty() && visible.isEmpty() && !paged.loading && paged.endReached) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("No results match your filters", "Try a different length, date or turn off some content filters.")
            }
        }
        items(visible, key = { it.key }, span = { e ->
            if (e is Entry.Channel) GridItemSpan(maxLineSpan) else GridItemSpan(1)
        }) { entry ->
            when (entry) {
                is Entry.Video -> VideoCard(entry.video, actions)
                is Entry.Channel -> ChannelRow(entry.channel, onClick = { actions.openChannel(entry.channel.url) })
                is Entry.Playlist -> PlaylistCard(entry.playlist, onClick = { actions.openPlaylist(entry.playlist.url) })
            }
        }
        if (paged.items.isNotEmpty() && paged.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        }
    }
}

@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -600f, targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x"
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    return Brush.linearGradient(listOf(base, hi, base), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 600f, 300f))
}

@Composable
fun ShimmerCard(compact: Boolean = false) {
    val brush = shimmerBrush()
    val corner = LocalDesign.current.corner
    if (compact) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(corner.dp)).background(brush))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            }
        }
    } else {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(corner.dp)).background(brush))
            Row(Modifier.padding(top = 10.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(brush))
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                }
            }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.ui_something_went_wrong), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.ui_try_again)) }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, icon: ImageVector? = null) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            Spacer(Modifier.height(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/**
 * Visible focus ring for D-pad / keyboard navigation (Android TV, Chromebooks, hardware keyboards).
 * Touch users never see it because touch doesn't move focus.
 */
fun Modifier.dpadFocusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) Modifier
                .border(3.dp, color, RoundedCornerShape(LocalDesign.current.corner.dp + 4.dp))
                .background(color.copy(alpha = 0.08f), RoundedCornerShape(LocalDesign.current.corner.dp + 4.dp))
            else Modifier
        )
}
