package com.vidora.app.player

import com.vidora.app.data.ProviderHealth

/** Pure, bounded recovery decisions. No retry path grants access to restricted content. */
object PlaybackRecovery {
    enum class Action { STOP, REFRESH, LOWER_QUALITY, RETRY, PAUSE }
    fun decide(httpCode: Int?, decoder: Boolean, attempt: Int): Action = when {
        httpCode in listOf(401, 404, 410, 451) -> Action.STOP
        httpCode == 429 -> Action.PAUSE
        decoder -> if (attempt == 0) Action.LOWER_QUALITY else Action.STOP
        httpCode == 403 -> if (attempt == 0) Action.REFRESH else Action.STOP
        attempt >= 2 -> Action.STOP
        httpCode != null && httpCode in 400..499 -> Action.STOP
        else -> Action.RETRY
    }
    fun delayMs(attempt: Int) = ProviderHealth.backoff(attempt)
}
