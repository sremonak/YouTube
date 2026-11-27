package com.vidora.app.account

import com.vidora.app.Graph
import com.vidora.app.notify.RssFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo

/**
 * Mirrors local actions (like / subscribe) to the signed-in YouTube account when the user enabled
 * "Sync likes & subscriptions". Failures are reported but never block the local action.
 */
object AccountSync {
    private val active get() = Graph.account.isSignedIn && Graph.settingsState.value.syncAccountActions && !Graph.settingsState.value.incognito

    fun liked(videoUrl: String, liked: Boolean) {
        if (!active) return
        val id = Graph.repository.videoId(videoUrl) ?: return
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching { if (liked) Graph.accountApi.like(id) else Graph.accountApi.removeLike(id) }
                .onFailure { Graph.player.toast("YouTube like sync failed: ${it.message}") }
        }
    }

    fun disliked(videoUrl: String) {
        if (!Graph.account.isSignedIn || Graph.settingsState.value.incognito) return
        val id = Graph.repository.videoId(videoUrl) ?: return
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching { Graph.accountApi.dislike(id) }
                .onSuccess { Graph.player.toast("Disliked on YouTube") }
                .onFailure { Graph.player.toast("Dislike failed: ${it.message}") }
        }
    }

    fun subscribed(channelUrl: String, subscribed: Boolean) {
        if (!active || com.vidora.app.data.GuestSources.isPeerChannel(channelUrl)) return
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching {
                val id = RssFeed.channelIdFromUrl(channelUrl)
                    ?: ChannelInfo.getInfo(ServiceList.YouTube, channelUrl).id
                if (subscribed) Graph.accountApi.subscribe(id) else Graph.accountApi.unsubscribe(id)
                changed()
            }.onFailure { Graph.player.toast("YouTube subscription sync failed: ${it.message}") }
        }
    }

    private val _watchStatus = MutableStateFlow("No playback sent yet")
    val watchStatus = _watchStatus.asStateFlow()
    val lastWatchSync: String get() = _watchStatus.value
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val reportLock = Mutex()
    private val batchLock = Mutex()

    private suspend fun changed() = withContext(Dispatchers.Main) {
        listOf("home:true:", "acc:", "you:history", "subs:").forEach(com.vidora.app.ui.UiCache::invalidate)
        _revision.value += 1
    }

    /** HTTP success is only delivery, not proof that YouTube added a history item. */
    suspend fun syncWatched(video: com.vidora.app.data.VideoItem, seconds: Double, start: Double): Boolean = reportLock.withLock {
        val settings = Graph.settings.flow.first()
        val id = Graph.repository.videoId(video.url)
        if (!WatchHistoryPolicy.eligible(Graph.account.isSignedIn, settings.syncWatchHistory, settings.incognito, id != null)) {
            _watchStatus.value = when {
                settings.incognito -> "Incognito · history is not sent"
                id == null -> "${video.platform} playback stays in device history; only YouTube videos can sync to YouTube"
                !Graph.account.isSignedIn -> "Sign in to YouTube to send history · device history is saved separately"
                else -> "YouTube history sync is off"
            }
            return@withLock false
        }
        _watchStatus.value = "Sending history…"
        try {
            val owner = Graph.account.session.value?.signedInAt
            val result = HistoryDelivery(
                send = { check(Graph.accountApi.markWatched(id!!, seconds, start)) { "History was not sent" } },
                verify = {
                    val privacy = Graph.settings.flow.first()
                    check(Graph.account.session.value?.signedInAt == owner && !privacy.incognito && privacy.syncWatchHistory) { "Account/privacy changed" }
                    Graph.accountApi.feed(AccountFeed.HISTORY).items.filterIsInstance<com.vidora.app.data.Entry.Video>()
                        .any { Graph.repository.videoId(it.video.url) == id }
                },
            ).deliver { _watchStatus.value = "Sent · checking YouTube history…"; changed() }
            if (result.confirmed) {
                Graph.library.confirmYouTubeHistory(video.url)
                _watchStatus.value = "Confirmed in YouTube history · ${video.title.take(50)}"
            } else _watchStatus.value = result.verificationError?.let { "Sent; history check failed: $it" }
                ?: "Sent, but not visible in YouTube history yet. History may be paused or delayed on YouTube."
            result.confirmed
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _watchStatus.value = "History sync failed: ${e.message}"; false }
    }

    /** Explicit user action: only recorded playback intervals, never guessed old resume positions. */
    fun syncRecordedHistory() = Graph.appScope.launch {
        if (!batchLock.tryLock()) return@launch
        try {
            val owner = Graph.account.session.value?.signedInAt ?: run { _watchStatus.value = "Sign in to YouTube first"; return@launch }
            val privacy = Graph.settings.flow.first()
            if (privacy.incognito || !privacy.syncWatchHistory) { _watchStatus.value = "History sync is off or incognito is active"; return@launch }
            val rows = Graph.library.data.value.history.filter {
                it.actualWatchSeconds > 0 && it.youtubeSyncedAt < it.watchedAt && Graph.repository.videoId(it.video.url) != null
            }.take(50)
            if (rows.isEmpty()) { _watchStatus.value = "No measured playback pending. Older resume positions cannot be treated as watched time."; return@launch }
            for (row in rows) {
                if (Graph.account.session.value?.signedInAt != owner) break
                val s = Graph.settings.flow.first()
                if (s.incognito || !s.syncWatchHistory) break
                syncWatched(row.video, row.actualWatchSeconds, row.watchStartSeconds)
            }
        } finally { batchLock.unlock() }
    }

    @Volatile private var subsImportedAt = 0L

    /** Copies the account's channels into Vidora's subscriptions (at most every 6 h, silently). */
    fun importSubscriptionsIfStale(force: Boolean = false) {
        if (!Graph.account.isSignedIn || Graph.settingsState.value.incognito) return
        val now = System.currentTimeMillis()
        if (!force && now - subsImportedAt < 6 * 3_600_000L) return
        subsImportedAt = now
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching { Graph.library.importSubscriptions(Graph.accountApi.subscriptions()) }
                .onFailure { subsImportedAt = 0L }
        }
    }

    /** Items hidden after a YouTube feedback action (url → message shown in their place). */
    val dismissed = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun feedback(video: com.vidora.app.data.VideoItem, action: com.vidora.app.data.FeedbackAction) {
        dismissed[video.url] = "${action.label} ✓"
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching { Graph.accountApi.sendFeedback(action.token) }
                .onFailure {
                    launch(Dispatchers.Main) { dismissed.remove(video.url) }
                    Graph.player.toast("YouTube: ${it.message}")
                }
        }
    }
}
