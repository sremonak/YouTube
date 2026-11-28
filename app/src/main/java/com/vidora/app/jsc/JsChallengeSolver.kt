package com.vidora.app.jsc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.vidora.app.Graph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Solves YouTube's JavaScript stream challenges (the `s` signature and the `n` throttling
 * parameter) with yt-dlp's EJS solver (bundled in assets/ejs, Unlicense) running inside a headless
 * WebView — the same approach yt-dlp uses with deno/node, so it keeps working when YouTube
 * changes the player and regex-based deobfuscators (NewPipe Extractor) break.
 *
 * The player is the `tv` variant that yt-dlp uses (tv-player-ias.js). Its preprocessed form is
 * cached on disk per player id, so after the first video solving only takes a few milliseconds.
 */
object JsChallengeSolver {
    private const val TAG = "JsChallengeSolver"
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true }

    data class PlayerInfo(val id: String, val sts: Int?)

    private val lock = Any()
    private var info: PlayerInfo? = null
    private var infoFetchedAt = 0L
    private var rawPlayer: String? = null
    private var preprocessed: String? = null
    private val sigCache = LruCache<String, String>(512)
    private val nCache = LruCache<String, String>(512)

    /** Last status for Stats for nerds / debugging. */
    @Volatile var lastStatus: String = "Not used yet"
        private set

    private val cacheDir: File get() = File(Graph.app.filesDir, "jsc").apply { mkdirs() }

    /** Current player id + signature timestamp. Blocking (network on first call). */
    fun player(): PlayerInfo = synchronized(lock) {
        val now = System.currentTimeMillis()
        info?.let { if (now - infoFetchedAt < 30 * 60_000) return it }
        val iframe = get("https://www.youtube.com/iframe_api")
        val id = Regex("""player\\?/([0-9a-fA-F]{8})\\?/""").find(iframe)?.groupValues?.get(1)
            ?: info?.id ?: throw IOException("Couldn't find the YouTube player version")
        if (id == info?.id) {
            infoFetchedAt = now
            return info!!
        }
        val stsFile = File(cacheDir, "$id.sts")
        val preFile = File(cacheDir, "$id.pre.js")
        val cachedSts = stsFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        val p = if (cachedSts != null && preFile.exists()) {
            rawPlayer = null
            preprocessed = preFile.readText()
            PlayerInfo(id, cachedSts)
        } else {
            val js = get("https://www.youtube.com/s/player/$id/tv-player-ias.vflset/tv-player-ias.js")
            val sts = Regex("""(?:signatureTimestamp|sts)\s*:\s*([0-9]{5})""").find(js)?.groupValues?.get(1)?.toIntOrNull()
            sts?.let { stsFile.writeText(it.toString()) }
            rawPlayer = js
            preprocessed = null
            PlayerInfo(id, sts)
        }
        // Old players are useless once YouTube moved on.
        cacheDir.listFiles()?.filter { !it.name.startsWith(id) }?.forEach { it.delete() }
        sigCache.evictAll(); nCache.evictAll()
        info = p
        infoFetchedAt = now
        p
    }

    /**
     * Solves the given challenges. Returns (signature → solution, n → solution); challenges that
     * couldn't be solved are simply missing from the maps. Blocking — call from an IO thread.
     */
    fun solve(sigs: Collection<String>, ns: Collection<String>): Pair<Map<String, String>, Map<String, String>> {
        val sigOut = HashMap<String, String>()
        val nOut = HashMap<String, String>()
        val sigTodo = sigs.distinct().filter { c -> sigCache.get(c)?.also { sigOut[c] = it } == null }
        val nTodo = ns.distinct().filter { c -> nCache.get(c)?.also { nOut[c] = it } == null }
        if (sigTodo.isEmpty() && nTodo.isEmpty()) return sigOut to nOut

        synchronized(lock) {
            val p = player()
            val requests = buildJsonArray {
                if (nTodo.isNotEmpty()) add(buildJsonObject { put("type", "n"); put("challenges", JsonArray(nTodo.map { JsonPrimitive(it) })) })
                if (sigTodo.isNotEmpty()) add(buildJsonObject { put("type", "sig"); put("challenges", JsonArray(sigTodo.map { JsonPrimitive(it) })) })
            }
            val pre = preprocessed
            val data = if (pre != null) buildJsonObject {
                put("type", "preprocessed"); put("preprocessed_player", pre); put("requests", requests)
            } else buildJsonObject {
                put("type", "player")
                put("player", rawPlayer ?: run {
                    get("https://www.youtube.com/s/player/${p.id}/tv-player-ias.vflset/tv-player-ias.js").also { rawPlayer = it }
                })
                put("requests", requests); put("output_preprocessed", true)
            }
            val started = System.currentTimeMillis()
            val out = try {
                json.parseToJsonElement(Engine.run(data.toString())).jsonObject
            } catch (t: Throwable) {
                lastStatus = "Failed: ${t.message ?: t.javaClass.simpleName}"
                throw IOException("Couldn't solve YouTube's stream challenge: ${t.message}", t)
            }
            if ((out["type"] as? JsonPrimitive)?.contentOrNull == "error") {
                val err = (out["error"] as? JsonPrimitive)?.contentOrNull
                lastStatus = "Solver error: $err"
                if (pre != null) { preprocessed = null; File(cacheDir, "${p.id}.pre.js").delete() }
                throw IOException("YouTube stream challenge solver failed: ${err?.take(200)}")
            }
            (out["preprocessed_player"] as? JsonPrimitive)?.contentOrNull?.let { pp ->
                preprocessed = pp
                rawPlayer = null
                runCatching { File(cacheDir, "${p.id}.pre.js").writeText(pp) }
            }
            val responses = out["responses"] as? JsonArray ?: JsonArray(emptyList())
            var idx = 0
            fun collect(into: MutableMap<String, String>, cache: LruCache<String, String>) {
                val r = responses.getOrNull(idx++) as? JsonObject ?: return
                if ((r["type"] as? JsonPrimitive)?.contentOrNull != "result") {
                    Log.w(TAG, "challenge error: ${(r["error"] as? JsonPrimitive)?.contentOrNull}")
                    return
                }
                (r["data"] as? JsonObject)?.forEach { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { into[k] = it; cache.put(k, it) }
                }
            }
            if (nTodo.isNotEmpty()) collect(nOut, nCache)
            if (sigTodo.isNotEmpty()) collect(sigOut, sigCache)
            lastStatus = "OK · player ${p.id} · ${System.currentTimeMillis() - started} ms"
        }
        return sigOut to nOut
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        return Graph.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${url.substringBefore('?')}")
            r.body?.string().orEmpty()
        }
    }

    /** A single long-lived headless WebView that has the EJS lib + core loaded. */
    @SuppressLint("SetJavaScriptEnabled")
    private object Engine {
        private val main = Handler(Looper.getMainLooper())
        private var webView: WebView? = null
        private var ready: CompletableFuture<Unit>? = null
        private val inputs = ConcurrentHashMap<String, String>()
        private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
        private val ids = AtomicInteger()

        private val bridge = Bridge()

        class Bridge {
            @JavascriptInterface fun take(id: String): String = inputs.remove(id) ?: "null"
            @JavascriptInterface fun done(id: String, result: String) { pending.remove(id)?.complete(result) }
            @JavascriptInterface fun fail(id: String, error: String) { pending.remove(id)?.completeExceptionally(IOException(error.take(300))) }
            @JavascriptInterface fun onReady(type: String) {
                val r = ready ?: return
                if (type == "function") r.complete(Unit) else r.completeExceptionally(IOException("EJS solver didn't load ($type)"))
            }
        }

        private fun ensure(context: Context): CompletableFuture<Unit> = synchronized(this) {
            ready?.let { if (!it.isCompletedExceptionally) return it }
            val f = CompletableFuture<Unit>()
            ready = f
            main.post {
                try {
                    webView?.destroy()
                    val wv = WebView(context.applicationContext)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.blockNetworkLoads = true
                    wv.addJavascriptInterface(bridge, "VidoraJsc")
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "js: ${m.message()}")
                            return true
                        }
                    }
                    val html = """<!doctype html><html><head>
                        <script src="ejs/lib.min.js"></script>
                        <script>Object.assign(globalThis, lib);</script>
                        <script src="ejs/core.min.js"></script>
                        <script>VidoraJsc.onReady(typeof jsc);</script>
                        </head><body></body></html>""".trimIndent()
                    wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
                    webView = wv
                } catch (t: Throwable) {
                    f.completeExceptionally(t)
                }
            }
            f
        }

        fun run(input: String): String {
            val r = ensure(Graph.app)
            try {
                r.get(20, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                synchronized(this) { if (ready === r) ready = null }
                throw IOException("JavaScript engine unavailable: ${((t as? ExecutionException)?.cause ?: t).message}")
            }
            val id = "c" + ids.incrementAndGet()
            val result = CompletableFuture<String>()
            inputs[id] = input
            pending[id] = result
            main.post {
                val wv = webView
                if (wv == null) {
                    pending.remove(id)?.completeExceptionally(IOException("WebView gone"))
                    return@post
                }
                wv.evaluateJavascript(
                    "(function(){try{var d=JSON.parse(VidoraJsc.take('$id'));var r=jsc(d);" +
                        "VidoraJsc.done('$id',JSON.stringify(r));}catch(e){VidoraJsc.fail('$id',String(e&&e.stack||e));}})();",
                    null
                )
            }
            return try {
                result.get(60, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                inputs.remove(id); pending.remove(id)
                throw (t as? ExecutionException)?.cause ?: t
            }
        }
    }
}
