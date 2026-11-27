package com.vidora.app.ui.screens

import com.vidora.app.ui.theme.GlassBottomSheet
import com.vidora.app.ui.theme.glass
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
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
import com.vidora.app.ui.icons.Info
import com.vidora.app.ui.icons.QueueMusic
import com.vidora.app.ui.icons.Audiotrack
import com.vidora.app.ui.icons.Bedtime
import com.vidora.app.ui.icons.Close
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.NotificationsActive
import com.vidora.app.ui.icons.Favorite
import com.vidora.app.ui.icons.FavoriteBorder
import com.vidora.app.ui.icons.HighQuality
import com.vidora.app.ui.icons.KeyboardArrowDown
import com.vidora.app.ui.icons.Pause
import com.vidora.app.ui.icons.PictureInPictureAlt
import com.vidora.app.ui.icons.PlayArrow
import com.vidora.app.ui.icons.Repeat
import com.vidora.app.ui.icons.RepeatOne
import com.vidora.app.ui.icons.Share
import com.vidora.app.ui.icons.SkipNext
import com.vidora.app.ui.icons.Speed
import com.vidora.app.ui.icons.ThumbUp
import com.vidora.app.ui.icons.ThumbDown
import com.vidora.app.ui.icons.ClosedCaption
import com.vidora.app.ui.icons.Translate
import com.vidora.app.ui.icons.GraphicEq
import com.vidora.app.ui.icons.PlaylistAdd
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import com.vidora.app.ui.components.AddToPlaylistSheet
import com.vidora.app.ui.icons.Verified
import com.vidora.app.ui.icons.WatchLater
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
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.luminance
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
import coil3.compose.AsyncImage
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import com.vidora.app.data.StreamDetails
import com.vidora.app.data.VideoItem
import com.vidora.app.player.PlayerUiState
import com.vidora.app.player.qualityLabel
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.Avatar
import com.vidora.app.ui.components.ErrorState
import com.vidora.app.ui.components.ShimmerCard
import com.vidora.app.ui.components.Thumbnail
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.components.VideoCard
import com.vidora.app.util.Format
import kotlinx.coroutines.delay

internal enum class Sheet { NONE, QUALITY, SPEED, SLEEP, QUEUE, CAPTIONS, AUDIO, EQ, COMMENTS, PLAYLIST, STATS, SETTINGS, CHAPTERS, CAST }

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

    val publicSearch = { nav.navigate(Routes.publicSearch(state.current?.title.orEmpty())) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    val comments = remember(state.current?.url) { state.current?.url?.let { com.vidora.app.ui.UiCache.comments(it) } }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(Modifier.fillMaxSize(), state, showControls = true, onFullscreen = exitFullscreen, fullscreen = true, onPublicSearch = publicSearch, openSheet = { sheet = it })
        }
        PlayerSheets(sheet, state, actions, comments) { sheet = Sheet.NONE }
        return
    }
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
                        state, true, onFullscreen, onPublicSearch = publicSearch, onSignIn = { nav.navigate(Routes.LOGIN) }, openSheet = { sheet = it }
                    )
                }
                detailsSection(state, nav, actions, activity, onDownload, comments) { sheet = it }
            }
            LazyColumn(Modifier.weight(0.36f).fillMaxHeight(), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
                relatedSection(state, actions)
            }
        }
    } else {
        val settings by Graph.settingsState.collectAsState()
        Box(Modifier.fillMaxSize()) {
            // Ambient mode: the video's colours glow behind the player and the title area
            if (settings.ambientMode) {
                AmbientGlow(
                    state.current?.thumbnail,
                    Modifier.fillMaxWidth().height((config.screenWidthDp * 9 / 16 + 260).dp),
                    dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                )
            }
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                VideoSurface(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .then(if (settings.ambientMode) Modifier.padding(horizontal = 0.dp) else Modifier)
                        .background(Color.Black),
                    state, true, onFullscreen, onPublicSearch = publicSearch, onSignIn = { nav.navigate(Routes.LOGIN) },
                    openSheet = { sheet = it }, onMinimize = { nav.popBackStack() }
                )
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    detailsSection(state, nav, actions, activity, onDownload, comments) { sheet = it }
                    relatedSection(state, actions)
                }
            }
        }
    }

    PlayerSheets(sheet, state, actions, comments) { sheet = Sheet.NONE }
}

@Composable
private fun PlayerSheets(sheet: Sheet, state: PlayerUiState, actions: VideoActions, comments: CommentsState?, close: () -> Unit) {
    var next by remember { mutableStateOf(Sheet.NONE) }
    // The gear menu opens another sheet after closing itself
    val shown = if (next != Sheet.NONE) next else sheet
    val dismiss = { next = Sheet.NONE; close() }
    when (shown) {
        Sheet.SETTINGS -> PlayerSettingsSheet(state, openSheet = { next = it }, onDismiss = { if (next == Sheet.NONE) close() })
        Sheet.CHAPTERS -> ChaptersSheet(state, dismiss)
        else -> LegacySheets(shown, state, actions, comments, dismiss)
    }
}

@Composable
private fun LegacySheets(sheet: Sheet, state: PlayerUiState, actions: VideoActions, comments: CommentsState?, close: () -> Unit) {
    val onDismiss = close
    when (sheet) {
        Sheet.QUALITY -> QualitySheet(state, onDismiss)
        Sheet.SPEED -> SpeedSheet(state.speed, onDismiss)
        Sheet.SLEEP -> SleepSheet(state, onDismiss)
        Sheet.STATS -> StatsSheet(onDismiss)
        Sheet.CAST -> CastSheet(onDismiss)
        Sheet.QUEUE -> QueueSheet(state, actions, onDismiss)
        Sheet.CAPTIONS -> CaptionsSheet(state, onDismiss)
        Sheet.AUDIO -> AudioTrackSheet(state, onDismiss)
        Sheet.EQ -> EqualizerSheet(onDismiss)
        Sheet.COMMENTS -> comments?.let { CommentsSheet(it, onSeek = { ms -> Graph.player.seekTo(ms) }, onDismiss = onDismiss) }
        Sheet.PLAYLIST -> state.current?.let { AddToPlaylistSheet(it, onDismiss) }
        Sheet.NONE, Sheet.SETTINGS, Sheet.CHAPTERS -> Unit
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
private fun VideoSurface(
    modifier: Modifier, state: PlayerUiState, showControls: Boolean, onFullscreen: () -> Unit, fullscreen: Boolean = false,
    onPublicSearch: (() -> Unit)? = null, onSignIn: (() -> Unit)? = null, openSheet: (Sheet) -> Unit = {}, onMinimize: (() -> Unit)? = null,
) {
    val settings by Graph.settingsState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    if (!fullscreen && locked) locked = false
    val gesturesOn by rememberUpdatedState(settings.gestures && showControls && !locked)
    val swipesOn by rememberUpdatedState(fullscreen && !locked)
    val tapToggles by rememberUpdatedState(showControls)
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    installPlayerGestures(this, { gesturesOn }, { swipesOn }, onSingleTap = { if (tapToggles) controlsVisible = !controlsVisible }) { feedback = it }
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
                view.useController = false
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
                }
            }
        }
        GestureOverlay(feedback, Modifier.fillMaxSize())
        if (showControls && !(state.restored && !state.loading && state.error == null)) {
            ProPlayerControls(
                state = state,
                visible = controlsVisible || (!state.isPlaying && !state.loading),
                onVisibleChange = { controlsVisible = it },
                fullscreen = fullscreen,
                locked = locked,
                onLockChange = { locked = it; controlsVisible = true },
                onFullscreen = onFullscreen,
                onMinimize = onMinimize,
                openSheet = openSheet,
            )
        }
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
                onPublicSearch?.let { search ->
                    androidx.compose.material3.TextButton(onClick = search) { Text("Find public alternatives", color = Color.White) }
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
        var titleExpanded by rememberSaveable(item.url) { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
            if (item.title.isBlank() && details == null) {
                // Still loading — skeleton instead of an empty, blurred band.
                SkeletonLine(0.92f, 18.dp); Spacer(Modifier.height(6.dp)); SkeletonLine(0.6f, 18.dp)
            } else Text(
                item.title.ifBlank { "Untitled video" },
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = if (titleExpanded) 10 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { titleExpanded = !titleExpanded }
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.clickable { DescriptionUi.toggle(item.url) }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        Format.views(item.views).takeIf { it.isNotEmpty() },
                        item.uploadedText?.takeIf { it.isNotBlank() } ?: Format.relative(item.uploadedEpoch).takeIf { it.isNotEmpty() },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
    if (item.uploader.isNullOrBlank() && item.uploaderUrl == null) {
        if (details == null) item(key = "channel_skeleton") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                Spacer(Modifier.width(12.dp))
                SkeletonLine(0.4f, 14.dp)
            }
        }
    } else item(key = "channel") {
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
            item { ActionChip(if (state.details != null) state.qualityLabel else stringResource(R.string.chip_quality), Icons.Rounded.HighQuality) { openSheet(Sheet.QUALITY) } }
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
    item(key = "description") { DescriptionCard(item, details) }
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
    GlassActionPill(label, icon, selected, onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val details: StreamDetails = state.details ?: run { onDismiss(); return }
    val options = details.videoStreams
        .groupBy { it.label }
        .map { (_, list) -> list.firstOrNull { it.codec?.startsWith("avc") == true } ?: list.first() }
        .sortedByDescending { it.height * 1000 + it.fps }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_video_quality), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val net = if (state.bandwidthKbps > 0) {
                    if (state.bandwidthKbps >= 1000) " · network ≈ ${"%.1f".format(state.bandwidthKbps / 1000f)} Mbps" else " · network ≈ ${state.bandwidthKbps} kbps"
                } else ""
                ListItem(
                    headlineContent = { Text(if (state.autoQuality && !state.audioOnly && state.playingHeight > 0) "Auto (${state.playingHeight}p)" else "Auto (recommended)") },
                    supportingContent = { Text("Starts fast on slow networks and adjusts to your connection$net") },
                    leadingContent = { RadioButton(selected = state.autoQuality && !state.audioOnly, onClick = null) },
                    modifier = Modifier.clickable { Graph.player.setAutoQuality(); onDismiss() }
                )
            }
            items(options) { opt ->
                ListItem(
                    headlineContent = { Text(opt.label) },
                    supportingContent = { Text(listOfNotNull(opt.suffix.uppercase(), opt.codec?.substringBefore('.')?.uppercase()).joinToString(" · ")) },
                    leadingContent = { RadioButton(selected = !state.autoQuality && state.quality?.label == opt.label && !state.audioOnly, onClick = null) },
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
    GlassBottomSheet(onDismissRequest = onDismiss) {
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
    GlassBottomSheet(onDismissRequest = onDismiss) {
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
    GlassBottomSheet(onDismissRequest = onDismiss) {
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

/** Mini player shown above the navigation bar while browsing (glass card; swipe sideways to close). */
@Composable
fun MiniPlayer(onOpen: () -> Unit, floating: Boolean = false) {
    val state by Graph.player.state.collectAsState()
    val item = state.current ?: return
    val glassOn = com.vidora.app.ui.theme.LocalGlass.current.enabled
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(item.url) {
        while (true) {
            val p = Graph.player.exoPlayer
            progress = if (p.duration > 0) p.currentPosition.toFloat() / p.duration else 0f
            delay(500)
        }
    }
    var dragX by remember { mutableStateOf(0f) }
    val animX by androidx.compose.animation.core.animateFloatAsState(dragX, label = "miniDrag")
    val shape = if (floating) RoundedCornerShape(20.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val base = if (floating) Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 560.dp).fillMaxWidth() else Modifier.fillMaxWidth()
    Box(
        base
            .graphicsLayer { translationX = animX; alpha = 1f - (kotlin.math.abs(animX) / 900f).coerceIn(0f, 0.8f) }
            .pointerInput(item.url) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (kotlin.math.abs(dragX) > size.width * 0.35f) Graph.player.stop(); dragX = 0f },
                    onDragCancel = { dragX = 0f },
                ) { change, d -> change.consume(); dragX += d }
            }
            .then(
                if (glassOn) Modifier.glass(shape, tint = MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(100.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                    com.vidora.app.ui.components.Thumbnail(item.copy(duration = 0, isLive = false), Modifier.fillMaxSize(), cornerOverride = 10)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title.ifBlank { if (state.loading) "Loading…" else "Now playing" }, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.uploader?.takeIf { it.isNotBlank() } ?: "Vidora", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { Graph.player.togglePlay() }) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else androidx.compose.animation.Crossfade(state.isPlaying, label = "miniPlay") { playing ->
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", Modifier.size(28.dp))
                    }
                }
                IconButton(onClick = { Graph.player.playNext() }) { Icon(Icons.Rounded.SkipNext, "Next") }
                IconButton(onClick = { Graph.player.stop() }) { Icon(Icons.Rounded.Close, "Close") }
            }
            // Thin accent progress line
            Box(Modifier.fillMaxWidth().padding(horizontal = if (floating) 14.dp else 0.dp).height(2.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
            if (floating) Spacer(Modifier.height(6.dp))
        }
    }
}

private fun trimSpeed(s: Float) = if (s % 1f == 0f) s.toInt().toString() else s.toString()

private fun htmlToText(html: String): String =
    androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()


/** Which videos have their description opened (shared by the meta line and the card). */
private object DescriptionUi {
    val open = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    fun toggle(url: String) { open[url] = open[url] != true }
}

/**
 * YouTube-style description box under the actions: views · date · category, then the
 * description (3 lines, tap for everything) with clickable links and timestamps, and tags.
 */
@Composable
private fun DescriptionCard(item: VideoItem, details: StreamDetails?) {
    val expanded = DescriptionUi.open[item.url] == true
    val text = remember(details?.description) { details?.description?.let(::htmlToText).orEmpty() }
    val primary = MaterialTheme.colorScheme.primary
    val linked = remember(text, primary) { linkify(text, primary) }
    com.vidora.app.ui.theme.GlassCard(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .clickable { DescriptionUi.toggle(item.url) }.animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        tint = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                listOfNotNull(
                    Format.views(item.views).takeIf { it.isNotEmpty() },
                    item.uploadedText?.takeIf { it.isNotBlank() } ?: Format.relative(item.uploadedEpoch).takeIf { it.isNotEmpty() },
                    details?.category?.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ").ifEmpty { "Description" },
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            when {
                details == null -> Text("Loading description…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                text.isBlank() -> Text("No description", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(
                    linked, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded && details != null) {
                if (details.tags.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(details.tags.take(15).joinToString("  ") { "#" + it.replace(" ", "") },
                        style = MaterialTheme.typography.bodySmall, color = primary)
                }
                if (details.likes >= 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("${Format.count(details.likes)} likes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (details != null && text.isNotBlank()) {
                Text(
                    if (expanded) "Show less" else "...more",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private val LINK_OR_TIME = Regex("""(https?://[^\s)]+)|(?<![\d:])((?:\d{1,2}:)?\d{1,2}:\d{2})(?![\d:])""")

/** Makes URLs open in the browser and timestamps (1:23, 1:02:03) seek the video. */
private fun linkify(text: String, color: Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val styles = androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = color))
        var i = 0
        for (m in LINK_OR_TIME.findAll(text)) {
            append(text.substring(i, m.range.first))
            val url = m.groups[1]?.value
            if (url != null) {
                withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, styles)) { append(url) }
            } else {
                val ts = m.value
                val parts = ts.split(':').map { it.toLongOrNull() ?: 0L }
                val sec = parts.fold(0L) { acc, p -> acc * 60 + p }
                withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("t$sec", styles) { Graph.player.seekTo(sec * 1000) }) { append(ts) }
            }
            i = m.range.last + 1
        }
        append(text.substring(i))
    }


/** Pulsing grey bar used while text is loading. */
@Composable
private fun SkeletonLine(fraction: Float, height: androidx.compose.ui.unit.Dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(
        0.06f, 0.14f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a)))
}
