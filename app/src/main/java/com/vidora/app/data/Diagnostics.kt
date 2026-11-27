package com.vidora.app.data

/** No URLs, titles, queries, cookies, signed links or device/IP identifiers are accepted here. */
object Diagnostics {
    @Volatile var enabled: Boolean = true
    private val events = ArrayDeque<String>()
    @Synchronized fun record(component: String, outcome: String, elapsedMs: Long = 0, count: Long = 0) {
        if (!enabled) return
        // Callers pass enum-like constants, never an exception's message.
        val c = component.takeIf { it in setOf("provider", "cache", "playback", "test") } ?: "other"
        val o = outcome.takeIf { it in setOf("success", "failure", "write_failed", "clear_failed", "extracted", "rebuffer", "dropped_frames", "STOP", "REFRESH", "LOWER_QUALITY", "RETRY", "PAUSE", "ok") } ?: "redacted"
        if (events.size == 80) events.removeFirst()
        events.addLast("$c $o elapsed_ms=${elapsedMs.coerceAtLeast(0)} count=${count.coerceAtLeast(0)}")
    }
    @Synchronized fun report(): String = "Vidora local diagnostics\nNo account, query, URL or IP data included.\n" + events.joinToString("\n")
    @Synchronized fun clear() { events.clear() }
}
