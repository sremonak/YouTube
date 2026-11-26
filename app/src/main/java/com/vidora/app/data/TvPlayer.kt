package com.vidora.app.data

import com.vidora.app.Graph
import com.vidora.app.potoken.PoTokenProviderImpl
import kotlinx.coroutines.runBlocking
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
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

/** YouTube refused to play the video with this client (bot check, age gate, login required…). */
class PlayabilityException(val status: String, val reason: String) : IOException(reason) {
    val isBotCheck: Boolean get() = reason.contains("bot", true)
    val needsLogin: Boolean get() = status.equals("LOGIN_REQUIRED", true)
}

/**
 * Our own InnerTube player request with the TV (Tizen / "tv_samsung") client — the client yt-dlp
 * uses by default and the one that accepts **account cookies**.
 *
 * NewPipe Extractor 0.27.x only uses the visionOS client, which talks to youtubei.googleapis.com
 * and can't carry a signed-in session. On VPN / shared IPs YouTube answers that client with
 * "Sign in to confirm you're not a bot" — even when the user is signed in. This class fixes that:
 *  - signed in: cookies + SAPISIDHASH on www.youtube.com, GVS PoToken bound to the dataSyncId
 *  - anonymous: visitorData + GVS PoToken bound to it (second chance before giving up)
 * Signatures and the `n` throttling parameter are solved with NewPipe Extractor's JS player tools.
 */
object TvPlayer {
    const val USER_AGENT =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"
    private const val CLIENT_NAME = "TVHTML5"
    private const val CLIENT_ID = "7"
    private const val CLIENT_VERSION = "5.20260707"
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
    )

    /** Blocking — call from an IO thread. */
    fun fetch(videoId: String, signedIn: Boolean): Result {
        val account = Graph.account
        val useAccount = signedIn && account.isSignedIn
        val anon = if (useAccount) null else PoTokenProviderImpl.anonymousSession()
        val visitorData = anon?.first
        val sts = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
        val locale = Locale.getDefault()

        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("deviceMake", "Samsung")
                    put("deviceModel", "UKS9800")
                    put("userAgent", USER_AGENT)
                    put("osName", "Tizen")
                    put("osVersion", "2.4.0")
                    put("hl", locale.language.ifBlank { "en" })
                    put("gl", locale.country.ifBlank { "US" })
                    visitorData?.let { put("visitorData", it) }
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
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
        visitorData?.let { req.header("X-Goog-Visitor-Id", it) }
        if (useAccount) {
            account.authHeaders(ORIGIN).forEach { (k, v) -> req.header(k, v) }
            req.header("X-Youtube-Bootstrap-Logged-In", "true")
        }

        val response = Graph.http.newCall(req.build()).execute().use { r ->
            if (useAccount) account.mergeSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IOException("YouTube rejected the session (HTTP ${r.code}). Sign out and sign in again.")
            if (!r.isSuccessful) throw IOException("TV player request failed (HTTP ${r.code})")
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

        // GVS PoToken: bound to the dataSyncId when signed in, to the visitorData otherwise.
        val pot = if (useAccount) {
            runCatching { runBlocking { Graph.accountApi.dataSyncId() } }.getOrNull()?.let { PoTokenProviderImpl.mint(it) }
        } else anon?.second

        val streaming = response.obj("streamingData")
        val videos = mutableListOf<VideoOption>()
        val audios = mutableListOf<AudioOption>()
        fun handle(f: JsonObject, adaptive: Boolean) {
            if (f["drmFamilies"] != null) return
            val itag = f.int("itag") ?: return
            if (!ItagItem.isSupported(itag)) return
            val url = resolveUrl(videoId, f, pot) ?: return
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
        streaming.arr("formats").forEach { (it as? JsonObject)?.let { f -> handle(f, adaptive = false) } }
        streaming.arr("adaptiveFormats").forEach { (it as? JsonObject)?.let { f -> handle(f, adaptive = true) } }

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

        val hls = streaming.str("hlsManifestUrl")
        if (videos.isEmpty() && audios.isEmpty() && hls == null) {
            throw IOException("No playable formats from the TV client (SABR-only or DRM)")
        }
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
        )
    }

    /** Plain URL or deciphered signatureCipher, with `n` de-throttled and the PoToken appended. */
    private fun resolveUrl(videoId: String, f: JsonObject, pot: String?): String? {
        var url = f.str("url") ?: run {
            val cipher = f.str("signatureCipher") ?: f.str("cipher") ?: return null
            val q = parseQuery(cipher)
            val base = q["url"] ?: return null
            val s = q["s"] ?: return base
            val sig = runCatching { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, s) }.getOrNull() ?: return null
            "$base&${q["sp"] ?: "signature"}=${java.net.URLEncoder.encode(sig, "UTF-8")}"
        }
        url = runCatching { YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url) }.getOrDefault(url)
        if (pot != null && !url.contains("&pot=")) url += "&pot=" + java.net.URLEncoder.encode(pot, "UTF-8")
        return url
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
