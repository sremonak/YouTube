package com.vidora.app

import com.vidora.app.account.*
import com.vidora.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncAndFeedsTest {
    private fun video(id: String, short: Boolean = false) = Entry.Video(VideoItem("https://www.youtube.com/watch?v=$id", id, isShort = short))

    @Test fun transportAcknowledgementIsNotConfirmedHistory() = runBlocking {
        var sends = 0
        val result = HistoryDelivery(send = { sends++ }, verify = { false }).deliver()
        assertEquals(1, sends)
        assertFalse(result.confirmed)
        assertNull(result.verificationError)
        assertTrue(HistoryDelivery(send = {}, verify = { true }).deliver().confirmed)
    }

    @Test fun verificationFailureIsNotPretendedToBeDeliveryFailure() = runBlocking {
        val result = HistoryDelivery(send = {}, verify = { error("session rejected") }).deliver()
        assertFalse(result.confirmed)
        assertEquals("session rejected", result.verificationError)
    }

    @Test fun historyRejectAndCancellationDoNotReportSuccess() = runBlocking {
        try { HistoryDelivery(send = { throw java.io.IOException("HTTP 403") }, verify = { fail("must not verify"); true }).deliver(); fail("expected failure") }
        catch (e: java.io.IOException) { assertEquals("HTTP 403", e.message) }
        try { HistoryDelivery(send = {}, verify = { throw CancellationException("privacy changed") }).deliver(); fail("expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun shortsUsesDedicatedBrowseWithoutSearchingAndPreservesNext() = runBlocking {
        val repo = ShortsRepository(browse = { PageResult(listOf(video("short", true)), next = { PageResult(listOf(video("next", true))) }) }, search = { error("Unexpected search") })
        val first = repo.load()
        assertEquals("short", (first.items.single() as Entry.Video).video.title)
        assertEquals("next", (first.next!!.invoke().items.single() as Entry.Video).video.title)
    }

    @Test fun shortsFallbackAdvancesEmptyPagesAndDoesNotSwallowCancellation() = runBlocking {
        val repo = ShortsRepository(browse = { error("unsupported") }, search = { PageResult(emptyList(), next = { PageResult(listOf(video("found", true))) }) })
        assertEquals(1, repo.load().items.size)
        try { ShortsRepository(browse = { throw CancellationException("left screen") }, search = { fail("must not fall back"); PageResult(emptyList()) }).load(); fail("expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun recommendationsAdvanceServerPagesWhenFirstIsUnchanged() = runBlocking {
        var reads = 0
        fun page(number: Int): PageResult = PageResult(listOf(video("$number")), next = { reads++; page(number + 1) })
        val feed = RecommendationFeed { AccountPage(page(1), emptyList()) }
        assertEquals("1", (feed.load().page.items.first() as Entry.Video).video.title)
        assertEquals("2", (feed.load().page.items.first() as Entry.Video).video.title)
        assertEquals("3", (feed.load().page.items.first() as Entry.Video).video.title)
        assertEquals(2, reads)
    }

    @Test fun subscriptionFailuresAreNotAnEmptySuccessfulFeed() = runBlocking {
        val channels = listOf(ChannelItem("good", "Good"), ChannelItem("bad", "Bad"))
        val feed = SubscriptionFeed { if (it == "bad") error("blocked") else listOf(VideoItem("video", "A video")) }
        val page = feed.load(channels)
        assertEquals(1, page.items.size)
        assertEquals("good", (page.items.single() as Entry.Video).video.uploaderUrl)
        assertTrue(page.notices.single().contains("Bad"))
        try { feed.load(channels.takeLast(1)); fail("expected visible failure") } catch (_: java.io.IOException) { }
    }

    @Test fun shortsHistoryDeadlineFitsBeforeTheVideoEnds() {
        assertTrue(WatchHistoryPolicy.delayFor(2) < 2_000)
        assertEquals(3_000L, WatchHistoryPolicy.delayFor(60))
        val old = kotlinx.serialization.json.Json.decodeFromString(HistoryEntry.serializer(), """{"video":{"url":"video","title":"Old"},"watchedAt":1,"positionMs":99000}""")
        assertEquals(0.0, old.actualWatchSeconds, 0.0) // no fabricated retrospective watch time
    }
}
