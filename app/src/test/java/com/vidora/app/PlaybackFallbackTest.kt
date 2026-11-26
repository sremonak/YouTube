package com.vidora.app

import com.vidora.app.data.Chapters
import com.vidora.app.data.PlayabilityException
import com.vidora.app.data.TvPlayer
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

    @Test fun botCheckDetection() {
        assertTrue(PlayabilityException("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot").isBotCheck)
        assertTrue(PlayabilityException("LOGIN_REQUIRED", "x").needsLogin)
        assertFalse(PlayabilityException("UNPLAYABLE", "Video unavailable").isBotCheck)
    }
}
