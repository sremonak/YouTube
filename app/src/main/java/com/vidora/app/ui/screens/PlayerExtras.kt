package com.vidora.app.ui.screens

import com.vidora.app.ui.theme.GlassBottomSheet
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import com.vidora.app.ui.icons.VolumeUp
import com.vidora.app.ui.icons.Send
import com.vidora.app.ui.icons.BrightnessMedium
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.ZoomInMap
import com.vidora.app.ui.icons.ZoomOutMap
import com.vidora.app.ui.icons.FastForward
import com.vidora.app.ui.icons.FastRewind
import com.vidora.app.ui.icons.Favorite
import com.vidora.app.ui.icons.KeyboardDoubleArrowRight
import com.vidora.app.ui.icons.PushPin
import com.vidora.app.ui.icons.ThumbUp
import com.vidora.app.ui.icons.Verified
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
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
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
/** [side]: −1 / +1 for double-tap seeks (drawn as a ripple on that half), 0 for a centred pill. */
data class GestureFeedback(val icon: ImageVector, val text: String, val sticky: Boolean = false, val stamp: Long = System.nanoTime(), val side: Int = 0)

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
    onSingleTap: (() -> Unit)? = null,
    onFeedback: (GestureFeedback?) -> Unit,
) {
    var lastSeekAt = 0L
    var lastSeekSide = 0
    var seekAcc = 0
    val ctx = view.context
    val audio = ctx.getSystemService(AudioManager::class.java)
    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var mode = 0 // 0 = none, 1 = brightness, 2 = volume
    var startValue = 0f
    var longPressing = false

    val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (onSingleTap != null) onSingleTap()
            else if (view.isControllerFullyVisible) view.hideController() else view.showController()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!enabled()) return false
            val p = Graph.player.exoPlayer
            val back = e.x < view.width / 2f
            val side = if (back) -1 else 1
            val now = android.os.SystemClock.uptimeMillis()
            // Repeated double taps on the same side add up (10 s, 20 s, 30 s…) like YouTube.
            seekAcc = if (now - lastSeekAt < 1200 && lastSeekSide == side) seekAcc + 10 else 10
            lastSeekAt = now; lastSeekSide = side
            val target = (p.currentPosition + if (back) -10_000 else 10_000).coerceIn(0, if (p.duration > 0) p.duration else Long.MAX_VALUE)
            p.seekTo(target)
            onFeedback(GestureFeedback(if (back) Icons.Rounded.FastRewind else Icons.Rounded.FastForward, if (back) "−$seekAcc s" else "+$seekAcc s", side = side))
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
        if (f.side != 0) {
            // YouTube-style half-screen ripple with the accumulated seek
            Box(Modifier.fillMaxSize()) {
                val shape = if (f.side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                Box(
                    Modifier.align(if (f.side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxHeight().fillMaxWidth(0.36f)
                        .clip(shape).background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(f.icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Text(f.text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@AnimatedVisibility
        }
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
        val bmp = context.imageLoader.execute(req).image?.toBitmap()?.asImageBitmap()
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
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_captions), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Auto-translate to ${java.util.Locale.getDefault().displayLanguage}") },
            supportingContent = { Text(stringResource(R.string.ui_uses_youtube_s_caption_translation)) },
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
                    headlineContent = { Text(stringResource(R.string.ui_off)) },
                    leadingContent = { RadioButton(selected = state.subtitle == null, onClick = null) },
                    modifier = Modifier.clickable { Graph.player.setSubtitle(null); onDismiss() }
                )
            }
            if (details.subtitles.isEmpty()) {
                item { Text(stringResource(R.string.ui_this_video_has_no_captions), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ui_audio_language), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
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
    GlassBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_equalizer), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Switch(checked = settings.eqEnabled, onCheckedChange = { v -> update { it.copy(eqEnabled = v) } })
                }
                if (!fx.supported) {
                    Text(stringResource(R.string.ui_start_playing_something_to_load_your_dev), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (fx.presets.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.ui_presets), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = settings.eqPreset < 0, onClick = { update { it.copy(eqPreset = -1, eqEnabled = true) } }, label = { Text(stringResource(R.string.ui_custom)) })
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
                TextButton(onClick = { update { it.copy(eqBands = "", eqPreset = -1, bassBoost = 0, volumeBoost = 0) } }) { Text(stringResource(R.string.ui_reset)) }
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
    var createParams by mutableStateOf<String?>(null)
        private set
    var posting by mutableStateOf(false)
        private set
    /** Local like state (id → liked) for instant feedback. */
    val likedOverride = mutableStateMapOf<String, Boolean>()

    fun toggleLike(c: CommentItem, onError: (String) -> Unit) {
        val liked = likedOverride[c.id] ?: c.liked
        val action = (if (liked) c.unlikeAction else c.likeAction) ?: return onError("Sign in to like comments")
        likedOverride[c.id] = !liked
        scope.launch {
            runCatching { Graph.accountApi.commentAction(action) }.onFailure {
                likedOverride[c.id] = liked
                onError(it.message ?: "Couldn't like")
            }
        }
    }

    /** Posts a comment (or a reply to [parent]) and shows it right away, like the app. */
    fun post(text: String, parent: CommentItem?, onDone: (Boolean, String?) -> Unit) {
        val params = (if (parent != null) parent.replyParams else createParams) ?: return onDone(false, "Sign in to comment")
        posting = true
        scope.launch {
            val r = runCatching {
                if (parent != null) Graph.accountApi.replyComment(params, text) else Graph.accountApi.createComment(params, text)
            }
            posting = false
            val ok = r.getOrDefault(false)
            if (ok) {
                val mine = CommentItem(
                    id = "local-" + System.currentTimeMillis(), author = Graph.account.session.value?.name ?: "You", authorAvatar = Graph.account.session.value?.avatar,
                    authorUrl = null, text = android.text.TextUtils.htmlEncode(text), likes = 0, published = "now", replyCount = 0,
                    pinned = false, hearted = false, verified = false, byCreator = false, loadReplies = null,
                )
                if (parent != null) replies[parent.id] = listOf(mine) + (replies[parent.id] ?: emptyList())
                else { items.add(0, mine); if (total >= 0) total += 1 }
                com.vidora.app.ui.UiCache.invalidate("comments:")
            }
            onDone(ok, r.exceptionOrNull()?.message ?: if (!ok) "YouTube rejected the comment" else null)
        }
    }

    fun start() {
        if (started) return
        started = true
        loading = true
        scope.launch {
            runCatching { Graph.repository.comments(url) }
                .onSuccess { p -> items.addAll(p.items); next = p.next; total = p.totalCount; disabled = p.disabled; createParams = p.createParams }
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
                state.disabled -> Text(stringResource(R.string.ui_comments_are_turned_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                first != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(first.authorAvatar, 24)
                    Spacer(Modifier.width(8.dp))
                    Text(first.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                state.loading -> Text(stringResource(R.string.ui_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error != null -> Text(stringResource(R.string.ui_couldn_t_load_comments), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Text(stringResource(R.string.ui_no_comments_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<CommentItem?>(null) }
    val toast: (String) -> Unit = { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Comments" + if (state.total > 0) " · ${Format.count(state.total.toLong())}" else "",
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        // Write a comment / reply (signed in, like the official app)
        if (state.createParams != null || replyTo != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                replyTo?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Replying to ${r.author}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { replyTo = null }) { Text("Cancel") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Graph.account.session.value?.avatar, 28)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = draft, onValueChange = { draft = it.take(10_000) },
                        placeholder = { Text(if (replyTo != null) "Add a reply…" else "Add a comment…") },
                        modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(20.dp),
                    )
                    androidx.compose.material3.IconButton(
                        enabled = draft.isNotBlank() && !state.posting,
                        onClick = {
                            state.post(draft.trim(), replyTo) { ok, err ->
                                if (ok) { draft = ""; replyTo = null; toast("Posted") } else toast(err ?: "Couldn't post")
                            }
                        }
                    ) {
                        if (state.posting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Rounded.Send, "Post")
                    }
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(state.items, key = { "c" + it.id }) { c ->
                Column {
                    CommentRow(
                        c, onSeek,
                        liked = state.likedOverride[c.id] ?: c.liked,
                        onLike = if (c.likeAction != null || c.unlikeAction != null) ({ state.toggleLike(c, toast) }) else null,
                        onReply = if (c.replyParams != null) ({ replyTo = c }) else null,
                    )
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
private fun CommentRow(
    c: CommentItem, onSeek: (Long) -> Unit,
    liked: Boolean = c.liked, onLike: (() -> Unit)? = null, onReply: (() -> Unit)? = null,
) {
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
                val likeTint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                val shownLikes = c.likes + (if (liked && !c.liked) 1 else 0) - (if (!liked && c.liked) 1 else 0)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = onLike != null) { onLike?.invoke() }.padding(4.dp)
                ) {
                    Icon(Icons.Rounded.ThumbUp, if (liked) "Unlike" else "Like", Modifier.size(14.dp), tint = likeTint)
                    if (shownLikes > 0) Text(" ${Format.count(shownLikes.toLong())}", style = MaterialTheme.typography.labelSmall, color = likeTint)
                }
                if (c.hearted) Icon(Icons.Rounded.Favorite, "Hearted by creator", Modifier.padding(start = 10.dp).size(14.dp), tint = Color(0xFFFF3B5C))
                if (onReply != null) {
                    Text(
                        "Reply", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(50)).clickable(onClick = onReply).padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private fun htmlToPlain(html: String): String =
    androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

// ======================================================================= live chat

/** Inline live chat for live streams: polls YouTube every few seconds and auto-scrolls. */
@Composable
fun LiveChatPanel(url: String, initialToken: String, modifier: Modifier = Modifier) {
    val messages = remember(url) { mutableStateListOf<CommentItem>() }
    var status by remember(url) { mutableStateOf<String?>("Connecting…") }
    var paused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(url, initialToken) {
        var token: String? = initialToken
        var failures = 0
        while (token != null) {
            val result = runCatching { Graph.repository.liveChat(url, token!!) }
            result.onSuccess { (items, next) ->
                failures = 0
                status = null
                if (items.isNotEmpty()) {
                    messages.addAll(items)
                    if (messages.size > 300) messages.removeRange(0, messages.size - 300)
                }
                token = next
            }.onFailure {
                failures++
                status = "Reconnecting…"
                if (failures >= 5) { status = "Live chat unavailable"; token = null }
            }
            kotlinx.coroutines.delay(if (failures > 0) 6_000L else 4_000L)
        }
        if (status == null) status = "Chat ended"
    }
    LaunchedEffect(messages.size) {
        if (!paused && messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE53935)))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_live_chat), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Pause") }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(280.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(messages.size) { i ->
                    val m = messages[i]
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Avatar(m.authorAvatar, 20)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                pushStyle(androidx.compose.ui.text.SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = if (m.byCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                ))
                                append(m.author)
                                pop()
                                append("  ")
                                append(htmlToPlain(m.text))
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** "Stats for nerds": live codec / resolution / bitrate / buffer / dropped-frame info. */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun StatsSheet(onDismiss: () -> Unit) {
    val player = com.vidora.app.Graph.player.exoPlayer
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    val rows = androidx.compose.runtime.remember(tick) {
        val v = player.videoFormat
        val a = player.audioFormat
        val size = player.videoSize
        val counters = player.videoDecoderCounters
        fun kbps(b: Int) = if (b > 0) "${b / 1000} kbps" else "—"
        buildList {
            add("Source" to (com.vidora.app.Graph.player.state.value.details?.source ?: "—"))
            add("Video" to (v?.let { "${size.width.takeIf { it > 0 } ?: it.width}×${size.height.takeIf { it > 0 } ?: it.height}" + (if (it.frameRate > 0) " @ ${it.frameRate.toInt()} fps" else "") } ?: "Audio only"))
            add("Video codec" to (v?.let { listOfNotNull(it.codecs, it.sampleMimeType).firstOrNull() } ?: "—"))
            add("Video bitrate" to (v?.let { kbps(maxOf(it.bitrate, it.averageBitrate, it.peakBitrate)) } ?: "—"))
            add("Audio codec" to (a?.let { listOfNotNull(it.codecs, it.sampleMimeType).firstOrNull() } ?: "—"))
            add("Audio" to (a?.let { "${it.sampleRate} Hz · ${it.channelCount} ch · ${kbps(maxOf(it.bitrate, it.averageBitrate, it.peakBitrate))}" } ?: "—"))
            add("Audio track" to (a?.language ?: "default"))
            add("Buffer" to "%.1f s".format((player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000f))
            add("Dropped frames" to (counters?.let { "${it.droppedBufferCount} / ${it.renderedOutputBufferCount}" } ?: "—"))
            add("Speed" to "${player.playbackParameters.speed}×")
            add("Volume" to "${(player.volume * 100).toInt()} %")
        }
    }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text("Stats for nerds", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding()) {
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}
