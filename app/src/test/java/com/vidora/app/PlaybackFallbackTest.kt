package com.vidora.app

import com.vidora.app.data.Chapters
import com.vidora.app.data.PlayabilityException
import com.vidora.app.data.TvPlayer
import com.vidora.app.data.YtComments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFallbackTest {
    @Test fun signatureCipherQueryIsDecoded() {
        val q = TvPlayer.parseQuery("s=AB%3DCD&sp=sig&url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fitag%3D18")
        assertEquals("AB=CD", q["s"])
        assertEquals("sig", q["sp"])
        assertEquals("https://r1.googlevideo.com/videoplayback?itag=18", q["url"])
    }

    @Test fun chaptersFromDescription() {
        val d = """
            Great video
            0:00 Intro
            1:05 - Setup
            (12:30) Deep dive
            1:02:03 Outro
        """.trimIndent()
        val c = Chapters.fromDescription(d)
        assertEquals(listOf("Intro", "Setup", "Deep dive", "Outro"), c.map { it.title })
        assertEquals(listOf(0, 65, 750, 3723), c.map { it.startSeconds })
        // Not starting at 0:00 or fewer than 3 → no chapters (YouTube's rule)
        assertTrue(Chapters.fromDescription("1:00 a\n2:00 b\n3:00 c").isEmpty())
        assertTrue(Chapters.fromDescription("0:00 a\n2:00 b").isEmpty())
    }

    @Test fun queryParamHelpers() {
        val u = "https://r1.googlevideo.com/videoplayback?expire=1&mn=sn-abc&n=AbC_d-1&ns=xyz&sparams=n"
        assertEquals("AbC_d-1", TvPlayer.queryParam(u, "n"))
        assertNull(TvPlayer.queryParam(u, "pot"))
        val r = TvPlayer.replaceQueryParam(u, "n", "solved123")
        assertEquals("https://r1.googlevideo.com/videoplayback?expire=1&mn=sn-abc&n=solved123&ns=xyz&sparams=n", r)
    }

    @Test fun pendingUrlNeedsBothChallengesSolved() {
        val f = Json.parseToJsonElement(
            """{"itag":140,"signatureCipher":"s=SIG1&sp=sig&url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fitag%3D140%26n%3DNCH%26c%3DTVHTML5"}"""
        ).jsonObject
        val p = TvPlayer.PendingUrl.of(f)!!
        assertEquals("SIG1", p.sig)
        assertEquals("sig", p.sp)
        assertEquals("NCH", p.n)
        assertNull(p.build(emptyMap(), mapOf("NCH" to "nn")))
        assertNull(p.build(mapOf("SIG1" to "ss"), emptyMap()))
        assertEquals(
            "https://r1.googlevideo.com/videoplayback?itag=140&n=nn&c=TVHTML5&sig=ss",
            p.build(mapOf("SIG1" to "ss"), mapOf("NCH" to "nn"))
        )
    }

    @Test fun preRollWaitIsReadFromAdSlots() {
        val pr = Json.parseToJsonElement(
            """{"adSlots":[{"adSlotRenderer":{"adSlotMetadata":{"triggerEvent":"SLOT_TRIGGER_EVENT_BEFORE_CONTENT"},
               "fulfillmentContent":{"fulfilledLayout":{"playerBytesAdLayoutRenderer":{"renderingContent":
               {"instreamVideoAdRenderer":{"skipOffsetMilliseconds":5000,"playerVars":"length_seconds=30"}}}}}}}],
               "adPlacements":[{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"AD_PLACEMENT_KIND_MILLISECONDS"}},
               "renderer":{"instreamVideoAdRenderer":{"playerVars":"length_seconds=15"}}}}]}"""
        ).jsonObject
        assertEquals(5.0, TvPlayer.adWaitSeconds(pr), 0.001)
        assertEquals(0.0, TvPlayer.adWaitSeconds(Json.parseToJsonElement("{}").jsonObject), 0.001)
    }

    @Test fun commentCounts() {
        assertEquals(1200L, YtComments.parseCount("1.2K"))
        assertEquals(3456L, YtComments.parseCount("3,456 Comments"))
        assertEquals(2_100_000L, YtComments.parseCount("2.1M"))
        assertEquals(0L, YtComments.parseCount(null))
        assertEquals(12L, YtComments.parseCount("12"))
    }

    @Test fun botCheckDetection() {
        assertTrue(PlayabilityException("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot").isBotCheck)
        assertTrue(PlayabilityException("LOGIN_REQUIRED", "x").needsLogin)
        assertFalse(PlayabilityException("UNPLAYABLE", "Video unavailable").isBotCheck)
    }
}
