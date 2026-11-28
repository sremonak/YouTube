package com.vidora.app.data

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Transient failures only. Restricted/deleted content must not open an entire host's circuit. */
class ProviderHealth(private val clock: () -> Long = System::currentTimeMillis) {
    data class State(val failures: Int = 0, val until: Long = 0)
    private val hosts = ConcurrentHashMap<String, State>()
    fun remaining(host: String): Long = ((hosts[host]?.until ?: 0) - clock()).coerceAtLeast(0)
    fun success(host: String) { hosts.remove(host) }
    fun failure(host: String, retryAfterMs: Long = 0) {
        hosts.compute(host) { _, old ->
            val count = ((old?.failures ?: 0) + 1).coerceAtMost(10)
            val pause = max(retryAfterMs.coerceIn(0, 3_600_000), if (count >= 3) 30_000L * (1L shl (count - 3).coerceAtMost(5)) else 0)
            State(count, clock() + pause)
        }
    }
    fun reset(host: String) { hosts.remove(host) }
    companion object {
        fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long {
            value?.toLongOrNull()?.let { return (it.coerceIn(0, 3600) * 1000) }
            return runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }
                .getOrDefault(0).coerceIn(0, 3_600_000)
        }
        fun backoff(attempt: Int, jitter: Long = (0..250L).random()) =
            (500L * (1L shl attempt.coerceIn(0, 5)) + jitter.coerceIn(0, 250)).coerceAtMost(16_250)
    }
}
