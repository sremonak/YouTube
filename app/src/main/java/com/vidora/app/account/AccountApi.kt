package com.vidora.app.account

import com.vidora.app.Graph
import com.vidora.app.data.ChannelItem
import com.vidora.app.data.Entry
import com.vidora.app.data.NetworkDownloader
import com.vidora.app.data.PageResult
import com.vidora.app.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.Locale

/** Account-only feeds of the signed-in user. */
enum class AccountFeed(val label: String, val browseId: String) {
    RECOMMENDED("Recommended for you", "FEwhat_to_watch"),
    SUBSCRIPTIONS("Subscriptions feed", "FEsubscriptions"),
    LIKED("Liked videos", "VLLL"),
    WATCH_LATER("Watch later", "VLWL"),
    HISTORY("Watch history", "FEhistory"),
}

/**
 * Minimal signed-in InnerTube (web client) client. Responses are parsed defensively by walking the
 * JSON tree for known renderer objects, so small layout changes don't break it. Ad renderers are
 * never parsed, so account feeds stay ad-free too.
 */
class AccountApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = Graph.http

    /** Web-client InnerTube call that works signed out too (uses the account when there is one). */
    suspend fun webPost(endpoint: String, body: JsonObject): JsonObject = post(endpoint, body, requireAuth = false)

    suspend fun webContext(): JsonObject = context()

    private suspend fun post(endpoint: String, body: JsonObject, requireAuth: Boolean = true): JsonObject = withContext(Dispatchers.IO) {
        val account = Graph.account
        if (requireAuth && !account.isSignedIn) throw IllegalStateException("Not signed in")
        val version = runCatching { YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20251101.00.00")
        val builder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", NetworkDownloader.USER_AGENT)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", version)
        val authed = account.isSignedIn
        if (authed) account.authHeaders().forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { r ->
            if (authed) account.mergeSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IOException("Session expired — please sign in again (HTTP ${r.code})")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            json.parseToJsonElement(text).jsonObject
        }
    }

    private suspend fun context(): JsonObject {
        val version = withContext(Dispatchers.IO) {
            runCatching { YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20251101.00.00")
        }
        val locale = Locale.getDefault()
        return buildJsonObject {
            putJsonObject("client") {
                put("clientName", "WEB")
                put("clientVersion", version)
                put("hl", locale.language.ifBlank { "en" })
                put("gl", locale.country.ifBlank { "US" })
            }
        }
    }

    // ------------------------------------------------------------------ account info

    suspend fun accountInfo(): AccountSession? {
        val session = Graph.account.session.value ?: return null
        val res = post("account/account_menu", buildJsonObject { put("context", context()) })
        val header = findFirst(res, "activeAccountHeaderRenderer") ?: return session
        return session.copy(
            name = text(header["accountName"]) ?: session.name,
            handle = text(header["channelHandle"]) ?: text(header["email"]) ?: session.handle,
            avatar = thumb(header["accountPhoto"]) ?: session.avatar,
        )
    }

    // ------------------------------------------------------------------ playback helpers

    @Volatile private var dataSync: Pair<String, String>? = null // cookies → dataSyncId

    /**
     * The account's dataSyncId ("USER_SESSION_ID||" or "DELEGATED||USER"). Signed-in GVS
     * PoTokens must be bound to it, otherwise streams stop with HTTP 403 after ~1 minute.
     */
    suspend fun dataSyncId(): String? {
        val cookies = Graph.account.session.value?.cookies ?: return null
        dataSync?.let { (c, id) -> if (c == cookies) return id }
        val res = runCatching { post("account/account_menu", buildJsonObject { put("context", context()) }) }.getOrNull() ?: return null
        val id = findString(res["responseContext"], "datasyncId") ?: return null
        dataSync = cookies to id
        return id
    }

    /** Related videos for a watch page (`next`, signed in when possible), used when the extractor is blocked. */
    suspend fun watchNext(videoId: String): List<VideoItem> {
        val res = post("next", buildJsonObject {
            put("context", context()); put("videoId", videoId)
            put("contentCheckOk", true); put("racyCheckOk", true)
        }, requireAuth = false)
        val out = mutableListOf<VideoItem>()
        walk(res, out, mutableListOf())
        return out.filter { !it.url.contains(videoId) }.distinctBy { it.url }
    }

    // ------------------------------------------------------------------ feeds

    suspend fun feed(feed: AccountFeed): PageResult {
        val res = post("browse", buildJsonObject { put("context", context()); put("browseId", feed.browseId) })
        return toPage(res)
    }

    private suspend fun continuation(token: String): PageResult {
        val res = post("browse", buildJsonObject { put("context", context()); put("continuation", token) })
        return toPage(res)
    }

    private fun toPage(res: JsonObject): PageResult {
        val videos = mutableListOf<VideoItem>()
        val tokens = mutableListOf<String>()
        walk(res, videos, tokens)
        return PageResult(videos.distinctBy { it.url }.map { Entry.Video(it) }, nextPage(tokens.lastOrNull(), videos.isNotEmpty()))
    }

    private fun nextPage(token: String?, hasItems: Boolean): (suspend () -> PageResult)? {
        if (token == null || !hasItems) return null
        return { continuation(token) }
    }

    /** All channels the account is subscribed to (follows continuations). */
    suspend fun subscriptions(): List<ChannelItem> {
        val channels = mutableListOf<ChannelItem>()
        var res = post("browse", buildJsonObject { put("context", context()); put("browseId", "FEchannels") })
        repeat(30) {
            val tokens = mutableListOf<String>()
            collectChannels(res, channels, tokens)
            val token = tokens.lastOrNull() ?: return channels.distinctBy { it.url }
            res = post("browse", buildJsonObject { put("context", context()); put("continuation", token) })
        }
        return channels.distinctBy { it.url }
    }

    // ------------------------------------------------------------------ actions

    suspend fun like(videoId: String) { post("like/like", buildJsonObject { put("context", context()); putJsonObject("target") { put("videoId", videoId) } }) }
    suspend fun dislike(videoId: String) { post("like/dislike", buildJsonObject { put("context", context()); putJsonObject("target") { put("videoId", videoId) } }) }
    suspend fun removeLike(videoId: String) { post("like/removelike", buildJsonObject { put("context", context()); putJsonObject("target") { put("videoId", videoId) } }) }

    suspend fun subscribe(channelId: String) {
        post("subscription/subscribe", buildJsonObject {
            put("context", context())
            put("channelIds", JsonArray(listOf(JsonPrimitive(channelId))))
        })
    }

    suspend fun unsubscribe(channelId: String) {
        post("subscription/unsubscribe", buildJsonObject {
            put("context", context())
            put("channelIds", JsonArray(listOf(JsonPrimitive(channelId))))
        })
    }

    // ------------------------------------------------------------------ YouTube playlists

    /** The signed-in user's playlists, with whether each already contains [videoId]. */
    suspend fun playlistsFor(videoId: String): List<RemotePlaylist> {
        val res = post("playlist/get_add_to_playlist", buildJsonObject {
            put("context", context())
            put("videoIds", JsonArray(listOf(JsonPrimitive(videoId))))
            put("excludeWatchLater", false)
        })
        val out = mutableListOf<RemotePlaylist>()
        fun rec(e: JsonElement?) {
            when (e) {
                is JsonObject -> {
                    (e["playlistAddToOptionRenderer"] as? JsonObject)?.let { r ->
                        val id = str(r["playlistId"]) ?: return@let
                        out += RemotePlaylist(
                            id = id,
                            title = text(r["title"]) ?: id,
                            containsVideo = str(r["containsSelectedVideos"]) == "ALL",
                            privacy = str(r["privacy"]),
                        )
                    }
                    e.values.forEach(::rec)
                }
                is JsonArray -> e.forEach(::rec)
                else -> Unit
            }
        }
        rec(res)
        return out.distinctBy { it.id }
    }

    suspend fun addToPlaylist(playlistId: String, videoId: String) {
        post("browse/edit_playlist", buildJsonObject {
            put("context", context())
            put("playlistId", playlistId)
            put("actions", JsonArray(listOf(buildJsonObject {
                put("action", "ACTION_ADD_VIDEO")
                put("addedVideoId", videoId)
            })))
        })
    }

    suspend fun removeFromPlaylist(playlistId: String, videoId: String) {
        post("browse/edit_playlist", buildJsonObject {
            put("context", context())
            put("playlistId", playlistId)
            put("actions", JsonArray(listOf(buildJsonObject {
                put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                put("removedVideoId", videoId)
            })))
        })
    }

    /** Creates a private playlist containing [videoId]; returns the new playlist id if reported. */
    suspend fun createPlaylist(title: String, videoId: String, privacy: String = "PRIVATE"): String? {
        val res = post("playlist/create", buildJsonObject {
            put("context", context())
            put("title", title)
            put("privacyStatus", privacy)
            put("videoIds", JsonArray(listOf(JsonPrimitive(videoId))))
        })
        return str(res["playlistId"])
    }

    // ------------------------------------------------------------------ JSON walking

    private val videoKeys = setOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer")

    private fun walk(e: JsonElement, out: MutableList<VideoItem>, tokens: MutableList<String>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                when {
                    k in videoKeys && v is JsonObject -> parseVideoRenderer(v)?.let(out::add)
                    k == "lockupViewModel" && v is JsonObject -> parseLockup(v)?.let(out::add)
                    k == "continuationCommand" && v is JsonObject -> str(v["token"])?.let(tokens::add)
                    k.endsWith("adSlotRenderer", ignoreCase = true) || k.contains("promoted", true) -> Unit
                    else -> walk(v, out, tokens)
                }
            }
            is JsonArray -> e.forEach { walk(it, out, tokens) }
            else -> Unit
        }
    }

    private fun collectChannels(e: JsonElement, out: MutableList<ChannelItem>, tokens: MutableList<String>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                when {
                    k == "channelRenderer" && v is JsonObject -> {
                        val id = str(v["channelId"]) ?: continue
                        out += ChannelItem(
                            url = "https://www.youtube.com/channel/$id",
                            name = text(v["title"]) ?: id,
                            avatar = thumb(v["thumbnail"]),
                        )
                    }
                    k == "continuationCommand" && v is JsonObject -> str(v["token"])?.let(tokens::add)
                    else -> collectChannels(v, out, tokens)
                }
            }
            is JsonArray -> e.forEach { collectChannels(it, out, tokens) }
            else -> Unit
        }
    }

    private fun parseVideoRenderer(v: JsonObject): VideoItem? {
        val id = str(v["videoId"]) ?: return null
        val byline = (v["ownerText"] ?: v["shortBylineText"] ?: v["longBylineText"]) as? JsonObject
        val run = (byline?.get("runs") as? JsonArray)?.firstOrNull() as? JsonObject
        val lengthSeconds = str(v["lengthSeconds"])?.toLongOrNull()
            ?: parseDuration(text(v["lengthText"]))
            ?: parseDuration(findBadgeText(v["thumbnailOverlays"]))
        val live = v["badges"]?.toString()?.contains("LIVE", true) == true ||
            v["thumbnailOverlays"]?.toString()?.contains("\"LIVE\"") == true
        return VideoItem(
            url = "https://www.youtube.com/watch?v=$id",
            title = text(v["title"]) ?: return null,
            thumbnail = thumb(v["thumbnail"]) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            uploader = text(byline),
            uploaderUrl = run?.let { channelUrl(it) },
            uploaderAvatar = thumb(findFirst(v, "channelThumbnailWithLinkRenderer")?.get("thumbnail") ?: v["channelThumbnail"]),
            duration = lengthSeconds ?: 0,
            views = parseCount(text(v["viewCountText"]) ?: text(v["shortViewCountText"])),
            uploadedText = text(v["publishedTimeText"]) ?: text(v["videoInfo"]),
            isLive = live,
        )
    }

    private fun parseLockup(v: JsonObject): VideoItem? {
        if (str(v["contentType"])?.contains("VIDEO") != true) return null
        val id = str(v["contentId"]) ?: return null
        val meta = findFirst(v, "lockupMetadataViewModel") ?: return null
        val title = text(meta["title"]) ?: return null
        val rows = (findFirst(meta, "contentMetadataViewModel")?.get("metadataRows") as? JsonArray).orEmpty()
        val parts = rows.map { row ->
            ((row as? JsonObject)?.get("metadataParts") as? JsonArray).orEmpty()
                .mapNotNull { text((it as? JsonObject)?.get("text")) }
        }
        val channel = parts.getOrNull(0)?.firstOrNull()
        val stats = parts.getOrNull(1).orEmpty()
        val sources = (findFirst(v["contentImage"] ?: v, "image")?.get("sources") as? JsonArray)
        val thumbUrl = sources?.lastOrNull()?.let { str((it as? JsonObject)?.get("url")) }
        val avatarSources = findFirst(meta, "avatarViewModel")?.let { findFirst(it, "image")?.get("sources") as? JsonArray }
        val canonical = findString(meta, "canonicalBaseUrl")
        val browseId = findString(meta, "browseId")
        return VideoItem(
            url = "https://www.youtube.com/watch?v=$id",
            title = title,
            thumbnail = thumbUrl ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            uploader = channel,
            uploaderUrl = canonical?.let { "https://www.youtube.com$it" } ?: browseId?.takeIf { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" },
            uploaderAvatar = avatarSources?.firstOrNull()?.let { str((it as? JsonObject)?.get("url")) },
            duration = parseDuration(findBadgeText(v["contentImage"])) ?: 0,
            views = parseCount(stats.firstOrNull { it.contains("view", true) }),
            uploadedText = stats.firstOrNull { !it.contains("view", true) },
        )
    }

    private fun channelUrl(run: JsonObject): String? {
        val ep = findFirst(run, "browseEndpoint") ?: return null
        str(ep["canonicalBaseUrl"])?.let { return "https://www.youtube.com$it" }
        return str(ep["browseId"])?.let { "https://www.youtube.com/channel/$it" }
    }

    // ------------------------------------------------------------------ helpers

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun text(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return str(e)
        str(o["simpleText"])?.let { return it }
        str(o["content"])?.let { return it }
        val runs = o["runs"] as? JsonArray ?: return null
        return runs.joinToString("") { str((it as? JsonObject)?.get("text")).orEmpty() }.ifBlank { null }
    }

    private fun thumb(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return null
        val list = (o["thumbnails"] as? JsonArray) ?: (findFirst(o, "thumbnail")?.get("thumbnails") as? JsonArray) ?: return null
        val url = str((list.lastOrNull() as? JsonObject)?.get("url")) ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun findFirst(e: JsonElement?, key: String): JsonObject? {
        when (e) {
            is JsonObject -> {
                (e[key] as? JsonObject)?.let { return it }
                for (v in e.values) findFirst(v, key)?.let { return it }
            }
            is JsonArray -> for (v in e) findFirst(v, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun findString(e: JsonElement?, key: String): String? {
        when (e) {
            is JsonObject -> {
                str(e[key])?.let { return it }
                for (v in e.values) findString(v, key)?.let { return it }
            }
            is JsonArray -> for (v in e) findString(v, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    /** Finds a "12:34" style badge anywhere in the thumbnail overlay tree. */
    private fun findBadgeText(e: JsonElement?): String? {
        val regex = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
        fun rec(x: JsonElement?): String? = when (x) {
            is JsonPrimitive -> x.contentOrNull?.takeIf { regex.matches(it) }
            is JsonObject -> x.values.firstNotNullOfOrNull { rec(it) }
            is JsonArray -> x.firstNotNullOfOrNull { rec(it) }
            else -> null
        }
        return rec(e)
    }

    private fun parseDuration(text: String?): Long? {
        if (text == null || !text.contains(':')) return null
        return text.trim().split(':').mapNotNull { it.toLongOrNull() }.fold(0L) { acc, n -> acc * 60 + n }.takeIf { it > 0 }
    }

    private fun parseCount(text: String?): Long {
        if (text == null) return -1
        val t = text.lowercase(Locale.US)
        val num = Regex("[\\d.,]+").find(t)?.value ?: return -1
        val base = num.replace(",", "").toDoubleOrNull() ?: return -1
        val mult = when {
            Regex("[\\d.]\\s*k").containsMatchIn(t) -> 1_000.0
            Regex("[\\d.]\\s*m").containsMatchIn(t) -> 1_000_000.0
            Regex("[\\d.]\\s*b").containsMatchIn(t) -> 1_000_000_000.0
            else -> 1.0
        }
        return (base * mult).toLong()
    }
}

data class RemotePlaylist(val id: String, val title: String, val containsVideo: Boolean, val privacy: String?)
