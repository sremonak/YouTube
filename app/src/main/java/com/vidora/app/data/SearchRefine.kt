package com.vidora.app.data

/** Client-side refinements for search results (NewPipeExtractor only exposes the content type server-side). */
enum class DurationFilter(val label: String, val minSec: Long, val maxSec: Long) {
    ANY("Any length", 0, Long.MAX_VALUE),
    SHORT("Under 4 min", 0, 4 * 60 - 1),
    MEDIUM("4–20 min", 4 * 60, 20 * 60),
    LONG("Over 20 min", 20 * 60 + 1, Long.MAX_VALUE),
}

enum class DateFilter(val label: String, val maxAgeMs: Long) {
    ANY("Any time", Long.MAX_VALUE),
    TODAY("Today", 24L * 3600_000),
    WEEK("This week", 7L * 24 * 3600_000),
    MONTH("This month", 31L * 24 * 3600_000),
    YEAR("This year", 366L * 24 * 3600_000),
}

object SearchRefine {
    /** True if [v] passes the filters. Unknown durations/dates (0) are kept rather than hidden. */
    fun matches(
        v: VideoItem,
        duration: DurationFilter,
        date: DateFilter,
        hideLive: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (hideLive && v.isLive) return false
        if (duration != DurationFilter.ANY) {
            if (v.isLive) return false
            if (v.duration > 0 && v.duration !in duration.minSec..duration.maxSec) return false
        }
        if (date != DateFilter.ANY && v.uploadedEpoch > 0 && now - v.uploadedEpoch > date.maxAgeMs) return false
        return true
    }
}

object ContinueWatching {
    /** Videos started (>30 s) but not finished (<90 %), newest first. */
    fun pick(history: List<HistoryEntry>, limit: Int = 12): List<HistoryEntry> =
        history.asSequence()
            .filter { !it.video.isLive && it.video.duration > 60 && it.positionMs >= 30_000 && it.positionMs / 1000.0 < it.video.duration * 0.9 }
            .distinctBy { it.video.url }
            .take(limit)
            .toList()
}
