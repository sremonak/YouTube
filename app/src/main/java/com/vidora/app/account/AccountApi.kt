package com.vidora.app.account

import com.vidora.app.Graph
import com.vidora.app.data.ChannelItem
import com.vidora.app.data.Entry
import com.vidora.app.data.FeedbackAction
import com.vidora.app.data.NetworkDownloader
import com.vidora.app.data.PageResult
import com.vidora.app.data.PlaylistItem
import com.vidora.app.data.VideoItem
import kotlinx.coroutines.flow.first
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.Locale

/** Thrown when YouTube no longer accepts the stored session. */
class SignedOutException(message: String) : IOException(message)

private const val MUSIC_VERSION = "1.20260707.12.00"

/** A personalised filter chip of a YouTube feed ("All", "Music", "Mixes", "Gaming"…). */
data class FeedChip(val label: String, val token: String?, val selected: Boolean)

/** One page of an account feed plus the feed's own chips (first page only). */
class AccountPage(val page: PageResult, val chips: List<FeedChip>)

/** Account-only feeds of the signed-in user. */
enum class AccountFeed(val label: String, val browseId: String, val music: Boolean = false) {
    RECOMMENDED("Recommended for you", "FEwhat_to_watch"),
    SUBSCRIPTIONS("Subscriptions feed", "FEsubscriptions"),
    LIKED("Liked videos", "VLLL"),
    WATCH_LATER("Watch later", "VLWL"),
    HISTORY("Watch history", "FEhistory"),
    PLAYLISTS("Your playlists", "FEplaylist_aggregation"),
    MUSIC_LIKED("YouTube Music · Liked songs", "FEmusic_liked_videos", music = true),
    MUSIC_PLAYLISTS("YouTube Music · Library playlists", "FEmusic_liked_playlists", music = true),
    MUSIC_ALBUMS("YouTube Music · Albums", "FEmusic_liked_albums", music = true),
    MUSIC_ARTISTS("YouTube Music · Subscribed artists", "FEmusic_library_corpus_track_artists", music = true),
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

    // ------------------------------------------------------------------ comments (signed in)

    /** Like / unlike a comment with YouTube's action token. */
    suspend fun commentAction(action: String) {
        post("comment/perform_comment_action", buildJsonObject {
            put("context", context()); put("actions", JsonArray(listOf(JsonPrimitive(action))))
        })
    }

    /** Posts a top-level comment; returns true when YouTube accepted it. */
    suspend fun createComment(params: String, text: String): Boolean {
        val res = post("comment/create_comment", buildJsonObject {
            put("context", context()); put("createCommentParams", params); put("commentText", text)
        })
        return res["actionResult"]?.toString()?.contains("STATUS_SUCCEEDED") != false
    }

    suspend fun replyComment(params: String, text: String): Boolean {
        val res = post("comment/create_comment_reply", buildJsonObject {
            put("context", context()); put("createReplyParams", params); put("commentText", text)
        })
        return res["actionResult"]?.toString()?.contains("STATUS_SUCCEEDED") != false
    }

    private suspend fun post(endpoint: String, body: JsonObject, requireAuth: Boolean = true): JsonObject =
        post(endpoint, body, requireAuth, WEB_HOST)

    /** Host of an InnerTube call: www.youtube.com (WEB) or music.youtube.com (WEB_REMIX). */
    private class Host(val origin: String, val clientId: String, val version: () -> String)

    private suspend fun post(endpoint: String, body: JsonObject, requireAuth: Boolean, host: Host, retried: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        val account = Graph.account
        if (requireAuth && !account.isSignedIn) throw IllegalStateException("Not signed in")
        val builder = Request.Builder()
            .url("${host.origin}/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", NetworkDownloader.USER_AGENT)
            .header("Cache-Control", "no-cache, no-store")
            .header("Origin", host.origin)
            .header("Referer", "${host.origin}/")
            .header("X-Youtube-Client-Name", host.clientId)
            .header("X-Youtube-Client-Version", host.version())
        val authed = account.isSignedIn && !Graph.settings.flow.first().incognito
        if (requireAuth && !authed) throw SignedOutException("YouTube account data is unavailable while signed out or incognito")
        val usedSessionHash = authed && account.userSessionId != null && account.sessionHashEnabled
        if (authed) account.authHeaders(host.origin).forEach { (k, v) -> builder.header(k, v) }
        val res = client.newCall(builder.build()).execute().use { r ->
            if (authed) account.mergeSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw SignedOutException("YouTube rejected your sign-in (HTTP ${r.code}). Sign out and sign in again in Settings → Account.")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            json.parseToJsonElement(text).jsonObject
        }
        if (authed) {
            val ctx = res["responseContext"] as? JsonObject
            val main = ctx?.get("mainAppWebResponseContext") as? JsonObject
            val loggedOut = str(main?.get("loggedOut")) == "true" || trackingParam(ctx, "logged_in") == "0"
            if (loggedOut) {
                lastLoggedIn = false
                if (usedSessionHash && !retried) {
                    // The session-bound hash wasn't accepted: fall back to the classic SAPISIDHASH once.
                    account.sessionHashEnabled = false
                    return@withContext post(endpoint, body, requireAuth, host, retried = true)
                }
                if (requireAuth) throw SignedOutException(
                    "YouTube is treating Vidora as signed out, so your history and recommendations can't load. " +
                        "Open Settings → Account → Sign out, then sign in again."
                )
            } else if (main != null) {
                lastLoggedIn = true
                // Remember the account's session ids (brand-account channels need X-Goog-PageId).
                str(main["datasyncId"])?.let { id ->
                    if (account.userSessionId == null && account.delegatedSessionId == null) account.applyDataSyncId(id)
                }
            }
        }
        res
    }

    /** Last observed sign-in state reported by YouTube (null = not known yet). */
    @Volatile var lastLoggedIn: Boolean? = null
        private set

    private fun trackingParam(ctx: JsonObject?, key: String): String? {
        val services = ctx?.get("serviceTrackingParams") as? JsonArray ?: return null
        for (svc in services) {
            val params = (svc as? JsonObject)?.get("params") as? JsonArray ?: continue
            for (p in params) {
                val o = p as? JsonObject ?: continue
                if (str(o["key"]) == key) return str(o["value"])
            }
        }
        return null
    }

    private val webVersion: String by lazy {
        runCatching { YoutubeParsingHelper.getClientVersion() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "2.20260708.00.00"
    }
    private val WEB_HOST = Host("https://www.youtube.com", "1") { webVersion }
    private val MUSIC_HOST = Host("https://music.youtube.com", "67") { MUSIC_VERSION }

    private suspend fun context(music: Boolean = false): JsonObject {
        val version = if (music) MUSIC_VERSION else withContext(Dispatchers.IO) { webVersion }
        val locale = Locale.getDefault()
        return buildJsonObject {
            putJsonObject("client") {
                put("clientName", if (music) "WEB_REMIX" else "WEB")
                put("clientVersion", version)
                put("hl", locale.language.ifBlank { "en" })
                put("gl", locale.country.ifBlank { "US" })
            }
            putJsonObject("user") { put("lockedSafetyMode", false) }
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
    suspend fun watchNext(videoId: String): List<VideoItem> = watchPage(videoId).related

    /** Title, channel, views, date, description… from the watch page (TV player responses lack them). */
    data class WatchMeta(
        val title: String?, val channel: String?, val channelUrl: String?, val channelAvatar: String?,
        val subscribers: Long, val views: Long, val dateText: String?, val description: String?, val likes: Long,
    )
    data class WatchPage(val related: List<VideoItem>, val meta: WatchMeta?)

    suspend fun watchPage(videoId: String): WatchPage {
        val res = post("next", buildJsonObject {
            put("context", context()); put("videoId", videoId)
            put("contentCheckOk", true); put("racyCheckOk", true)
        }, requireAuth = false)
        val out = mutableListOf<Entry>()
        val secondary = res["contents"]?.let { findFirst(it, "secondaryResults") }
        walk(secondary ?: res, out, mutableListOf())
        val related = out.filterIsInstance<Entry.Video>().map { it.video }
            .filter { !it.url.contains(videoId) && isRealVideo(it) }
            .distinctBy { it.url }
        val meta = runCatching { parseWatchMeta(res) }.getOrNull()
        return WatchPage(related, meta)
    }

    /** Drops YouTube's own promos ("Upgrade to YouTube Premium…") that look like videos. */
    private fun isRealVideo(v: VideoItem): Boolean {
        val id = v.url.substringAfter("v=", "").substringBefore('&')
        if (id.length != 11) return false
        if (v.title.contains("YouTube Premium", true) || v.title.contains("YouTube Music Premium", true)) return false
        return true
    }

    private fun parseWatchMeta(res: JsonObject): WatchMeta? {
        val primary = findFirst(res, "videoPrimaryInfoRenderer")
        val secondary = findFirst(res, "videoSecondaryInfoRenderer")
        if (primary == null && secondary == null) return null
        val owner = secondary?.let { findFirst(it, "videoOwnerRenderer") }
        val viewsText = primary?.let { findFirst(it, "videoViewCountRenderer") }?.let { text(it["viewCount"]) ?: text(it["originalViewCount"]) }
        val ownerRun = (owner?.get("title") as? JsonObject)?.get("runs")?.let { (it as? JsonArray)?.firstOrNull() as? JsonObject }
        val likeText = primary?.let { p ->
            findString(p, "likeCountWithLikeText") ?: findFirst(p, "likeButtonViewModel")?.let { findString(it, "title") }
        }
        return WatchMeta(
            title = text(primary?.get("title")),
            channel = text(owner?.get("title")),
            channelUrl = ownerRun?.let { channelUrl(it) } ?: owner?.let { channelUrl(it) },
            channelAvatar = owner?.let { thumb(it["thumbnail"]) },
            subscribers = parseCount(text(owner?.get("subscriberCountText"))),
            views = parseCount(viewsText),
            dateText = text(primary?.get("relativeDateText")) ?: text(primary?.get("dateText")),
            description = (secondary?.get("attributedDescription") as? JsonObject)?.let { str(it["content"]) }
                ?: text(secondary?.get("description")),
            likes = likeText?.let { parseCount(it) } ?: -1,
        )
    }

    // ------------------------------------------------------------------ feeds

    suspend fun feed(feed: AccountFeed): PageResult = feedWithChips(feed).page

    /** A feed's first page together with YouTube's own chips for it (Home: All / Music / Mixes / Gaming…). */
    suspend fun feedWithChips(feed: AccountFeed): AccountPage {
        if (feed.music) {
            val res = post("browse", buildJsonObject { put("context", context(music = true)); put("browseId", feed.browseId) }, true, MUSIC_HOST)
            return AccountPage(toPage(res, music = true), emptyList())
        }
        val res = post("browse", buildJsonObject { put("context", context()); put("browseId", feed.browseId) })
        return AccountPage(toPage(res), collectChips(res))
    }

    suspend fun shorts(): PageResult {
        val res = post("browse", buildJsonObject { put("context", context()); put("browseId", "FEshorts") }, requireAuth = false)
        return toPage(res)
    }

    /** Loads the feed behind one of YouTube's chips (they are continuation tokens). */
    suspend fun chip(token: String): PageResult = continuation(token)

    private suspend fun continuation(token: String): PageResult {
        val res = post("browse", buildJsonObject { put("context", context()); put("continuation", token) })
        return toPage(res)
    }

    internal fun toPage(res: JsonObject, music: Boolean = false): PageResult {
        val entries = mutableListOf<Entry>()
        val tokens = mutableListOf<String>()
        if (music) walkMusic(res, entries, tokens) else walk(res, entries, tokens)
        val items = entries.distinctBy { it.key }
        if (items.isEmpty()) emptyMessage(res)?.let { throw IOException(it) }
        val token = tokens.lastOrNull()
        val next: (suspend () -> PageResult)? = if (token == null || items.isEmpty()) null else ({
            if (music) musicContinuation(token) else continuation(token)
        })
        return PageResult(items, next)
    }

    /** YouTube's own explanation for an empty feed ("Your watch history is off", "Try searching to get started"…). */
    private fun emptyMessage(res: JsonObject): String? {
        for (key in listOf("feedNudgeRenderer", "backgroundPromoRenderer", "messageRenderer", "promptRenderer", "emptyStateViewModel")) {
            val r = findFirst(res, key) ?: continue
            val title = text(r["title"]) ?: text(r["text"]) ?: findFirst(r, "title")?.let { text(it) }
            val body = text(r["subtitle"]) ?: text(r["bodyText"]) ?: text(r["body"])
            val msg = listOfNotNull(title, body).joinToString(" — ").ifBlank { null }
            if (msg != null) return "YouTube says: $msg"
        }
        return null
    }

    internal fun collectChips(res: JsonObject): List<FeedChip> {
        val out = mutableListOf<FeedChip>()
        fun rec(e: JsonElement?) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (v !is JsonObject) { rec(v); continue }
                    when (k) {
                        "chipCloudChipRenderer" -> {
                            val label = text(v["text"]) ?: continue
                            out += FeedChip(label, findFirst(v["navigationEndpoint"], "continuationCommand")?.let { str(it["token"]) }, str(v["isSelected"]) == "true")
                        }
                        "chipViewModel" -> {
                            val label = text(v["text"]) ?: continue
                            out += FeedChip(label, findFirst(v["tapCommand"], "continuationCommand")?.let { str(it["token"]) }, str(v["selected"]) == "true")
                        }
                        else -> rec(v)
                    }
                }
                is JsonArray -> e.forEach(::rec)
                else -> Unit
            }
        }
        // Only the feed's own chip bar (header), not chips embedded in shelves.
        rec(findFirst(res, "feedFilterChipBarRenderer") ?: findFirst(res, "chipBarViewModel"))
        return out.distinctBy { it.label }
    }

    /** Sends one of YouTube's feedback tokens (Not interested, Remove from watch history…). */
    suspend fun sendFeedback(token: String) {
        val res = post("feedback", buildJsonObject {
            put("context", context())
            put("feedbackTokens", JsonArray(listOf(JsonPrimitive(token))))
            put("isFeedbackTokenUnencrypted", false)
            put("shouldMerge", false)
        })
        val ok = findString(res, "isProcessed")
        if (ok == "false") throw IOException("YouTube didn't accept the feedback")
    }

    /** A playlist through the account (works for private playlists, Liked videos and Watch later). */
    suspend fun playlistDetails(playlistId: String): com.vidora.app.data.PlaylistDetails {
        val res = post("browse", buildJsonObject { put("context", context()); put("browseId", "VL$playlistId") })
        val title = findFirst(res, "playlistMetadataRenderer")?.let { str(it["title"]) }
            ?: findFirst(res, "microformatDataRenderer")?.let { str(it["title"]) }
            ?: findFirst(res, "pageHeaderViewModel")?.let { h -> findFirst(h["title"], "text")?.let { str(it["content"]) } }
            ?: playlistId
        val description = findFirst(res, "playlistMetadataRenderer")?.let { str(it["description"]) }.orEmpty()
        val page = toPage(res)
        val firstVideo = page.items.firstNotNullOfOrNull { (it as? Entry.Video)?.video }
        return com.vidora.app.data.PlaylistDetails(
            playlist = PlaylistItem(
                url = "https://www.youtube.com/playlist?list=$playlistId", name = title,
                thumbnail = firstVideo?.thumbnail, uploader = Graph.account.session.value?.name,
            ),
            description = description,
            first = page,
        )
    }

    /** YouTube's loudness (dB above reference) for Stable volume; works signed out too. */
    suspend fun loudness(videoId: String): Double? {
        val res = post("player", buildJsonObject {
            put("context", context()); put("videoId", videoId)
            put("contentCheckOk", true); put("racyCheckOk", true)
        }, requireAuth = false)
        return com.vidora.app.data.TvPlayer.loudnessDb(res)
    }

    // ------------------------------------------------------------------ YouTube Music

    /** music.youtube.com home of the signed-in account (Quick picks, Mixed for you, Listen again…). */
    suspend fun musicHome(): PageResult {
        val res = post("browse", buildJsonObject { put("context", context(music = true)); put("browseId", "FEmusic_home") }, true, MUSIC_HOST)
        return toPage(res, music = true)
    }

    private suspend fun musicContinuation(token: String): PageResult {
        val res = post("browse", buildJsonObject { put("context", context(music = true)); put("continuation", token) }, true, MUSIC_HOST)
        return toPage(res, music = true)
    }

    private fun walkMusic(e: JsonElement, out: MutableList<Entry>, tokens: MutableList<String>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                when {
                    v !is JsonObject -> walkMusic(v, out, tokens)
                    k == "musicTwoRowItemRenderer" -> parseMusicTwoRow(v)?.let(out::add)
                    k == "musicResponsiveListItemRenderer" -> parseMusicListItem(v)?.let(out::add)
                    k == "nextContinuationData" -> str(v["continuation"])?.let(tokens::add)
                    k == "continuationItemRenderer" -> findString(v, "token")?.let(tokens::add)
                    else -> walkMusic(v, out, tokens)
                }
            }
            is JsonArray -> e.forEach { walkMusic(it, out, tokens) }
            else -> Unit
        }
    }

    private fun parseMusicTwoRow(v: JsonObject): Entry? {
        val title = text(v["title"]) ?: return null
        val subtitle = text(v["subtitle"])
        val thumbnail = thumb(findFirst(v["thumbnailRenderer"], "thumbnail"))
        val nav = v["navigationEndpoint"] as? JsonObject
        val watch = nav?.get("watchEndpoint") as? JsonObject
        val videoId = str(watch?.get("videoId"))
        if (videoId != null) {
            return Entry.Video(VideoItem(
                url = "https://www.youtube.com/watch?v=$videoId", title = title,
                thumbnail = thumbnail ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                uploader = subtitle?.substringAfter(" • ")?.ifBlank { subtitle } ?: subtitle,
            ))
        }
        val browseId = str((nav?.get("browseEndpoint") as? JsonObject)?.get("browseId"))
        val playlistId = when {
            browseId == null -> str(watch?.get("playlistId"))
            browseId.startsWith("VL") -> browseId.removePrefix("VL")
            else -> null
        } ?: findFirst(v["thumbnailOverlay"], "watchPlaylistEndpoint")?.let { str(it["playlistId"]) }
            ?: return null
        return Entry.Playlist(PlaylistItem(url = "https://www.youtube.com/playlist?list=$playlistId", name = title, thumbnail = thumbnail, uploader = subtitle))
    }

    private fun parseMusicListItem(v: JsonObject): Entry? {
        val videoId = str((v["playlistItemData"] as? JsonObject)?.get("videoId"))
            ?: findFirst(v["overlay"], "watchEndpoint")?.let { str(it["videoId"]) } ?: return null
        val columns = (v["flexColumns"] as? JsonArray).orEmpty().map { text(findFirst(it, "musicResponsiveListItemFlexColumnRenderer")?.get("text")) }
        val title = columns.getOrNull(0) ?: return null
        return Entry.Video(VideoItem(
            url = "https://www.youtube.com/watch?v=$videoId", title = title,
            thumbnail = thumb(findFirst(v["thumbnail"], "thumbnail")) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            uploader = columns.getOrNull(1)?.substringBefore(" • "),
        ))
    }

    // ------------------------------------------------------------------ watch history

    /**
     * Tells YouTube the video was watched, so it lands in the account's watch history and shapes
     * recommendations — the same playback/watchtime stats pings the official player sends
     * (algorithm from yt-dlp's `--mark-watched`).
     */
    suspend fun markWatched(videoId: String, watchedSeconds: Double = 0.0, startSeconds: Double = 0.0): Boolean = withContext(Dispatchers.IO) {
        val account = Graph.account
        val seconds = WatchHistoryPolicy.seconds(watchedSeconds)
        val privacy = Graph.settings.flow.first()
        if (!WatchHistoryPolicy.eligible(account.isSignedIn, privacy.syncWatchHistory, privacy.incognito, true) || seconds <= 0) throw IOException("History sync disabled, signed out, or no measured playback")
        val owner = account.session.value?.signedInAt
        val tracking = playbackTracking(videoId) ?: throw IOException("YouTube returned no authenticated history tracking URLs")
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val rnd = java.security.SecureRandom()
        val cpn = (1..16).map { alphabet[rnd.nextInt(64)] }.joinToString("")
        val historyClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        for ((i, key) in listOf("videostatsPlaybackUrl", "videostatsWatchtimeUrl").withIndex()) {
            val base = str((tracking[key] as? JsonObject)?.get("baseUrl")) ?: throw IOException("YouTube omitted $key")
            val url = base.toHttpUrlOrNull() ?: throw IOException("Invalid history tracking URL")
            if (!WatchHistoryPolicy.allowedTrackingUrl(url.scheme, url.host, url.encodedPath)) throw IOException("History tracking host was rejected")
            val currentSettings = Graph.settings.flow.first()
            if (!account.isSignedIn || account.session.value?.signedInAt != owner || currentSettings.incognito || !currentSettings.syncWatchHistory) throw IOException("History sync cancelled by privacy/account change")
            val duration = url.queryParameter("len")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: (WatchHistoryPolicy.seconds(startSeconds) + seconds)
            val start = WatchHistoryPolicy.seconds(startSeconds).coerceAtMost(duration)
            val len = minOf(start + seconds, duration).toString()
            val b = url.newBuilder()
                .setQueryParameter("ver", "2").setQueryParameter("cpn", cpn)
                .setQueryParameter("cmt", len).setQueryParameter("el", "detailpage")
            if (i == 1) b.setQueryParameter("st", start.toString()).setQueryParameter("et", len)
            val req = Request.Builder().url(b.build()).header("User-Agent", NetworkDownloader.USER_AGENT)
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")
            account.authHeaders().forEach { (k, v) -> req.header(k, v) }
            historyClient.newCall(req.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("YouTube history rejected $key: HTTP ${response.code}")
            }
        }
        true
    }

    /** Never substitute an anonymous TV response for account-bound history tracking. */
    private suspend fun playbackTracking(videoId: String): JsonObject? {
        val web = post("player", buildJsonObject {
            put("context", context()); put("videoId", videoId)
            put("contentCheckOk", true); put("racyCheckOk", true)
        })
        return web["playbackTracking"] as? JsonObject
    }

    // ------------------------------------------------------------------ diagnostics

    /** Human-readable account check (no secrets): is YouTube accepting the session, and do the feeds load? */
    suspend fun diagnose(): String {
        val account = Graph.account
        val lines = mutableListOf<String>()
        val cookies = account.cookieReport()
        lines += "Cookies: " + cookies.entries.joinToString(" ") { (k, v) -> (if (v) "✓" else "✗") + k }
        val menu = runCatching { post("account/account_menu", buildJsonObject { put("context", context()) }, requireAuth = false) }
        menu.onFailure { lines += "Account: ERROR ${it.message}" }
        menu.onSuccess { res ->
            val name = findFirst(res, "activeAccountHeaderRenderer")?.let { text(it["accountName"]) }
            lines += "YouTube sees you as: " + when (lastLoggedIn) { true -> "SIGNED IN"; false -> "SIGNED OUT"; null -> "unknown" } + (name?.let { " ($it)" } ?: "")
            lines += "Session id: " + (if (account.userSessionId != null) "✓" else "✗") + (if (account.delegatedSessionId != null) "  · brand channel ✓" else "")
        }
        for (feed in listOf(AccountFeed.RECOMMENDED, AccountFeed.HISTORY, AccountFeed.SUBSCRIPTIONS, AccountFeed.LIKED)) {
            val r = runCatching { feedWithChips(feed) }
            lines += feed.label + ": " + r.fold(
                { p -> "${p.page.items.size} items" + (if (p.chips.isNotEmpty()) " · chips: " + p.chips.joinToString(", ") { it.label } else "") },
                { "ERROR ${it.message}" })
        }
        lines += "YouTube Music: " + runCatching { musicHome() }.fold({ "${it.items.size} items" }, { "ERROR ${it.message}" })
        return lines.joinToString("\n")
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

    private val playlistKeys = setOf("playlistRenderer", "gridPlaylistRenderer", "compactPlaylistRenderer", "radioRenderer", "compactRadioRenderer", "gridRadioRenderer")

    private fun walk(e: JsonElement, out: MutableList<Entry>, tokens: MutableList<String>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                when {
                    v !is JsonObject -> walk(v, out, tokens)
                    k in videoKeys -> parseVideoRenderer(v)?.let { out += Entry.Video(it) }
                    k == "reelItemRenderer" -> parseReel(v)?.let { out += Entry.Video(it) }
                    k == "shortsLockupViewModel" -> parseShortsLockup(v)?.let { out += Entry.Video(it) }
                    k == "lockupViewModel" -> parseLockup(v)?.let(out::add)
                    k in playlistKeys -> parsePlaylistRenderer(v)?.let(out::add)
                    // The feed's next page lives in a continuationItemRenderer; chip bars carry their own tokens.
                    k == "continuationItemRenderer" -> findString(v, "token")?.let(tokens::add)
                    k == "nextContinuationData" -> str(v["continuation"])?.let(tokens::add)
                    k == "feedFilterChipBarRenderer" || k == "chipBarViewModel" || k == "chipCloudRenderer" -> Unit
                    k.endsWith("adSlotRenderer", ignoreCase = true) || k.contains("promo", true) ||
                        k.endsWith("AdRenderer") || k == "adSlotViewModel" || k == "statementBannerRenderer" -> Unit
                    else -> walk(v, out, tokens)
                }
            }
            is JsonArray -> e.forEach { walk(it, out, tokens) }
            else -> Unit
        }
    }

    private fun parseReel(v: JsonObject): VideoItem? {
        val id = str(v["videoId"]) ?: return null
        return VideoItem(
            url = "https://www.youtube.com/shorts/$id",
            title = text(v["headline"]) ?: text(v["accessibility"]) ?: return null,
            thumbnail = thumb(v["thumbnail"]) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            views = parseCount(text(v["viewCountText"])),
            isShort = true,
        )
    }

    private fun parseShortsLockup(v: JsonObject): VideoItem? {
        val id = findFirst(v, "reelWatchEndpoint")?.let { str(it["videoId"]) }
            ?: str(v["videoId"])
            ?: str(v["entityId"])?.removePrefix("shorts-shelf-item-")?.takeIf { it.length == 11 } ?: return null
        val overlay = v["overlayMetadata"] as? JsonObject
        val sources = findArray(v["thumbnail"], "sources")
        return VideoItem(
            url = "https://www.youtube.com/shorts/$id",
            title = text(overlay?.get("primaryText")) ?: str(v["accessibilityText"])?.substringBefore(",") ?: "Short",
            thumbnail = sources?.lastOrNull()?.let { str((it as? JsonObject)?.get("url")) }?.let { if (it.startsWith("//")) "https:$it" else it } ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            views = parseCount(text(overlay?.get("secondaryText"))),
            isShort = true,
        )
    }

    private fun parsePlaylistRenderer(v: JsonObject): Entry? {
        val id = str(v["playlistId"]) ?: return null
        val firstVideo = findFirst(v["navigationEndpoint"], "watchEndpoint")?.let { str(it["videoId"]) }
        val url = if (id.startsWith("RD") && firstVideo != null) "https://www.youtube.com/watch?v=$firstVideo&list=$id"
        else "https://www.youtube.com/playlist?list=$id"
        val count = text(v["videoCountText"]) ?: text(v["videoCountShortText"]) ?: str(v["videoCount"])
        return Entry.Playlist(PlaylistItem(
            url = url,
            name = text(v["title"]) ?: return null,
            thumbnail = thumb(v["thumbnail"]) ?: thumb((v["thumbnails"] as? JsonArray)?.firstOrNull())
                ?: firstVideo?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
            uploader = text(v["longBylineText"]) ?: text(v["shortBylineText"]),
            count = count?.let { Regex("[\\d,]+").find(it)?.value?.replace(",", "")?.toLongOrNull() } ?: -1,
        ))
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
            watchedPercent = watchedPercent(v),
            feedback = feedbackActions(v["menu"]),
        )
    }

    private fun parseLockup(v: JsonObject): Entry? {
        val type = str(v["contentType"]).orEmpty()
        if (type.contains("VIDEO")) return parseLockupVideo(v)?.let { Entry.Video(it) }
        if (!(type.contains("PLAYLIST") || type.contains("ALBUM") || type.contains("PODCAST"))) return null
        val id = str(v["contentId"]) ?: return null
        val meta = findFirst(v, "lockupMetadataViewModel") ?: return null
        val title = text(meta["title"]) ?: return null
        val firstVideo = findFirst(v["rendererContext"], "watchEndpoint")?.let { str(it["videoId"]) }
        val url = if (id.startsWith("RD") && firstVideo != null) "https://www.youtube.com/watch?v=$firstVideo&list=$id"
        else "https://www.youtube.com/playlist?list=$id"
        val sources = (findFirst(v["contentImage"] ?: v, "image")?.get("sources") as? JsonArray)
        val rows = (findFirst(meta, "contentMetadataViewModel")?.get("metadataRows") as? JsonArray).orEmpty()
        val firstPart = rows.firstNotNullOfOrNull { row ->
            ((row as? JsonObject)?.get("metadataParts") as? JsonArray)?.firstNotNullOfOrNull { text((it as? JsonObject)?.get("text")) }
        }
        val badge = findBadgeCount(v["contentImage"])
        return Entry.Playlist(PlaylistItem(
            url = url, name = title,
            thumbnail = sources?.lastOrNull()?.let { str((it as? JsonObject)?.get("url")) } ?: firstVideo?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
            uploader = firstPart,
            count = badge ?: -1,
        ))
    }

    /** "25 videos" style thumbnail badge → 25. */
    private fun findBadgeCount(e: JsonElement?): Long? {
        val regex = Regex("^([\\d,]+)\\s+\\p{L}+")
        fun rec(x: JsonElement?): Long? = when (x) {
            is JsonPrimitive -> x.contentOrNull?.let { regex.find(it)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() }
            is JsonObject -> x.values.firstNotNullOfOrNull { rec(it) }
            is JsonArray -> x.firstNotNullOfOrNull { rec(it) }
            else -> null
        }
        return rec(e)
    }

    private fun parseLockupVideo(v: JsonObject): VideoItem? {
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
            watchedPercent = watchedPercent(v),
            feedback = feedbackActions(meta["menuButton"] ?: v),
        )
    }

    /** Red "already watched" bar: classic overlay or the newer progress-bar view model. */
    private fun watchedPercent(v: JsonObject): Int {
        findFirst(v, "thumbnailOverlayResumePlaybackRenderer")?.let { o -> str(o["percentDurationWatched"])?.toIntOrNull()?.let { return it } }
        findFirst(v, "thumbnailOverlayProgressBarViewModel")?.let { o -> str(o["startPercent"])?.toIntOrNull()?.let { return it } }
        return -1
    }

    /** YouTube's feedback menu entries (Not interested, Don't recommend channel, Remove from history…). */
    private fun feedbackActions(menu: JsonElement?): List<FeedbackAction> {
        if (menu == null) return emptyList()
        val out = mutableListOf<FeedbackAction>()
        fun rec(e: JsonElement?) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (v !is JsonObject) { rec(v); continue }
                    when (k) {
                        "menuServiceItemRenderer" -> {
                            val token = findFirst(v["serviceEndpoint"], "feedbackEndpoint")?.let { str(it["feedbackToken"]) }
                            val label = text(v["text"])
                            if (token != null && label != null) out += FeedbackAction(label, token, findString(v["icon"], "iconType"))
                        }
                        "listItemViewModel" -> {
                            val token = findFirst(v["rendererContext"], "feedbackEndpoint")?.let { str(it["feedbackToken"]) }
                            val label = text(v["title"])
                            if (token != null && label != null) out += FeedbackAction(label, token, findString(v["leadingImage"], "imageName"))
                        }
                        else -> rec(v)
                    }
                }
                is JsonArray -> e.forEach(::rec)
                else -> Unit
            }
        }
        rec(menu)
        return out.distinctBy { it.token }
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

    private fun findArray(e: JsonElement?, key: String): JsonArray? {
        when (e) {
            is JsonObject -> {
                (e[key] as? JsonArray)?.let { return it }
                for (v in e.values) findArray(v, key)?.let { return it }
            }
            is JsonArray -> for (v in e) findArray(v, key)?.let { return it }
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
