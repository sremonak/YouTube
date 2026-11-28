package com.vidora.app

import com.vidora.app.data.ContinueWatching
import com.vidora.app.data.DateFilter
import com.vidora.app.data.DurationFilter
import com.vidora.app.data.HistoryEntry
import com.vidora.app.data.SearchRefine
import com.vidora.app.data.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRefineTest {
    private val now = 1_800_000_000_000L
    private fun v(dur: Long, ageDays: Long = 1, live: Boolean = false, url: String = "u$dur") =
        VideoItem(url = url, title = "t", duration = dur, isLive = live, uploadedEpoch = if (ageDays < 0) 0 else now - ageDays * 86_400_000L)

    @Test fun durationBuckets() {
        assertTrue(SearchRefine.matches(v(120), DurationFilter.SHORT, DateFilter.ANY, false, now))
        assertFalse(SearchRefine.matches(v(600), DurationFilter.SHORT, DateFilter.ANY, false, now))
        assertTrue(SearchRefine.matches(v(600), DurationFilter.MEDIUM, DateFilter.ANY, false, now))
        assertTrue(SearchRefine.matches(v(3600), DurationFilter.LONG, DateFilter.ANY, false, now))
        // unknown duration is kept
        assertTrue(SearchRefine.matches(v(0), DurationFilter.LONG, DateFilter.ANY, false, now))
        // live never matches a length filter
        assertFalse(SearchRefine.matches(v(0, live = true), DurationFilter.SHORT, DateFilter.ANY, false, now))
    }

    @Test fun dateAndLive() {
        assertTrue(SearchRefine.matches(v(100, ageDays = 3), DurationFilter.ANY, DateFilter.WEEK, false, now))
        assertFalse(SearchRefine.matches(v(100, ageDays = 10), DurationFilter.ANY, DateFilter.WEEK, false, now))
        assertTrue(SearchRefine.matches(v(100, ageDays = -1), DurationFilter.ANY, DateFilter.TODAY, false, now))
        assertFalse(SearchRefine.matches(v(0, live = true), DurationFilter.ANY, DateFilter.ANY, true, now))
    }

    @Test fun continueWatching() {
        val h = listOf(
            HistoryEntry(v(600, url = "a"), 3, positionMs = 120_000),   // in progress
            HistoryEntry(v(600, url = "b"), 2, positionMs = 590_000),   // finished
            HistoryEntry(v(600, url = "c"), 1, positionMs = 5_000),     // barely started
            HistoryEntry(v(600, url = "a"), 0, positionMs = 100_000),   // duplicate
        )
        assertEquals(listOf("a"), ContinueWatching.pick(h).map { it.video.url })
    }
}
