package com.vidora.app.player

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaStyleNotificationHelper
import com.vidora.app.R
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import kotlinx.coroutines.launch

/**
 * Hosts the MediaSession → system media notification, lock-screen controls, Bluetooth
 * buttons, background playback, and a browsable library for Android Auto / Wear OS.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "playback"
        private const val STARTUP_GRACE_MS = 30_000L
    }

    private var session: MediaLibrarySession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startupCheck: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
        )
        // Bootstrap and Media3 replace the SAME notification; never leave two media rows.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL_ID)
            .setChannelName(R.string.channel_playback).build())
    }

    private fun launchIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun ensureSession(): MediaLibrarySession = session ?: MediaLibrarySession.Builder(
        this, Graph.player.sessionPlayer, LibraryCallback()
    ).setSessionActivity(launchIntent()).build().also { session = it; addSession(it) }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = ensureSession()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Media3 can enqueue startForegroundService while playing, then receive its own
        // start intent AFTER pause/stop/empty timeline. Waiting for a READY notification
        // leaves Android 11's foreground deadline outstanding and kills the process.
        // Fulfil EVERY delivered start synchronously, before player/session/lazy work.
        val notification = getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID }?.notification
            ?: NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.playback_starting))
            .setContentIntent(launchIntent())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(true)
            .apply { session?.let { setStyle(MediaStyleNotificationHelper.MediaStyle(it)) } }
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        com.vidora.app.data.Diagnostics.record("playback", "foreground_started")
        ensureSession()
        super.onStartCommand(intent, flags, startId)
        scheduleStartupCleanup(startId)
        // No automatic idle sticky resurrection; the queue is restored by the app, not autoplayed.
        return START_NOT_STICKY
    }

    private fun scheduleStartupCleanup(startId: Int) {
        startupCheck?.let(mainHandler::removeCallbacks)
        val startedAt = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val player = session?.player
                if (player != null && player.playWhenReady &&
                    (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)) {
                    // Media3 owns ongoing controls/artwork and paused notification lifetime.
                    if (!isPlaybackOngoing) triggerNotificationUpdate()
                    return
                }
                if (Graph.player.state.value.loading && SystemClock.elapsedRealtime() - startedAt < STARTUP_GRACE_MS) {
                    mainHandler.postDelayed(this, 1_000)
                    return
                }
                if (player != null && player.mediaItemCount > 0 && player.playbackState == Player.STATE_READY) {
                    if (!isPlaybackOngoing) triggerNotificationUpdate() // respect Media3's paused grace period
                } else {
                    triggerNotificationUpdate() // also reset Media3's notification ownership for an empty player
                    ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
        startupCheck = check
        mainHandler.postDelayed(check, 1_000)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        startupCheck?.let(mainHandler::removeCallbacks)
        startupCheck = null
        session?.release()
        session = null
        super.onDestroy()
    }

    private class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(AutoLibrary.rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            Graph.appScope.launch {
                val all = runCatching { AutoLibrary.children(parentId) }.getOrDefault(emptyList())
                val from = (page * pageSize).coerceAtMost(all.size)
                val to = (from + pageSize).coerceAtMost(all.size)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = AutoLibrary.item(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        /** Give browser-selected items a URI so the session accepts them; the player resolves them. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.mapNotNull { mi -> AutoLibrary.videoFor(mi)?.let(AutoLibrary::resolved) ?: mi.takeIf { it.localConfiguration != null } }
                    .toMutableList()
            )
    }
}
