package com.vidora.app.data

import java.util.Locale
import kotlin.random.Random

/** Shared, deterministic local collection operations. Never invokes an account/network API. */
enum class CollectionOrder(val label: String) {
    SAVED("Saved order"), TITLE("Title A–Z"), CREATOR("Creator A–Z"), SHORTEST("Shortest first"), LONGEST("Longest first")
}
enum class CollectionLength(val label: String) {
    ALL("Any length"), SHORT("Under 4 minutes"), MEDIUM("4–20 minutes"), LONG("20+ minutes")
}
object CollectionTools {
    fun videos(source: List<VideoItem>, query: String = "", order: CollectionOrder = CollectionOrder.SAVED,
               length: CollectionLength = CollectionLength.ALL): List<VideoItem> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matches = source.filter { v ->
            val text = "${v.title} ${v.uploader.orEmpty()}".lowercase(Locale.ROOT)
            terms.all { it in text } && when (length) {
                CollectionLength.ALL -> true
                CollectionLength.SHORT -> !v.isLive && v.duration in 1..239
                CollectionLength.MEDIUM -> !v.isLive && v.duration in 240..1199
                CollectionLength.LONG -> !v.isLive && v.duration >= 1200
            }
        }
        return when (order) {
            CollectionOrder.SAVED -> matches
            CollectionOrder.TITLE -> matches.sortedBy { it.title.lowercase(Locale.ROOT) }
            CollectionOrder.CREATOR -> matches.sortedBy { it.uploader.orEmpty().lowercase(Locale.ROOT) }
            CollectionOrder.SHORTEST -> matches.sortedBy { if (it.duration <= 0 || it.isLive) Long.MAX_VALUE else it.duration }
            CollectionOrder.LONGEST -> matches.sortedByDescending { if (it.isLive) 0 else it.duration }
        }
    }

    fun move(queue: List<VideoItem>, from: Int, to: Int): List<VideoItem> =
        if (from !in queue.indices || to !in queue.indices) queue
        else queue.toMutableList().apply { add(to, removeAt(from)) }

    /** Played/current entries are untouched; only the upcoming tail is randomized. */
    fun shuffleUpcoming(queue: List<VideoItem>, index: Int, random: Random = Random.Default): List<VideoItem> {
        if (index !in queue.indices) return queue
        return queue.take(index + 1) + queue.drop(index + 1).shuffled(random)
    }
}
