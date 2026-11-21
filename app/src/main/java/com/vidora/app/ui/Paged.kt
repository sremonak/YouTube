package com.vidora.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vidora.app.data.Entry
import com.vidora.app.data.PageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Infinite-scroll list state backed by continuation based [PageResult]s. */
class Paged(private val scope: CoroutineScope, private val first: suspend () -> PageResult) {
    var items by mutableStateOf<List<Entry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set

    private var next: (suspend () -> PageResult)? = null
    private var job: Job? = null

    fun load(refresh: Boolean = false) {
        job?.cancel()
        if (refresh) refreshing = true else if (items.isEmpty()) loading = true
        error = null
        job = scope.launch {
            try {
                val result = first()
                items = result.items.distinctBy { it.key }
                next = result.next
                endReached = result.next == null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                error = t.message ?: t.javaClass.simpleName
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val n = next ?: return
        if (job?.isActive == true) return
        loading = true
        job = scope.launch {
            try {
                val result = n()
                val known = items.map { it.key }.toHashSet()
                items = items + result.items.filter { known.add(it.key) }
                next = result.next
                endReached = result.next == null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                error = t.message
            } finally {
                loading = false
            }
        }
    }
}
