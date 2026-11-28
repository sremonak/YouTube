package com.vidora.app.data

import com.vidora.app.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Last-resort stream source: public Piped and Invidious instances (like LibreTube / FreeTube).
 * Their servers talk to YouTube and proxy the media, so this works even when YouTube blocks the
 * phone's own IP (VPN, shared mobile IP, "confirm you're not a bot").
 */
object AltFrontends {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        Graph.http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }

    val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz",
    )
    val invidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
    )

    data class Result(val details: StreamDetails, val instance: String)

    suspend fun load(videoId: String, url: String): Result = withContext(Dispatchers.IO) {
        var last: Throwable = IOException("No instance answered")
        for (base in pipedInstances) {
            try {
                return@withContext Result(piped(base, videoId, url), base)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                last = t
            }
        }
        for (base in invidiousInstances) {
            try {
                return@withContext Result(invidious(base, videoId, url), base)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                last = t
            }
        }
        throw last
    }

    private fun get(url: String): JsonObject =
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from ${url.substringBefore("/api").substringBefore("/streams")}")
            val o = json.parseToJsonElement(r.body.string()) as? JsonObject ?: throw IOException("Bad JSON")
            o.s("error")?.let { throw IOException(it) }
            o
        }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.l(k: String) = s(k)?.toDoubleOrNull()?.toLong()
    private fun JsonObject.i(k: String) = s(k)?.toDoubleOrNull()?.toInt()
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun pipedRange(o: JsonObject, k: String): String? {
        val a = o.l(k + "Start") ?: return null
        val b = o.l(k + "End") ?: return null
        return if (b > a && a >= 0) "$a-$b" else null
    }

    private fun suffixOf(mime: String) = when {
        "webm" in mime -> "webm"
        mime.startsWith("audio") -> "m4a"
        else -> "mp4"
    }

    private fun codecOf(mime: String) = mime.substringAfter("codecs=\"", "").substringBefore('"').ifBlank { null }

    internal fun piped(base: String, id: String, url: String): StreamDetails {
        val o = get("$base/streams/$id")
        val videos = o.arr("videoStreams").mapNotNull { v ->
            val mime = v.s("mimeType") ?: return@mapNotNull null
            val height = v.i("height") ?: v.s("quality")?.substringBefore('p')?.toIntOrNull() ?: 0
            VideoOption(
                url = v.s("url") ?: return@mapNotNull null,
                itag = v.i("itag") ?: 0,
                height = height,
                fps = v.i("fps") ?: 30,
                resolution = v.s("quality") ?: "${height}p",
                mime = mime.substringBefore(';'),
                suffix = suffixOf(mime),
                codec = v.s("codec") ?: codecOf(mime),
                videoOnly = v.b("videoOnly") ?: true,
                bitrate = v.i("bitrate") ?: 0,
                contentLength = v.l("contentLength") ?: -1,
                width = v.i("width") ?: 0,
                initRange = pipedRange(v, "init"),
                indexRange = pipedRange(v, "index"),
            )
        }.sortedByDescending { it.height }
        val audios = o.arr("audioStreams").mapNotNull { a ->
            val mime = a.s("mimeType") ?: return@mapNotNull null
            AudioOption(
                url = a.s("url") ?: return@mapNotNull null,
                itag = a.i("itag") ?: 0,
                bitrateKbps = (a.i("bitrate") ?: 0) / 1000,
                mime = mime.substringBefore(';'),
                suffix = suffixOf(mime),
                codec = a.s("codec") ?: codecOf(mime),
                trackName = a.s("audioTrackName"),
                contentLength = a.l("contentLength") ?: -1,
                trackId = a.s("audioTrackId"),
                trackType = a.s("audioTrackType"),
                language = a.s("audioTrackLocale"),
                initRange = pipedRange(a, "init"),
                indexRange = pipedRange(a, "index"),
            )
        }.sortedByDescending { it.bitrateKbps }
        if (videos.isEmpty() && audios.isEmpty() && o.s("hls") == null) throw IOException("No streams")
        val subs = o.arr("subtitles").mapNotNull { s ->
            SubtitleOption(
                url = s.s("url") ?: return@mapNotNull null,
                languageCode = s.s("code") ?: "",
                label = s.s("name") ?: s.s("code") ?: "",
                autoGenerated = s.b("autoGenerated") ?: false,
                mime = s.s("mimeType") ?: "text/vtt",
            )
        }
        val related = o.arr("relatedStreams").filter { it.s("type") == null || it.s("type") == "stream" }.mapNotNull { r ->
            val path = r.s("url") ?: return@mapNotNull null
            VideoItem(
                url = "https://www.youtube.com$path",
                title = r.s("title") ?: return@mapNotNull null,
                thumbnail = r.s("thumbnail"),
                uploader = r.s("uploaderName"),
                uploaderUrl = r.s("uploaderUrl")?.let { "https://www.youtube.com$it" },
                uploaderAvatar = r.s("uploaderAvatar"),
                duration = r.l("duration") ?: 0,
                views = r.l("views") ?: -1,
                uploadedText = r.s("uploadedDate"),
                isShort = r.b("isShort") ?: false,
            )
        }
        val description = o.s("description").orEmpty()
            .replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        val item = VideoItem(
            url = url,
            title = o.s("title") ?: "",
            thumbnail = o.s("thumbnailUrl") ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            uploader = o.s("uploader"),
            uploaderUrl = o.s("uploaderUrl")?.let { "https://www.youtube.com$it" },
            uploaderAvatar = o.s("uploaderAvatar"),
            duration = o.l("duration") ?: 0,
            views = o.l("views") ?: -1,
            isLive = o.b("livestream") ?: false,
            verified = o.b("uploaderVerified") ?: false,
        )
        return StreamDetails(
            item = item,
            description = description,
            likes = o.l("likes") ?: -1,
            subscriberCount = o.l("uploaderSubscriberCount") ?: -1,
            videoStreams = videos,
            audioStreams = audios.filter { it.trackType == null || it.trackType == "ORIGINAL" || audios.none { a -> a.trackType == "ORIGINAL" } },
            hlsUrl = o.s("hls"),
            dashUrl = null,
            related = related.map { Entry.Video(it) },
            chapters = Chapters.fromDescription(description),
            tags = emptyList(),
            category = o.s("category"),
            allAudioStreams = audios,
            subtitles = subs,
            source = "Piped · " + base.removePrefix("https://"),
            preferHls = item.isLive && o.s("hls") != null,
        )
    }

    internal fun invidious(base: String, id: String, url: String): StreamDetails {
        val o = get("$base/api/v1/videos/$id?local=true")
        val videos = o.arr("adaptiveFormats").filter { it.s("type")?.startsWith("video") == true }.mapNotNull { v ->
            val type = v.s("type") ?: return@mapNotNull null
            val height = v.s("resolution")?.substringBefore('p')?.toIntOrNull() ?: v.s("size")?.substringAfter('x')?.toIntOrNull() ?: 0
            VideoOption(
                url = v.s("url") ?: return@mapNotNull null,
                itag = v.i("itag") ?: 0,
                height = height,
                fps = v.i("fps") ?: 30,
                resolution = v.s("qualityLabel") ?: "${height}p",
                mime = type.substringBefore(';'),
                suffix = suffixOf(type),
                codec = codecOf(type),
                videoOnly = true,
                bitrate = v.i("bitrate") ?: 0,
                contentLength = v.l("clen") ?: -1,
                width = v.s("size")?.substringBefore('x')?.toIntOrNull() ?: 0,
                initRange = v.s("init"), indexRange = v.s("index"),
            )
        } + o.arr("formatStreams").mapNotNull { v ->
            val type = v.s("type") ?: return@mapNotNull null
            val height = v.s("resolution")?.substringBefore('p')?.toIntOrNull() ?: 0
            VideoOption(v.s("url") ?: return@mapNotNull null, v.i("itag") ?: 0, height, v.i("fps") ?: 30,
                v.s("qualityLabel") ?: "${height}p", type.substringBefore(';'), suffixOf(type), codecOf(type), false, 0, v.l("clen") ?: -1)
        }
        val audios = o.arr("adaptiveFormats").filter { it.s("type")?.startsWith("audio") == true }.mapNotNull { a ->
            val type = a.s("type") ?: return@mapNotNull null
            AudioOption(a.s("url") ?: return@mapNotNull null, a.i("itag") ?: 0, (a.i("bitrate") ?: 0) / 1000,
                type.substringBefore(';'), suffixOf(type), codecOf(type), null, a.l("clen") ?: -1,
                initRange = a.s("init"), indexRange = a.s("index"))
        }.sortedByDescending { it.bitrateKbps }
        if (videos.isEmpty() && audios.isEmpty()) throw IOException("No streams")
        val description = o.s("description").orEmpty()
        val item = VideoItem(
            url = url,
            title = o.s("title") ?: "",
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            uploader = o.s("author"),
            uploaderUrl = o.s("authorId")?.let { "https://www.youtube.com/channel/$it" },
            duration = o.l("lengthSeconds") ?: 0,
            views = o.l("viewCount") ?: -1,
            isLive = o.b("liveNow") ?: false,
        )
        val subs = o.arr("captions").mapNotNull { c ->
            val u = c.s("url") ?: return@mapNotNull null
            SubtitleOption(base + u, c.s("language_code") ?: "", c.s("label") ?: "", (c.s("label") ?: "").contains("auto", true), "text/vtt")
        }
        return StreamDetails(
            item = item, description = description, likes = o.l("likeCount") ?: -1, subscriberCount = -1,
            videoStreams = videos.sortedByDescending { it.height }, audioStreams = audios, hlsUrl = o.s("hlsUrl"), dashUrl = null,
            related = emptyList(), chapters = Chapters.fromDescription(description), tags = emptyList(), category = o.s("genre"),
            subtitles = subs, source = "Invidious · " + base.removePrefix("https://"),
        )
    }
}
