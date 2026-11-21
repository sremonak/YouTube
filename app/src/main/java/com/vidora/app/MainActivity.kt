package com.vidora.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.vidora.app.data.AppSettings
import com.vidora.app.player.PlaybackService
import com.vidora.app.ui.AppRoot
import com.vidora.app.ui.theme.VidoraTheme
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var settings = AppSettings()

    /** Links shared to / opened with the app. */
    val incomingLinks = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    val openPlayerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    var isInPip by mutableStateOf(false)
        private set

    /** Set by the player screen so PiP only triggers while a video is on screen. */
    var playerVisible = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        lifecycleScope.launch { Graph.settings.flow.collect { settings = it; updatePipParams() } }
        lifecycleScope.launch { Graph.player.state.collect { updatePipParams() } }

        setContent {
            val s by Graph.settings.flow.collectAsState(initial = null)
            val current = s
            if (current != null) {
                VidoraTheme(current) {
                    AppRoot(activity = this, settings = current)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller starts the MediaSessionService → background playback & notification.
        if (controllerFuture == null) {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, token).buildAsync()
        }
        Graph.player.setVideoTrackEnabled(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isInPip) {
            if (settings.backgroundPlay) Graph.player.setVideoTrackEnabled(false)
            else Graph.player.exoPlayer.pause()
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { incomingLinks.tryEmit(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value?.let { incomingLinks.tryEmit(it) }
            }
            ACTION_OPEN_PLAYER -> openPlayerRequests.tryEmit(Unit)
        }
    }

    // ---------------------------------------------------------------- Picture in picture

    private fun canPip(): Boolean {
        val s = Graph.player.state.value
        return settings.autoPip && playerVisible && s.current != null && !s.audioOnly &&
            Graph.player.exoPlayer.isPlaying &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(canPip())
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { setPictureInPictureParams(pipParams()) }
        }
    }

    fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && canPip()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.vidora.app.OPEN_PLAYER"
    }
}
