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

val http1 by lazy { http.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build() }
fun rangeStatusH1(url: String, ua: String, from: Long): String = runCatching {
    http1.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=$from-${from + 4095}").build())
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
    val f = all.firstOrNull { it.s("itag") == "140" && it["drmFamilies"] == null && (it.s("url") != null || it.s("signatureCipher") != null) }
        ?: all.firstOrNull { it["drmFamilies"] == null && (it.s("url") != null || it.s("signatureCipher") != null) }
    if (f != null) {
        var url = f.s("url")
        var sigChallenge: String? = null
        var sc: Map<String, String> = emptyMap()
        if (url == null) {
            sc = q(f.s("signatureCipher")!!)
            sigChallenge = sc["s"]; url = sc["url"]
        }
        val nChallenge = url?.let { u -> Regex("[?&]n=([^&]+)").find(u)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } }
        val solved = runCatching { ejsSolve(listOfNotNull(sigChallenge), listOfNotNull(nChallenge)) }
        solved.exceptionOrNull()?.let { sb.append(" EJS=FAIL(${it.message?.take(160)})") }
        val (sigs, ns) = solved.getOrDefault(emptyMap<String, String>() to emptyMap())
        if (sigChallenge != null) {
            val sig = sigs[sigChallenge]
            sb.append(" sig=").append(if (sig != null) "ok" else "FAIL")
            url = sig?.let { "$url&${sc["sp"] ?: "signature"}=${URLEncoder.encode(it, "UTF-8")}" }
        }
        if (url != null && nChallenge != null) {
            val n = ns[nChallenge]
            sb.append(" n=").append(if (n != null && n != nChallenge) "ok" else "FAIL")
            if (n != null) url = url!!.replace("n=" + URLEncoder.encode(nChallenge, "UTF-8"), "n=" + URLEncoder.encode(n, "UTF-8"))
                .replace("n=$nChallenge", "n=$n")
        }
        if (url != null) {
            sb.append(" itag=${f.s("itag")} GET0=${rangeStatus(url!!, c.ua, 0)} GET1.5M=${rangeStatus(url, c.ua, 1_500_000)}")
            if (c.key == "tv_samsung") {
                val u = url!!
                sb.append(" host=" + u.substringAfter("://").substringBefore('/'))
                sb.append(" keys=" + u.substringAfter('?').split('&').map { it.substringBefore('=') }.joinToString(","))
                sb.append(" rangeParam=" + runCatching { http.newCall(okhttp3.Request.Builder().url("$u&range=0-4095").header("User-Agent", c.ua).build()).execute().use { "${it.code}" } }.getOrElse { "ERR" })
                sb.append(" ffUA=" + rangeStatus(u, FIREFOX, 0))
                sb.append(" post=" + runCatching { http.newCall(okhttp3.Request.Builder().url("$u&rn=1").header("User-Agent", c.ua).post(byteArrayOf(0x78, 0).toRequestBody()).build()).execute().use { "${it.code}" } }.getOrElse { "ERR" })
                sb.append(" h1=" + rangeStatusH1(u, c.ua, 0))
                sb.append(" noSig=" + rangeStatus(u.replace(Regex("&sig=[^&]*"), ""), c.ua, 0))
            }
        }
    }
    sd.s("hlsManifestUrl")?.let { h0 ->
        fun get(u: String) = http.newCall(okhttp3.Request.Builder().url(u).header("User-Agent", c.ua).build()).execute().use { it.code to it.body.string() }
        val nCh = Regex("/n/([^/]+)/").find(h0)?.groupValues?.get(1)
        val nSol = nCh?.let { runCatching { ejsSolve(emptyList(), listOf(it)).second[it] }.getOrNull() }
        for ((label, h) in listOf("raw" to h0, "nfix" to (if (nCh != null && nSol != null) h0.replace("/n/$nCh/", "/n/$nSol/") else null))) {
            if (h == null) { sb.append(" hls[$label]=no-n"); continue }
            val r = runCatching {
                val (code, master) = get(h)
                val variant = master.lines().lastOrNull { it.startsWith("http") } ?: return@runCatching "master=$code(no variants)"
                val (vc, pl) = get(variant)
                val segs = pl.lines().filter { it.startsWith("http") }
                val seg = segs.firstOrNull() ?: return@runCatching "master=$code variant=$vc(no segs)"
                val mid = segs.getOrNull(segs.size / 2) ?: seg
                val s1 = http.newCall(okhttp3.Request.Builder().url(seg).header("User-Agent", c.ua).build()).execute().use { "${it.code}(${it.body.bytes().size}B)" }
                val s2 = http.newCall(okhttp3.Request.Builder().url(mid).header("User-Agent", c.ua).build()).execute().use { "${it.code}(${it.body.bytes().size}B)" }
                "master=$code variants=${master.lines().count { it.startsWith("http") }} variant=$vc segs=${segs.size} seg0=$s1 segMid=$s2"
            }.getOrElse { "ERR ${it.message}" }
            sb.append(" hls[$label]=$r")
        }
    }
    return sb.toString()
}

lateinit var PLAYER_JS: String
lateinit var PLAYER_ID: String

fun loadPlayer() {
    val iframe = http.newCall(okhttp3.Request.Builder().url("https://www.youtube.com/iframe_api").header("User-Agent", FIREFOX).build()).execute().use { it.body.string() }
    PLAYER_ID = Regex("""player\\?/([0-9a-fA-F]{8})\\?/""").find(iframe)!!.groupValues[1]
    PLAYER_JS = http.newCall(okhttp3.Request.Builder().url("https://www.youtube.com/s/player/$PLAYER_ID/tv-player-ias.vflset/tv-player-ias.js").header("User-Agent", FIREFOX).build()).execute().use { it.body.string() }
}

fun playerSts(): Int? = Regex("""(?:signatureTimestamp|sts)\s*:\s*([0-9]{5})""").find(PLAYER_JS)?.groupValues?.get(1)?.toInt()

/** Runs yt-dlp's EJS solver (same assets the app ships) in node — the app runs it in a WebView. */
fun ejsSolve(sigs: List<String>, ns: List<String>): Pair<Map<String, String>, Map<String, String>> {
    if (sigs.isEmpty() && ns.isEmpty()) return emptyMap<String, String>() to emptyMap()
    val assets = java.io.File(System.getenv("EJS_DIR") ?: "../../app/src/main/assets/ejs")
    val reqs = buildList {
        if (ns.isNotEmpty()) add("""{"type":"n","challenges":${JsonArray(ns.map { JsonPrimitive(it) })}}""")
        if (sigs.isNotEmpty()) add("""{"type":"sig","challenges":${JsonArray(sigs.map { JsonPrimitive(it) })}}""")
    }
    val data = """{"type":"player","player":${JsonPrimitive(PLAYER_JS)},"requests":[${reqs.joinToString(",")}],"output_preprocessed":false}"""
    val script = java.io.File.createTempFile("ejs", ".js")
    script.writeText(java.io.File(assets, "lib.min.js").readText() + "\nObject.assign(globalThis, lib);\n" +
        java.io.File(assets, "core.min.js").readText() + "\nconsole.log(JSON.stringify(jsc(" + data + ")));\n")
    val p = ProcessBuilder("node", script.absolutePath).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    val res = json.parseToJsonElement(out.trim().lines().last()).jsonObject
    if (res.s("type") == "error") error(res.s("error") ?: "error")
    val responses = res.a("responses").map { it as JsonObject }
    var i = 0
    fun next(): Map<String, String> {
        val r = responses[i++]
        if (r.s("type") == "error") error("challenge: " + r.s("error"))
        return r.o("data").mapValues { (it.value as JsonPrimitive).content }
    }
    val nMap = if (ns.isNotEmpty()) next() else emptyMap()
    val sMap = if (sigs.isNotEmpty()) next() else emptyMap()
    return sMap to nMap
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
    loadPlayer()
    val ejsSts = playerSts()
    val videos = listOf("dQw4w9WgXcQ")
    for (v in videos) {
        val lines = mutableListOf("runner ip=$ip visitor=${visitor != null}")
        val sts = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(v) }
        lines += "sts(NPE)=" + sts.fold({ "$it" }, { "FAIL ${it.message?.take(100)}" }) + " player=$PLAYER_ID sts(tv js)=$ejsSts js=${PLAYER_JS.length}B"
        lines += "NPE(visionOS): " + runCatching {
            val i = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$v")
            val a = i.audioStreams.firstOrNull()
            "OK video=${i.videoOnlyStreams.size} audio=${i.audioStreams.size}" + (a?.let { " GET=${rangeStatus(it.content, YoutubeParsingHelper.getVisionOsUserAgent(null), 0)}" } ?: "")
        }.getOrElse { "FAIL ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
        for (c in CLIENTS) {
            lines += "${c.key}: " + runCatching { probeClient(c, v, visitor, ejsSts ?: sts.getOrNull()) }.getOrElse { "CRASH ${it.message}" }
            lines += "${c.key}(no visitor): " + runCatching { probeClient(c, v, null, ejsSts ?: sts.getOrNull()) }.getOrElse { "CRASH ${it.message}" }
        }
        notice("probe $v", lines)
        lines.forEach(::println)
    }
}
