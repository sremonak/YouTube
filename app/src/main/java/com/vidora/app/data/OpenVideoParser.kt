package com.vidora.app.data

import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant

/** Pure parsers for documented public APIs; fixtures exercise routing, safety and attribution. */
internal object OpenVideoParser {
    fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.n(key: String) = s(key)?.toDoubleOrNull()?.toLong() ?: 0L
    fun JsonObject.o(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    fun JsonObject.rows(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun plain(s: String?) = s.orEmpty().replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
    private fun epoch(value: String?) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
    private fun https(base: String, value: String?): String? = runCatching {
        val uri = URI(base).resolve(value ?: return@runCatching null)
        uri.toString().takeIf { uri.scheme == "https" && uri.host != null && uri.userInfo == null }
    }.getOrNull()

    fun peerItem(base: String, o: JsonObject): VideoItem? {
        if ((o["nsfw"] as? JsonPrimitive)?.booleanOrNull == true || o.o("privacy").n("id") != 1L ||
            (o["requiresPassword"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val url = o.s("url")?.takeIf(GuestSources::isPeer) ?: return null
        val name = o.s("name")?.takeIf { it.isNotBlank() } ?: return null
        val channel = o.o("channel")
        return VideoItem(url, name, https(base, o.s("thumbnailPath")), channel.s("displayName"),
            uploaderUrl = channel.s("url")?.takeIf(GuestSources::isPeerChannel),
            uploaderAvatar = https(base, channel.rows("avatars").firstOrNull()?.s("path")),
            duration = o.n("duration"), views = o.n("views"), uploadedEpoch = epoch(o.s("publishedAt")),
            isLive = (o["isLive"] as? JsonPrimitive)?.booleanOrNull == true, platform = "PeerTube")
    }

    fun peerDetails(base: String, o: JsonObject): StreamDetails {
        val item = peerItem(base, o) ?: error("This PeerTube video is not available as public, non-sensitive content.")
        val playlists = o.rows("streamingPlaylists")
        val files = (o.rows("files") + playlists.flatMap { it.rows("files") }).distinctBy { it.s("fileUrl") }
        val videos = files.mapIndexedNotNull { index, f ->
            val url = https(base, f.s("fileUrl")) ?: return@mapIndexedNotNull null
            val h = f.o("resolution").n("id").toInt()
            if (h <= 0) return@mapIndexedNotNull null
            VideoOption(url, 10_000 + index, h, f.n("fps").toInt().coerceAtLeast(25), "${h}p", "video/mp4", "mp4", null,
                false, 0, f.n("size").takeIf { it > 0 } ?: -1)
        }.sortedByDescending { it.height }
        val hls = playlists.firstNotNullOfOrNull { https(base, it.s("playlistUrl")) }
        check(videos.isNotEmpty() || hls != null) { "Publisher has not provided a playable public stream." }
        val licence = o.o("licence").s("label").orEmpty()
        val description = listOfNotNull(o.s("description"), "Platform: PeerTube", "Creator: ${item.uploader.orEmpty()}",
            o.o("channel").s("url"), "Licence: $licence", "Original: ${item.url}").joinToString("\n\n")
        return details(item, description, videos, hls, "PeerTube · public API", o.n("likes"))
    }

    fun commonsItem(page: JsonObject): VideoItem? {
        val info = page.rows("videoinfo").firstOrNull() ?: return null
        if (!info.s("mime").orEmpty().startsWith("video/")) return null
        val title = page.s("title")?.takeIf { it.startsWith("File:") } ?: return null
        val meta = info.o("extmetadata")
        val licence = plain(meta.o("LicenseShortName").s("value"))
        // Never present files with missing rights metadata as freely licensed.
        if (licence.isBlank()) return null
        return VideoItem(GuestSources.commonsUrl(title), plain(meta.o("ObjectName").s("value")).ifBlank { title.removePrefix("File:") },
            https("https://commons.wikimedia.org", info.s("thumburl")), plain(meta.o("Artist").s("value")).ifBlank { "Wikimedia Commons" },
            duration = info.n("duration"), uploadedEpoch = epoch(info.s("timestamp")), platform = "Commons")
    }

    fun commonsDetails(page: JsonObject): StreamDetails {
        val item = commonsItem(page) ?: error("No public video with licence information was found.")
        val info = page.rows("videoinfo").first()
        val videos = info.rows("derivatives").mapIndexedNotNull { index, f ->
            val mime = f.s("type").orEmpty().substringBefore(';')
            if (mime !in listOf("video/webm", "video/mp4")) return@mapIndexedNotNull null
            val url = https("https://commons.wikimedia.org", f.s("src")) ?: return@mapIndexedNotNull null
            if (URI(url).host != "upload.wikimedia.org") return@mapIndexedNotNull null
            val h = f.n("height").toInt()
            VideoOption(url, 20_000 + index, h, 30, "${h}p", mime, if (mime == "video/webm") "webm" else "mp4", null,
                false, f.n("bandwidth").toInt(), -1, f.n("width").toInt())
        }.sortedByDescending { it.height }.toMutableList()
        if (videos.isEmpty() && info.s("mime") in listOf("video/webm", "video/mp4")) {
            val src = https("https://commons.wikimedia.org", info.s("url")) ?: error("Video file missing")
            check(URI(src).host == "upload.wikimedia.org")
            val mime = info.s("mime")!!
            videos += VideoOption(src, 20_000, info.n("height").toInt(), 30, "Original", mime,
                if (mime == "video/webm") "webm" else "mp4", null, false, 0, info.n("size"))
        }
        check(videos.isNotEmpty()) { "No compatible public rendition is currently available." }
        val meta = info.o("extmetadata")
        val description = buildString {
            appendLine(plain(meta.o("ImageDescription").s("value")))
            appendLine("\nPlatform: Wikimedia Commons\nCreator: ${item.uploader}")
            for (key in listOf("Credit", "Attribution", "LicenseShortName", "UsageTerms", "LicenseUrl", "Restrictions")) {
                val value = plain(meta.o(key).s("value"))
                if (value.isNotBlank()) appendLine("$key: $value")
            }
            appendLine("Original / attribution: ${item.url}")
            appendLine("Check the original file page for rights and reuse conditions.")
        }
        return details(item, description, videos, null, "Wikimedia Commons · public API", -1)
    }

    private fun details(item: VideoItem, description: String, videos: List<VideoOption>, hls: String?, source: String, likes: Long) = StreamDetails(
        item, description, likes, -1, videos, emptyList(), hls, null, emptyList(), emptyList(), emptyList(), null,
        source = source, preferHls = hls != null,
    )
}
