package com.vidora.app.data

import com.vidora.app.Graph
import com.vidora.app.data.OpenVideoParser.n
import com.vidora.app.data.OpenVideoParser.o
import com.vidora.app.data.OpenVideoParser.rows
import com.vidora.app.data.OpenVideoParser.s
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

class OpenVideoRepository {
    private val client by lazy { Graph.http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun get(url: String): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Vidora/1.0 (Android public video browser)").build()).execute().use { r ->
            check(r.isSuccessful) { "Public video service unavailable (${r.code})" }
            val result = json.parseToJsonElement(r.body.string()).jsonObject
            check(result["error"] == null) { "Public video service could not complete this request" }
            result
        }
    }

    suspend fun peerSearch(query: String?, start: Int = 0, count: Int = 12): PageResult = supervisorScope {
        val pages = GuestSources.peerBases.map { base -> async {
            try {
                val path = if (query.isNullOrBlank()) "/api/v1/videos" else "/api/v1/search/videos"
                val url = (base + path).toHttpUrl().newBuilder().addQueryParameter("count", "$count")
                    .addQueryParameter("start", "$start").addQueryParameter("nsfw", "false")
                    .addQueryParameter("sort", if (query.isNullOrBlank()) "-trending" else "-match")
                    .apply { if (!query.isNullOrBlank()) addQueryParameter("search", query) }.build().toString()
                val body = get(url)
                Triple(base, body.rows("data").mapNotNull { OpenVideoParser.peerItem(base, it)?.let { video -> Entry.Video(video) } }, body.n("total"))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Triple(base, emptyList<Entry>(), -1L) }
        } }.awaitAll()
        if (pages.all { it.third < 0 }) error("PeerTube could not be reached. Try another source or refresh.")
        PageResult(GuestSources.interleave(pages.map { it.second }),
            next = if (pages.any { it.third > start + count }) ({ peerSearch(query, start + count, count) }) else null,
            notices = pages.filter { it.third < 0 }.map { "${URI(it.first).host} is temporarily unavailable." })
    }

    suspend fun commonsSearch(query: String, offset: Int = 0): PageResult {
        val url = commonsRequest().addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "$query filetype:video")
            .addQueryParameter("gsrnamespace", "6").addQueryParameter("gsrlimit", "12")
            .addQueryParameter("gsroffset", "$offset").build().toString()
        val body = get(url)
        val items = body.o("query").rows("pages").sortedBy { it.n("index") }
            .mapNotNull { OpenVideoParser.commonsItem(it)?.let { video -> Entry.Video(video) } }
        val nextOffset = body.o("continue").s("gsroffset")?.toIntOrNull()
        return PageResult(items, nextOffset?.let { next -> ({ commonsSearch(query, next) }) })
    }

    suspend fun stream(url: String): StreamDetails = when {
        GuestSources.isPeer(url) -> {
            val uri = URI(url)
            val base = "https://${uri.host}"
            OpenVideoParser.peerDetails(base, get("$base/api/v1/videos/${GuestSources.peerId(url)}"))
        }
        GuestSources.isCommons(url) -> {
            val request = commonsRequest().addQueryParameter("titles", GuestSources.commonsTitle(url)!!).build().toString()
            val page = get(request).o("query").rows("pages").firstOrNull() ?: error("Public video no longer available")
            OpenVideoParser.commonsDetails(page)
        }
        else -> error("Unsupported public video link")
    }

    suspend fun channel(url: String): ChannelDetails {
        check(GuestSources.isPeerChannel(url))
        val uri = URI(url)
        val base = "https://${uri.host}"
        val name = uri.path.trimEnd('/').substringAfterLast('/')
        val body = get("$base/api/v1/video-channels/" + name)
        val avatar = body.rows("avatars").firstOrNull()?.s("path")?.let { base + it }
        val channel = ChannelItem(url, body.s("displayName") ?: name, avatar,
            body.n("followersCount"), body.s("description"))
        return ChannelDetails(channel, null, listOf(ChannelTab("Videos") { channelVideos(url) }))
    }

    suspend fun channelVideos(url: String, start: Int = 0): PageResult {
        check(GuestSources.isPeerChannel(url))
        val uri = URI(url)
        val base = "https://${uri.host}"
        val name = uri.path.trimEnd('/').substringAfterLast('/')
        val request = "$base/api/v1/video-channels/$name/videos".toHttpUrl().newBuilder()
            .addQueryParameter("count", "12").addQueryParameter("start", "$start").addQueryParameter("nsfw", "false")
            .addQueryParameter("sort", "-publishedAt").build().toString()
        val body = get(request)
        val entries = body.rows("data").mapNotNull { OpenVideoParser.peerItem(base, it)?.let { v -> Entry.Video(v) } }
        return PageResult(entries, if (body.n("total") > start + 12) ({ channelVideos(url, start + 12) }) else null)
    }

    private fun commonsRequest() = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
        .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
        .addQueryParameter("prop", "videoinfo").addQueryParameter("viprop", "url|size|mime|derivatives|extmetadata|timestamp")
        .addQueryParameter("viurlwidth", "480")
}
