package com.vidora.app

import com.vidora.app.data.*
import com.vidora.app.player.PlaybackRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EngineeringTest {
    private fun row(id: String, creator: String = id) = Entry.Video(VideoItem("https://example.org/$id", id, uploader = creator))
    @Test fun fastProviderAppearsBeforeSlowAndDoesNotJump() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first = CompletableDeferred<PageResult>()
        val repo = DiscoveryRepository(youtubeSearch = { _, _ -> gate.await(); PageResult(listOf(row("slow"))) },
            peerSearch = { PageResult(listOf(row("fast"))) }, commonsSearch = { PageResult(emptyList()) })
        val pages = mutableListOf<PageResult>()
        val job = launch { repo.searchUpdates("exact query").collect { pages += it; if (it.items.isNotEmpty()) first.complete(it) } }
        assertEquals("fast", (withTimeout(2000) { first.await() }.items.first() as Entry.Video).video.title)
        assertFalse(job.isCompleted)
        gate.complete(Unit); job.join()
        assertEquals(listOf("fast", "slow"), pages.last().items.map { (it as Entry.Video).video.title })
        assertFalse(pages.last().pending)
    }
    @Test fun cancellationReachesProvider() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val repo = DiscoveryRepository(youtubeSearch = { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        val job = launch { repo.searchUpdates("demo", VideoSource.YOUTUBE).collect() }
        started.await(); job.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }
    @Test fun providerRetryDoesNotRerunHealthySources() = runBlocking {
        var youtube = 0; var peers = 0
        val repo = DiscoveryRepository(youtubeSearch = { _, _ -> youtube++; if (youtube == 1) error("down") else PageResult(listOf(row("yt"))) },
            peerSearch = { peers++; PageResult(listOf(row("peer"))) }, commonsSearch = { PageResult(emptyList()) })
        val p = repo.search("demo")
        assertEquals(1, p.retries.size)
        assertEquals(1, p.retries.single().load().items.size)
        assertEquals(1, peers); assertEquals(2, youtube)
    }
    @Test fun circuitCooldownAndRetryAfterAreBounded() {
        var now = 1000L; val health = ProviderHealth { now }
        repeat(2) { health.failure("host") }; assertEquals(0, health.remaining("host"))
        health.failure("host"); assertEquals(30_000, health.remaining("host"))
        now += 30_001; assertEquals(0, health.remaining("host"))
        health.success("host"); health.failure("host", 60_000); assertEquals(60_000, health.remaining("host"))
        assertEquals(120_000, ProviderHealth.retryAfter("120"))
        assertEquals(0, ProviderHealth.retryAfter("invalid"))
        assertEquals(500, ProviderHealth.backoff(0, 0)); assertEquals(16_250, ProviderHealth.backoff(50, 250))
    }
    @Test fun homeDiversityHiddenAndWatchedRanking() {
        val s = AppSettings(homeInterests = setOf("science"), lessLikeVideos = setOf("https://example.org/hidden"))
        val rows = listOf(row("science", "A"), row("other", "A"), row("nature", "B"), row("hidden"))
        val ranked = HomeRanking.rank(rows, s, emptySet())
        assertEquals(listOf("science", "nature", "other"), ranked.map { (it as Entry.Video).video.title })
        assertTrue(HomeRanking.score(VideoItem("v", "science"), s, emptySet(), 0) > HomeRanking.score(VideoItem("v", "science"), s, setOf("v"), 0))
    }
    @Test fun searchIgnoresHomePreferences() = runBlocking {
        val queries = mutableListOf<String>()
        DiscoveryRepository(youtubeSearch = { q, _ -> queries += q; PageResult(emptyList()) }).search("বাংলা query", VideoSource.YOUTUBE)
        assertEquals(listOf("বাংলা query"), queries)
    }
    @Test fun serializedEntriesRoundTripWithoutRuntimeActions() {
        val rows = listOf(row("video"), Entry.Channel(ChannelItem("c", "creator")), Entry.Playlist(PlaylistItem("p", "playlist")))
        assertEquals(rows, Json.decodeFromString<List<Entry>>(Json.encodeToString(rows)))
    }
    @Test fun diagnosticsNeverAcceptArbitraryPrivateStrings() {
        Diagnostics.clear(); Diagnostics.record("https://secret?token=abc", "cookie=SAPISID"); val report = Diagnostics.report()
        assertFalse(report.contains("SAPISID")); assertFalse(report.contains("token=abc")); assertTrue(report.contains("redacted"))
        repeat(100) { Diagnostics.record("test", "ok") }; assertTrue(Diagnostics.report().lines().size <= 83)
    }
    @Test fun recoveryDoesNotHammerRestrictedOrMissingMedia() {
        for (code in listOf(401, 404, 410, 451)) assertEquals(PlaybackRecovery.Action.STOP, PlaybackRecovery.decide(code, false, 0))
        assertEquals(PlaybackRecovery.Action.REFRESH, PlaybackRecovery.decide(403, false, 0))
        assertEquals(PlaybackRecovery.Action.STOP, PlaybackRecovery.decide(403, false, 1))
        assertEquals(PlaybackRecovery.Action.PAUSE, PlaybackRecovery.decide(429, false, 0))
        assertEquals(PlaybackRecovery.Action.LOWER_QUALITY, PlaybackRecovery.decide(null, true, 0))
        assertEquals(PlaybackRecovery.Action.STOP, PlaybackRecovery.decide(null, true, 1))
        assertEquals(PlaybackRecovery.Action.STOP, PlaybackRecovery.decide(503, false, 2))
    }
}
