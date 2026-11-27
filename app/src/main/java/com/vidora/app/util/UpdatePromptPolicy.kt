package com.vidora.app.util

/** Foreground checks use monotonic time; failures must not consume the success throttle. */
internal object UpdatePromptPolicy {
    const val SUCCESS_INTERVAL_MS = 5 * 60_000L
    const val RETRY_INTERVAL_MS = 30_000L
    const val SNOOZE_MS = 6 * 3_600_000L

    fun shouldCheck(now: Long, lastSuccess: Long?, lastAttempt: Long?): Boolean {
        fun elapsed(at: Long?, interval: Long) = at == null || now < at || now - at >= interval
        return elapsed(lastSuccess, SUCCESS_INTERVAL_MS) && elapsed(lastAttempt, RETRY_INTERVAL_MS)
    }

    fun shouldPrompt(version: String, laterVersion: String?, laterUntil: Long, now: Long): Boolean =
        version != laterVersion || now >= laterUntil
}
