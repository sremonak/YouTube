package com.vidora.app.notify

import android.util.Xml
import com.vidora.app.Graph
import com.vidora.app.data.VideoItem
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.time.OffsetDateTime

/**
 * YouTube's public per-channel Atom feed. Fast, light and needs no login or extractor work —
 * ideal for background "new video" checks.
 */
object RssFeed {
    private val channelIdRegex = Regex("/channel/(UC[\\w-]{20,})")

    fun channelIdFromUrl(url: String): String? = channelIdRegex.find(url)?.groupValues?.get(1)

    fun fetch(channelId: String): List<VideoItem> {
        val req = Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId").build()
        val xml = Graph.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string().orEmpty()
        }
        return parse(xml)
    }

    fun parse(xml: String): List<VideoItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        val out = mutableListOf<VideoItem>()
        var inEntry = false
        var inAuthor = false
        var id: String? = null
        var title: String? = null
        var published: String? = null
        var author: String? = null
        var authorUrl: String? = null
        var thumb: String? = null
        var views = -1L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> { inEntry = true; id = null; title = null; published = null; thumb = null; views = -1 }
                    "author" -> inAuthor = true
                    "yt:videoId" -> if (inEntry) id = parser.nextText()
                    "title" -> if (inEntry && title == null) title = parser.nextText()
                    "published" -> if (inEntry) published = parser.nextText()
                    "name" -> if (inAuthor) author = parser.nextText()
                    "uri" -> if (inAuthor) authorUrl = parser.nextText()
                    "media:thumbnail" -> thumb = parser.getAttributeValue(null, "url")
                    "media:statistics" -> views = parser.getAttributeValue(null, "views")?.toLongOrNull() ?: -1
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        val vid = id
                        if (vid != null && title != null) {
                            out += VideoItem(
                                url = "https://www.youtube.com/watch?v=$vid",
                                title = title!!,
                                thumbnail = thumb ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                                uploader = author,
                                uploaderUrl = authorUrl,
                                views = views,
                                uploadedEpoch = published?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L,
                                isShort = false,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
