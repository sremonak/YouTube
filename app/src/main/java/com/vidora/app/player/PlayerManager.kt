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
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
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
    /** Adaptive (Auto) quality is active — ExoPlayer picks the bitrate from the measured bandwidth. */
    val autoQuality: Boolean = false,
    /** Height of the video currently being decoded (follows adaptive switches). */
    val playingHeight: Int = 0,
    /** Audio-only was switched on automatically because the network was too slow. */
    val slowNetworkAudio: Boolean = false,
    /** Estimated network speed (kbps) from ExoPlayer's bandwidth meter. */
    val bandwidthKbps: Long = 0,
    /** Start-time metric: tap → first frame (ms), and how much of it was stream extraction. */
    val startupMs: Long = 0,
    val extractMs: Long = 0,
    /** The streams were already resolved (touch- or next-video prefetch) when the video was opened. */
    val startPrefetched: Boolean = false,
    /** Codec of the video being decoded ("av01.0.08M.08", "vp09…", "avc1…"). */
    val playingCodec: String? = null,
)

/** "Auto · 480p", "1080p60", "Audio only (slow network)" … for chips and the settings sheet. */
val PlayerUiState.qualityLabel: String
    get() = when {
        audioOnly && slowNetworkAudio -> "Audio only · slow net"
        audioOnly -> "Audio only"
        quality != null && !autoQuality -> quality.label
        autoQuality -> if (playingHeight > 0) "Auto · ${playingHeight}p" else quality?.let { "Auto · ${it.height}p" } ?: "Auto"
        else -> quality?.label ?: "Auto"
    }

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

    private var historyReportJob: Job? = null
    private var historyReportedFor: String? = null
    private var historyAttempts = 0
    private var loadJob: Job? = null
    private var loudnessJob: Job? = null

    /**
     * "Stable volume" like YouTube: videos louder than YouTube's reference level are turned down
     * by their announced loudnessDb (quiet videos are left alone, as YouTube does).
     */
    private fun applyLoudness(details: StreamDetails) {
        loudnessJob?.cancel()
        exoPlayer.volume = 1f
        if (!settings.stableVolume || details.item.isLive) return
        loudnessJob = scope.launch(Dispatchers.Main) {
            val db = details.loudnessDb ?: Graph.repository.videoId(details.item.url)?.let { id ->
                runCatching { kotlinx.coroutines.withTimeout(8_000) { Graph.accountApi.loudness(id) } }.getOrNull()
            }
            if (db != null && db > 0) {
                exoPlayer.volume = Math.pow(10.0, -db / 20.0).toFloat().coerceIn(0.25f, 1f)
            }
        }
    }
    private var tickerJob: Job? = null
    private var sleepJob: Job? = null
    private val skippedSegments = mutableSetOf<String>()

    private val streamClient = Graph.http.newBuilder()
        .addInterceptor(StreamInterceptor())
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val networkFactory = OkHttpDataSource.Factory(streamClient)
    private val cachedFactory by lazy { MediaCache.factory(context, networkFactory) }
    /** Segment cache in front of the network (Settings → Video cache). Live streams bypass it. */
    private val httpFactory: androidx.media3.datasource.DataSource.Factory
        get() = if (settings.mediaCache && _state.value.current?.isLive != true) cachedFactory else networkFactory
    private val dataSourceFactory = DefaultDataSource.Factory(context, networkFactory)

    /** Shared bandwidth estimate — learned across videos, used for the first quality pick. */
    private val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)

    private val trackSelector = DefaultTrackSelector(
        context,
        AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 6_000,
            /* maxDurationForQualityDecreaseMs = */ 20_000,
            /* minDurationToRetainAfterDiscardMs = */ 20_000,
            /* bandwidthFraction = */ 0.7f,
        ),
    )

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
        .setBandwidthMeter(bandwidthMeter)
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Start after ~0.8 s of media (default 2.5 s) and resume after 2 s of rebuffering.
                .setBufferDurationsMs(15_000, 50_000, 800, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                // Keep 15 s behind the playhead so short rewinds / double-tap back are instant.
                .setBackBuffer(15_000, true)
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
        exoPlayer.addAnalyticsListener(androidx.media3.exoplayer.analytics.PlaybackStatsListener(false) { _, stats ->
            if (!settings.incognito) com.vidora.app.data.Diagnostics.record("playback", "rebuffer", stats.totalRebufferTimeMs, stats.totalRebufferCount.toLong())
        })
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (!settings.incognito) com.vidora.app.data.Diagnostics.record("playback", "dropped_frames", elapsedMs, droppedFrames.toLong())
            }
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (format.height > 0 && (format.height != _state.value.playingHeight || format.codecs != _state.value.playingCodec)) {
                    _state.value = _state.value.copy(playingHeight = format.height, playingCodec = format.codecs)
                }
            }
        })
        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                effects.attach(audioSessionId, settings)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                scheduleWatchHistory(isPlaying)
                if (!isPlaying) persistPosition()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // A seek must not be counted as watched time; start a fresh continuous interval.
                scheduleWatchHistory(exoPlayer.isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> { bufferingSince = 0; onEnded() }
                    Player.STATE_BUFFERING -> if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                    Player.STATE_READY -> {
                        bufferingSince = 0
                        if (!startedOnce) {
                            startedOnce = true
                            if (tapAt > 0) {
                                _state.value = _state.value.copy(startupMs = android.os.SystemClock.elapsedRealtime() - tapAt)
                                tapAt = 0
                            }
                            prefetchNext()
                        }
                    }
                    else -> bufferingSince = 0
                }
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

    private fun scheduleWatchHistory(isPlaying: Boolean) {
        historyReportJob?.cancel()
        val video = _state.value.current ?: return
        if (!isPlaying || historyReportedFor == video.url) return
        val startSeconds = exoPlayer.currentPosition.coerceAtLeast(0) / 1000.0
        // Media3 getters stay on Main before and after suspension; short videos must
        // not end before the old 15-second timer and silently disappear from sync.
        historyReportJob = scope.launch(Dispatchers.Main) {
            val wait = com.vidora.app.account.WatchHistoryPolicy.delayFor(video.duration)
            kotlinx.coroutines.delay(wait)
            if (_state.value.current?.url == video.url && exoPlayer.isPlaying && !settings.incognito) {
                val seconds = (exoPlayer.currentPosition / 1000.0 - startSeconds).coerceAtLeast(0.0)
                if (seconds > 0) {
                    if (settings.saveHistory) Graph.library.recordWatched(video, startSeconds, seconds)
                    historyAttempts++
                    val synced = com.vidora.app.account.AccountSync.syncWatched(video, seconds, startSeconds)
                    if (synced) historyReportedFor = video.url
                    else if (historyAttempts < 3 && Graph.account.isSignedIn && settings.syncWatchHistory) {
                        kotlinx.coroutines.delay(20_000)
                        if (_state.value.current?.url == video.url && exoPlayer.isPlaying) scheduleWatchHistory(true)
                    }
                }
            }
        }
    }

    private var rateLimitedUrl: String? = null
    private var rateLimitUntil = 0L
    private var userRetryPosition: Pair<String, Long>? = null
    private fun pausedByPublisher(url: String?): Boolean {
        if (url != rateLimitedUrl || System.currentTimeMillis() >= rateLimitUntil) return false
        _state.value = _state.value.copy(error = "Publisher rate limit. Retry in ${((rateLimitUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1)} seconds.")
        return true
    }
    fun play(item: VideoItem, queue: List<VideoItem>? = null) {
        if (pausedByPublisher(item.url)) return
        val newQueue = (queue ?: _state.value.queue.takeIf { q -> q.any { it.url == item.url } } ?: listOf(item))
            .distinctBy { it.url }
        val index = newQueue.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
        if (_state.value.current?.url == item.url && _state.value.details != null) {
            _state.value = _state.value.copy(queue = newQueue, queueIndex = index)
            exoPlayer.play()
            return
        }
        persistPosition()
        historyReportedFor = null
        historyAttempts = 0
        skippedSegments.clear()
        resetNetworkAdaptation()
        tapAt = android.os.SystemClock.elapsedRealtime()
        val prefetched = Graph.repository.isStreamCached(item.url)
        _state.value = _state.value.copy(
            startupMs = 0, extractMs = 0, startPrefetched = prefetched, playingCodec = null,
            current = item, details = null, loading = true, error = null,
            queue = newQueue, queueIndex = index, localFile = false, segments = emptyList(),
            quality = null, audio = null, subtitle = null, audioTrackId = null, restored = false,
            playingHeight = 0,
            // audio-only that was forced by a slow network: try video again on the next item
            audioOnly = if (_state.value.slowNetworkAudio) false else _state.value.audioOnly,
            slowNetworkAudio = false,
        )
        exoPlayer.stop()
        loadJob?.cancel()
        recoveryJob?.cancel()
        refreshing = false
        loadJob = scope.launch(Dispatchers.Main) {
            try {
                val details = Graph.repository.stream(item.url, forceRefresh = userRetryPosition?.first == item.url)
                val extractMs = android.os.SystemClock.elapsedRealtime() - tapAt
                _state.value = _state.value.copy(extractMs = extractMs)
                if (!settings.incognito) com.vidora.app.data.Diagnostics.record("playback", "extracted", extractMs)
                // Never lose what the feed already knew (title, channel, views…) when a fallback
                // player source returns blanks.
                val d = details.item
                val merged = d.copy(
                    title = d.title.ifBlank { item.title },
                    thumbnail = d.thumbnail?.takeIf { it.isNotBlank() } ?: item.thumbnail,
                    uploader = d.uploader?.takeIf { it.isNotBlank() } ?: item.uploader,
                    uploaderUrl = d.uploaderUrl?.takeIf { it.isNotBlank() } ?: item.uploaderUrl,
                    uploaderAvatar = d.uploaderAvatar?.takeIf { it.isNotBlank() } ?: item.uploaderAvatar,
                    views = if (d.views > 0) d.views else item.views,
                    uploadedText = d.uploadedText?.takeIf { it.isNotBlank() } ?: item.uploadedText,
                    uploadedEpoch = if (d.uploadedEpoch > 0) d.uploadedEpoch else item.uploadedEpoch,
                    duration = if (d.duration > 0) d.duration else item.duration,
                    verified = d.verified || item.verified,
                )
                detailsFetchedAt = System.currentTimeMillis()
                refreshAttempts = 0
                _state.value = _state.value.copy(
                    current = merged, details = details, loading = false,
                    subtitle = defaultSubtitle(details),
                    audioTrackId = defaultAudioTrack(details),
                )
                if (settings.saveHistory && !settings.incognito) Graph.library.addHistory(merged)
                val requestedResume = userRetryPosition?.takeIf { it.first == item.url }?.second
                userRetryPosition = null
                val resumeAt = requestedResume ?: if (settings.resumePlayback && !merged.isLive) {
                    Graph.library.positionOf(merged.url).takeIf { it > 5_000 && (merged.duration == 0L || it < merged.duration * 1000 - 10_000) } ?: 0
                } else 0
                prepare(details, resumeAt, autoPlay = true)
                applyLoudness(details)
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
        if (pausedByPublisher(current.url)) return
        userRetryPosition = current.url to exoPlayer.currentPosition
        _state.value = _state.value.copy(details = null)
        play(current, _state.value.queue)
    }

    fun setQuality(option: VideoOption) {
        if (pausedByPublisher(_state.value.current?.url)) return
        val details = _state.value.details ?: return
        manualQuality = true
        _state.value = _state.value.copy(quality = option, audioOnly = false, slowNetworkAudio = false)
        prepare(details, exoPlayer.currentPosition, exoPlayer.playWhenReady, forced = option)
    }

    /** Back to automatic quality (adaptive DASH when possible, otherwise bandwidth-based pick). */
    fun setAutoQuality() {
        if (pausedByPublisher(_state.value.current?.url)) return
        val details = _state.value.details ?: return
        manualQuality = false
        downshifts = 0
        _state.value = _state.value.copy(quality = null, audioOnly = false, slowNetworkAudio = false)
        prepare(details, exoPlayer.currentPosition, exoPlayer.playWhenReady, forced = null)
    }

    fun setAudioOnly(enabled: Boolean) {
        val details = _state.value.details
        _state.value = _state.value.copy(audioOnly = enabled, slowNetworkAudio = false)
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
        recoveryJob?.cancel()
        refreshing = false
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
        var adaptive = false
        val meta = metadata(item)

        when {
            (item.isLive && (details.hlsUrl != null || details.dashUrl != null)) ||
                (details.preferHls && details.hlsUrl != null && forced == null) -> {
                source = if (details.hlsUrl != null) {
                    HlsMediaSource.Factory(httpFactory).setLoadErrorHandlingPolicy(PlaybackLoadPolicy()).createMediaSource(
                        MediaItem.Builder().setUri(details.hlsUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                            .setMediaMetadata(meta).build()
                    )
                } else {
                    DashMediaSource.Factory(httpFactory).setLoadErrorHandlingPolicy(PlaybackLoadPolicy()).createMediaSource(
                        MediaItem.Builder().setUri(details.dashUrl).setMediaMetadata(meta).build()
                    )
                }
            }

            s.audioOnly && details.audioStreams.isNotEmpty() -> {
                chosenAudio = pickAudio(details.audioFor(s.audioTrackId), slow = s.slowNetworkAudio || isSlow())
                source = progressive(chosenAudio.url, meta)
            }

            else -> {
                val dash = if (forced == null) adaptiveSource(details, item, meta, s.audioTrackId) else null
                if (dash != null) {
                    adaptive = true
                    source = dash
                } else {
                    val video = forced ?: pickVideo(details, autoMaxHeight(details))
                        ?: throw IllegalStateException("No playable stream")
                    chosenVideo = video
                    source = if (video.videoOnly && details.audioStreams.isNotEmpty()) {
                        chosenAudio = pickAudio(details.audioFor(s.audioTrackId), preferMp4 = video.suffix == "mp4", slow = forced == null && isSlow())
                        MergingMediaSource(true, progressive(video.url, meta), progressive(chosenAudio.url, meta))
                    } else progressive(video.url, meta)
                }
            }
        }

        adaptiveActive = adaptive
        bufferingSince = if (autoPlay) System.currentTimeMillis() else 0
        _state.value = _state.value.copy(
            quality = if (adaptive) null else chosenVideo ?: s.quality,
            audio = chosenAudio,
            autoQuality = adaptive || (!s.audioOnly && !manualQuality),
            bandwidthKbps = bandwidthMeter.bitrateEstimate / 1000,
        )
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
        exoPlayer.playWhenReady = autoPlay && !CastManager.isCasting
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
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context, httpFactory)).setLoadErrorHandlingPolicy(PlaybackLoadPolicy()).createMediaSource(
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
        if (settings.efficientCodecs) {
            for (fam in CodecSupport.preference(true, bestHeight)) {
                if (fam == "avc") break
                atHeight.firstOrNull { it.videoOnly && CodecSupport.family(it.codec) == fam }?.let { return it }
            }
        }
        return atHeight.firstOrNull { it.codec?.startsWith("avc") == true && it.videoOnly }
            ?: atHeight.firstOrNull { it.videoOnly && it.codec?.startsWith("vp9") == true }
            ?: atHeight.firstOrNull { !it.videoOnly }
            ?: atHeight.first()
    }

    private fun pickAudio(list: List<AudioOption>, preferMp4: Boolean = true, slow: Boolean = false): AudioOption {
        val preferred = if (preferMp4) list.filter { it.suffix == "m4a" } else list.filter { it.suffix == "webm" }
        val pool = preferred.ifEmpty { list }
        // On a slow link the ~50 kbps stream starts several times faster than the 128–160 kbps one.
        return if (slow || DataSaver.active()) pool.minBy { it.bitrateKbps } else pool.maxBy { it.bitrateKbps }
    }

    // ------------------------------------------------------------------ network-aware quality

    private var adaptiveActive = false
    /** URL of a video whose adaptive manifest failed → progressive streams for the rest of it. */
    private var adaptiveFailedFor: String? = null
    private var manualQuality = false
    private var downshifts = 0
    private var bufferingSince = 0L
    private var lastDownshiftAt = 0L
    private var startedOnce = false
    /** elapsedRealtime of the tap that opened the current video (0 = not measuring). */
    private var tapAt = 0L
    private var prefetchJob: Job? = null

    private fun resetNetworkAdaptation() {
        manualQuality = false
        downshifts = 0
        bufferingSince = 0
        lastDownshiftAt = 0
        startedOnce = false
        adaptiveFailedFor = null
        prefetchJob?.cancel()
    }

    private fun isMetered(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.isActiveNetworkMetered
    }.getOrDefault(false)

    /** Below ~1.2 Mbps 720p cannot start quickly; prefer small streams. */
    private fun isSlow(): Boolean = bandwidthMeter.bitrateEstimate in 1 until 1_200_000

    /** Upper quality limit: user setting, tightened on mobile data. */
    private fun qualityCap(): Int {
        var cap = settings.defaultQuality
        if (settings.mobileQuality > 0 && isMetered()) cap = minOf(cap, settings.mobileQuality)
        if (DataSaver.active()) cap = minOf(cap, 360)
        return cap
    }

    /**
     * Height for a fixed (progressive) stream: the cap, lowered to what the measured bandwidth can
     * sustain (70 % of the estimate) when Smart quality is on, and lowered further after rebuffering.
     */
    private fun autoMaxHeight(details: StreamDetails): Int {
        var cap = qualityCap()
        if (settings.smartQuality && !manualQuality) {
            val budget = bandwidthMeter.bitrateEstimate * 0.7
            if (budget > 0) {
                val audioBps = if (isSlow()) 50_000 else 130_000
                val fitting = details.videoStreams
                    .filter { it.height <= cap && approxBitrate(it) + audioBps <= budget }
                    .maxOfOrNull { it.height }
                cap = fitting ?: details.videoStreams.minOfOrNull { it.height } ?: cap
            }
        }
        if (downshifts > 0) {
            val heights = details.videoStreams.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
            val start = heights.indexOfFirst { it <= cap }.coerceAtLeast(0)
            cap = heights.getOrNull(start + downshifts) ?: heights.lastOrNull() ?: cap
        }
        return cap
    }

    private fun approxBitrate(v: VideoOption): Int = v.bitrate.takeIf { it > 0 } ?: when {
        v.height <= 144 -> 110_000
        v.height <= 240 -> 250_000
        v.height <= 360 -> 500_000
        v.height <= 480 -> 900_000
        v.height <= 720 -> 1_800_000
        v.height <= 1080 -> 3_500_000
        else -> 9_000_000
    }

    /** Builds an adaptive DASH source from the extracted streams, or null to use progressive streams. */
    private fun adaptiveSource(details: StreamDetails, item: VideoItem, meta: MediaMetadata, trackId: String?): MediaSource? {
        if (!settings.smartQuality || manualQuality || item.isLive || adaptiveFailedFor == details.item.url) return null
        val cap = qualityCap()
        val plan = AdaptiveManifest.plan(
            details.videoStreams, details.audioFor(trackId), details.item.duration, cap,
            families = CodecSupport.preference(settings.efficientCodecs, cap),
            lowAudio = DataSaver.active(),
        ) ?: return null
        return runCatching {
            val manifest = DashManifestParser().parse(
                Uri.parse("https://www.youtube.com/vidora/${item.url.hashCode()}.mpd"),
                plan.xml.byteInputStream(),
            )
            DashMediaSource.Factory(httpFactory).setLoadErrorHandlingPolicy(PlaybackLoadPolicy()).createMediaSource(
                manifest,
                MediaItem.Builder().setMediaId(item.url).setMediaMetadata(meta).build(),
            )
        }.onFailure { android.util.Log.w("PlayerManager", "adaptive manifest failed", it) }.getOrNull()
    }

    /**
     * Called from the ticker. Long stalls → step the quality down (progressive) or, when even
     * the lowest video stalls, keep the user listening with audio only. Brings video back once
     * the measured bandwidth recovers.
     */
    private fun checkNetworkHealth() {
        val s = _state.value
        val details = s.details ?: return
        if (s.localFile || details.item.isLive || refreshing) return
        val now = System.currentTimeMillis()
        if (s.bandwidthKbps != bandwidthMeter.bitrateEstimate / 1000) {
            _state.value = s.copy(bandwidthKbps = bandwidthMeter.bitrateEstimate / 1000)
        }
        // recovered → bring the video back
        if (s.slowNetworkAudio && exoPlayer.isPlaying && bandwidthMeter.bitrateEstimate > 900_000 && now - lastDownshiftAt > 30_000) {
            _events.tryEmit("Network recovered — video is back")
            downshifts = 0
            lastDownshiftAt = now
            _state.value = _state.value.copy(audioOnly = false, slowNetworkAudio = false)
            prepare(details, exoPlayer.currentPosition, true, forced = null)
            return
        }
        val since = bufferingSince
        if (since == 0L || !exoPlayer.playWhenReady || manualQuality || s.audioOnly) return
        val stalled = now - since
        val limit = if (!startedOnce) 7_000 else 4_500
        if (stalled < limit || now - lastDownshiftAt < 8_000) return
        val position = exoPlayer.currentPosition
        val lowestHeight = details.videoStreams.minOfOrNull { it.height } ?: 0
        val atLowest = if (adaptiveActive) s.playingHeight in 1..maxOf(lowestHeight, 160)
        else (s.quality?.height ?: 0) <= lowestHeight
        if (settings.smartQuality && !adaptiveActive && !atLowest && downshifts < 6) {
            downshifts++
            val next = pickVideo(details, autoMaxHeight(details))
            if (next != null && next.height < (s.quality?.height ?: Int.MAX_VALUE)) {
                lastDownshiftAt = now
                _events.tryEmit("Slow network — switched to ${next.height}p")
                prepare(details, position, true, forced = null)
                return
            }
        }
        val giveUpOnVideo = atLowest || (adaptiveActive && stalled > 9_000)
        if (giveUpOnVideo && settings.autoAudioOnSlow && details.audioStreams.isNotEmpty()) {
            lastDownshiftAt = now
            _events.tryEmit("Network too slow — playing audio only for now")
            _state.value = _state.value.copy(audioOnly = true, slowNetworkAudio = true)
            prepare(details, position, true, forced = null)
        }
    }

    /** Resolves the next video's streams in the background so pressing Next starts instantly. */
    private fun prefetchNext() {
        if (!settings.prefetchNext || DataSaver.active()) return
        val s = _state.value
        if (s.localFile) return
        val next = s.queue.getOrNull(s.queueIndex + 1)
            ?: if (settings.autoplayNext) s.details?.relatedVideos?.firstOrNull { r -> s.queue.none { it.url == r.url } } else null
        next ?: return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            delay(4_000) // let the current video fill its first buffer first
            runCatching { Graph.repository.stream(next.url) }
        }
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

    private var recoveryJob: Job? = null
    private fun handleError(error: PlaybackException) {
        val s = _state.value
        val details = s.details
        val http = generateSequence(error as Throwable?) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val decoder = error.errorCode in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED)
        val action = PlaybackRecovery.decide(http?.responseCode, decoder, refreshAttempts)
        if (!settings.incognito) com.vidora.app.data.Diagnostics.record("playback", action.name, count = refreshAttempts.toLong())
        if (action == PlaybackRecovery.Action.PAUSE) {
            val header = http?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
            val seconds = (com.vidora.app.data.ProviderHealth.retryAfter(header) / 1000).coerceAtLeast(30)
            rateLimitedUrl = s.current?.url
            rateLimitUntil = System.currentTimeMillis() + seconds * 1000
            _state.value = s.copy(error = "Publisher rate limit. Wait $seconds seconds before retrying; other public videos may still work.")
            return
        }
        if (action == PlaybackRecovery.Action.STOP || details == null || s.localFile) {
            _state.value = s.copy(error = when (http?.responseCode) {
                401, 403, 451 -> "Publisher denied access. This video may be restricted; try another public video."
                404, 410 -> "This video or rendition was removed. Try another public video."
                else -> "Playback could not recover. Try another quality or public video."
            })
            return
        }
        if (refreshing) return
        if (adaptiveActive && !decoder && http == null) {
            adaptiveFailedFor = details.item.url; adaptiveActive = false
        }
        val position = exoPlayer.currentPosition
        refreshing = true
        val attempt = refreshAttempts++
        recoveryJob?.cancel()
        recoveryJob = scope.launch(Dispatchers.Main) {
            try {
                if (action == PlaybackRecovery.Action.RETRY) delay(PlaybackRecovery.delayMs(attempt))
                val fresh = if (action == PlaybackRecovery.Action.LOWER_QUALITY) details
                    else Graph.repository.stream(details.item.url, forceRefresh = true)
                if (_state.value.current?.url != s.current?.url) return@launch
                val fallback = if (action == PlaybackRecovery.Action.LOWER_QUALITY) {
                    fresh.videoStreams.filter { !it.videoOnly || fresh.audioStreams.isNotEmpty() }
                        .filter { it.itag != s.quality?.itag }
                        .sortedWith(compareBy<VideoOption> { if (it.suffix == "mp4" && it.codec?.startsWith("avc") != false) 0 else 1 }.thenBy { it.height })
                        .firstOrNull() ?: throw IllegalStateException("No compatible alternative rendition. Try another public video.")
                } else s.quality?.let { q -> fresh.videoStreams.firstOrNull { it.itag == q.itag } }
                detailsFetchedAt = System.currentTimeMillis()
                _state.value = _state.value.copy(details = fresh, error = null)
                prepare(fresh, position, true, fallback)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(error = e.message ?: "Playback recovery failed") }
            finally { if (recoveryJob == coroutineContext[Job]) refreshing = false }
        }
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
                if (counter % 2 == 0) checkNetworkHealth()
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
