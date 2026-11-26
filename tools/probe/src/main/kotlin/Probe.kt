import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

val http: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
val json = Json { ignoreUnknownKeys = true }
const val FIREFOX = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

class Dl : Downloader() {
    override fun execute(request: Request): Response {
        val b = okhttp3.Request.Builder().url(request.url()).header("User-Agent", FIREFOX)
        val body = request.dataToSend()?.toRequestBody()
        when (request.httpMethod()) {
            "POST" -> b.post(body ?: ByteArray(0).toRequestBody())
            "HEAD" -> b.head()
            else -> b.get()
        }
        request.headers().forEach { (k, v) -> b.removeHeader(k); v.forEach { b.addHeader(k, it) } }
        http.newCall(b.build()).execute().use { r ->
            return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}

data class Client(
    val key: String, val name: String, val id: Int, val version: String, val ua: String,
    val extra: Map<String, Any> = emptyMap(), val js: Boolean = true, val embed: Boolean = false,
)

val CLIENTS = listOf(
    Client("tv_samsung", "TVHTML5", 7, "5.20260707",
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
        mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0")),
    Client("tv", "TVHTML5", 7, "7.20260707.07.00",
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"),
    Client("web_embedded", "WEB_EMBEDDED_PLAYER", 56, "2.20260708.00.00",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)", embed = true),
    Client("web_safari", "WEB", 1, "2.20260708.00.00",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"),
    Client("mweb", "MWEB", 2, "2.20260708.05.00",
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"),
    Client("android_vr", "ANDROID_VR", 28, "1.65.10",
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"), js = false),
)

fun JsonObject.o(k: String) = this[k] as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.a(k: String) = this[k] as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

fun q(s: String) = s.split('&').mapNotNull { val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8") }.toMap()

fun rangeStatus(url: String, ua: String, from: Long): String = runCatching {
    http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=$from-${from + 4095}").build())
        .execute().use { r -> "${r.code}(${r.body.bytes().size}B)" }
}.getOrElse { "ERR ${it.javaClass.simpleName}" }

fun probeClient(c: Client, videoId: String, visitor: String?, sts: Int?): String {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", c.name); put("clientVersion", c.version); put("userAgent", c.ua)
                put("hl", "en"); put("gl", "US")
                c.extra.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
                visitor?.let { put("visitorData", it) }
            }
            if (c.embed) putJsonObject("thirdParty") { put("embedUrl", "https://www.reddit.com/") }
        }
        put("videoId", videoId)
        putJsonObject("playbackContext") {
            putJsonObject("contentPlaybackContext") {
                put("html5Preference", "HTML5_PREF_WANTS")
                if (c.js && sts != null) put("signatureTimestamp", sts)
            }
        }
        put("contentCheckOk", true); put("racyCheckOk", true)
    }
    val req = okhttp3.Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .header("User-Agent", c.ua).header("Origin", "https://www.youtube.com")
        .header("X-YouTube-Client-Name", c.id.toString()).header("X-YouTube-Client-Version", c.version)
    visitor?.let { req.header("X-Goog-Visitor-Id", it) }
    if (c.embed) req.header("Referer", "https://www.reddit.com/")
    val res = runCatching {
        http.newCall(req.build()).execute().use { r -> if (!r.isSuccessful) return "HTTP ${r.code}"; json.parseToJsonElement(r.body.string()).jsonObject }
    }.getOrElse { return "ERR ${it.message}" }
    val ps = res.o("playabilityStatus")
    val status = ps.s("status")
    if (status != "OK") return "status=$status reason=${ps.s("reason")}"
    val sd = res.o("streamingData")
    val all = (sd.a("formats") + sd.a("adaptiveFormats")).mapNotNull { it as? JsonObject }
    val withUrl = all.count { it.s("url") != null }
    val cipher = all.count { it.s("signatureCipher") != null }
    val drm = all.count { it["drmFamilies"] != null }
    val otf = all.count { it.s("type") == "FORMAT_STREAM_TYPE_OTF" }
    val sb = StringBuilder("OK fmts=${all.size} url=$withUrl cipher=$cipher drm=$drm otf=$otf sabr=${sd.s("serverAbrStreamingUrl") != null} hls=${sd.s("hlsManifestUrl") != null}")
    // Try one audio-ish adaptive format (itag 140/251) or any
    val f = all.firstOrNull { it.s("itag") == "140" && it["drmFamilies"] == null }
        ?: all.firstOrNull { it["drmFamilies"] == null && (it.s("url") != null || it.s("signatureCipher") != null) }
    if (f != null) {
        var url = f.s("url")
        if (url == null) {
            val sc = q(f.s("signatureCipher")!!)
            val sig = runCatching { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, sc["s"]!!) }
            sb.append(" sig=").append(sig.fold({ "ok" }, { "FAIL(${it.javaClass.simpleName}: ${it.message?.take(80)})" }))
            url = sig.getOrNull()?.let { "${sc["url"]}&${sc["sp"] ?: "signature"}=${URLEncoder.encode(it, "UTF-8")}" }
        }
        if (url != null) {
            val n = runCatching { YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url!!) }
            sb.append(" n=").append(n.fold({ if (it != url) "ok" else "same" }, { "FAIL(${it.message?.take(80)})" }))
            url = n.getOrDefault(url)
            sb.append(" itag=${f.s("itag")} GET0=${rangeStatus(url!!, c.ua, 0)} GET1.5M=${rangeStatus(url, c.ua, 1_500_000)}")
        }
    }
    sd.s("hlsManifestUrl")?.let { h ->
        val st = runCatching { http.newCall(okhttp3.Request.Builder().url(h).header("User-Agent", c.ua).build()).execute().use { "${it.code}" } }.getOrElse { "ERR" }
        sb.append(" hlsGET=$st")
    }
    return sb.toString()
}

fun notice(title: String, lines: List<String>) {
    val msg = lines.joinToString("%0A") { it.replace("%", "%25").replace("\r", "").replace("\n", " ") }
    println("::notice title=$title::$msg")
}

fun main() {
    NewPipe.init(Dl(), Localization("en", "US"), ContentCountry("US"))
    val ip = runCatching { http.newCall(okhttp3.Request.Builder().url("https://api.ipify.org").build()).execute().use { it.body.string() } }.getOrDefault("?")
    val visitor = runCatching {
        val info = InnertubeClientRequestInfo.ofWebClient()
        info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
        YoutubeParsingHelper.getVisitorDataFromInnertube(info, Localization("en", "US"), ContentCountry("US"),
            YoutubeParsingHelper.getYouTubeHeaders(), YoutubeParsingHelper.YOUTUBEI_V1_URL, null, false)
    }.getOrNull()
    val videos = listOf("kJQP7kiw5Fk", "dQw4w9WgXcQ", "jNQXAC9IVRw")
    for (v in videos) {
        val lines = mutableListOf("runner ip=$ip visitor=${visitor != null}")
        val sts = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(v) }
        lines += "sts=" + sts.fold({ "$it" }, { "FAIL ${it.message?.take(100)}" })
        lines += "NPE(visionOS): " + runCatching {
            val i = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$v")
            val a = i.audioStreams.firstOrNull()
            "OK video=${i.videoOnlyStreams.size} audio=${i.audioStreams.size}" + (a?.let { " GET=${rangeStatus(it.content, YoutubeParsingHelper.getVisionOsUserAgent(null), 0)}" } ?: "")
        }.getOrElse { "FAIL ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
        for (c in CLIENTS) {
            lines += "${c.key}: " + runCatching { probeClient(c, v, visitor, sts.getOrNull()) }.getOrElse { "CRASH ${it.message}" }
            lines += "${c.key}(no visitor): " + runCatching { probeClient(c, v, null, sts.getOrNull()) }.getOrElse { "CRASH ${it.message}" }
        }
        notice("probe $v", lines)
        lines.forEach(::println)
    }
}
