package com.vidora.app.player

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.vidora.app.Graph
import androidx.media3.common.Format
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.webvtt.WebvttParser
import com.vidora.app.data.AppSettings
import com.vidora.app.data.AudioTrack
import com.vidora.app.data.SubtitleOption
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.vidora.app.data.AudioOption
import com.vidora.app.data.SponsorCategories
import com.vidora.app.data.SponsorSegment
import com.vidora.app.data.StreamDetails
import com.vidora.app.data.VideoItem
import com.vidora.app.data.VideoOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class PlayerUiState(
    val current: VideoItem? = null,
    val details: StreamDetails? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val audioOnly: Boolean = false,
    val quality: VideoOption? = null,
    val audio: AudioOption? = null,
    val speed: Float = 1f,
    val queue: List<VideoItem> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val localFile: Boolean = false,
    val segments: List<SponsorSegment> = emptyList(),
    val sleepTimerEndsAt: Long = 0,
    /** Sleep timer set to "end of current video". */
    val sleepAtEnd: Boolean = false,
    val loop: Boolean = false,
    val subtitle: SubtitleOption? = null,
    val audioTrackId: String? = null,
    /** Restored from the last session; media is loaded on first play. */
    val restored: Boolean = false,
)

/**
 * Owns the single ExoPlayer instance. The [PlaybackService] wraps the same player in a
 * MediaSession, which gives us background playback, lock-screen & notification controls.
 */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var sleepJob: Job? = null
    private val skippedSegments = mutableSetOf<String>()

    private val streamClient = Graph.http.newBuilder()
        .addInterceptor(StreamInterceptor())
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val httpFactory = OkHttpDataSource.Factory(streamClient)
    private val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
                .build()
        )
        .build()

    /** Player exposed to the MediaSession / PlayerView: adds queue-aware next/previous. */
    val sessionPlayer: Player = object : ForwardingPlayer(exoPlayer) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun play() {
            val s = _state.value
            if (s.restored && s.current != null) this@PlayerManager.play(s.current, s.queue)
            else if (!refreshIfStale()) super.play()
        }

        // Items chosen in Android Auto / other MediaBrowsers arrive as placeholders → resolve them.
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (!routeBrowserItems(mediaItems, startIndex)) super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            if (!routeBrowserItems(mediaItems, 0)) super.setMediaItems(mediaItems, resetPosition)
        }

        override fun setMediaItem(mediaItem: MediaItem) {
            if (!routeBrowserItems(mutableListOf(mediaItem), 0)) super.setMediaItem(mediaItem)
        }

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
        override fun seekToNext() = playNext()
        override fun seekToNextMediaItem() = playNext()
        override fun seekToPrevious() = playPrevious()
        override fun seekToPreviousMediaItem() = playPrevious()
    }

    private var settings = AppSettings()
    val effects = AudioEffects()
    private val sessionStore = SessionStore(context)

    init {
        runCatching { exoPlayer.audioSessionId = Util.generateAudioSessionIdV21(context) }
        scope.launch { Graph.settings.flow.collect { applySettings(it) } }
        scope.launch(Dispatchers.IO) {
            _state.map { it.queue.map { v -> v.url } to it.queueIndex }.distinctUntilChanged().collect {
                val s = _state.value
                if (settings.persistQueue && !s.localFile) sessionStore.save(PersistedSession(s.queue, s.queueIndex))
            }
        }
        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                effects.attach(audioSessionId, settings)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (!isPlaying) persistPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })
    }

    private suspend fun applySettings(s: AppSettings) = withContext(Dispatchers.Main) {
        val first = settings == AppSettings() && s != settings
        settings = s
        exoPlayer.skipSilenceEnabled = s.skipSilence
        effects.attach(exoPlayer.audioSessionId, s)
        Graph.repository.useAccountForRestricted = s.useAccountForRestricted
        if (first && _state.value.current == null) {
            _state.value = _state.value.copy(audioOnly = s.audioOnlyDefault, speed = s.defaultSpeed)
        }
        if (!restoreChecked) {
            restoreChecked = true
            if (s.persistQueue && _state.value.current == null) restoreSession()
        }
    }

    private var restoreChecked = false

    private suspend fun restoreSession() {
        val saved = withContext(Dispatchers.IO) { sessionStore.load() } ?: return
        if (_state.value.current == null) {
            _state.value = _state.value.copy(
                current = saved.queue[saved.index], queue = saved.queue, queueIndex = saved.index, restored = true,
            )
        }
    }

    // ------------------------------------------------------------------ public API

    fun play(item: VideoItem, queue: List<VideoItem>? = null) {
        val newQueue = (queue ?: _state.value.queue.takeIf { q -> q.any { it.url == item.url } } ?: listOf(item))
            .distinctBy { it.url }
        val index = newQueue.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
        if (_state.value.current?.url == item.url && _state.value.details != null) {
            _state.value = _state.value.copy(queue = newQueue, queueIndex = index)
            exoPlayer.play()
            return
        }
        persistPosition()
        skippedSegments.clear()
        _state.value = _state.value.copy(
            current = item, details = null, loading = true, error = null,
            queue = newQueue, queueIndex = index, localFile = false, segments = emptyList(),
            quality = null, audio = null, subtitle = null, audioTrackId = null, restored = false,
        )
        exoPlayer.stop()
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Main) {
            try {
                val details = Graph.repository.stream(item.url)
                val merged = details.item.copy(
                    thumbnail = details.item.thumbnail ?: item.thumbnail,
                    uploaderAvatar = details.item.uploaderAvatar ?: item.uploaderAvatar,
                )
                detailsFetchedAt = System.currentTimeMillis()
                refreshAttempts = 0
                _state.value = _state.value.copy(
                    current = merged, details = details, loading = false,
                    subtitle = defaultSubtitle(details),
                    audioTrackId = defaultAudioTrack(details),
                )
                if (settings.saveHistory && !settings.incognito) Graph.library.addHistory(merged)
                val resumeAt = if (settings.resumePlayback && !merged.isLive) {
                    Graph.library.positionOf(merged.url).takeIf { it > 5_000 && (merged.duration == 0L || it < merged.duration * 1000 - 10_000) } ?: 0
                } else 0
                prepare(details, resumeAt, autoPlay = true)
                loadSponsorSegments(merged)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(loading = false, error = t.message ?: t.javaClass.simpleName)
            }
        }
        startTicker()
    }

    /** Plays a downloaded file from local storage (works fully offline). */
    fun playLocal(item: VideoItem, uri: Uri, audioOnly: Boolean) {
        persistPosition()
        loadJob?.cancel()
        _state.value = PlayerUiState(
            current = item, localFile = true, audioOnly = audioOnly, speed = _state.value.speed,
            queue = listOf(item), queueIndex = 0,
        )
        val mediaItem = MediaItem.Builder().setUri(uri).setMediaMetadata(metadata(item)).build()
        exoPlayer.setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem))
        exoPlayer.playbackParameters = PlaybackParameters(_state.value.speed)
        exoPlayer.prepare()
        exoPlayer.play()
        startTicker()
    }

    fun retry() {
        val current = _state.value.current ?: return
        _state.value = _state.value.copy(details = null)
        play(current, _state.value.queue)
    }

    fun setQuality(option: VideoOption) {
        val details = _state.value.details ?: return
        _state.value = _state.value.copy(quality = option, audioOnly = false)
        prepare(details, exoPlayer.currentPosition, exoPlayer.playWhenReady, forced = option)
    }

    fun setAudioOnly(enabled: Boolean) {
        val details = _state.value.details
        _state.value = _state.value.copy(audioOnly = enabled)
        if (details != null && !_state.value.localFile) {
            prepare(details, exoPlayer.currentPosition, exoPlayer.playWhenReady, forced = _state.value.quality)
        }
    }

    fun setSubtitle(option: SubtitleOption?) {
        val details = _state.value.details ?: return
        _state.value = _state.value.copy(subtitle = option)
        if (option != null) scope.launch { Graph.settings.update { it.copy(captionsEnabled = true, captionLang = option.languageCode) } }
        else scope.launch { Graph.settings.update { it.copy(captionsEnabled = false) } }
        prepare(details, exoPlayer.currentPosition, exoPlayer.playWhenReady, forced = _state.value.quality)
    }

    fun setAudioTrack(track: AudioTrack) {
        val details = _state.value.details ?: return
        _state.value = _state.value.copy(audioTrackId = track.id)
        val pref = if (track.type == null || track.type == "ORIGINAL") "" else track.name
        scope.launch { Graph.settings.update { it.copy(preferredAudioLang = pref) } }
        prepare(details, exoPlayer.currentPosition, exoPlayer.playWhenReady, forced = _state.value.quality)
    }

    private fun defaultSubtitle(details: StreamDetails): SubtitleOption? {
        if (!settings.captionsEnabled || details.subtitles.isEmpty()) return null
        val lang = settings.captionLang.ifBlank { java.util.Locale.getDefault().language }
        return details.subtitles.firstOrNull { it.languageCode == lang && !it.autoGenerated }
            ?: details.subtitles.firstOrNull { it.languageCode.substringBefore('-') == lang.substringBefore('-') }
            ?: if (settings.captionTranslate) details.subtitles.firstOrNull() else null
    }

    private fun defaultAudioTrack(details: StreamDetails): String? {
        val tracks = details.audioTracks
        if (tracks.size <= 1) return null
        val pref = settings.preferredAudioLang
        if (pref.isNotBlank()) tracks.firstOrNull { it.name.equals(pref, true) }?.let { return it.id }
        return null
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    fun toggleLoop() {
        val loop = !_state.value.loop
        _state.value = _state.value.copy(loop = loop)
        exoPlayer.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Pause when the current video finishes instead of playing the next one. */
    fun setSleepAtEnd() {
        sleepJob?.cancel()
        _state.value = _state.value.copy(sleepTimerEndsAt = 0, sleepAtEnd = true)
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _state.value = _state.value.copy(sleepAtEnd = false)
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepTimerEndsAt = 0)
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepTimerEndsAt = endsAt)
        sleepJob = scope.launch(Dispatchers.Main) {
            delay(minutes * 60_000L)
            exoPlayer.pause()
            _state.value = _state.value.copy(sleepTimerEndsAt = 0)
            _events.tryEmit("Sleep timer: playback paused")
        }
    }

    fun seekTo(ms: Long) = exoPlayer.seekTo(ms)

    /** Stops decoding video while the app is in the background → much lower battery use. */
    fun setVideoTrackEnabled(enabled: Boolean) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    fun togglePlay() {
        val s = _state.value
        if (s.restored && s.current != null) {
            play(s.current, s.queue); return
        }
        if (!exoPlayer.isPlaying && refreshIfStale()) return
        if (exoPlayer.isPlaying) exoPlayer.pause() else {
            if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
            exoPlayer.play()
        }
    }

    fun enqueue(item: VideoItem, playNextUp: Boolean = false) {
        val s = _state.value
        if (s.current == null) {
            play(item); return
        }
        val q = s.queue.filter { it.url != item.url }.toMutableList()
        val currentIndex = q.indexOfFirst { it.url == s.current.url }.coerceAtLeast(0)
        if (playNextUp) q.add((currentIndex + 1).coerceAtMost(q.size), item) else q.add(item)
        _state.value = s.copy(queue = q, queueIndex = q.indexOfFirst { it.url == s.current.url })
        _events.tryEmit(if (playNextUp) "Playing next: ${item.title}" else "Added to queue")
    }

    fun removeFromQueue(item: VideoItem) {
        val s = _state.value
        if (item.url == s.current?.url) return
        val q = s.queue.filter { it.url != item.url }
        _state.value = s.copy(queue = q, queueIndex = q.indexOfFirst { it.url == s.current?.url })
    }

    fun playNext() {
        val s = _state.value
        val next = s.queue.getOrNull(s.queueIndex + 1)
            ?: if (settings.autoplayNext && !s.localFile) s.details?.relatedVideos?.firstOrNull { r -> s.queue.none { it.url == r.url } } else null
        if (next != null) {
            val queue = if (s.queue.any { it.url == next.url }) s.queue else s.queue + next
            play(next, queue)
        }
    }

    fun playPrevious() {
        val s = _state.value
        if (exoPlayer.currentPosition > 5_000 || s.queueIndex <= 0) {
            exoPlayer.seekTo(0)
            return
        }
        s.queue.getOrNull(s.queueIndex - 1)?.let { play(it, s.queue) }
    }

    fun stop() {
        persistPosition()
        loadJob?.cancel()
        tickerJob?.cancel()
        sleepJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlayerUiState(audioOnly = settings.audioOnlyDefault, speed = settings.defaultSpeed)
        scope.launch(Dispatchers.IO) { sessionStore.save(null) }
    }

    // ------------------------------------------------------------------ internals

    private fun prepare(details: StreamDetails, positionMs: Long, autoPlay: Boolean, forced: VideoOption? = null) {
        val s = _state.value
        val item = s.current ?: details.item
        val source: MediaSource
        var chosenVideo: VideoOption? = null
        var chosenAudio: AudioOption? = null
        val meta = metadata(item)

        when {
            item.isLive && (details.hlsUrl != null || details.dashUrl != null) -> {
                source = if (details.hlsUrl != null) {
                    HlsMediaSource.Factory(httpFactory).createMediaSource(
                        MediaItem.Builder().setUri(details.hlsUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                            .setMediaMetadata(meta).build()
                    )
                } else {
                    DashMediaSource.Factory(httpFactory).createMediaSource(
                        MediaItem.Builder().setUri(details.dashUrl).setMediaMetadata(meta).build()
                    )
                }
            }

            s.audioOnly && details.audioStreams.isNotEmpty() -> {
                chosenAudio = pickAudio(details.audioFor(s.audioTrackId))
                source = progressive(chosenAudio.url, meta)
            }

            else -> {
                val video = forced ?: pickVideo(details, settings.defaultQuality)
                    ?: throw IllegalStateException("No playable stream")
                chosenVideo = video
                source = if (video.videoOnly && details.audioStreams.isNotEmpty()) {
                    chosenAudio = pickAudio(details.audioFor(s.audioTrackId), preferMp4 = video.suffix == "mp4")
                    MergingMediaSource(true, progressive(video.url, meta), progressive(chosenAudio.url, meta))
                } else progressive(video.url, meta)
            }
        }

        _state.value = _state.value.copy(quality = chosenVideo ?: s.quality, audio = chosenAudio)
        val sub = s.subtitle
        val withSubs = if (sub != null && !item.isLive) MergingMediaSource(true, source, subtitleSource(sub)) else source
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, sub == null)
            .setPreferredTextLanguage(sub?.languageCode)
            .setSelectUndeterminedTextLanguage(true)
            .build()
        exoPlayer.setMediaSource(withSubs, positionMs)
        exoPlayer.playbackParameters = PlaybackParameters(_state.value.speed)
        exoPlayer.repeatMode = if (_state.value.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exoPlayer.prepare()
        exoPlayer.playWhenReady = autoPlay
    }

    /** Side-loaded WebVTT captions, optionally auto-translated by YouTube (tlang). */
    private fun subtitleSource(sub: SubtitleOption): MediaSource {
        val target = java.util.Locale.getDefault().language
        val translate = settings.captionTranslate && target.isNotBlank() &&
            sub.languageCode.substringBefore('-') != target
        val url = if (translate) sub.url + "&tlang=" + target else sub.url
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(if (translate) target else sub.languageCode)
            .setLabel(sub.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(WebvttParser(), format)) }
        return ProgressiveMediaSource.Factory(httpFactory, extractors)
            .createMediaSource(MediaItem.fromUri(url))
    }

    private fun progressive(url: String, meta: MediaMetadata): MediaSource =
        ProgressiveMediaSource.Factory(httpFactory).createMediaSource(
            MediaItem.Builder().setUri(url).setMediaMetadata(meta).build()
        )

    private fun metadata(item: VideoItem): MediaMetadata = MediaMetadata.Builder()
        .setTitle(item.title)
        .setArtist(item.uploader)
        .setDisplayTitle(item.title)
        .setArtworkUri(item.thumbnail?.let(Uri::parse))
        .build()

    /** Chooses the best stream at or below the preferred height. Prefers H.264 for compatibility. */
    private fun pickVideo(details: StreamDetails, preferredHeight: Int): VideoOption? {
        val all = details.videoStreams
        if (all.isEmpty()) return null
        val candidates = all.filter { it.height <= preferredHeight }.ifEmpty { listOf(all.minBy { it.height }) }
        val bestHeight = candidates.maxOf { it.height }
        val atHeight = candidates.filter { it.height == bestHeight }
        return atHeight.firstOrNull { it.codec?.startsWith("avc") == true && it.videoOnly }
            ?: atHeight.firstOrNull { it.videoOnly && it.codec?.startsWith("vp9") == true }
            ?: atHeight.firstOrNull { !it.videoOnly }
            ?: atHeight.first()
    }

    private fun pickAudio(list: List<AudioOption>, preferMp4: Boolean = true): AudioOption {
        val preferred = if (preferMp4) list.filter { it.suffix == "m4a" } else list.filter { it.suffix == "webm" }
        return (preferred.ifEmpty { list }).maxBy { it.bitrateKbps }
    }

    private fun onEnded() {
        if (_state.value.sleepAtEnd) {
            _state.value = _state.value.copy(sleepAtEnd = false)
            persistPosition(reset = true)
            _events.tryEmit("Sleep timer: stopped at the end of the video")
            return
        }
        persistPosition(reset = true)
        if (!_state.value.loop) playNext()
    }

    private fun handleError(error: PlaybackException) {
        val s = _state.value
        val details = s.details
        // Stream URLs expire — refresh them once transparently.
        if (details != null && !s.localFile && error.errorCode in listOf(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            ) && !refreshing && refreshAttempts < 3
        ) {
            refreshing = true
            refreshAttempts++
            val position = exoPlayer.currentPosition
            scope.launch(Dispatchers.Main) {
                if (refreshAttempts > 1) delay(1_500L * refreshAttempts) // back off on repeated 403s
                runCatching {
                    val fresh = Graph.repository.stream(details.item.url, forceRefresh = true)
                    detailsFetchedAt = System.currentTimeMillis()
                    _state.value = _state.value.copy(details = fresh, error = null)
                    // decoder failure → fall back to a muxed/H.264 stream
                    val fallback = if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                        fresh.videoStreams.firstOrNull { it.codec?.startsWith("avc") == true && it.height <= (s.quality?.height ?: 720) }
                    } else s.quality?.let { q -> fresh.videoStreams.firstOrNull { it.itag == q.itag } }
                    prepare(fresh, position, true, fallback)
                }.onFailure { _state.value = _state.value.copy(error = it.message) }
                refreshing = false
            }
            return
        }
        _state.value = s.copy(error = error.localizedMessage ?: "Playback error")
    }

    private fun routeBrowserItems(items: List<MediaItem>, startIndex: Int): Boolean {
        if (items.isEmpty() || items.none { AutoLibrary.isVidoraId(it.mediaId) }) return false
        val videos = items.mapNotNull { AutoLibrary.videoFor(it) }
        val start = videos.getOrNull(startIndex) ?: videos.firstOrNull() ?: return false
        val queue = if (videos.size > 1) videos else AutoLibrary.queueFor(start)
        scope.launch(Dispatchers.Main) { play(start, queue) }
        return true
    }

    private var refreshing = false
    private var refreshAttempts = 0
    private var detailsFetchedAt = 0L

    /**
     * googlevideo URLs expire (~6 h). When resuming after a long pause, re-extract first so
     * playback continues instead of failing with HTTP 403. Returns true if a refresh was started.
     */
    private fun refreshIfStale(): Boolean {
        val s = _state.value
        val details = s.details ?: return false
        if (s.localFile || details.item.isLive || refreshing) return false
        if (System.currentTimeMillis() - detailsFetchedAt < STREAM_MAX_AGE_MS) return false
        refreshing = true
        val position = exoPlayer.currentPosition
        _state.value = s.copy(loading = true)
        scope.launch(Dispatchers.Main) {
            runCatching { Graph.repository.stream(details.item.url, forceRefresh = true) }
                .onSuccess { fresh ->
                    detailsFetchedAt = System.currentTimeMillis()
                    _state.value = _state.value.copy(details = fresh, loading = false, error = null)
                    prepare(fresh, position, true, _state.value.quality?.let { q -> fresh.videoStreams.firstOrNull { it.itag == q.itag } })
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
            refreshing = false
        }
        return true
    }

    private fun loadSponsorSegments(item: VideoItem) {
        if (!settings.sponsorBlock || item.isLive) return
        val id = Graph.repository.videoId(item.url) ?: return
        scope.launch {
            val segments = Graph.sponsorBlock.segments(id, settings.sponsorCategories)
            if (_state.value.current?.url == item.url) _state.value = _state.value.copy(segments = segments)
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch(Dispatchers.Main) {
            var counter = 0
            while (isActive) {
                delay(500)
                val s = _state.value
                if (exoPlayer.isPlaying && settings.sponsorBlock && s.segments.isNotEmpty()) {
                    val pos = exoPlayer.currentPosition
                    s.segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 500 && it.UUID !in skippedSegments }
                        ?.let { seg ->
                            skippedSegments += seg.UUID
                            exoPlayer.seekTo(seg.endMs)
                            _events.tryEmit("Skipped ${SponsorCategories[seg.category] ?: seg.category}")
                        }
                }
                if (++counter % 20 == 0 && exoPlayer.isPlaying) persistPosition()
            }
        }
    }

    private fun persistPosition(reset: Boolean = false) {
        val s = _state.value
        val current = s.current ?: return
        if (current.isLive || s.localFile || !settings.saveHistory || settings.incognito) return
        val position = if (reset) 0 else exoPlayer.currentPosition
        if (reset || position > 0) Graph.library.savePosition(current.url, position)
    }

    suspend fun awaitSettings(): AppSettings = Graph.settings.flow.first()

    fun toast(message: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        /** Re-extract before resuming if the stream URLs are older than this. */
        const val STREAM_MAX_AGE_MS = 4 * 60 * 60 * 1000L
    }
}
