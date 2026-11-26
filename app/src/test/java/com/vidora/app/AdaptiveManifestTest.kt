package com.vidora.app

import com.vidora.app.data.AudioOption
import com.vidora.app.data.VideoOption
import com.vidora.app.player.AdaptiveManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveManifestTest {

    private fun v(itag: Int, h: Int, codec: String, ranges: Boolean = true, mime: String = "video/mp4") = VideoOption(
        url = "https://rr1.googlevideo.com/videoplayback?itag=$itag&x=1", itag = itag, height = h, fps = 30,
        resolution = "${h}p", mime = mime, suffix = if (mime.endsWith("webm")) "webm" else "mp4", codec = codec,
        videoOnly = true, bitrate = h * 2000, contentLength = 1000,
        initRange = if (ranges) "0-740" else null, indexRange = if (ranges) "741-1500" else null,
    )

    private fun a(itag: Int, kbps: Int, suffix: String = "m4a") = AudioOption(
        url = "https://rr1.googlevideo.com/videoplayback?itag=$itag", itag = itag, bitrateKbps = kbps,
        mime = if (suffix == "m4a") "audio/mp4" else "audio/webm", suffix = suffix,
        codec = if (suffix == "m4a") "mp4a.40.2" else "opus", trackName = null, contentLength = 100,
        initRange = "0-600", indexRange = "601-900",
    )

    @Test
    fun buildsAvcLadderUnderCap() {
        val plan = AdaptiveManifest.plan(
            listOf(v(137, 1080, "avc1.640028"), v(136, 720, "avc1.4d401f"), v(134, 360, "avc1.4d401e"),
                v(248, 1080, "vp9", mime = "video/webm"), v(160, 144, "avc1.4d400c")),
            listOf(a(140, 128), a(139, 48), a(251, 160, "webm")),
            durationSec = 212, maxHeight = 720,
        )
        assertNotNull(plan)
        plan!!
        assertEquals(listOf(144, 360, 720), plan.videos.map { it.height })
        assertEquals(listOf(139, 140), plan.audios.map { it.itag })
        assertTrue(plan.xml.contains("indexRange=\"741-1500\""))
        assertTrue(plan.xml.contains("&amp;x=1"))
        assertFalse(plan.xml.contains("vp9"))
        assertTrue(plan.xml.contains("mediaPresentationDuration=\"PT212.000S\""))
    }

    @Test
    fun noRangesMeansProgressiveFallback() {
        assertNull(AdaptiveManifest.plan(listOf(v(137, 1080, "avc1", ranges = false)), listOf(a(140, 128)), 100, 1080))
        assertNull(AdaptiveManifest.plan(listOf(v(137, 1080, "avc1")), listOf(a(140, 128)), 0, 1080))
    }
}
