package com.vidora.app.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import com.vidora.app.player.PlaybackService

/**
 * Sends a transport command through a MediaController so the PlaybackService is bound and
 * promoted to a foreground service (otherwise Android may kill background playback).
 */
object RemoteControl {
    const val TOGGLE = "toggle"
    const val NEXT = "next"
    const val PREVIOUS = "previous"

    fun send(context: Context, action: String) {
        val app = context.applicationContext
        if (Graph.player.state.value.current == null) {
            openApp(app); return
        }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            when (action) {
                TOGGLE -> if (controller.isPlaying) controller.pause() else {
                    if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0)
                    controller.play()
                }
                NEXT -> controller.seekToNext()
                PREVIOUS -> controller.seekToPrevious()
            }
            // Give the session a moment to start the foreground notification, then let go.
            android.os.Handler(app.mainLooper).postDelayed({ controller.release() }, 3_000)
        }, ContextCompat.getMainExecutor(app))
    }

    fun openApp(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
