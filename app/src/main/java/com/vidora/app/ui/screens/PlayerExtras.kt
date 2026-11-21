package com.vidora.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ZoomInMap
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilledTonalButton
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.imageLoader
import coil.request.ImageRequest
import com.vidora.app.Graph
import com.vidora.app.data.AppSettings
import com.vidora.app.data.CommentItem
import com.vidora.app.data.CommentsPage
import com.vidora.app.data.FrameSet
import com.vidora.app.player.AudioEffects
import com.vidora.app.player.PlayerUiState
import com.vidora.app.ui.components.Avatar
import com.vidora.app.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ======================================================================= gestures

/** What the gesture overlay should currently show. */
data class GestureFeedback(val icon: ImageVector, val text: String, val sticky: Boolean = false, val stamp: Long = System.nanoTime())

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * YouTube-style gestures on the video surface:
 *  - single tap: show/hide controls
 *  - double tap left/right: ∓10 s
 *  - long press: 2× speed while held
 *  - vertical swipe left/right half (fullscreen only): brightness / volume
 */
@SuppressLint("ClickableViewAccessibility")
@androidx.annotation.OptIn(UnstableApi::class)
fun installPlayerGestures(
    view: PlayerView,
    enabled: () -> Boolean,
    allowSwipes: () -> Boolean,
    onFeedback: (GestureFeedback?) -> Unit,
) {
    val ctx = view.context
    val audio = ctx.getSystemService(AudioManager::class.java)
    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var mode = 0 // 0 = none, 1 = brightness, 2 = volume
    var startValue = 0f
    var longPressing = false

    val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!enabled()) return false
            val p = Graph.player.exoPlayer
            val back = e.x < view.width / 2f
            val target = (p.currentPosition + if (back) -10_000 else 10_000).coerceIn(0, if (p.duration > 0) p.duration else Long.MAX_VALUE)
            p.seekTo(target)
            onFeedback(GestureFeedback(if (back) Icons.Rounded.FastRewind else Icons.Rounded.FastForward, if (back) "−10 s" else "+10 s"))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!enabled()) return
            val p = Graph.player.exoPlayer
            if (!p.isPlaying) return
            longPressing = true
            p.playbackParameters = PlaybackParameters(2f)
            onFeedback(GestureFeedback(Icons.Rounded.KeyboardDoubleArrowRight, "2× speed", sticky = true))
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!enabled() || !allowSwipes() || e1 == null) return false
            val dy = e1.y - e2.y
            val dx = e2.x - e1.x
            if (mode == 0) {
                if (abs(dy) < 24f || abs(dy) < abs(dx) * 1.5f) return false
                mode = if (e1.x < view.width / 2f) 1 else 2
                startValue = if (mode == 1) {
                    val current = ctx.findActivity()?.window?.attributes?.screenBrightness ?: -1f
                    if (current < 0) 0.5f else current
                } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
            }
            val value = (startValue + dy / (view.height * 0.9f)).coerceIn(0f, 1f)
            if (mode == 1) {
                ctx.findActivity()?.window?.let { w ->
                    w.attributes = w.attributes.apply { screenBrightness = value.coerceAtLeast(0.01f) }
                }
                onFeedback(GestureFeedback(Icons.Rounded.BrightnessMedium, "Brightness ${(value * 100).roundToInt()}%", sticky = true))
            } else {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maxVolume).roundToInt(), 0)
                onFeedback(GestureFeedback(Icons.AutoMirrored.Rounded.VolumeUp, "Volume ${(value * 100).roundToInt()}%", sticky = true))
            }
            return true
        }
    })

    // Pinch: zoom to fill (crop) ↔ fit
    var scaleAcc = 1f
    val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean { scaleAcc = 1f; return enabled() }
        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean { scaleAcc *= detector.scaleFactor; return true }
        override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
            val zoom = when {
                scaleAcc > 1.08f -> true
                scaleAcc < 0.92f -> false
                else -> return
            }
            view.resizeMode = if (zoom) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            onFeedback(GestureFeedback(if (zoom) Icons.Rounded.ZoomOutMap else Icons.Rounded.ZoomInMap, if (zoom) "Zoomed to fill" else "Original"))
        }
    })

    view.setOnTouchListener { v, ev ->
        scaler.onTouchEvent(ev)
        if (scaler.isInProgress) return@setOnTouchListener true
        detector.onTouchEvent(ev)
        if (mode != 0) v.parent?.requestDisallowInterceptTouchEvent(true)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (longPressing) {
                longPressing = false
                Graph.player.exoPlayer.playbackParameters = PlaybackParameters(Graph.player.state.value.speed)
                onFeedback(null)
            }
            if (mode != 0) {
                mode = 0
                onFeedback(null)
            }
            v.parent?.requestDisallowInterceptTouchEvent(false)
        }
        true
    }
}

@Composable
fun GestureOverlay(feedback: GestureFeedback?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<GestureFeedback?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            shown = feedback
            if (!feedback.sticky) {
                kotlinx.coroutines.delay(650)
                if (shown?.stamp == feedback.stamp) shown = null
            }
        } else {
            kotlinx.coroutines.delay(250)
            shown = null
        }
    }
    AnimatedVisibility(shown != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val f = shown ?: return@AnimatedVisibility
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(f.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(f.text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ======================================================================= seek preview

@Composable
fun SeekPreview(frames: FrameSet?, positionMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frame = frames?.frameAt(positionMs)
    val cache = remember(frames) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(frame?.first) {
        val url = frame?.first ?: return@LaunchedEffect
        if (cache.containsKey(url)) return@LaunchedEffect
        val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val bmp = (context.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
        if (bmp != null) cache[url] = bmp
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val bmp = frame?.let { cache[it.first] }
        if (frame != null && bmp != null && frames != null) {
            val ratio = frames.frameWidth.toFloat() / frames.frameHeight.coerceAtLeast(1)
            Canvas(
                Modifier.width(176.dp).height((176 / ratio).dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)
            ) {
                drawImage(
                    bmp,
                    srcOffset = IntOffset(frame.second, frame.third),
                    srcSize = IntSize(frames.frameWidth.coerceAtMost(bmp.width - frame.second), frames.frameHeight.coerceAtMost(bmp.height - frame.third)),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Text(
            Format.duration(positionMs / 1000).ifEmpty { "0:00" },
            color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

// ======================================================================= captions & audio language

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionsSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val details = state.details ?: run { onDismiss(); return }
    val settings by Graph.settingsState.collectAsState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Captions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Auto-translate to ${java.util.Locale.getDefault().displayLanguage}") },
            supportingContent = { Text("Uses YouTube's caption translation") },
            trailingContent = {
                Switch(checked = settings.captionTranslate, onCheckedChange = { v ->
                    scope.launch {
                        Graph.settings.update { it.copy(captionTranslate = v) }
                        state.subtitle?.let { Graph.player.setSubtitle(it) }
                    }
                })
            }
        )
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Text size ${(settings.captionScale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = settings.captionScale, onValueChange = { v -> scope.launch { Graph.settings.update { it.copy(captionScale = v) } } }, valueRange = 0.6f..2f, steps = 6)
        }
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("Off") },
                    leadingContent = { RadioButton(selected = state.subtitle == null, onClick = null) },
                    modifier = Modifier.clickable { Graph.player.setSubtitle(null); onDismiss() }
                )
            }
            if (details.subtitles.isEmpty()) {
                item { Text("This video has no captions.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(details.subtitles, key = { it.languageCode + it.autoGenerated }) { sub ->
                val ctx = LocalContext.current
                ListItem(
                    headlineContent = { Text(sub.label) },
                    leadingContent = { RadioButton(selected = state.subtitle?.url == sub.url, onClick = null) },
                    trailingContent = {
                        androidx.compose.material3.IconButton(onClick = { downloadCaption(ctx, details.item.title, sub) }) {
                            Icon(Icons.Rounded.Download, "Download captions")
                        }
                    },
                    modifier = Modifier.clickable { Graph.player.setSubtitle(sub); onDismiss() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val details = state.details ?: run { onDismiss(); return }
    val tracks = details.audioTracks
    val currentId = state.audioTrackId ?: details.audioStreams.firstOrNull()?.trackId
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Audio language", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(tracks, key = { it.id ?: "default" }) { t ->
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = t.type?.let { type -> { Text(type.lowercase().replaceFirstChar { it.uppercase() }) } },
                    leadingContent = { RadioButton(selected = t.id == currentId, onClick = null) },
                    modifier = Modifier.clickable { Graph.player.setAudioTrack(t); onDismiss() }
                )
            }
        }
    }
}

/** Saves a caption track (WebVTT) to Downloads/Vidora via the system download manager. */
fun downloadCaption(context: Context, title: String, sub: com.vidora.app.data.SubtitleOption) {
    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(100).trim()
    val name = "$safe.${sub.languageCode}.vtt"
    runCatching {
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(sub.url))
            .setTitle(name)
            .setMimeType("text/vtt")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (android.os.Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Vidora/$name")
        else req.setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, name)
        context.getSystemService(android.app.DownloadManager::class.java).enqueue(req)
    }.onSuccess { android.widget.Toast.makeText(context, "Saving captions to Downloads/Vidora", android.widget.Toast.LENGTH_SHORT).show() }
        .onFailure { android.widget.Toast.makeText(context, "Couldn't download captions: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
}

// ======================================================================= equaliser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(onDismiss: () -> Unit) {
    val settings by Graph.settingsState.collectAsState()
    val scope = rememberCoroutineScope()
    val fx = Graph.player.effects
    fun update(block: (AppSettings) -> AppSettings) = scope.launch { Graph.settings.update(block) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Equalizer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Switch(checked = settings.eqEnabled, onCheckedChange = { v -> update { it.copy(eqEnabled = v) } })
                }
                if (!fx.supported) {
                    Text("Start playing something to load your device's equalizer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (fx.presets.isNotEmpty()) {
                item {
                    Text("Presets", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = settings.eqPreset < 0, onClick = { update { it.copy(eqPreset = -1, eqEnabled = true) } }, label = { Text("Custom") })
                        }
                        items(fx.presets.size) { i ->
                            FilterChip(selected = settings.eqPreset == i, onClick = { update { it.copy(eqPreset = i, eqEnabled = true) } }, label = { Text(fx.presets[i]) })
                        }
                    }
                }
            }
            if (fx.bands.isNotEmpty()) {
                item {
                    val levels = AudioEffects.parseBands(settings.eqBands).let { saved ->
                        if (saved.size == fx.bands.size) saved else fx.currentLevels().ifEmpty { List(fx.bands.size) { 0 } }
                    }
                    Column(Modifier.padding(top = 12.dp)) {
                        fx.bands.forEachIndexed { i, band ->
                            val label = if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                                Slider(
                                    value = levels.getOrElse(i) { 0 }.toFloat(),
                                    onValueChange = { v ->
                                        val updated = levels.toMutableList().also { it[i] = v.roundToInt() }
                                        update { it.copy(eqBands = AudioEffects.encodeBands(updated), eqPreset = -1, eqEnabled = true) }
                                    },
                                    valueRange = fx.levelRange.first.toFloat()..fx.levelRange.last.toFloat(),
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${levels.getOrElse(i) { 0 } / 100} dB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
                            }
                        }
                    }
                }
            }
            item {
                Text("Bass boost ${settings.bassBoost / 10}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Slider(value = settings.bassBoost.toFloat(), onValueChange = { v -> update { it.copy(bassBoost = v.roundToInt(), eqEnabled = true) } }, valueRange = 0f..1000f)
                Text("Volume boost +${settings.volumeBoost / 100} dB", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(value = settings.volumeBoost.toFloat(), onValueChange = { v -> update { it.copy(volumeBoost = v.roundToInt()) } }, valueRange = 0f..1500f)
                TextButton(onClick = { update { it.copy(eqBands = "", eqPreset = -1, bassBoost = 0, volumeBoost = 0) } }) { Text("Reset") }
            }
        }
    }
}

// ======================================================================= comments

@Stable
class CommentsState(private val url: String, private val scope: CoroutineScope) {
    val items = mutableStateListOf<CommentItem>()
    var total by mutableStateOf(-1)
        private set
    var disabled by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var next: (suspend () -> CommentsPage)? = null
    private var started = false
    val hasMore get() = next != null

    val replies = mutableStateMapOf<String, List<CommentItem>>()
    val loadingReplies = mutableStateMapOf<String, Boolean>()

    fun start() {
        if (started) return
        started = true
        loading = true
        scope.launch {
            runCatching { Graph.repository.comments(url) }
                .onSuccess { p -> items.addAll(p.items); next = p.next; total = p.totalCount; disabled = p.disabled }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    fun loadMore() {
        val n = next ?: return
        if (loading) return
        loading = true
        scope.launch {
            runCatching { n() }
                .onSuccess { p -> val known = items.map { it.id }.toHashSet(); items.addAll(p.items.filter { known.add(it.id) }); next = p.next }
                .onFailure { error = it.message }
            loading = false
        }
    }

    fun toggleReplies(c: CommentItem) {
        if (replies.containsKey(c.id)) { replies.remove(c.id); return }
        val load = c.loadReplies ?: return
        loadingReplies[c.id] = true
        scope.launch {
            val all = mutableListOf<CommentItem>()
            runCatching {
                var page: CommentsPage? = load()
                var guard = 0
                while (page != null && guard++ < 5) {
                    all += page.items
                    page = page.next?.invoke()
                }
            }
            replies[c.id] = all
            loadingReplies.remove(c.id)
        }
    }
}

@Composable
fun CommentsPreview(state: CommentsState, onOpen: () -> Unit) {
    LaunchedEffect(state) { state.start() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(MaterialTheme.shapes.medium).clickable(enabled = !state.disabled, onClick = onOpen)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.comments), style = MaterialTheme.typography.titleSmall)
                if (state.total > 0) Text("  ${Format.count(state.total.toLong())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            val first = state.items.firstOrNull()
            when {
                state.disabled -> Text("Comments are turned off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                first != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(first.authorAvatar, 24)
                    Spacer(Modifier.width(8.dp))
                    Text(first.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                state.loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error != null -> Text("Couldn't load comments", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Text("No comments yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(state: CommentsState, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.hasMore) state.loadMore() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Comments" + if (state.total > 0) " · ${Format.count(state.total.toLong())}" else "",
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(state.items, key = { "c" + it.id }) { c ->
                Column {
                    CommentRow(c, onSeek)
                    if (c.replyCount > 0 && c.loadReplies != null) {
                        TextButton(onClick = { state.toggleReplies(c) }, modifier = Modifier.padding(start = 52.dp)) {
                            Text(if (state.replies.containsKey(c.id)) "Hide replies" else "${c.replyCount} replies")
                        }
                    }
                    if (state.loadingReplies[c.id] == true) {
                        CircularProgressIndicator(Modifier.padding(start = 64.dp, bottom = 8.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                    state.replies[c.id]?.forEach { r ->
                        Box(Modifier.padding(start = 40.dp)) { CommentRow(r, onSeek) }
                    }
                }
            }
            if (state.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (!state.loading && state.items.isEmpty()) {
                item {
                    Text(
                        if (state.disabled) "Comments are turned off" else state.error ?: "No comments yet",
                        modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val timestampRegex = Regex("\\b(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\b")

@Composable
private fun CommentRow(c: CommentItem, onSeek: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Avatar(c.authorAvatar, 32)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    c.author, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (c.byCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (c.verified) Icon(Icons.Rounded.Verified, null, Modifier.padding(start = 3.dp).size(12.dp))
                c.published?.let { Text("  $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val plain = htmlToPlain(c.text)
            Text(plain, style = MaterialTheme.typography.bodyMedium)
            // Tappable timestamps ("12:34") seek the video
            val stamps = timestampRegex.findAll(plain).take(4).toList()
            if (stamps.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stamps.forEach { m ->
                        val h = m.groupValues[1].toLongOrNull() ?: 0
                        val sec = h * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()
                        FilledTonalButton(onClick = { onSeek(sec * 1000) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                            Text(m.value, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Rounded.ThumbUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.likes > 0) Text(" ${Format.count(c.likes.toLong())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.hearted) Icon(Icons.Rounded.Favorite, "Hearted by creator", Modifier.padding(start = 10.dp).size(14.dp), tint = Color(0xFFFF3B5C))
            }
        }
    }
}

private fun htmlToPlain(html: String): String =
    androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
