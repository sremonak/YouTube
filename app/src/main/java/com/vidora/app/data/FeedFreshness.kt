package com.vidora.app.data

import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** Home only. Search is never rewritten. Rotation changes the requested candidate pool, not just its display order. */
class FeedFreshness(private val clock: () -> Long = System::currentTimeMillis) {
    private val sequence = AtomicLong()
    data class Plan(val query: String, val topic: String, val round: Long)
    fun next(country: String, settings: AppSettings): Plan {
        val round = sequence.getAndIncrement()
        val topics = settings.homeInterests.sorted().ifEmpty { listOf("", "science", "nature", "technology", "music", "travel", "education") }
        val slot = Math.floorMod(round + clock() / ROTATION_MS, topics.size.toLong()).toInt()
        val topic = topics[slot]
        val after = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate().minusDays(30)
        val query = listOf(GuestSources.countryName(country), HomeRanking.languageTerm(settings.homeLanguage), topic, "after:$after")
            .filter(String::isNotBlank).joinToString(" ")
        return Plan(query, topic, round)
    }
    companion object {
        const val REVALIDATE_MS = 5 * 60_000L
        const val ROTATION_MS = 30 * 60_000L
        fun stale(lastSuccess: Long, now: Long, maxAge: Long = REVALIDATE_MS) = lastSuccess == 0L || now < lastSuccess || now - lastSuccess >= maxAge
    }
}
