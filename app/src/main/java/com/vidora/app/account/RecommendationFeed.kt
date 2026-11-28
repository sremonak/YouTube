package com.vidora.app.account

import com.vidora.app.data.Entry
import com.vidora.app.data.PageResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Revalidate the first page, then advance real server candidates if it is unchanged. */
class RecommendationFeed(private val first: suspend () -> AccountPage) {
    private val lock = Mutex()
    private var seen = emptySet<String>()
    private var next: (suspend () -> PageResult)? = null
    suspend fun load(): AccountPage = lock.withLock {
        val fresh = first()
        val unseen = fresh.page.items.filter { it.key !in seen }
        val extra = if (seen.isNotEmpty() && unseen.isEmpty()) (next ?: fresh.page.next)?.invoke() else null
        val candidates = fresh.page.items + extra?.items.orEmpty()
        val ordered = candidates.distinctBy { it.key }.sortedBy { it.key in seen }
        next = if (extra != null) extra.next else fresh.page.next
        seen = (seen + ordered.map(Entry::key)).toList().takeLast(600).toSet()
        AccountPage(PageResult(ordered, next, fresh.page.notices + extra?.notices.orEmpty(), retries = fresh.page.retries + extra?.retries.orEmpty()), fresh.chips)
    }
}
