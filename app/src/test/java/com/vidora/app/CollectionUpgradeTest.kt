package com.vidora.app

import com.vidora.app.data.*
import com.vidora.app.download.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CollectionUpgradeTest {
    private val short = VideoItem("short", "Alpha Piano", uploader = "Calm Creator", duration = 239)
    private val medium = VideoItem("medium", "Beta Piano", uploader = "Another Creator", duration = 240)
    private val long = VideoItem("long", "Gamma", duration = 1200)
    private val unknown = VideoItem("unknown", "Unknown")
    private val live = VideoItem("live", "Live", duration = 4000, isLive = true)
    private val all = listOf(unknown, long, medium, short, live)

    @Test fun collectionSearchCombinesTitleCreatorTokensCaseInsensitively() {
        assertEquals(listOf(short), CollectionTools.videos(all, "  PIANO   calm "))
        assertTrue(CollectionTools.videos(all, "no-match").isEmpty())
        assertEquals(all, CollectionTools.videos(all, "  "))
    }
    @Test fun lengthBoundariesAreNonOverlappingAndUnknownsAreNotInvented() {
        assertEquals(listOf(short), CollectionTools.videos(all, length = CollectionLength.SHORT))
        assertEquals(listOf(medium), CollectionTools.videos(all, length = CollectionLength.MEDIUM))
        assertEquals(listOf(long), CollectionTools.videos(all, length = CollectionLength.LONG))
        assertEquals(listOf(medium.copy(duration = 1199)), CollectionTools.videos(listOf(medium.copy(duration = 1199)), length = CollectionLength.MEDIUM))
    }
    @Test fun sortsPreserveSourceAndKeepUnknownDurationsAtTheEnd() {
        assertEquals(short, CollectionTools.videos(all, order = CollectionOrder.SHORTEST).first())
        assertEquals(long, CollectionTools.videos(all, order = CollectionOrder.LONGEST).first())
        assertEquals(unknown, all.first())
        assertEquals(listOf(short, medium, long, live, unknown), CollectionTools.videos(all, order = CollectionOrder.TITLE))
    }
    @Test fun queueMovesAreBoundedAndDoNotDropVideos() {
        assertEquals(all, CollectionTools.move(all, -1, 3))
        assertEquals(all, CollectionTools.move(all, 0, all.size))
        val moved = CollectionTools.move(all, 4, 0)
        assertEquals(live, moved.first())
        assertEquals(all.toSet(), moved.toSet())
    }
    @Test fun shuffleOnlyChangesUpcomingTail() {
        val shuffled = CollectionTools.shuffleUpcoming(all, 1, Random(17))
        assertEquals(all.take(2), shuffled.take(2))
        assertEquals(all.drop(2).toSet(), shuffled.drop(2).toSet())
        assertEquals(all, CollectionTools.shuffleUpcoming(all, -1, Random(17)))
        assertEquals(all, CollectionTools.shuffleUpcoming(all, all.lastIndex, Random(17)))
    }
    private fun record(id: String, status: DownloadStatus, kind: DownloadKind = DownloadKind.VIDEO,
                       size: Long = 100, time: Long = 1) = DownloadRecord(id, short, kind, qualityLabel = "720p", fileName = "$id.mp4", status = status, totalBytes = size, createdAt = time)
    @Test fun dashboardFiltersStatusKindQueryTogether() {
        val failed = record("failed", DownloadStatus.FAILED)
        val saved = record("saved", DownloadStatus.COMPLETED, DownloadKind.AUDIO)
        val active = record("active", DownloadStatus.MUXING)
        val records = listOf(failed, saved, active)
        assertEquals(listOf(failed), DownloadDashboard.filter(records, "failed piano", view = DownloadView.FAILED))
        assertEquals(listOf(saved), DownloadDashboard.filter(records, kind = DownloadKind.AUDIO))
        assertEquals(listOf(active), DownloadDashboard.filter(records, view = DownloadView.ACTIVE))
        assertTrue(DownloadDashboard.filter(records, kind = DownloadKind.AUDIO, view = DownloadView.FAILED).isEmpty())
    }
    @Test fun dashboardSizeExcludesEstimatesForUnfinishedWork() {
        assertEquals(200L, DownloadDashboard.completedBytes(listOf(record("ok", DownloadStatus.COMPLETED, size = 200),
            record("running", DownloadStatus.RUNNING, size = 900), record("bad-size", DownloadStatus.COMPLETED, size = -1))))
        assertTrue(DownloadDashboard.retryable(DownloadStatus.CANCELLED))
        assertFalse(DownloadDashboard.retryable(DownloadStatus.RUNNING))
        assertFalse(DownloadDashboard.active(DownloadStatus.COMPLETED))
    }
    @Test fun dashboardOrderIsStableAndDoesNotMutateStore() {
        val first = record("one", DownloadStatus.FAILED, size = 200, time = 1)
        val second = record("two", DownloadStatus.COMPLETED, size = 100, time = 2)
        assertEquals(listOf(second, first), DownloadDashboard.filter(listOf(first, second)))
        assertEquals(listOf(first, second), DownloadDashboard.filter(listOf(first, second), order = DownloadOrder.LARGEST))
    }
    @Test fun oldLibraryBackupsLoadWithoutBookmarksAndNewOnesRoundTrip() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(LibraryData.serializer(), "{\"history\":[],\"playlists\":[]}").bookmarks.isEmpty())
        val saved = LibraryData(bookmarks = listOf(PlaybackBookmark("moment", short, 42_500, "A useful moment", 123)))
        assertEquals(saved, json.decodeFromString(LibraryData.serializer(), json.encodeToString(LibraryData.serializer(), saved)))
        assertTrue(saved.history.isEmpty()) // a saved timestamp never fabricates measured playback
    }
}
