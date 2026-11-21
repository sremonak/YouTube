package com.vidora.app.remote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.RemoteViews
import coil.imageLoader
import coil.request.ImageRequest
import com.vidora.app.Graph
import com.vidora.app.MainActivity
import com.vidora.app.R
import com.vidora.app.player.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home-screen "now playing" widget with artwork and prev / play-pause / next. */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        observe(context)
        render(context, Graph.player.state.value, art = lastArt)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> RemoteControl.send(context, RemoteControl.TOGGLE)
            ACTION_NEXT -> RemoteControl.send(context, RemoteControl.NEXT)
            ACTION_PREVIOUS -> RemoteControl.send(context, RemoteControl.PREVIOUS)
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.vidora.app.widget.TOGGLE"
        private const val ACTION_NEXT = "com.vidora.app.widget.NEXT"
        private const val ACTION_PREVIOUS = "com.vidora.app.widget.PREVIOUS"
        @Volatile private var lastArt: Bitmap? = null
        @Volatile private var lastArtUrl: String? = null

        @Volatile private var observing = false

        fun hasWidgets(context: Context) = ids(context).isNotEmpty()

        /** Keeps widgets in sync with the player. Must be called on the main thread (creates the player). */
        fun observe(context: Context) {
            if (observing) return
            observing = true
            val app = context.applicationContext
            Graph.player // initialise on the main thread
            Graph.appScope.launch {
                Graph.player.state
                    .map { Triple(it.current?.url, it.isPlaying, it.current?.thumbnail) }
                    .distinctUntilChanged()
                    .collect {
                        if (ids(app).isEmpty()) return@collect
                        val s = Graph.player.state.value
                        val url = s.current?.thumbnail
                        if (url != lastArtUrl) {
                            lastArtUrl = url
                            lastArt = if (url == null) null else loadArt(app, url)
                        }
                        render(app, s, lastArt)
                    }
            }
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))

        private suspend fun loadArt(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(url).size(320, 180).allowHardware(false).build()
                (context.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
        }

        private fun render(context: Context, s: PlayerUiState, art: Bitmap?) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
            val item = s.current
            views.setTextViewText(R.id.widget_title, item?.title ?: context.getString(R.string.widget_idle_title))
            views.setTextViewText(R.id.widget_subtitle, item?.uploader ?: context.getString(R.string.widget_idle_subtitle))
            views.setImageViewResource(R.id.widget_play, if (s.isPlaying) R.drawable.widget_pause else R.drawable.widget_play)
            if (art != null && item != null) views.setImageViewBitmap(R.id.widget_art, art)
            else views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher_classic)

            views.setOnClickPendingIntent(R.id.widget_play, broadcast(context, ACTION_TOGGLE, 1))
            views.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 2))
            views.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREVIOUS, 3))
            val open = PendingIntent.getActivity(
                context, 4,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code,
                Intent(context, NowPlayingWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
