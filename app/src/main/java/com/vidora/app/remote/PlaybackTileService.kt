package com.vidora.app.remote

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vidora.app.Graph
import com.vidora.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Quick Settings tile: shows what's playing and toggles play/pause. */
class PlaybackTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch {
            Graph.player.state.map { it.current?.title to it.isPlaying }.distinctUntilChanged().collect { (title, playing) ->
                val tile = qsTile ?: return@collect
                tile.icon = Icon.createWithResource(this@PlaybackTileService, if (playing) R.drawable.widget_pause else R.drawable.widget_play)
                tile.label = getString(R.string.tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = title ?: getString(R.string.tile_idle)
                tile.state = when {
                    title == null -> Tile.STATE_INACTIVE
                    playing -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (Graph.player.state.value.current == null) {
            val intent = android.content.Intent(this, com.vidora.app.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            return
        }
        RemoteControl.send(this, RemoteControl.TOGGLE)
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
