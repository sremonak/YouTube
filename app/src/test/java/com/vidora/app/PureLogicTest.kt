package com.vidora.app

import com.vidora.app.data.SubscriptionImport
import com.vidora.app.potoken.u8ToBase64
import com.vidora.app.util.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {

    @Test
    fun versionsCompare() {
        assertTrue(Versions.isNewer("v1.1.0", "1.0.42"))
        assertTrue(Versions.isNewer("1.0.10", "1.0.9"))
        assertFalse(Versions.isNewer("v1.0.9", "1.0.9"))
        assertFalse(Versions.isNewer("1.0.8", "1.0.9-debug"))
        assertTrue(Versions.isNewer("2", "1.9.9"))
    }

    @Test
    fun poTokenBase64IsUrlSafe() {
        assertEquals("YWJj", u8ToBase64("97,98,99"))
        assertEquals("-_8=", u8ToBase64("251,255"))
    }

    @Test
    fun importNewPipeJson() {
        val json = """{"app_version":"0.27.1","subscriptions":[
            {"service_id":0,"url":"https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA","name":"MrBeast"},
            {"service_id":1,"url":"https://soundcloud.com/x","name":"SC"}]}"""
        val list = SubscriptionImport.parse(json)
        assertEquals(1, list.size)
        assertEquals("MrBeast", list[0].name)
    }

    @Test
    fun importLibreTubeJson() {
        val json = """{"localSubscriptions":[{"channelId":"UCX6OQ3DkcsbYNE6H8uQQuVA"}],
            "subscriptionGroups":[]}"""
        val list = SubscriptionImport.parse(json)
        assertEquals("https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA", list.single().url)
    }

    @Test
    fun importTakeoutCsv() {
        val csv = "Channel Id,Channel Url,Channel Title\n" +
            "UCX6OQ3DkcsbYNE6H8uQQuVA,http://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA,\"Mr, Beast\"\n"
        val list = SubscriptionImport.parse(csv)
        assertEquals(1, list.size)
        assertEquals("Mr, Beast", list[0].name)
    }

    @Test
    fun importOpml() {
        val opml = """<opml><body><outline text="YouTube Subscriptions"><outline text="Kurzgesagt" title="Kurzgesagt"
            xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCsXVk37bltHxD1rDPwtNM8Q"/></outline></body></opml>"""
        assertEquals("Kurzgesagt", SubscriptionImport.parse(opml).single().name)
    }
}
