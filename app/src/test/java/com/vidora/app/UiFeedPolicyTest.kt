package com.vidora.app

import com.vidora.app.data.*
import com.vidora.app.account.WatchHistoryPolicy
import com.vidora.app.ui.theme.accentForeground
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UiFeedPolicyTest {
    @Test fun refreshChangesCandidateQueryAndCyclesEveryInterest() {
        val planner = FeedFreshness { 0L }
        val settings = AppSettings(homeInterests = setOf("science", "music", "nature"))
        val plans = List(3) { planner.next("BD", settings) }
        assertEquals(setOf("science", "music", "nature"), plans.map { it.topic }.toSet())
        assertEquals(3, plans.map { it.query }.distinct().size)
        assertTrue(plans.all { it.query.contains("after:") })
    }
    @Test fun freshCacheSurvivesQuickSettingsReturnButRevalidatesLater() {
        assertFalse(FeedFreshness.stale(1000, 1001))
        assertTrue(FeedFreshness.stale(1000, 1000 + FeedFreshness.REVALIDATE_MS))
        assertTrue(FeedFreshness.stale(1000, 0))
    }
    @Test fun refreshFetchesMoreCandidatesWithoutChangingSearch() = runBlocking {
        var more = 0
        val queries = mutableListOf<String>()
        val repo = DiscoveryRepository(youtubeSearch = { q, _ ->
            queries += q
            PageResult(listOf(Entry.Video(VideoItem("old", "old"))), next = { more++; PageResult(listOf(Entry.Video(VideoItem("new", "new")))) })
        }, commonsSearch = { PageResult(emptyList()) }, peerSearch = { PageResult(emptyList()) }, peerHome = { PageResult(emptyList()) }, freshness = FeedFreshness { 0L })
        assertEquals(1, repo.home("US").items.size)
        assertEquals(2, repo.home("US").items.size)
        assertEquals(1, more)
        repo.search("unchanged query", VideoSource.YOUTUBE)
        assertEquals("unchanged query", queries.last())
    }
    @Test fun historyNeverUploadsForGuestIncognitoOrDisabledSync() {
        assertTrue(WatchHistoryPolicy.eligible(true, true, false, true))
        assertFalse(WatchHistoryPolicy.eligible(false, true, false, true))
        assertFalse(WatchHistoryPolicy.eligible(true, true, true, true))
        assertFalse(WatchHistoryPolicy.eligible(true, false, false, true))
        assertFalse(WatchHistoryPolicy.eligible(true, true, false, false))
        assertTrue(WatchHistoryPolicy.allowedTrackingUrl("https", "s.youtube.com", "/api/stats/watchtime"))
        assertFalse(WatchHistoryPolicy.allowedTrackingUrl("https", "attacker.example", "/api/stats/watchtime"))
        assertFalse(WatchHistoryPolicy.allowedTrackingUrl("http", "www.youtube.com", "/api/stats/playback"))
        assertEquals(0.0, WatchHistoryPolicy.seconds(Double.NaN), 0.0)
        assertEquals(15.0, WatchHistoryPolicy.seconds(15.0), 0.0)
    }
    @Test fun oversizedBackupIsRejectedBeforeParsing() {
        assertEquals("{}", BackupFiles.readText("{}".byteInputStream(), 2))
        assertThrows(IllegalArgumentException::class.java) { BackupFiles.readText("too large".byteInputStream(), 2) }
    }
    @Test fun customAccentForegroundMeetsNormalTextContrast() {
        listOf(Color.Yellow, Color.White, Color.Black, Color.Red, Color.Blue, Color.Green, Color(0xFFBF1E5A)).forEach { bg ->
            val fg = accentForeground(bg)
            val ratio = (maxOf(fg.luminance(), bg.luminance()) + 0.05f) / (minOf(fg.luminance(), bg.luminance()) + 0.05f)
            assertTrue("Contrast for $bg was $ratio", ratio >= 4.5f)
        }
    }
}
