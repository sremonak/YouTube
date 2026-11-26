package com.vidora.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vidora.app.data.PageResult
import com.vidora.app.ui.screens.CommentsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * In-memory screen cache, like the YouTube app: feeds, channel tabs, playlists, search results
 * and comments survive navigation (Settings, a channel, the player…) instead of being fetched
 * again from the network each time a screen comes back. Entries expire after [FEED_TTL_MS]
 * or when the user pulls to refresh.
 */
object UiCache {
    /** Lives as long as the process, so loads in progress continue while you're on another screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    const val FEED_TTL_MS = 30 * 60_000L

    private class Slot<T>(val value: T, val createdAt: Long)

    private fun <T> lru(max: Int) = object : LinkedHashMap<String, Slot<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot<T>>?) = size > max
    }

    private val pages = lru<Paged>(48)
    private val comments = lru<CommentsState>(12)
    private val values = lru<Any>(64)

    /** The cached [Paged] for [key], or a fresh one if missing / older than [ttlMs]. */
    @Synchronized
    fun paged(key: String, ttlMs: Long = FEED_TTL_MS, load: suspend () -> PageResult): Paged {
        val now = System.currentTimeMillis()
        pages[key]?.let { if (now - it.createdAt < ttlMs && it.value.error == null) return it.value }
        return Paged(scope, load).also { pages[key] = Slot(it, now) }
    }

    @Synchronized
    fun comments(url: String): CommentsState {
        val now = System.currentTimeMillis()
        comments[url]?.let { if (now - it.createdAt < FEED_TTL_MS && it.value.error == null) return it.value }
        return CommentsState(url, scope).also { comments[url] = Slot(it, now) }
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String, ttlMs: Long = FEED_TTL_MS): T? {
        val slot = values[key] ?: return null
        if (System.currentTimeMillis() - slot.createdAt > ttlMs) { values.remove(key); return null }
        return slot.value as? T
    }

    @Synchronized
    fun put(key: String, value: Any) { values[key] = Slot(value, System.currentTimeMillis()) }

    /** Value from the cache, else [load] (the result is cached). */
    suspend fun <T : Any> getOrLoad(key: String, ttlMs: Long = FEED_TTL_MS, load: suspend () -> T): T {
        get<T>(key, ttlMs)?.let { return it }
        return load().also { put(key, it) }
    }

    @Synchronized
    fun invalidate(prefix: String) {
        pages.keys.filter { it.startsWith(prefix) }.forEach(pages::remove)
        values.keys.filter { it.startsWith(prefix) }.forEach(values::remove)
    }

    /** Called on sign-in / sign-out: personalised content must not leak between accounts. */
    @Synchronized
    fun clearAll() { pages.clear(); comments.clear(); values.clear() }
}

/** A [Paged] list that survives navigation (see [UiCache]). */
@Composable
fun rememberCachedPaged(key: String, load: suspend () -> PageResult): Paged =
    remember(key) { UiCache.paged(key, load = load) }
