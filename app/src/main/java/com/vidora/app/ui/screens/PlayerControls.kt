package com.vidora.app.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import com.vidora.app.ui.theme.GlassBottomSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.KeyboardArrowRight
import com.vidora.app.ui.icons.Cast
import com.vidora.app.ui.icons.CastConnected
import com.vidora.app.ui.icons.QueueMusic
import com.vidora.app.ui.icons.Audiotrack
import com.vidora.app.ui.icons.Bedtime
import com.vidora.app.ui.icons.ClosedCaption
import com.vidora.app.ui.icons.ClosedCaptionDisabled
import com.vidora.app.ui.icons.Forward10
import com.vidora.app.ui.icons.Fullscreen
import com.vidora.app.ui.icons.FullscreenExit
import com.vidora.app.ui.icons.GraphicEq
import com.vidora.app.ui.icons.HighQuality
import com.vidora.app.ui.icons.Info
import com.vidora.app.ui.icons.KeyboardArrowDown
import com.vidora.app.ui.icons.Lock
import com.vidora.app.ui.icons.LockOpen
import com.vidora.app.ui.icons.Pause
import com.vidora.app.ui.icons.PlayArrow
import com.vidora.app.ui.icons.Repeat
import com.vidora.app.ui.icons.RepeatOne
import com.vidora.app.ui.icons.Replay
import com.vidora.app.ui.icons.Replay10
import com.vidora.app.ui.icons.Settings
import com.vidora.app.ui.icons.SkipNext
import com.vidora.app.ui.icons.SkipPrevious
import com.vidora.app.ui.icons.Speed
import com.vidora.app.ui.icons.Toc
import com.vidora.app.ui.icons.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.blur
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.vidora.app.Graph
import com.vidora.app.data.Chapter
import com.vidora.app.player.PlayerUiState
import com.vidora.app.player.qualityLabel
import com.vidora.app.ui.theme.glass
import com.vidora.app.ui.theme.glassOverlay
import com.vidora.app.util.Format
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** SponsorBlock category colours (as on sponsor.ajay.app). */
internal fun sponsorColor(category: String): Color = when (category) {
    "sponsor" -> Color(0xFF00D400)
    "selfpromo" -> Color(0xFFFFFF00)
    "interaction" -> Color(0xFFCC00FF)
    "intro" -> Color(0xFF00FFFF)
    "outro" -> Color(0xFF0202ED)
    "preview" -> Color(0xFF008FD6)
    "music_offtopic" -> Color(0xFFFF9900)
    "filler" -> Color(0xFF7300FF)
    else -> Color(0xFF00D400)
}

/** Live playback position, polled from the player (fast while the controls are visible). */
@Composable
private fun rememberPlaybackClock(key: Any?, fast: Boolean): Triple<Long, Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buf by remember { mutableLongStateOf(0L) }
    val isFast by rememberUpdatedState(fast)
    LaunchedEffect(key) {
        while (true) {
            val p = Graph.player.exoPlayer
            pos = p.currentPosition.coerceAtLeast(0)
            dur = p.duration.takeIf { it > 0 } ?: 0
            buf = p.bufferedPosition.coerceAtLeast(0)
            delay(if (isFast) 200 else 600)
        }
    }
    return Triple(pos, dur, buf)
}

private fun currentChapter(chapters: List<Chapter>, ms: Long): Chapter? =
    chapters.lastOrNull { it.startSeconds * 1000L <= ms }

/**
 * Glass player controller drawn in Compose over the video surface: smoked-glass buttons,
 * animated play/pause, ±10 s, chapter title, remaining-time toggle, speed badge, lock,
 * quick settings and a custom seek bar with chapter gaps, buffered range, SponsorBlock
 * segments and a thumbnail preview while scrubbing.
 */
@Composable
internal fun ProPlayerControls(
    state: PlayerUiState,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    fullscreen: Boolean,
    locked: Boolean,
    onLockChange: (Boolean) -> Unit,
    onFullscreen: () -> Unit,
    onMinimize: (() -> Unit)?,
    openSheet: (Sheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (pos, dur, buf) = rememberPlaybackClock(state.current?.url, visible)
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var showRemaining by remember { mutableStateOf(false) }
    var interaction by remember { mutableLongStateOf(0L) }
    val details = state.details
    val chapters = details?.chapters.orEmpty()
    val isLive = state.current?.isLive == true
    val ended = !state.isPlaying && dur > 0 && pos >= dur - 500

    // Auto-hide like YouTube: 3.5 s after the last interaction while playing.
    LaunchedEffect(visible, state.isPlaying, interaction, scrubMs == null, locked) {
        if (visible && state.isPlaying && scrubMs == null) {
            delay(if (locked) 1800 else 3500)
            onVisibleChange(false)
        }
    }
    fun touch() { interaction = System.nanoTime() }

    Box(modifier.fillMaxSize()) {
        // Thin progress line while the controls are hidden (portrait), like the app.
        if (!visible && !fullscreen && dur > 0 && !isLive) {
            val frac = (pos.toFloat() / dur).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }

        if (locked) {
            AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                GlassIconButton(Icons.Rounded.Lock, "Unlock", size = 52.dp) { onLockChange(false); touch() }
            }
            return@Box
        }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f), 0.28f to Color.Transparent,
                        0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.70f)
                    )
                )
            ) {
                // ---------------------------------------------------------------- top bar
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = if (fullscreen) 14.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (fullscreen) GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Exit fullscreen") { onFullscreen() }
                    else if (onMinimize != null) GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Minimize") { onMinimize() }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        if (fullscreen) {
                            Text(state.current?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.current?.uploader.orEmpty(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                    if (state.speed != 1f) {
                        GlassPill(Modifier.clickable { openSheet(Sheet.SPEED); touch() }) {
                            Icon(Icons.Rounded.Speed, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(" ${trimSpeedLabel(state.speed)}×", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (details?.subtitles?.isNotEmpty() == true) {
                        GlassIconButton(if (state.subtitle != null) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled, "Captions") {
                            openSheet(Sheet.CAPTIONS); touch()
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (fullscreen) {
                        GlassIconButton(Icons.Rounded.LockOpen, "Lock controls") { onLockChange(true); touch() }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (com.vidora.app.player.CastManager.available) {
                        val castingTo by com.vidora.app.player.CastManager.castingTo.collectAsState()
                        GlassIconButton(if (castingTo != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, "Cast to TV") { openSheet(Sheet.CAST); touch() }
                        Spacer(Modifier.width(6.dp))
                    }
                    GlassIconButton(Icons.Rounded.Settings, "Player settings") { openSheet(Sheet.SETTINGS); touch() }
                }

                // ---------------------------------------------------------------- centre
                if (!state.loading && state.error == null) {
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 40.dp else 22.dp)
                    ) {
                        GlassIconButton(Icons.Rounded.SkipPrevious, "Previous", enabled = state.queueIndex > 0 || pos > 3000) { Graph.player.playPrevious(); touch() }
                        if (!isLive) GlassIconButton(Icons.Rounded.Replay10, "Back 10 seconds") { Graph.player.seekTo((pos - 10_000).coerceAtLeast(0)); touch() }
                        val playScale by animateFloatAsState(if (state.isPlaying) 1f else 1.06f, label = "play")
                        Box(
                            Modifier.size((if (fullscreen) 76 else 64).dp * playScale).glassOverlay(CircleShape, alpha = 0.42f)
                                .clickable {
                                    if (com.vidora.app.player.CastManager.isCasting) { openSheet(Sheet.CAST); return@clickable }
                                    if (ended) Graph.player.seekTo(0)
                                    Graph.player.togglePlay(); touch()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = when { ended -> 2; state.isPlaying -> 1; else -> 0 },
                                transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                                label = "playIcon"
                            ) { s ->
                                Icon(
                                    when (s) { 2 -> Icons.Rounded.Replay; 1 -> Icons.Rounded.Pause; else -> Icons.Rounded.PlayArrow },
                                    if (s == 1) "Pause" else "Play", tint = Color.White,
                                    modifier = Modifier.size(if (fullscreen) 44.dp else 38.dp)
                                )
                            }
                        }
                        if (!isLive) GlassIconButton(Icons.Rounded.Forward10, "Forward 10 seconds") { Graph.player.seekTo(if (dur > 0) (pos + 10_000).coerceAtMost(dur) else pos + 10_000); touch() }
                        GlassIconButton(Icons.Rounded.SkipNext, "Next") { Graph.player.playNext(); touch() }
                    }
                }

                // ---------------------------------------------------------------- bottom
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .padding(horizontal = if (fullscreen) 20.dp else 10.dp)
                        .padding(bottom = if (fullscreen) 14.dp else 0.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        if (isLive) {
                            GlassPill(Modifier.clickable { Graph.player.exoPlayer.seekToDefaultPosition(); touch() }) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF0033)))
                                Text("  LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            val shown = scrubMs ?: pos
                            Text(
                                (if (showRemaining && dur > 0) "−" + fmt(dur - shown) else fmt(shown)) + "  /  " + fmt(dur),
                                color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { showRemaining = !showRemaining; touch() }.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                        currentChapter(chapters, scrubMs ?: pos)?.let { ch ->
                            Spacer(Modifier.width(6.dp))
                            Row(
                                Modifier.weight(1f, fill = false).clip(RoundedCornerShape(50)).clickable { openSheet(Sheet.CHAPTERS); touch() }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("•  " + ch.title, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Fullscreen", tint = Color.White,
                            modifier = Modifier.clip(CircleShape).clickable { onFullscreen() }.padding(6.dp).size(26.dp)
                        )
                    }
                    if (!isLive && dur > 0) {
                        ProSeekBar(
                            position = pos, buffered = buf, duration = dur,
                            chapters = chapters.drop(1).map { it.startSeconds * 1000L },
                            segments = state.segments.map { Triple(it.startMs, it.endMs, sponsorColor(it.category)) },
                            frames = details?.frames,
                            chapterAt = { ms -> currentChapter(chapters, ms)?.title },
                            onScrub = { scrubMs = it; touch() },
                            onSeek = { ms -> Graph.player.seekTo(ms); touch() },
                        )
                    } else Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

private fun fmt(ms: Long) = Format.duration(ms / 1000).ifEmpty { "0:00" }

private fun trimSpeedLabel(s: Float) = if (s % 1f == 0f) s.toInt().toString() else s.toString()

@Composable
internal fun GlassIconButton(icon: ImageVector, label: String, size: Dp = 48.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).glassOverlay(CircleShape, alpha = 0.32f).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun GlassPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.glassOverlay(RoundedCornerShape(50), alpha = 0.34f).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

/**
 * Seek bar: chapter segments with gaps, buffered range, SponsorBlock ranges in their category
 * colours, a thumb that grows while dragging and a storyboard preview that follows the finger.
 */
@Composable
private fun ProSeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    chapters: List<Long>,
    segments: List<Triple<Long, Long, Color>>,
    frames: com.vidora.app.data.FrameSet?,
    chapterAt: (Long) -> String?,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val active = dragFrac != null
    val trackH by animateDpAsState(if (active) 6.dp else 3.dp, label = "track")
    val thumbR by animateDpAsState(if (active) 9.dp else 6.dp, label = "thumb")
    val density = LocalDensity.current
    val frac = dragFrac ?: (position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxW = maxWidth
        // Storyboard preview above the thumb
        dragFrac?.let { f ->
            val ms = (f * duration).toLong()
            val previewW = 176.dp
            val x = (maxW * f - previewW / 2).coerceIn(0.dp, (maxW - previewW).coerceAtLeast(0.dp))
            Column(
                Modifier.offset { IntOffset(with(density) { x.roundToPx() }, with(density) { (-150).dp.roundToPx() }) }.width(previewW),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                chapterAt(ms)?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 2.dp))
                }
                SeekPreview(frames, ms)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(48.dp)
                .semantics {
                    contentDescription = "Playback position"
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(frac, 0f..1f)
                    setProgress { value -> onSeek((value.coerceIn(0f, 1f) * duration).toLong()); true }
                }
                .pointerInput(duration) {
                    detectTapGestures { o ->
                        val f = (o.x / size.width).coerceIn(0f, 1f)
                        onSeek((f * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f); onScrub((dragFrac!! * duration).toLong()) },
                        onDragEnd = { dragFrac?.let { onSeek((it * duration).toLong()) }; dragFrac = null; onScrub(null) },
                        onDragCancel = { dragFrac = null; onScrub(null) },
                    ) { change, delta ->
                        change.consume()
                        val f = ((dragFrac ?: 0f) + delta / size.width).coerceIn(0f, 1f)
                        dragFrac = f
                        onScrub((f * duration).toLong())
                    }
                }
        ) {
            val h = trackH.toPx()
            val y = size.height / 2 - h / 2
            val gap = 2.dp.toPx()
            val r = CornerRadius(h / 2, h / 2)
            val bounds = (listOf(0L) + chapters.filter { it in 1 until duration } + listOf(duration)).distinct().sorted()
            fun x(ms: Long) = ms.toFloat() / duration * size.width
            for (i in 0 until bounds.size - 1) {
                val s = x(bounds[i]) + if (i > 0) gap / 2 else 0f
                val e = x(bounds[i + 1]) - if (i < bounds.size - 2) gap / 2 else 0f
                if (e <= s) continue
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(s, y), Size(e - s, h), r)
                val bEnd = minOf(e, x(buffered))
                if (bEnd > s) drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(s, y), Size(bEnd - s, h), r)
                segments.forEach { (a, b, c) ->
                    val ss = maxOf(s, x(a)); val ee = minOf(e, x(b))
                    if (ee > ss) drawRect(c.copy(alpha = 0.9f), Offset(ss, y), Size(ee - ss, h))
                }
                val pEnd = minOf(e, frac * size.width)
                if (pEnd > s) drawRoundRect(primary, Offset(s, y), Size(pEnd - s, h), r)
            }
            val tx = frac * size.width
            drawCircle(primary.copy(alpha = 0.25f), radius = thumbR.toPx() * 1.8f, center = Offset(tx, size.height / 2))
            drawCircle(primary, radius = thumbR.toPx(), center = Offset(tx, size.height / 2))
        }
    }
}

// ========================================================================== sheets

/** Quick settings (gear): everything the player can do in one glassy list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSettingsSheet(state: PlayerUiState, openSheet: (Sheet) -> Unit, onDismiss: () -> Unit) {
    val details = state.details
    val settings by Graph.settingsState.collectAsState()
    GlassBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            fun go(s: Sheet) { onDismiss(); openSheet(s) }
            SettingRow(Icons.Rounded.HighQuality, "Quality", state.qualityLabel) { go(Sheet.QUALITY) }
            SettingRow(Icons.Rounded.Speed, "Playback speed", if (state.speed == 1f) "Normal" else "${trimSpeedLabel(state.speed)}×") { go(Sheet.SPEED) }
            SettingRow(Icons.Rounded.ClosedCaption, "Captions", state.subtitle?.label ?: "Off") { go(Sheet.CAPTIONS) }
            if (details != null && details.audioTracks.size > 1) {
                SettingRow(Icons.Rounded.Translate, "Audio track", details.audioTracks.firstOrNull { it.id == state.audioTrackId }?.name ?: "Default") { go(Sheet.AUDIO) }
            }
            if (details?.chapters?.isNotEmpty() == true) SettingRow(Icons.Rounded.Toc, "Chapters", "${details.chapters.size}") { go(Sheet.CHAPTERS) }
            SettingRow(Icons.Rounded.Bedtime, "Sleep timer", when {
                state.sleepAtEnd -> "End of video"
                state.sleepTimerEndsAt > 0 -> "On"
                else -> "Off"
            }) { go(Sheet.SLEEP) }
            SettingRow(Icons.Rounded.GraphicEq, "Equalizer & volume boost", if (settings.eqEnabled) "On" else "Off") { go(Sheet.EQ) }
            SettingRow(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", "${state.queue.size}") { go(Sheet.QUEUE) }
            ToggleRow(if (state.loop) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Loop video", state.loop) { Graph.player.toggleLoop() }
            ToggleRow(Icons.Rounded.Audiotrack, "Audio only", state.audioOnly) { Graph.player.setAudioOnly(!state.audioOnly) }
            SettingRow(Icons.Rounded.Info, "Stats for nerds", details?.source ?: "") { go(Sheet.STATS) }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, checked: Boolean, onToggle: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = { onToggle() }) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onToggle)
    )
}

/** Chapter list with thumbnails; tapping jumps there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChaptersSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val chapters = state.details?.chapters.orEmpty()
    val (pos, _, _) = rememberPlaybackClock(state.current?.url, true)
    val current = currentChapter(chapters, pos)
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(chapters) { ch ->
                val isCurrent = ch == current
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { Graph.player.seekTo(ch.startSeconds * 1000L); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                        AsyncImage(ch.thumbnail ?: state.current?.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(Format.duration(ch.startSeconds.toLong()).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Ambient mode: the video's colours as a soft glow behind the player and the top of the
 * details, like YouTube. A tiny thumbnail upscaled is already a blur on every Android version;
 * Android 12+ adds a real blur on top.
 */
@Composable
internal fun AmbientGlow(thumbnail: String?, modifier: Modifier, dark: Boolean) {
    if (thumbnail == null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(thumbnail) {
        coil3.request.ImageRequest.Builder(context).data(thumbnail).size(64, 36).crossfade(600).build()
    }
    Box(modifier) {
        AsyncImage(
            request, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
                .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else Modifier),
            alpha = if (dark) 0.55f else 0.35f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                    1f to MaterialTheme.colorScheme.background
                )
            )
        )
    }
}

/** Glass action pill for the details row (Like, Share, Download…). */
@Composable
internal fun GlassActionPill(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val selectedBg = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.height(36.dp)
            .then(
                if (selected) Modifier.clip(shape).background(selectedBg)
                else Modifier.glass(shape, tint = MaterialTheme.colorScheme.surfaceContainerHigh, fallbackAlpha = 0.62f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}
