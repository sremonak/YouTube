package com.vidora.app.data

import com.vidora.app.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A failing channel is not an empty subscription list; cancellation must propagate. */
class SubscriptionFeed(private val uploads: suspend (String) -> List<VideoItem> = { Graph.repository.latestUploads(it) }) {
    suspend fun load(channels: List<ChannelItem>): PageResult = coroutineScope {
        val gate = Semaphore(4)
        val results = channels.map { channel -> async {
            gate.withPermit {
                try { Pair(uploads(channel.url).take(20).map { it.copy(uploaderUrl = channel.url) }, null) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Pair(emptyList<VideoItem>(), "${channel.name}: ${e.message}") }
            }
        } }.awaitAll()
        val failures = results.mapNotNull { it.second }
        if (channels.isNotEmpty() && failures.size == channels.size) throw java.io.IOException("Subscriptions could not load. " + failures.take(3).joinToString("; "))
        PageResult(results.flatMap { it.first }.sortedByDescending { it.uploadedEpoch }.distinctBy { it.url }.map { Entry.Video(it) }, notices = failures)
    }
}
