package com.vidora.app.data

import com.vidora.app.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** Dedicated Shorts first; bounded fallback, no silent spinner over a string of slow searches. */
class ShortsRepository(
    private val browse: suspend () -> PageResult = { Graph.accountApi.shorts() },
    private val search: suspend (String) -> PageResult = { Graph.repository.search(it, SearchFilter.VIDEOS) },
) {
    private fun shortPage(page: PageResult): PageResult = PageResult(
        items = page.items.filter { it is Entry.Video && (it.video.isShort || it.video.url.contains("/shorts/") || it.video.duration in 1..75) },
        next = page.next?.let { load -> suspend { shortPage(load()) } },
        notices = page.notices, retries = page.retries,
    )

    suspend fun load(): PageResult {
        val failures = mutableListOf<String>()
        val loaders: List<Pair<String, suspend () -> PageResult>> = listOf(
            "YouTube Shorts" to browse,
            "Shorts search" to { search("#shorts") },
            "Recent Shorts search" to { search("shorts today") },
        )
        for ((name, load) in loaders) {
            try {
                val page = withTimeout(12_000) {
                    var current = shortPage(load())
                    repeat(3) {
                        if (current.items.isNotEmpty() || current.next == null) return@withTimeout current
                        current = current.next!!.invoke()
                    }
                    current
                }
                if (page.items.isNotEmpty()) return page
                failures += "$name returned no short videos"
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { failures += "$name timed out" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += "$name: ${e.message}" }
        }
        throw java.io.IOException(failures.joinToString("\n"))
    }
}
