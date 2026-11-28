package com.vidora.app.ui

import androidx.compose.runtime.*
import com.vidora.app.Graph
import com.vidora.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Continuations stay in memory; public rows can be restored without persisting signed media URLs. */
class Paged(private val scope: CoroutineScope, private val first: suspend () -> PageResult) {
    var items by mutableStateOf<List<Entry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notices by mutableStateOf<List<String>>(emptyList()); private set
    var retries by mutableStateOf<List<ProviderRetry>>(emptyList()); private set
    var endReached by mutableStateOf(false); private set
    var fromDisk by mutableStateOf(false); private set
    private var next: (suspend () -> PageResult)? = null
    private var job: Job? = null
    var lastUpdatedAt by mutableStateOf(0L); private set
    var refreshMessage by mutableStateOf<String?>(null); private set
    private var lastSuccess = 0L
    private var loadedOnce = false
    private var generation = 0
    private var updates: (() -> Flow<PageResult>)? = null
    private var diskKey: String? = null

    fun progressive(key: String?, stream: () -> Flow<PageResult>): Paged = apply { diskKey = key; updates = stream }
    fun ensureLoaded(maxAgeMs: Long = Long.MAX_VALUE) {
        if (job?.isActive == true) return
        if (!loadedOnce || (maxAgeMs != Long.MAX_VALUE && FeedFreshness.stale(lastSuccess, System.currentTimeMillis(), maxAgeMs))) load(refresh = items.isNotEmpty())
    }
    private suspend fun mayPersist(): Boolean {
        val s = Graph.settings.flow.first()
        return !s.incognito && (diskKey?.startsWith("search:") != true || s.saveSearchHistory)
    }
    fun cancelPending() { generation++; job?.cancel(); loading = false; refreshing = false }
    /** Also invalidate references held by an already-composed Home after history sync. */
    fun invalidate() { cancelPending(); loadedOnce = false; lastSuccess = 0L }
    fun load(refresh: Boolean = false) {
        job?.cancel()
        val ticket = ++generation
        if (refresh) refreshing = true else if (items.isEmpty()) loading = true
        error = null
        refreshMessage = null
        val previousKeys = items.map { it.key }.toSet()
        job = scope.launch {
            try {
                if (items.isEmpty() && diskKey != null && mayPersist()) {
                    items = Graph.feedStore.read(diskKey!!)
                    fromDisk = items.isNotEmpty()
                }
                val stream = updates?.invoke() ?: flow { emit(first()) }
                var receivedRows = false
                stream.collect { page ->
                    ensureActive()
                    if (ticket != generation) return@collect
                    // Keep stale data until a source returns usable rows, or all sources succeed empty.
                    if (page.items.isNotEmpty() || (!page.pending && page.retries.isEmpty())) {
                        items = page.items.distinctBy { it.key }; fromDisk = false; receivedRows = true
                    }
                    notices = page.notices.distinct()
                    retries = page.retries
                    next = page.next
                    endReached = !page.pending && page.next == null
                }
                loadedOnce = true
                if (receivedRows) {
                    lastSuccess = System.currentTimeMillis(); lastUpdatedAt = lastSuccess
                    if (refresh) refreshMessage = if (items.any { it.key !in previousKeys }) "Feed updated · fresh results loaded" else "Checked just now · providers returned the same videos"
                }
                if (receivedRows && diskKey != null && mayPersist()) {
                    try { Graph.feedStore.write(diskKey!!, items) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { Diagnostics.record("cache", "write_failed") }
                }
            } catch (e: CancellationException) { throw e }
            catch (t: Exception) { if (ticket == generation) error = t.message ?: "Request failed" }
            finally { if (ticket == generation) { loading = false; refreshing = false } }
        }
    }
    fun retrySource(retry: ProviderRetry) {
        if (job?.isActive == true) return
        loading = true
        val ticket = ++generation
        job = scope.launch {
            try {
                val page = retry.load()
                ensureActive()
                if (ticket != generation) return@launch
                val known = items.mapTo(hashSetOf()) { it.key }
                items = items + page.items.filter { known.add(it.key) }
                retries = retries.filterNot { it.label == retry.label } + page.retries
                notices = notices.filterNot { it.startsWith(retry.label) } + page.notices
                val previous = next
                next = when {
                    previous == null -> page.next
                    page.next == null -> previous
                    else -> ({ val a = previous(); val b = page.next.invoke(); PageResult(a.items + b.items,
                        next = mergeNext(a.next, b.next), notices = a.notices + b.notices, retries = a.retries + b.retries) })
                }
                endReached = next == null; loadedOnce = true; error = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) error = e.message }
            finally { if (ticket == generation) loading = false }
        }
    }
    private fun mergeNext(a: (suspend () -> PageResult)?, b: (suspend () -> PageResult)?): (suspend () -> PageResult)? = when {
        a == null -> b
        b == null -> a
        else -> ({ val x = a(); val y = b(); PageResult(x.items + y.items, mergeNext(x.next, y.next), x.notices + y.notices, retries = x.retries + y.retries) })
    }
    fun loadMore() {
        val n = next ?: return
        if (job?.isActive == true) return
        loading = true
        val ticket = ++generation
        job = scope.launch {
            try {
                val page = n()
                ensureActive()
                if (ticket != generation) return@launch
                notices = (notices + page.notices).distinct(); retries = (retries + page.retries).distinctBy { it.label }
                val known = items.mapTo(hashSetOf()) { it.key }
                items = items + page.items.filter { known.add(it.key) }
                next = page.next; endReached = next == null
            } catch (e: CancellationException) { throw e }
            catch (t: Exception) { if (ticket == generation) error = t.message }
            finally { if (ticket == generation) loading = false }
        }
    }
}
