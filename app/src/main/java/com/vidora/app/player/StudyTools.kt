package com.vidora.app.player

import com.vidora.app.data.PlaybackBookmark
import java.util.Locale

object StudyTools {
    const val MIN_SECTION_MS = 1_000L
    const val MAX_SLEEP_MINUTES = 240
    fun validSection(start: Long, end: Long) = start >= 0 && end >= start && end - start >= MIN_SECTION_MS
    fun customMinutes(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_SLEEP_MINUTES }
    fun matches(moment: PlaybackBookmark, query: String): Boolean {
        val text = "${moment.label} ${moment.video.title} ${moment.video.uploader.orEmpty()} ${moment.note}".lowercase(Locale.ROOT)
        return query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).all { it in text }
    }
    fun remaining(deadlineElapsed: Long, nowElapsed: Long) = (deadlineElapsed - nowElapsed).coerceAtLeast(0)
}
