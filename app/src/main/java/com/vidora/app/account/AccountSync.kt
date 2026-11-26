package com.vidora.app.account

import com.vidora.app.Graph
import com.vidora.app.notify.RssFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo

/**
 * Mirrors local actions (like / subscribe) to the signed-in YouTube account when the user enabled
 * "Sync likes & subscriptions". Failures are reported but never block the local action.
 */
object AccountSync {
    private val active get() = Graph.account.isSignedIn && Graph.settingsState.value.syncAccountActions

    fun liked(videoUrl: String, liked: Boolean) {
        if (!active) return
        val id = Graph.repository.videoId(videoUrl) ?: return
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching { if (liked) Graph.accountApi.like(id) else Graph.accountApi.removeLike(id) }
                .onFailure { Graph.player.toast("YouTube like sync failed: ${it.message}") }
        }
    }

    fun disliked(videoUrl: String) {
        if (!Graph.account.isSignedIn) return
        val id = Graph.repository.videoId(videoUrl) ?: return
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching { Graph.accountApi.dislike(id) }
                .onSuccess { Graph.player.toast("Disliked on YouTube") }
                .onFailure { Graph.player.toast("Dislike failed: ${it.message}") }
        }
    }

    fun subscribed(channelUrl: String, subscribed: Boolean) {
        if (!active) return
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching {
                val id = RssFeed.channelIdFromUrl(channelUrl)
                    ?: ChannelInfo.getInfo(ServiceList.YouTube, channelUrl).id
                if (subscribed) Graph.accountApi.subscribe(id) else Graph.accountApi.unsubscribe(id)
            }.onFailure { Graph.player.toast("YouTube subscription sync failed: ${it.message}") }
        }
    }

    /** Result of the last watch-history report, shown in Settings → Account check. */
    @Volatile var lastWatchSync: String = "nothing reported yet"
        private set

    /** Adds the video to the YouTube account's watch history (fire and forget). */
    fun markWatched(video: com.vidora.app.data.VideoItem) {
        if (!Graph.account.isSignedIn) return
        val id = Graph.repository.videoId(video.url) ?: return
        Graph.appScope.launch(Dispatchers.IO) {
            val ok = runCatching { Graph.accountApi.markWatched(id) }
            lastWatchSync = ok.fold({ if (it) "✓ \"${video.title.take(40)}\" added to YouTube history" else "✗ YouTube gave no history URL for \"${video.title.take(40)}\"" },
                { "✗ ${it.message}" })
        }
    }

    @Volatile private var subsImportedAt = 0L

    /** Copies the account's channels into Vidora's subscriptions (at most every 6 h, silently). */
    fun importSubscriptionsIfStale(force: Boolean = false) {
        if (!Graph.account.isSignedIn) return
        val now = System.currentTimeMillis()
        if (!force && now - subsImportedAt < 6 * 3_600_000L) return
        subsImportedAt = now
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching { Graph.library.importSubscriptions(Graph.accountApi.subscriptions()) }
                .onFailure { subsImportedAt = 0L }
        }
    }
}
