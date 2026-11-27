package com.vidora.app

import com.vidora.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GuestSourcesTest {
    private val peerUrl = "https://tilvids.com/videos/watch/3620cd16-97f4-488c-a018-a6897baddbc4"
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun row(id: String) = Entry.Video(VideoItem("https://example.org/$id", id))
    private fun peer(extra: String = "") = obj("""{
        "url":"$peerUrl","name":"Demo","privacy":{"id":1},"nsfw":false,"duration":42,
        "channel":{"displayName":"Creator","url":"https://tilvids.com/video-channels/demo"},
        "licence":{"label":"CC BY 4.0"},"files":[],"streamingPlaylists":[] $extra
    }""")
    private fun commons() = obj("""{"title":"File:Demo + river.webm","videoinfo":[{
        "mime":"video/webm","duration":12.5,"height":720,"width":1280,
        "url":"https://upload.wikimedia.org/demo.webm",
        "extmetadata":{"Artist":{"value":"<b>Creator</b>"},"LicenseShortName":{"value":"CC BY-SA 4.0"},
          "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"},"Attribution":{"value":"Credit Creator"}},
        "derivatives":[{"src":"https://upload.wikimedia.org/demo.480p.webm","type":"video/webm; codecs=vp9","height":480,"width":854,"bandwidth":12345},
          {"src":"http://bad.example/video.webm","type":"video/webm","height":1080},
          {"src":"https://evil.example/video.webm","type":"video/webm","height":1080},
          {"src":"https://upload.wikimedia.org/demo.ogv","type":"video/ogg","height":360}]
    }]}""")

    @Test fun providerRoutingRejectsSpoofedOrUnsafeOrigins() {
        assertTrue(GuestSources.isPeer(peerUrl))
        assertFalse(GuestSources.isPeer(peerUrl.replace("tilvids.com", "tilvids.com.evil.test")))
        assertFalse(GuestSources.isPeer(peerUrl.replace("https:", "http:")))
        assertFalse(GuestSources.isPeer(peerUrl.replace("tilvids.com", "user@tilvids.com")))
        assertFalse(GuestSources.isPeer(peerUrl.replace("tilvids.com", "tilvids.com:8443")))
        assertFalse(GuestSources.isPeer(peerUrl.replace("watch/", "watch/not-a-video/")))
        assertTrue(GuestSources.isPeerChannel("https://tilvids.com/video-channels/demo"))
        assertFalse(GuestSources.isPeerChannel("https://evil.test/video-channels/demo"))
        assertFalse(GuestSources.supported("https://youtube.com/watch?v=abcdefghijk"))
    }

    @Test fun commonsUrlsRoundTripWithoutLosingLiteralPlus() {
        val title = "File:Bangla + river & nature.webm"
        assertEquals(title.replace(' ', '_'), GuestSources.commonsTitle(GuestSources.commonsUrl(title)))
        assertFalse(GuestSources.isCommons("https://commons.wikimedia.org.evil.test/wiki/File:Demo.webm"))
        assertFalse(GuestSources.isCommons("https://commons.wikimedia.org/wiki/Category:Video"))
        assertFalse(GuestSources.isCommons("https://user@commons.wikimedia.org/wiki/File:Demo.webm"))
    }

    @Test fun countryValidationAndDeduplication() {
        assertEquals("BD", GuestSources.country("bd"))
        assertNull(GuestSources.country("XX"))
        assertEquals("Bangladesh", GuestSources.countryName("BD"))
        assertEquals("Bangladesh nature", GuestSources.regionalQuery("BD", "nature"))
        assertEquals(listOf("a", "c", "b"), GuestSources.interleave(listOf(listOf(row("a"), row("b")), listOf(row("c"), row("a")))).map { (it as Entry.Video).video.title })
        assertTrue(GuestSources.interleave(emptyList()).isEmpty())
    }

    @Test fun peerRejectsPrivateSensitiveAndPasswordVideos() {
        val p = peer()
        assertNotNull(OpenVideoParser.peerItem("https://tilvids.com", p))
        assertNull(OpenVideoParser.peerItem("https://tilvids.com", JsonObject(p + ("privacy" to obj("""{"id":3}""")))))
        assertNull(OpenVideoParser.peerItem("https://tilvids.com", JsonObject(p + ("nsfw" to JsonPrimitive(true)))))
        assertNull(OpenVideoParser.peerItem("https://tilvids.com", JsonObject(p + ("requiresPassword" to JsonPrimitive(true)))))
    }

    @Test fun peerSupportsHlsWithoutProgressiveFiles() {
        val d = OpenVideoParser.peerDetails("https://tilvids.com", peer(""", "streamingPlaylists":[{"playlistUrl":"https://tilvids.com/live/master.m3u8","files":[]}]"""))
        assertEquals("PeerTube", d.item.platform)
        assertEquals("https://tilvids.com/live/master.m3u8", d.hlsUrl)
        assertTrue(d.preferHls)
        assertTrue(d.videoStreams.isEmpty())
        assertTrue(d.description.contains("CC BY 4.0"))
    }

    @Test(expected = IllegalStateException::class) fun peerMissingPublicStreamHasClearFailure() {
        OpenVideoParser.peerDetails("https://tilvids.com", peer())
    }

    @Test fun commonsUsesDocumentedDerivativesAndKeepsAttribution() {
        val d = OpenVideoParser.commonsDetails(commons())
        assertEquals("Commons", d.item.platform)
        assertEquals("Creator", d.item.uploader)
        assertEquals(1, d.videoStreams.size)
        assertEquals(480, d.videoStreams.single().height)
        assertFalse(d.videoStreams.single().videoOnly)
        assertTrue(d.description.contains("CC BY-SA 4.0"))
        assertTrue(d.description.contains("Credit Creator"))
        assertTrue(d.description.contains("https://creativecommons.org/licenses/by-sa/4.0/"))
        assertTrue(d.description.contains(d.item.url))
    }

    @Test fun commonsRejectsMissingLicenceAndAudio() {
        val page = commons()
        val info = page["videoinfo"]!!.jsonArray.first().jsonObject
        fun withInfo(i: JsonObject) = JsonObject(page + ("videoinfo" to JsonArray(listOf(i))))
        assertNull(OpenVideoParser.commonsItem(withInfo(JsonObject(info - "extmetadata"))))
        assertNull(OpenVideoParser.commonsItem(withInfo(JsonObject(info + ("mime" to JsonPrimitive("audio/ogg"))))))
    }

    @Test fun typedSearchIsUnmodifiedAndSurvivesProviderFailure() = runBlocking {
        val received = mutableListOf<String>()
        val repo = DiscoveryRepository(
            youtubeSearch = { q, _ -> received += q; error("blocked") },
            peerSearch = { q -> received += q!!; PageResult(listOf(row("peer"))) },
            commonsSearch = { q -> received += q; PageResult(listOf(row("commons"))) },
        )
        val p = repo.search("বাংলা cooking 2026")
        assertEquals(listOf("বাংলা cooking 2026", "বাংলা cooking 2026", "বাংলা cooking 2026"), received)
        assertEquals(2, p.items.size)
        assertEquals(1, p.notices.size)
    }

    @Test fun publicAlternativeSearchNeverCallsYoutube() = runBlocking {
        val repo = DiscoveryRepository(
            youtubeSearch = { _, _ -> fail("Must not call YouTube"); PageResult(emptyList()) },
            peerSearch = { PageResult(listOf(row("peer"))) }, commonsSearch = { PageResult(emptyList()) },
        )
        assertEquals(1, repo.search("demo", VideoSource.PUBLIC).items.size)
        val unsupported = repo.search("demo", VideoSource.COMMONS, SearchFilter.CHANNELS)
        assertTrue(unsupported.items.isEmpty())
        assertTrue(unsupported.notices.isNotEmpty())
    }

    @Test fun homeAddsCountryTermsButNotToGeneralPeerFeed() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = DiscoveryRepository(
            youtubeSearch = { q, _ -> calls += q; PageResult(emptyList()) },
            commonsSearch = { q -> calls += q; PageResult(emptyList()) },
            peerHome = { calls += "global"; PageResult(emptyList()) },
        )
        repo.home("BD")
        assertEquals(listOf("Bangladesh music news culture", "Bangladesh", "global"), calls)
    }

    @Test fun continuationKeepsOtherProviderResultsOnFailure() = runBlocking {
        val repo = DiscoveryRepository(
            youtubeSearch = { _, _ -> PageResult(listOf(row("yt")), { error("blocked later") }) },
            peerSearch = { PageResult(listOf(row("p1")), { PageResult(listOf(row("p2"))) }) },
            commonsSearch = { PageResult(emptyList()) },
        )
        val next = repo.search("demo").next!!.invoke()
        assertEquals(listOf(row("p2")), next.items)
        assertTrue(next.notices.single().contains("YouTube"))
    }

    @Test(expected = IllegalStateException::class) fun allSourcesFailDoesNotPretendToBeEmpty() = runBlocking {
        DiscoveryRepository(youtubeSearch = { _, _ -> error("down") }, peerSearch = { error("down") }, commonsSearch = { error("down") }).search("demo")
        Unit
    }

    @Test(expected = CancellationException::class) fun cancellationIsNotSwallowed() = runBlocking {
        DiscoveryRepository(youtubeSearch = { _, _ -> throw CancellationException("cancel") }).search("demo", VideoSource.YOUTUBE)
        Unit
    }
}
