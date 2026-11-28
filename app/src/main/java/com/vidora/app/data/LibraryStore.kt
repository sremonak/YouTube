package com.vidora.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(val video: VideoItem, val watchedAt: Long, val positionMs: Long = 0,
    val watchStartSeconds: Double = 0.0, val actualWatchSeconds: Double = 0.0, val youtubeSyncedAt: Long = 0)

@Serializable
data class LibraryData(
    val history: List<HistoryEntry> = emptyList(),
    val favorites: List<VideoItem> = emptyList(),
    val watchLater: List<VideoItem> = emptyList(),
    val subscriptions: List<ChannelItem> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val groups: List<SubscriptionGroup> = emptyList(),
    val bookmarks: List<PlaybackBookmark> = emptyList(),
)

/** Explicit local timestamp, independent of resume/history measurements. Included in library backup. */
@Serializable
data class PlaybackBookmark(
    val id: String,
    val video: VideoItem,
    val positionMs: Long,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** User-defined folder of subscribed channels (by channel URL). */
@Serializable
data class SubscriptionGroup(
    val id: String,
    val name: String,
    val channels: List<String> = emptyList(),
)

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val videos: List<VideoItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val thumbnail: String? get() = videos.firstOrNull()?.thumbnail
}

/** Local-only library (history, likes, watch later, subscriptions). Nothing leaves the device. */
class LibraryStore(context: Context, private val scope: CoroutineScope) {

    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LibraryData> = _data.asStateFlow()

    private fun load(): LibraryData = runCatching {
        if (file.exists()) json.decodeFromString(LibraryData.serializer(), file.readText()) else LibraryData()
    }.getOrDefault(LibraryData())

    private fun mutate(block: (LibraryData) -> LibraryData) {
        _data.update(block)
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(LibraryData.serializer(), _data.value))
                tmp.renameTo(file)
            }
        }
    }

    // history
    fun addHistory(video: VideoItem) = mutate { d ->
        val previous = d.history.firstOrNull { it.video.url == video.url }
        val entry = previous?.copy(video = video, watchedAt = System.currentTimeMillis())
            ?: HistoryEntry(video, System.currentTimeMillis())
        d.copy(history = (listOf(entry) + d.history.filter { it.video.url != video.url }).take(500))
    }

    fun savePosition(url: String, positionMs: Long) = mutate { d ->
        d.copy(history = d.history.map { if (it.video.url == url) it.copy(positionMs = positionMs) else it })
    }

    /** Only measured continuous playback; a resume position/seek is never fabricated as watch time. */
    fun recordWatched(video: VideoItem, startSeconds: Double, seconds: Double) = mutate { d ->
        val old = d.history.firstOrNull { it.video.url == video.url } ?: HistoryEntry(video, System.currentTimeMillis())
        val updated = old.copy(watchStartSeconds = startSeconds, actualWatchSeconds = seconds)
        d.copy(history = (listOf(updated) + d.history.filterNot { it.video.url == video.url }).take(500))
    }

    fun confirmYouTubeHistory(url: String) = mutate { d -> d.copy(history = d.history.map {
        if (it.video.url == url) it.copy(youtubeSyncedAt = System.currentTimeMillis()) else it
    }) }

    fun positionOf(url: String): Long = _data.value.history.firstOrNull { it.video.url == url }?.positionMs ?: 0

    fun removeHistory(url: String) = mutate { d -> d.copy(history = d.history.filter { it.video.url != url }) }
    fun clearHistory() = mutate { it.copy(history = emptyList()) }

    // favourites / watch later
    fun isFavorite(url: String) = _data.value.favorites.any { it.url == url }
    fun toggleFavorite(video: VideoItem) {
        val nowLiked = !isFavorite(video.url)
        mutate { d ->
            if (d.favorites.any { it.url == video.url }) d.copy(favorites = d.favorites.filter { it.url != video.url })
            else d.copy(favorites = listOf(video) + d.favorites)
        }
        com.vidora.app.account.AccountSync.liked(video.url, nowLiked)
    }

    fun toggleWatchLater(video: VideoItem) = mutate { d ->
        if (d.watchLater.any { it.url == video.url }) d.copy(watchLater = d.watchLater.filter { it.url != video.url })
        else d.copy(watchLater = d.watchLater + video)
    }

    /** Local cleanup only: deliberately does not unlike/delete remote YouTube history. */
    fun removeLocalVideos(kind: String, urls: Set<String>) = mutate { d -> when (kind) {
        "liked" -> d.copy(favorites = d.favorites.filterNot { it.url in urls })
        "later" -> d.copy(watchLater = d.watchLater.filterNot { it.url in urls })
        "history" -> d.copy(history = d.history.filterNot { it.video.url in urls })
        else -> d
    } }

    fun saveBookmark(video: VideoItem, positionMs: Long, label: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val position = positionMs.coerceAtLeast(0)
        mutate { d -> d.copy(bookmarks = (listOf(PlaybackBookmark(id, video, position,
            label.trim().take(160).ifBlank { "Saved moment" })) + d.bookmarks).take(500)) }
        return id
    }
    fun renameBookmark(id: String, label: String) = mutate { d -> d.copy(bookmarks = d.bookmarks.map {
        if (it.id == id) it.copy(label = label.trim().take(160).ifBlank { it.label }) else it
    }) }
    fun removeBookmark(id: String) = mutate { d -> d.copy(bookmarks = d.bookmarks.filterNot { it.id == id }) }

    // subscriptions
    fun isSubscribed(url: String) = _data.value.subscriptions.any { it.url == url }
    fun toggleSubscription(channel: ChannelItem) {
        val nowSubscribed = !isSubscribed(channel.url)
        mutate { d ->
            if (d.subscriptions.any { it.url == channel.url }) d.copy(subscriptions = d.subscriptions.filter { it.url != channel.url })
            else d.copy(subscriptions = (d.subscriptions + channel).sortedBy { it.name.lowercase() })
        }
        com.vidora.app.account.AccountSync.subscribed(channel.url, nowSubscribed)
    }

    /** Merges channels (e.g. imported from the YouTube account); returns how many were new. */
    fun importSubscriptions(channels: List<ChannelItem>): Int {
        var added = 0
        mutate { d ->
            val known = d.subscriptions.map { it.url }.toHashSet()
            val fresh = channels.filter { known.add(it.url) }
            added = fresh.size
            d.copy(subscriptions = (d.subscriptions + fresh).sortedBy { it.name.lowercase() })
        }
        return added
    }

    // search history
    fun addSearch(query: String) = mutate { d ->
        val q = query.trim()
        if (q.isEmpty()) d else d.copy(searchHistory = (listOf(q) + d.searchHistory.filter { !it.equals(q, true) }).take(50))
    }

    fun removeSearch(query: String) = mutate { d -> d.copy(searchHistory = d.searchHistory - query) }
    fun clearSearchHistory() = mutate { it.copy(searchHistory = emptyList()) }

    // local playlists
    fun playlist(id: String): LocalPlaylist? = _data.value.playlists.firstOrNull { it.id == id }

    fun createPlaylist(name: String, first: VideoItem? = null): String = createPlaylistFrom(name, listOfNotNull(first))

    fun createPlaylistFrom(name: String, videos: List<VideoItem>): String {
        val id = java.util.UUID.randomUUID().toString()
        mutate { d -> d.copy(playlists = d.playlists + LocalPlaylist(id, name.trim().ifBlank { "My playlist" }, videos.distinctBy { it.url })) }
        return id
    }

    fun renamePlaylist(id: String, name: String) = updatePlaylist(id) { it.copy(name = name.trim().ifBlank { it.name }) }
    fun deletePlaylist(id: String) = mutate { d -> d.copy(playlists = d.playlists.filter { it.id != id }) }

    fun addToPlaylist(id: String, videos: List<VideoItem>) = updatePlaylist(id) { p ->
        val known = p.videos.map { it.url }.toHashSet()
        p.copy(videos = p.videos + videos.filter { known.add(it.url) })
    }

    fun removeFromPlaylist(id: String, url: String) = updatePlaylist(id) { p -> p.copy(videos = p.videos.filter { it.url != url }) }

    fun movePlaylistItem(id: String, from: Int, to: Int) = updatePlaylist(id) { p ->
        if (from !in p.videos.indices || to !in p.videos.indices) p
        else p.copy(videos = p.videos.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun reorderPlaylist(id: String, order: CollectionOrder) = updatePlaylist(id) {
        it.copy(videos = CollectionTools.videos(it.videos, order = order))
    }

    private fun updatePlaylist(id: String, block: (LocalPlaylist) -> LocalPlaylist) = mutate { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) block(it) else it })
    }

    // ---------------------------------------------------------------- subscription groups
    fun saveGroup(id: String?, name: String, channels: List<String>): String {
        val gid = id ?: java.util.UUID.randomUUID().toString()
        mutate { d ->
            val g = SubscriptionGroup(gid, name.trim().ifBlank { "Group" }, channels.distinct())
            val exists = d.groups.any { it.id == gid }
            d.copy(groups = if (exists) d.groups.map { if (it.id == gid) g else it } else d.groups + g)
        }
        return gid
    }

    fun deleteGroup(id: String) = mutate { d -> d.copy(groups = d.groups.filter { it.id != id }) }

    fun unsubscribe(url: String) = mutate { d -> d.copy(subscriptions = d.subscriptions.filter { it.url != url }) }

    // backup / restore
    fun exportJson(): String = json.encodeToString(LibraryData.serializer(), _data.value)
    fun importJson(text: String): Boolean = runCatching {
        val imported = json.decodeFromString(LibraryData.serializer(), text)
        mutate { imported }
    }.isSuccess
}
