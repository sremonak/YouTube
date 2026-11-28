package com.vidora.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidora.app.data.TvPlayer
import com.vidora.app.data.YtClient
import com.vidora.app.data.YtComments
import com.vidora.app.jsc.JsChallengeSolver
import com.vidora.app.player.StreamInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-network checks of the playback fallbacks on an emulator (real WebView for the EJS solver).
 * Run by .github/workflows/e2e.yml; every step is logged with tag "E2E" and surfaced as CI notices.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackE2ETest {
    private val id = "dQw4w9WgXcQ"
    private val http by lazy { Graph.http.newBuilder().addInterceptor(StreamInterceptor()).build() }

    private fun log(msg: String) = Log.i("E2E", msg)

    private fun get(url: String, range: Boolean = true): Pair<Int, Int> {
        val b = Request.Builder().url(url)
        if (range) b.header("Range", "bytes=0-4095")
        return http.newCall(b.build()).execute().use { it.code to (it.body?.bytes()?.size ?: 0) }
    }

    private fun waitFor(at: Long) {
        val left = at - System.currentTimeMillis()
        if (left > 0) Thread.sleep(left)
    }

    @Test fun ejsSolver() {
        val p = JsChallengeSolver.player()
        log("player=${p.id} sts=${p.sts}")
        assertNotNull(p.sts)
        // Solve a dummy n challenge just to exercise the WebView engine end to end.
        val (_, ns) = JsChallengeSolver.solve(emptyList(), listOf("AbCdEfGhIjKlMnOp"))
        log("ejs n=${ns["AbCdEfGhIjKlMnOp"]} status=${JsChallengeSolver.lastStatus}")
        assertTrue(ns.isNotEmpty())
    }

    @Test fun tvClient() {
        val r = TvPlayer.fetch(id, signedIn = false, client = YtClient.TV)
        val wait = r.availableAt - System.currentTimeMillis()
        log("tv videos=${r.videos.size} audios=${r.audios.size} wait=${wait}ms solver=${JsChallengeSolver.lastStatus}")
        assertTrue("no audio formats", r.audios.isNotEmpty())
        val early = get(r.audios.first().url)
        waitFor(r.availableAt)
        val audio = get(r.audios.first().url)
        val video = r.videos.firstOrNull { it.videoOnly }?.let { get(it.url) }
        log("tv early=$early audio=$audio video=$video")
        assertEquals(206, audio.first)
    }

    @Test fun safariHls() {
        val r = TvPlayer.fetch(id, signedIn = false, client = YtClient.WEB_SAFARI)
        log("safari hls=${r.hlsUrl != null} videos=${r.videos.size}")
        val master = assertNotNullAnd(r.hlsUrl, "no hls")
        val text = http.newCall(Request.Builder().url(master).header("User-Agent", YtClient.WEB_SAFARI.userAgent).build()).execute().use { it.body!!.string() }
        val variant = text.lines().last { it.startsWith("http") }
        val pl = http.newCall(Request.Builder().url(variant).header("User-Agent", YtClient.WEB_SAFARI.userAgent).build()).execute().use { it.body!!.string() }
        val seg = pl.lines().first { it.startsWith("http") }
        val res = get(seg, range = false)
        log("safari segment=$res")
        assertEquals(200, res.first)
    }

    @Test fun mobileWeb() {
        val r = TvPlayer.fetch(id, signedIn = false, client = YtClient.MWEB)
        log("mweb videos=${r.videos.map { it.itag }}")
        val v = r.videos.first()
        waitFor(r.availableAt)
        val res = get(v.url)
        log("mweb itag=${v.itag} get=$res")
        assertEquals(206, res.first)
    }

    @Test fun comments() {
        val page = runBlocking { YtComments.first(id) }
        log("comments items=${page.items.size} total=${page.totalCount} first=${page.items.firstOrNull()?.let { it.author + ": " + it.text.take(40) }}")
        assertTrue(page.items.isNotEmpty())
    }

    @Test fun repositoryChain() {
        val d = runBlocking { Graph.repository.stream("https://www.youtube.com/watch?v=$id", forceRefresh = true) }
        log("repository source=${d.source} videos=${d.videoStreams.size} audios=${d.audioStreams.size} hls=${d.hlsUrl != null} related=${d.relatedVideos.size}")
        assertTrue(d.videoStreams.isNotEmpty() || d.hlsUrl != null)
    }

    @Test fun publicShortsFeed() = runBlocking {
        val page = com.vidora.app.data.ShortsRepository().load()
        val videos = page.items.filterIsInstance<com.vidora.app.data.Entry.Video>()
        log("shorts candidates=${videos.size} continuation=${page.next != null}")
        assertTrue("YouTube returned no playable Shorts candidates", videos.isNotEmpty())
    }

    @Test fun publicSubscriptionUploads() = runBlocking {
        val channel = com.vidora.app.data.ChannelItem("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw", "Public fixture channel")
        val page = com.vidora.app.data.SubscriptionFeed().load(listOf(channel))
        log("subscription uploads=${page.items.size} notices=${page.notices.size}")
        assertTrue("Public subscribed-channel uploads were empty", page.items.isNotEmpty())
    }

    private fun <T : Any> assertNotNullAnd(v: T?, msg: String): T {
        assertNotNull(msg, v)
        return v!!
    }
}
