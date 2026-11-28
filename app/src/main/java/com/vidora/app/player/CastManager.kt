package com.vidora.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.vidora.app.Graph
import com.vidora.app.data.StreamDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chromecast / Google TV casting through Media3's RemoteCastPlayer and the Default Media Receiver.
 *
 * The receiver fetches the stream itself, so we send it a single muxed stream (up to 720p/360p,
 * depending on what YouTube offers) or the HLS manifest for live videos. Devices without Google
 * Play services simply never show cast devices.
 */
@OptIn(UnstableApi::class)
object CastManager {
    data class Device(val id: String, val name: String, val description: String?, val selected: Boolean)
    data class Remote(val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val buffering: Boolean = false)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices
    private val _castingTo = MutableStateFlow<String?>(null)
    /** Name of the TV we're casting to, or null. */
    val castingTo: StateFlow<String?> = _castingTo
    private val _remote = MutableStateFlow(Remote())
    val remote: StateFlow<Remote> = _remote

    private var castContext: CastContext? = null
    private var player: RemoteCastPlayer? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var ticker: Job? = null
    private var loadedUrl: String? = null

    val available: Boolean get() = castContext != null
    val isCasting: Boolean get() = _castingTo.value != null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
    }

    /** Call on the main thread (Application.onCreate). Never throws. */
    fun init(context: Context) {
        if (castContext != null) return
        val app = context.applicationContext
        @Suppress("DEPRECATION")
        val cc = runCatching { CastContext.getSharedInstance(app) }.getOrNull() ?: return
        castContext = cc
        router = runCatching { MediaRouter.getInstance(app) }.getOrNull()
        selector = runCatching { cc.mergedSelector }.getOrNull()
        val p = runCatching { RemoteCastPlayer.Builder(app).build() }.getOrNull() ?: return
        player = p
        p.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = startCasting()
            override fun onCastSessionUnavailable() = stopCasting()
        })
        // A new video opened while casting → send it to the TV.
        Graph.appScope.launch(Dispatchers.Main) {
            Graph.player.state.map { it.details }.distinctUntilChanged().collect { d ->
                if (d != null && isCasting && d.item.url != loadedUrl) load(d, 0)
            }
        }
    }

    // ------------------------------------------------------------------ device picker

    fun startDiscovery() {
        val r = router ?: return
        val sel = selector ?: return
        r.addCallback(sel, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refreshRoutes()
    }

    fun stopDiscovery() {
        runCatching { router?.removeCallback(routerCallback) }
    }

    private fun refreshRoutes() {
        val r = router ?: return
        val sel = selector ?: return
        _devices.value = r.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) }
            .map { Device(it.id, it.name, it.description, it.isSelected) }
    }

    fun select(device: Device) {
        val r = router ?: return
        r.routes.firstOrNull { it.id == device.id }?.select()
    }

    fun disconnect() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
        runCatching { router?.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
    }

    // ------------------------------------------------------------------ remote controls

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun seekTo(ms: Long) { player?.seekTo(ms) }

    // ------------------------------------------------------------------ session

    private fun startCasting() {
        val name = runCatching { castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName }.getOrNull() ?: "TV"
        _castingTo.value = name
        val local = Graph.player.exoPlayer
        val details = Graph.player.state.value.details
        val position = local.currentPosition
        local.pause()
        if (details != null) load(details, position)
        ticker?.cancel()
        ticker = Graph.appScope.launch(Dispatchers.Main) {
            while (isActive) {
                player?.let { p ->
                    _remote.value = Remote(
                        isPlaying = p.isPlaying,
                        positionMs = p.currentPosition,
                        durationMs = p.duration.coerceAtLeast(0),
                        buffering = p.playbackState == androidx.media3.common.Player.STATE_BUFFERING,
                    )
                }
                delay(1000)
            }
        }
        Graph.player.toast("Casting to $name")
    }

    private fun stopCasting() {
        val position = player?.currentPosition ?: 0
        val wasCasting = isCasting
        _castingTo.value = null
        ticker?.cancel()
        loadedUrl = null
        if (wasCasting && Graph.player.state.value.details != null) {
            // Continue on the phone where the TV stopped.
            val local = Graph.player.exoPlayer
            if (position > 0) local.seekTo(position)
            local.play()
        }
    }

    private fun load(details: StreamDetails, positionMs: Long) {
        val p = player ?: return
        val item = details.item
        val media = castable(details) ?: run { Graph.player.toast("This video can't be cast"); return }
        val meta = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.uploader)
            .setArtworkUri(item.thumbnail?.let(Uri::parse))
            .build()
        val mi = MediaItem.Builder()
            .setUri(media.first)
            .setMimeType(media.second)
            .setMediaId(item.url)
            .setMediaMetadata(meta)
            .build()
        loadedUrl = item.url
        p.setMediaItem(mi, positionMs)
        p.prepare()
        p.play()
        Graph.player.exoPlayer.pause()
    }

    /** URL + MIME type the Default Media Receiver can play on its own. */
    private fun castable(d: StreamDetails): Pair<String, String>? {
        if (d.item.isLive || d.videoStreams.none { !it.videoOnly }) {
            d.hlsUrl?.let { return it to MimeTypes.APPLICATION_M3U8 }
        }
        d.videoStreams.filter { !it.videoOnly && it.suffix == "mp4" }.maxByOrNull { it.height }
            ?.let { return it.url to MimeTypes.VIDEO_MP4 }
        d.videoStreams.filter { !it.videoOnly }.maxByOrNull { it.height }
            ?.let { return it.url to (it.mime.ifBlank { MimeTypes.VIDEO_MP4 }) }
        d.hlsUrl?.let { return it to MimeTypes.APPLICATION_M3U8 }
        d.audioStreams.firstOrNull { it.suffix == "m4a" }?.let { return it.url to MimeTypes.AUDIO_MP4 }
        return null
    }
}
