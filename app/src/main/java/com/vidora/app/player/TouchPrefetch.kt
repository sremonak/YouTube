package com.vidora.app.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.vidora.app.Graph
import com.vidora.app.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Touch-prefetch: the ~100–300 ms between a finger landing on a video card and the tap being
 * recognised is used to start resolving that video's streams. The extraction is shared with the
 * real tap (ContentRepository de-duplicates in-flight requests), so playback starts sooner.
 */
object TouchPrefetch {
    private var last: String? = null
    private var job: Job? = null
    @Volatile var hits = 0
        private set

    fun touch(item: VideoItem) {
        val s = Graph.settingsState.value
        if (!s.prefetchNext || DataSaver.active() || item.isLive) return
        if (item.url == last && job?.isActive == true) return
        if (Graph.repository.isStreamCached(item.url)) return
        last = item.url
        job = Graph.appScope.launch(Dispatchers.IO) {
            runCatching { Graph.repository.stream(item.url) }
        }
        hits++
    }
}

/** Starts [TouchPrefetch] on finger-down without consuming the event (clicks still work). */
fun Modifier.touchPrefetch(item: VideoItem): Modifier = pointerInput(item.url) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        TouchPrefetch.touch(item)
    }
}

/** Data Saver is applied on metered (mobile) networks only, like YouTube's. */
object DataSaver {
    fun active(): Boolean {
        if (!Graph.settingsState.value.dataSaver) return false
        return runCatching {
            Graph.app.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
        }.getOrDefault(true)
    }
}
