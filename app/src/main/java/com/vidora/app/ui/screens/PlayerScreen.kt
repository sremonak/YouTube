package com.vidora.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import com.vidora.app.ui.components.AddToPlaylistSheet
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import com.vidora.app.data.StreamDetails
import com.vidora.app.data.VideoItem
import com.vidora.app.player.PlayerUiState
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.Avatar
import com.vidora.app.ui.components.ErrorState
import com.vidora.app.ui.components.ShimmerCard
import com.vidora.app.ui.components.Thumbnail
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.components.VideoCard
import com.vidora.app.util.Format
import kotlinx.coroutines.delay

private enum class Sheet { NONE, QUALITY, SPEED, SLEEP, QUEUE, CAPTIONS, AUDIO, EQ, COMMENTS, PLAYLIST, STATS }

@Composable
fun PlayerScreen(
    nav: NavHostController,
    actions: VideoActions,
    activity: MainActivity,
    pipMode: Boolean,
    onDownload: (VideoItem) -> Unit = {},
) {
    val state by Graph.player.state.collectAsState()
    val config = LocalConfiguration.current
    var fullscreenRequested by rememberSaveable { mutableStateOf(false) }
    val landscapePhone = config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenHeightDp < 500
    val fullscreen = fullscreenRequested || landscapePhone

    DisposableEffect(Unit) {
        activity.playerVisible = true
        activity.updatePipParams()
        onDispose {
            activity.playerVisible = false
            activity.updatePipParams()
            if (!pipMode) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    if (pipMode) {
        VideoSurface(Modifier.fillMaxSize().background(Color.Black), state, showControls = false, onFullscreen = {})
        return
    }

    FullscreenEffect(activity, fullscreen)
    val exitFullscreen = {
        fullscreenRequested = false
        // force portrait so a phone held sideways leaves fullscreen too
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }
    BackHandler(enabled = fullscreen) { exitFullscreen() }

    if (state.current == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(Modifier.fillMaxSize(), state, showControls = true, onFullscreen = exitFullscreen, fullscreen = true)
        }
        return
    }

    var sheet by remember { mutableStateOf(Sheet.NONE) }
    val commentsScope = rememberCoroutineScope()
    val comments = remember(state.current?.url) { state.current?.url?.let { com.vidora.app.ui.UiCache.comments(it) } }
    val wide = config.screenWidthDp >= 840
    val onFullscreen = {
        fullscreenRequested = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    if (wide) {
        Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(0.64f).fillMaxHeight(), contentPadding = PaddingValues(16.dp)) {
                item {
                    VideoSurface(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                        state, true, onFullscreen, onSignIn = { nav.navigate(Routes.LOGIN) }
                    )
                }
                detailsSection(state, nav, actions, activity, onDownload, comments) { sheet = it }
            }
            LazyColumn(Modifier.weight(0.36f).fillMaxHeight(), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
                relatedSection(state, actions)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box {
                VideoSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), state, true, onFullscreen, onSignIn = { nav.navigate(Routes.LOGIN) })
                IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = Color.White)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                detailsSection(state, nav, actions, activity, onDownload, comments) { sheet = it }
                relatedSection(state, actions)
            }
        }
    }

    when (sheet) {
        Sheet.QUALITY -> QualitySheet(state) { sheet = Sheet.NONE }
        Sheet.SPEED -> SpeedSheet(state.speed) { sheet = Sheet.NONE }
        Sheet.SLEEP -> SleepSheet(state) { sheet = Sheet.NONE }
        Sheet.STATS -> StatsSheet { sheet = Sheet.NONE }
        Sheet.QUEUE -> QueueSheet(state, actions) { sheet = Sheet.NONE }
        Sheet.CAPTIONS -> CaptionsSheet(state) { sheet = Sheet.NONE }
        Sheet.AUDIO -> AudioTrackSheet(state) { sheet = Sheet.NONE }
        Sheet.EQ -> EqualizerSheet { sheet = Sheet.NONE }
        Sheet.COMMENTS -> comments?.let { CommentsSheet(it, onSeek = { ms -> Graph.player.seekTo(ms) }) { sheet = Sheet.NONE } }
        Sheet.PLAYLIST -> state.current?.let { AddToPlaylistSheet(it) { sheet = Sheet.NONE } }
        Sheet.NONE -> Unit
    }
}

@Composable
private fun FullscreenEffect(activity: Activity, fullscreen: Boolean) {
    DisposableEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier, state: PlayerUiState, showControls: Boolean, onFullscreen: () -> Unit, fullscreen: Boolean = false, onSignIn: (() -> Unit)? = null) {
    val settings by Graph.settingsState.collectAsState()
    val gesturesOn by rememberUpdatedState(settings.gestures && showControls)
    val swipesOn by rememberUpdatedState(fullscreen)
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    installPlayerGestures(this, { gesturesOn }, { swipesOn }) { feedback = it }
                    (findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? TimeBar)?.addListener(object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubMs = position }
                        override fun onScrubMove(timeBar: TimeBar, position: Long) { scrubMs = position }
                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { scrubMs = null }
                    })
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    player = Graph.player.sessionPlayer
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    setShowSubtitleButton(false)
                    keepScreenOn = true
                    setFullscreenButtonClickListener { onFullscreen() }
                }
            },
            update = { view ->
                view.useController = showControls
                // Chapter starts + SponsorBlock segments as markers on the seek bar
                val markers = (state.details?.chapters.orEmpty().drop(1).map { it.startSeconds * 1000L } + state.segments.map { it.startMs })
                    .distinct().sorted().toLongArray()
                if (view.tag != markers.toList()) {
                    view.tag = markers.toList()
                    view.setExtraAdGroupMarkers(markers, BooleanArray(markers.size))
                }
                view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.captionScale)
                view.setFullscreenButtonClickListener { onFullscreen() }
                if (view.player == null) view.player = Graph.player.sessionPlayer
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
        // Audio-only mode: show artwork instead of a black frame
        if (state.audioOnly && state.current != null) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                AsyncImage(state.current.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.35f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Audiotrack, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.ui_audio_only_mode), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    IconButton(
                        onClick = { Graph.player.togglePlay() },
                        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
                    }
                }
            }
        }
        GestureOverlay(feedback, Modifier.align(Alignment.Center))
        scrubMs?.let { ms -> SeekPreview(state.details?.frames, ms, Modifier.align(Alignment.Center)) }
        if (state.loading) {
            val status by Graph.repository.loadStatus.collectAsState()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
        }
        if (state.restored && !state.loading && state.error == null) {
            AsyncImage(state.current?.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            IconButton(
                onClick = { Graph.player.togglePlay() },
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
            ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
        state.error?.let { err ->
            Column(Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.ui_playback_failed), color = Color.White, fontWeight = FontWeight.Bold)
                Text(err, color = Color.White.copy(alpha = 0.85f), maxLines = 6, style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Graph.player.retry() }) { Text(stringResource(R.string.ui_retry)) }
                    if (onSignIn != null && !Graph.account.isSignedIn && (err.contains("Sign in", true) || err.contains("bot", true))) {
                        androidx.compose.material3.OutlinedButton(onClick = onSignIn) { Text(stringResource(R.string.ui_sign_in)) }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.detailsSection(
    state: PlayerUiState,
    nav: NavHostController,
    actions: VideoActions,
    activity: MainActivity,
    onDownload: (VideoItem) -> Unit,
    comments: CommentsState?,
    openSheet: (Sheet) -> Unit,
) {
    val item = state.current ?: return
    val details = state.details
    item(key = "title") {
        var expanded by rememberSaveable(item.url) { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
                maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        Format.views(item.views).takeIf { it.isNotEmpty() },
                        item.uploadedText?.takeIf { it.isNotBlank() } ?: Format.relative(item.uploadedEpoch).takeIf { it.isNotEmpty() },
                        details?.category,
                    ).joinToString("  "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    if (expanded) "  Show less" else "  ...more",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold
                )
            }
            if (expanded && details != null) {
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp)) {
                        Text(htmlToText(details.description), style = MaterialTheme.typography.bodyMedium)
                        if (details.tags.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(details.tags.take(12).joinToString("  ") { "#" + it.replace(" ", "") },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
    item(key = "channel") {
        val library by Graph.library.data.collectAsState()
        val subscribed = library.subscriptions.any { it.url == item.uploaderUrl }
        Row(
            Modifier.fillMaxWidth().clickable { item.uploaderUrl?.let { actions.openChannel(it) } }.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(item.uploaderAvatar, 36)
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(item.uploader.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.verified) Icon(Icons.Rounded.Verified, null, Modifier.padding(start = 4.dp).size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                details?.subscriberCount?.takeIf { it >= 0 }?.let {
                    Text("  " + Format.subscribers(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            val uploaderUrl = item.uploaderUrl
            if (uploaderUrl != null) {
                Spacer(Modifier.width(8.dp))
                if (subscribed) {
                    FilledTonalButton(
                        onClick = { Graph.library.toggleSubscription(com.vidora.app.data.ChannelItem(uploaderUrl, item.uploader.orEmpty(), item.uploaderAvatar)) },
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.subscribed))
                    }
                } else {
                    Button(
                        onClick = {
                            Graph.library.toggleSubscription(
                                com.vidora.app.data.ChannelItem(uploaderUrl, item.uploader.orEmpty(), item.uploaderAvatar, details?.subscriberCount ?: -1)
                            )
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(36.dp),
                    ) { Text(stringResource(R.string.subscribe), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
    item(key = "actions") {
        val library by Graph.library.data.collectAsState()
        val context = LocalContext.current
        val liked = library.favorites.any { it.url == item.url }
        val later = library.watchLater.any { it.url == item.url }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ActionChip(
                    if (details != null && details.likes >= 0) Format.count(details.likes) else stringResource(R.string.chip_like),
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, selected = liked
                ) { Graph.library.toggleFavorite(item) }
            }
            item {
                val settings by Graph.settingsState.collectAsState()
                var votes by remember(item.url) { mutableStateOf(com.vidora.app.ui.UiCache.get<com.vidora.app.data.Votes>("votes:${item.url}")) }
                var disliked by remember(item.url) { mutableStateOf(false) }
                LaunchedEffect(item.url, settings.returnDislike) {
                    if (settings.returnDislike && votes == null) {
                        votes = Graph.repository.videoId(item.url)?.let { com.vidora.app.data.Community.votes(it) }
                        votes?.let { com.vidora.app.ui.UiCache.put("votes:${item.url}", it) }
                    }
                }
                val count = votes?.dislikes?.let { it + if (disliked) 1 else 0 }
                ActionChip(count?.let { Format.count(it) } ?: stringResource(R.string.chip_dislike), Icons.Rounded.ThumbDown, selected = disliked) {
                    disliked = !disliked
                    if (disliked) {
                        if (liked) Graph.library.toggleFavorite(item)
                        com.vidora.app.account.AccountSync.disliked(item.url)
                    } else com.vidora.app.account.AccountSync.liked(item.url, false)
                }
            }
            item {
                ActionChip(stringResource(R.string.chip_share), Icons.Rounded.Share) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.url).putExtra(Intent.EXTRA_SUBJECT, item.title)
                    context.startActivity(Intent.createChooser(send, "Share video"))
                }
            }
            item { ActionChip(stringResource(R.string.chip_download), Icons.Rounded.Download) { onDownload(item) } }
            item { ActionChip(stringResource(R.string.chip_playlist), Icons.AutoMirrored.Rounded.PlaylistAdd) { openSheet(Sheet.PLAYLIST) } }
            item { ActionChip(state.subtitle?.label ?: stringResource(R.string.chip_captions), Icons.Rounded.ClosedCaption, selected = state.subtitle != null) { openSheet(Sheet.CAPTIONS) } }
            if (details != null && details.audioTracks.size > 1) {
                item {
                    val current = details.audioTracks.firstOrNull { it.id == state.audioTrackId }?.name ?: "Audio track"
                    ActionChip(current, Icons.Rounded.Translate) { openSheet(Sheet.AUDIO) }
                }
            }
            item { ActionChip(stringResource(R.string.chip_equalizer), Icons.Rounded.GraphicEq, selected = Graph.settingsState.value.eqEnabled) { openSheet(Sheet.EQ) } }
            item { ActionChip(state.quality?.label ?: stringResource(R.string.chip_quality), Icons.Rounded.HighQuality) { openSheet(Sheet.QUALITY) } }
            item { ActionChip(if (state.audioOnly) "Audio only" else "Audio mode", Icons.Rounded.Audiotrack, selected = state.audioOnly) { Graph.player.setAudioOnly(!state.audioOnly) } }
            item { ActionChip("${trimSpeed(state.speed)}x", Icons.Rounded.Speed) { openSheet(Sheet.SPEED) } }
            item { ActionChip("Queue ${state.queue.size}", Icons.AutoMirrored.Rounded.QueueMusic) { openSheet(Sheet.QUEUE) } }
            item { ActionChip(stringResource(R.string.chip_save), Icons.Rounded.WatchLater, selected = later) { Graph.library.toggleWatchLater(item) } }
            item { ActionChip(stringResource(R.string.chip_loop), if (state.loop) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, selected = state.loop) { Graph.player.toggleLoop() } }
            item { ActionChip(if (state.sleepTimerEndsAt > 0 || state.sleepAtEnd) "Sleep on" else "Sleep", Icons.Rounded.Bedtime, selected = state.sleepTimerEndsAt > 0 || state.sleepAtEnd) { openSheet(Sheet.SLEEP) } }
            item { ActionChip("PiP", Icons.Rounded.PictureInPictureAlt) { activity.enterPip() } }
            item { ActionChip("Stats", Icons.Rounded.Info) { openSheet(Sheet.STATS) } }
        }
    }
    if (details != null && details.chapters.isNotEmpty()) {
        item(key = "chapters") {
            Column {
                Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(details.chapters) { ch ->
                        Column(Modifier.width(150.dp).clip(MaterialTheme.shapes.small).clickable { Graph.player.seekTo(ch.startSeconds * 1000L) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                AsyncImage(ch.thumbnail ?: item.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text(Format.duration(ch.startSeconds.toLong()).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                            Text(ch.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (state.segments.isNotEmpty()) {
        item(key = "sponsor") {
            Text(
                "SponsorBlock: ${state.segments.size} segment(s) will be skipped automatically",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
    if (details != null && item.isLive && details.liveChat != null) {
        item(key = "livechat") { LiveChatPanel(item.url, details.liveChat) }
    }
    if (comments != null && !item.isLive) {
        item(key = "comments") { CommentsPreview(comments) { openSheet(Sheet.COMMENTS) } }
    }
    item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
}

private fun LazyListScope.relatedSection(state: PlayerUiState, actions: VideoActions) {
    val details = state.details
    val upNext = state.queue.drop(state.queueIndex + 1)
    if (upNext.isNotEmpty()) {
        item(key = "upnext_h") {
            Text(stringResource(R.string.up_next), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        items(upNext.distinctBy { it.url }, key = { "q" + it.url }) { v -> VideoCard(v, actions, compact = true) }
    }
    item(key = "related_h") {
        Text(stringResource(R.string.related), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    if (details == null) {
        if (state.error == null) items(5) { ShimmerCard(compact = true) }
    } else {
        items(details.relatedVideos.filter { r -> upNext.none { it.url == r.url } }.distinctBy { it.url }, key = { "r" + it.url }) { v ->
            VideoCard(v, actions, compact = true)
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        shape = RoundedCornerShape(50),
        // YouTube's flat grey pills; "on" state = inverted colours.
        colors = if (selected) AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            labelColor = MaterialTheme.colorScheme.surface,
            leadingIconContentColor = MaterialTheme.colorScheme.surface,
        ) else AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val details: StreamDetails = state.details ?: run { onDismiss(); return }
    val options = details.videoStreams
        .groupBy { it.label }
        .map { (_, list) -> list.firstOrNull { it.codec?.startsWith("avc") == true } ?: list.first() }
        .sortedByDescending { it.height * 1000 + it.fps }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_video_quality), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(options) { opt ->
                ListItem(
                    headlineContent = { Text(opt.label) },
                    supportingContent = { Text(listOfNotNull(opt.suffix.uppercase(), opt.codec?.substringBefore('.')?.uppercase()).joinToString(" · ")) },
                    leadingContent = { RadioButton(selected = state.quality?.label == opt.label && !state.audioOnly, onClick = null) },
                    modifier = Modifier.clickable { Graph.player.setQuality(opt); onDismiss() }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ui_audio_only)) },
                    supportingContent = { Text(stringResource(R.string.ui_saves_data_keeps_playing_with_the_screen)) },
                    leadingContent = { RadioButton(selected = state.audioOnly, onClick = null) },
                    modifier = Modifier.clickable { Graph.player.setAudioOnly(true); onDismiss() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(current: Float, onDismiss: () -> Unit) {
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_playback_speed), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(speeds) { s ->
                ListItem(
                    headlineContent = { Text(if (s == 1f) "Normal" else "${trimSpeed(s)}x") },
                    leadingContent = { RadioButton(selected = s == current, onClick = null) },
                    modifier = Modifier.clickable { Graph.player.setSpeed(s); onDismiss() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_sleep_timer), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (state.sleepTimerEndsAt > 0) {
            val left = ((state.sleepTimerEndsAt - now) / 1000).coerceAtLeast(0)
            Text("Pausing in ${Format.duration(left)}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp))
        }
        if (state.sleepAtEnd) {
            Text(stringResource(R.string.ui_stopping_when_this_video_ends), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp))
        }
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ui_end_of_current_video)) },
                    modifier = Modifier.clickable { Graph.player.setSleepAtEnd(); onDismiss() }
                )
            }
            items(listOf(0, 5, 10, 15, 30, 45, 60, 90)) { m ->
                ListItem(
                    headlineContent = { Text(if (m == 0) "Off" else "$m minutes") },
                    modifier = Modifier.clickable { Graph.player.setSleepTimer(m); onDismiss() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(state: PlayerUiState, actions: VideoActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.queue, key = { _, v -> v.url }) { index, v ->
                val isCurrent = index == state.queueIndex
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable { Graph.player.play(v, state.queue); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumbnail(v, Modifier.width(110.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(v.uploader.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!isCurrent) IconButton(onClick = { Graph.player.removeFromQueue(v) }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
        }
    }
}

/** Mini player shown above the navigation bar while browsing. */
@Composable
fun MiniPlayer(onOpen: () -> Unit, floating: Boolean = false) {
    val state by Graph.player.state.collectAsState()
    val item = state.current ?: return
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(item.url) {
        while (true) {
            val p = Graph.player.exoPlayer
            progress = if (p.duration > 0) p.currentPosition.toFloat() / p.duration else 0f
            delay(500)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = if (floating) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 4.dp,
        shadowElevation = if (floating) 8.dp else 0.dp,
        modifier = if (floating) Modifier.widthIn(max = 560.dp).fillMaxWidth() else Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                    AsyncImage(item.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.uploader.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { Graph.player.togglePlay() }) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause")
                }
                IconButton(onClick = { Graph.player.playNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
                IconButton(onClick = { Graph.player.stop() }) { Icon(Icons.Rounded.Close, "Close") }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

private fun trimSpeed(s: Float) = if (s % 1f == 0f) s.toInt().toString() else s.toString()

private fun htmlToText(html: String): String =
    androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
