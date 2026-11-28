package com.vidora.app

import com.vidora.app.data.AppSettings
import com.vidora.app.data.ChannelItem
import com.vidora.app.data.ContentFilter
import com.vidora.app.data.Entry
import com.vidora.app.data.FrameSet
import com.vidora.app.data.VideoItem
import com.vidora.app.player.AudioEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureLogicTest {

    private val video = VideoItem(
        url = "https://www.youtube.com/watch?v=abc",
        title = "Amazing CLICKBAIT trick",
        uploaderUrl = "https://www.youtube.com/channel/UC1",
        duration = 300,
    )

    @Test
    fun filterBlocksKeywordsCaseInsensitive() {
        val s = AppSettings(blockedKeywords = setOf("clickbait"))
        assertFalse(ContentFilter.allows(Entry.Video(video), s, emptySet()))
        assertTrue(ContentFilter.allows(Entry.Video(video.copy(title = "Calm video")), s, emptySet()))
    }

    @Test
    fun filterBlocksChannels() {
        val s = AppSettings(blockedChannels = setOf(ContentFilter.encodeChannel("https://www.youtube.com/channel/UC1", "Spam")))
        assertFalse(ContentFilter.allows(Entry.Video(video), s, emptySet()))
        assertFalse(ContentFilter.allows(Entry.Channel(ChannelItem("https://www.youtube.com/channel/UC1", "Spam")), s, emptySet()))
        assertTrue(ContentFilter.allows(Entry.Video(video.copy(uploaderUrl = "https://www.youtube.com/channel/UC2")), s, emptySet()))
    }

    @Test
    fun filterHidesShortsAndWatched() {
        assertFalse(ContentFilter.allows(Entry.Video(video.copy(isShort = true)), AppSettings(hideShorts = true), emptySet()))
        assertFalse(ContentFilter.allows(Entry.Video(video), AppSettings(hideWatched = true), setOf(video.url)))
        assertTrue(ContentFilter.allows(Entry.Video(video), AppSettings(hideWatched = false), setOf(video.url)))
    }

    @Test
    fun encodedChannelRoundTrip() {
        val e = ContentFilter.encodeChannel("https://y/c", "Name with\tTab?")
        assertEquals("https://y/c", ContentFilter.channelUrl(e))
        assertEquals("https://y/c", ContentFilter.channelName(ContentFilter.channelUrl(e)))
    }

    @Test
    fun storyboardFrameMath() {
        // 2 sheets of 5x5 frames, 160x90 each, one frame per second, 40 frames total
        val fs = FrameSet(listOf("s0", "s1"), 160, 90, 5, 5, 1000, 40)
        assertEquals(Triple("s0", 0, 0), fs.frameAt(0))
        assertEquals(Triple("s0", 160 * 2, 90 * 1), fs.frameAt(7_500)) // frame 7 → col 2, row 1
        assertEquals(Triple("s1", 0, 0), fs.frameAt(25_000))           // frame 25 → second sheet
        assertEquals(Triple("s1", 160 * 4, 90 * 2), fs.frameAt(999_999)) // clamped to last frame (39)
        assertNull(FrameSet(emptyList(), 1, 1, 1, 1, 1, 1).frameAt(0))
    }

    @Test
    fun eqBandsEncoding() {
        val levels = listOf(-300, 0, 150, 1200, -1500)
        assertEquals(levels, AudioEffects.parseBands(AudioEffects.encodeBands(levels)))
        assertEquals(emptyList<Int>(), AudioEffects.parseBands(""))
        assertEquals(listOf(1, 3), AudioEffects.parseBands("1, x, 3"))
    }
}
