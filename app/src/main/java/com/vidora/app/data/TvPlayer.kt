package com.vidora.app.data

import com.vidora.app.Graph
import com.vidora.app.jsc.JsChallengeSolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.net.URLEncoder
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

/** YouTube refused to play the video with this client (bot check, age gate, login required…). */
class PlayabilityException(val status: String, val reason: String) : IOException(reason) {
    val isBotCheck: Boolean get() = reason.contains("bot", true)
    val needsLogin: Boolean get() = status.equals("LOGIN_REQUIRED", true)
}

/** InnerTube clients we talk to directly (specs follow yt-dlp). */
enum class YtClient(
    val label: String,
    val clientName: String,
    val clientId: String,
    val version: String,
    val userAgent: String,
    val extra: Map<String, String> = emptyMap(),
    val supportsCookies: Boolean = false,
) {
    /** yt-dlp's default: full DASH ladder, accepts account cookies. URLs need the pre-roll wait. */
    TV("TV client", "TVHTML5", "7", "5.20260707",
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
        mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0"),
        supportsCookies = true),
    /** Safari web client: adaptive HLS up to 1080p (needs the n challenge solved in the manifest path). */
    WEB_SAFARI("Web · Safari HLS", "WEB", "1", "2.20260708.00.00",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"),
    /** Mobile web: only the 360p muxed stream (itag 18), but it rarely fails. */
    MWEB("Mobile web · 360p", "MWEB", "2", "2.20260708.05.00",
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"),
}

/**
 * Our own InnerTube player requests, used when NewPipe Extractor (visionOS client) is blocked —
 * typically "Sign in to confirm you're not a bot" on VPN / shared IPs.
 *
 *  - [YtClient.TV] (tv_samsung) carries the signed-in session (cookies + SAPISIDHASH), exactly
 *    like yt-dlp. Its URLs only start working after the pre-roll ad time YouTube announces in the
 *    response (≈5 s), so [Result.availableAt] tells the caller when to start.
 *  - [YtClient.WEB_SAFARI] and [YtClient.MWEB] are anonymous fallbacks.
 *
 * Signatures and `n` are solved by [JsChallengeSolver] (yt-dlp's EJS solver in a WebView) —
 * NewPipe's regex deobfuscator can't parse current players any more.
 */
object TvPlayer {
    val USER_AGENT = YtClient.TV.userAgent
    private const val ORIGIN = "https://www.youtube.com"

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val videoId: String,
        val title: String,
        val author: String?,
        val channelId: String?,
        val description: String,
        val lengthSeconds: Long,
        val views: Long,
        val thumbnail: String?,
        val isLive: Boolean,
        val videos: List<VideoOption>,
        val audios: List<AudioOption>,
        val hlsUrl: String?,
        val dashUrl: String?,
        val subtitles: List<SubtitleOption>,
        val signedIn: Boolean,
        val client: YtClient,
        /** Epoch ms from which the stream URLs are accepted (TV pre-roll wait). */
        val availableAt: Long,
    )

    /** Blocking — call from an IO thread. */
    fun fetch(videoId: String, signedIn: Boolean, client: YtClient = YtClient.TV): Result {
        val account = Graph.account
        val useAccount = signedIn && client.supportsCookies && account.isSignedIn
        val sts = runCatching { JsChallengeSolver.player().sts }.getOrNull()
            ?: runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
        val locale = Locale.getDefault()

        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.clientName)
                    put("clientVersion", client.version)
                    put("userAgent", client.userAgent)
                    client.extra.forEach { (k, v) -> put(k, v) }
                    put("hl", locale.language.ifBlank { "en" })
                    put("gl", locale.country.ifBlank { "US" })
                }
            }
            put("videoId", videoId)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    sts?.let { put("signatureTimestamp", it) }
                }
            }
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val req = Request.Builder()
            .url("$ORIGIN/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("Origin", ORIGIN)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.version)
        if (useAccount) {
            account.authHeaders(ORIGIN).forEach { (k, v) -> req.header(k, v) }
            req.header("X-Youtube-Bootstrap-Logged-In", "true")
        }
        val requestedAt = System.currentTimeMillis()

        val response = Graph.http.newCall(req.build()).execute().use { r ->
            if (useAccount) account.mergeSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IOException("YouTube rejected the session (HTTP ${r.code}). Sign out and sign in again.")
            if (!r.isSuccessful) throw IOException("${client.label} player request failed (HTTP ${r.code})")
            json.parseToJsonElement(text).jsonObject
        }

        val playability = response.obj("playabilityStatus")
        val status = playability.str("status") ?: "OK"
        if (!status.equals("OK", true)) {
            val reason = playability.str("reason")
                ?: playability.obj("errorScreen").obj("playerErrorMessageRenderer").obj("subreason").text()
                ?: status
            val loggedOut = response.obj("responseContext").obj("mainAppWebResponseContext").bool("loggedOut") == true
            if (useAccount && loggedOut) {
                throw PlayabilityException(status, "Your YouTube sign-in has expired or was rejected — sign out and sign in again in Settings → Account. ($reason)")
            }
            throw PlayabilityException(status, reason)
        }

        val details = response.obj("videoDetails")
        if (details.str("videoId") != null && details.str("videoId") != videoId) {
            throw IOException("YouTube returned a different video (IP rate-limited?)")
        }

        val streaming = response.obj("streamingData")
        val videos = mutableListOf<VideoOption>()
        val audios = mutableListOf<AudioOption>()

        // Collect every format, then solve all signature / n challenges in one batch.
        val raw = buildList {
            streaming.arr("formats").forEach { (it as? JsonObject)?.let { f -> add(f to false) } }
            streaming.arr("adaptiveFormats").forEach { (it as? JsonObject)?.let { f -> add(f to true) } }
        }.filter { (f, _) ->
            f["drmFamilies"] == null && f.str("type") != "FORMAT_STREAM_TYPE_OTF" &&
                (f.int("itag")?.let(ItagItem::isSupported) == true) &&
                (f.str("url") != null || f.str("signatureCipher") != null || f.str("cipher") != null)
        }
        val pending = raw.map { (f, adaptive) -> Triple(f, adaptive, PendingUrl.of(f)) }.filter { it.third != null }
        val hlsRaw = streaming.str("hlsManifestUrl")
        val hlsN = hlsRaw?.let { Regex("/n/([^/]+)/").find(it)?.groupValues?.get(1) }
        val noneSolved: Pair<Map<String, String>, Map<String, String>> = emptyMap<String, String>() to emptyMap<String, String>()
        val (sigs, ns) = if (pending.isEmpty() && hlsN == null) noneSolved else try {
            JsChallengeSolver.solve(
                pending.mapNotNull { it.third!!.sig },
                pending.mapNotNull { it.third!!.n } + listOfNotNull(hlsN),
            )
        } catch (t: Throwable) {
            android.util.Log.w("TvPlayer", "challenge solving failed", t)
            // Only formats that need nothing solved remain usable.
            noneSolved
        }

        fun handle(f: JsonObject, adaptive: Boolean, p: PendingUrl) {
            val itag = f.int("itag") ?: return
            val url = p.build(sigs, ns) ?: return
            val mimeFull = f.str("mimeType") ?: return
            val mime = mimeFull.substringBefore(';').trim()
            val codec = Regex("codecs=\"([^\"]+)\"").find(mimeFull)?.groupValues?.get(1)
            val length = f.str("contentLength")?.toLongOrNull() ?: -1L
            val bitrate = f.int("bitrate") ?: 0
            val webm = mime.endsWith("webm")
            if (mime.startsWith("video")) {
                val height = f.int("height") ?: 0
                val fps = f.int("fps") ?: 30
                videos += VideoOption(
                    url = url, itag = itag, height = height, fps = fps,
                    resolution = f.str("qualityLabel") ?: "${height}p",
                    mime = mime, suffix = if (webm) "webm" else "mp4", codec = codec,
                    videoOnly = adaptive, bitrate = bitrate, contentLength = length,
                )
            } else if (mime.startsWith("audio") && adaptive) {
                val track = f.obj("audioTrack")
                val hasTrack = track.isNotEmpty()
                audios += AudioOption(
                    url = url, itag = itag,
                    bitrateKbps = ((f.int("averageBitrate") ?: bitrate) / 1000).coerceAtLeast(0),
                    mime = mime, suffix = if (webm) "webm" else "m4a", codec = codec,
                    trackName = track.str("displayName"), contentLength = length,
                    trackId = track.str("id"),
                    trackType = when {
                        !hasTrack -> null
                        track.bool("audioIsDefault") == true -> "ORIGINAL"
                        else -> "DUBBED"
                    },
                    language = track.str("displayName"),
                )
            }
        }
        pending.forEach { (f, adaptive, p) -> handle(f, adaptive, p!!) }

        val subs = response.obj("captions").obj("playerCaptionsTracklistRenderer").arr("captionTracks").mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val base = t.str("baseUrl") ?: return@mapNotNull null
            val auto = t.str("kind") == "asr"
            val lang = t.str("languageCode") ?: return@mapNotNull null
            SubtitleOption(
                url = base.replace(Regex("&fmt=[^&]*"), "") + "&fmt=vtt",
                languageCode = lang,
                label = (t.obj("name").text() ?: lang) + if (auto) " (auto-generated)" else "",
                autoGenerated = auto, mime = "text/vtt",
            )
        }.distinctBy { it.languageCode + it.autoGenerated }.sortedWith(compareBy<SubtitleOption> { it.autoGenerated }.thenBy { it.label })

        // The HLS manifest carries its own n challenge in the path; unsolved it gives 403 on segments.
        val hls = when {
            hlsRaw == null -> null
            hlsN == null -> hlsRaw
            else -> ns[hlsN]?.let { hlsRaw.replace("/n/$hlsN/", "/n/$it/") }
        }
        if (videos.isEmpty() && audios.isEmpty() && hls == null) {
            val why = when {
                raw.isEmpty() && streaming.str("serverAbrStreamingUrl") != null -> "SABR-only"
                raw.isEmpty() -> "DRM or no formats"
                else -> "couldn't solve the stream challenge: ${JsChallengeSolver.lastStatus}"
            }
            throw IOException("No playable formats from the ${client.label} ($why)")
        }
        val availableAt = requestedAt + (adWaitSeconds(response) * 1000).toLong().let { if (it > 0) it + 500 else 0 }
        return Result(
            videoId = videoId,
            title = details.str("title") ?: "",
            author = details.str("author"),
            channelId = details.str("channelId"),
            description = details.str("shortDescription").orEmpty(),
            lengthSeconds = details.str("lengthSeconds")?.toLongOrNull() ?: 0,
            views = details.str("viewCount")?.toLongOrNull() ?: -1,
            thumbnail = details.obj("thumbnail").arr("thumbnails").lastOrNull()?.let { (it as? JsonObject)?.str("url") },
            isLive = details.bool("isLive") == true || (details.bool("isLiveContent") == true && hls != null),
            videos = videos.distinctBy { it.itag }.sortedWith(compareByDescending<VideoOption> { it.height }.thenByDescending { it.fps }),
            audios = audios.distinctBy { it.itag to it.trackId }.sortedByDescending { it.bitrateKbps },
            hlsUrl = hls,
            dashUrl = streaming.str("dashManifestUrl"),
            subtitles = subs,
            signedIn = useAccount,
            client = client,
            availableAt = availableAt,
        )
    }

    /**
     * Seconds of pre-roll ads YouTube announces for this playback (yt-dlp's
     * `_get_available_at_timestamp`). TV stream URLs answer 403 until that time has passed.
     */
    internal fun adWaitSeconds(pr: JsonObject): Double {
        var total = 0.0
        fun renderer(r: JsonObject?) {
            val ivr = r?.get("instreamVideoAdRenderer") as? JsonObject ?: return
            val skip = ivr.str("skipOffsetMilliseconds")?.toDoubleOrNull()?.div(1000)
            val len = ivr.str("playerVars")?.let { pv -> Regex("(?:^|&)length_seconds=([0-9.]+)").find(pv)?.groupValues?.get(1)?.toDoubleOrNull() }
            (skip ?: len)?.let { total += it }
        }
        pr.arr("adPlacements").forEach { p ->
            val apr = (p as? JsonObject)?.obj("adPlacementRenderer") ?: return@forEach
            if (apr.obj("config").obj("adPlacementConfig").str("kind") == "AD_PLACEMENT_KIND_START") renderer(apr.obj("renderer"))
        }
        pr.arr("adSlots").forEach { p ->
            val asr = (p as? JsonObject)?.obj("adSlotRenderer") ?: return@forEach
            if (asr.obj("adSlotMetadata").str("triggerEvent") != "SLOT_TRIGGER_EVENT_BEFORE_CONTENT") return@forEach
            val rc = asr.obj("fulfillmentContent").obj("fulfilledLayout").obj("playerBytesAdLayoutRenderer").obj("renderingContent")
            renderer(rc)
            rc.obj("playerBytesSequentialLayoutRenderer").arr("sequentialLayouts").forEach { l ->
                renderer((l as? JsonObject)?.obj("playerBytesAdLayoutRenderer")?.obj("renderingContent"))
            }
        }
        return total.coerceIn(0.0, 60.0)
    }

    /** A format URL that may still need its signature and/or `n` challenge solved. */
    internal class PendingUrl(val base: String, val sig: String?, val sp: String, val n: String?) {
        fun build(sigs: Map<String, String>, ns: Map<String, String>): String? {
            var url = base
            if (sig != null) {
                val solved = sigs[sig] ?: return null
                url += "&$sp=" + URLEncoder.encode(solved, "UTF-8")
            }
            if (n != null) {
                val solved = ns[n] ?: return null // unsolved n = throttled to death / 403
                url = replaceQueryParam(url, "n", solved)
            }
            return url
        }

        companion object {
            fun of(f: JsonObject): PendingUrl? {
                val direct = (f["url"] as? JsonPrimitive)?.contentOrNull
                val (base, sig, sp) = if (direct != null) Triple(direct, null, "signature") else {
                    val cipher = (f["signatureCipher"] as? JsonPrimitive)?.contentOrNull ?: (f["cipher"] as? JsonPrimitive)?.contentOrNull ?: return null
                    val q = parseQuery(cipher)
                    Triple(q["url"] ?: return null, q["s"], q["sp"] ?: "signature")
                }
                val n = queryParam(base, "n")
                return PendingUrl(base, sig, sp, n)
            }
        }
    }

    internal fun queryParam(url: String, key: String): String? =
        url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }

    internal fun replaceQueryParam(url: String, key: String, value: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val parts = url.substring(q + 1).split('&').map { if (it.startsWith("$key=")) "$key=" + URLEncoder.encode(value, "UTF-8") else it }
        return url.substring(0, q + 1) + parts.joinToString("&")
    }

    internal fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    // ---------------------------------------------------------------- tiny JSON helpers
    private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.arr(k: String): JsonArray = this[k] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.text(): String? = str("simpleText")
        ?: (this["runs"] as? JsonArray)?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }?.ifBlank { null }
}
