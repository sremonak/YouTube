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
data class HistoryEntry(val video: VideoItem, val watchedAt: Long, val positionMs: Long = 0)

@Serializable
data class LibraryData(
    val history: List<HistoryEntry> = emptyList(),
    val favorites: List<VideoItem> = emptyList(),
    val watchLater: List<VideoItem> = emptyList(),
    val subscriptions: List<ChannelItem> = emptyList(),
    val searchHistory: List<String> = emptyList(),
)

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
        val entry = HistoryEntry(video, System.currentTimeMillis(), previous?.positionMs ?: 0)
        d.copy(history = (listOf(entry) + d.history.filter { it.video.url != video.url }).take(500))
    }

    fun savePosition(url: String, positionMs: Long) = mutate { d ->
        d.copy(history = d.history.map { if (it.video.url == url) it.copy(positionMs = positionMs) else it })
    }

    fun positionOf(url: String): Long = _data.value.history.firstOrNull { it.video.url == url }?.positionMs ?: 0

    fun removeHistory(url: String) = mutate { d -> d.copy(history = d.history.filter { it.video.url != url }) }
    fun clearHistory() = mutate { it.copy(history = emptyList()) }

    // favourites / watch later
    fun isFavorite(url: String) = _data.value.favorites.any { it.url == url }
    fun toggleFavorite(video: VideoItem) = mutate { d ->
        if (d.favorites.any { it.url == video.url }) d.copy(favorites = d.favorites.filter { it.url != video.url })
        else d.copy(favorites = listOf(video) + d.favorites)
    }

    fun toggleWatchLater(video: VideoItem) = mutate { d ->
        if (d.watchLater.any { it.url == video.url }) d.copy(watchLater = d.watchLater.filter { it.url != video.url })
        else d.copy(watchLater = d.watchLater + video)
    }

    // subscriptions
    fun isSubscribed(url: String) = _data.value.subscriptions.any { it.url == url }
    fun toggleSubscription(channel: ChannelItem) = mutate { d ->
        if (d.subscriptions.any { it.url == channel.url }) d.copy(subscriptions = d.subscriptions.filter { it.url != channel.url })
        else d.copy(subscriptions = (d.subscriptions + channel).sortedBy { it.name.lowercase() })
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

    // backup / restore
    fun exportJson(): String = json.encodeToString(LibraryData.serializer(), _data.value)
    fun importJson(text: String): Boolean = runCatching {
        val imported = json.decodeFromString(LibraryData.serializer(), text)
        mutate { imported }
    }.isSuccess
}
