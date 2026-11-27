package com.vidora.app.account

/** Consent is still required even when an account session exists; guest/incognito never report. */
object WatchHistoryPolicy {
    const val MIN_PLAY_MS = 15_000L
    fun eligible(signedIn: Boolean, enabled: Boolean, incognito: Boolean, youtube: Boolean) = signedIn && enabled && !incognito && youtube
    fun allowedTrackingUrl(scheme: String, host: String, path: String) = scheme == "https" &&
        host in setOf("www.youtube.com", "s.youtube.com", "youtube.com") && path.startsWith("/api/stats/")
    fun seconds(watchedSeconds: Double) = if (watchedSeconds.isFinite()) watchedSeconds.coerceIn(0.0, 86_400.0) else 0.0
}
