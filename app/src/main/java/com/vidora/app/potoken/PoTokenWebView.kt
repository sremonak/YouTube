// Adapted from NewPipe (GPL-3.0): org.schabi.newpipe.util.potoken.PoTokenWebView
// RxJava was replaced with CompletableFuture to keep Vidora's dependency footprint small.
package com.vidora.app.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import com.vidora.app.Graph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Runs Google's BotGuard inside a headless WebView to mint poTokens, like youtube.com does. */
@SuppressLint("SetJavaScriptEnabled")
class PoTokenWebView private constructor(
    context: Context,
    private val generatorFuture: CompletableFuture<PoTokenWebView>,
) {
    private val webView = WebView(context)
    private val poTokenFutures = mutableListOf<Pair<String, CompletableFuture<String>>>()
    private var expirationInstant: Instant = Instant.now()
    @Volatile private var closed = false

    init {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.safeBrowsingEnabled = false
        s.userAgentString = USER_AGENT
        s.blockNetworkLoads = true // the WebView itself never needs network access
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    val exception = BadWebViewException(fmt)
                    Log.e(TAG, "This WebView implementation is broken: $fmt")
                    onInitializationErrorCloseAndCancel(exception)
                    popAllPoTokenFutures().forEach { (_, f) -> f.completeExceptionally(exception) }
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun loadHtmlAndObtainBotguard(context: Context) {
        io.execute {
            try {
                val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                main.post {
                    webView.loadDataWithBaseURL(
                        "https://www.youtube.com",
                        html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"),
                        "text/html", "utf-8", null
                    )
                }
            } catch (t: Throwable) {
                onInitializationErrorCloseAndCancel(t)
            }
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        makeBotguardServiceRequest("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]") { body ->
            val parsedChallengeData = parseChallengeData(body)
            webView.evaluateJavascript(
                """try {
                    data = $parsedChallengeData
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"
        ) { body ->
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(body)
            // leave 10 minutes of margin
            expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)
            webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                generatorFuture.complete(this)
            }
        }
    }

    /** Mints a poToken for [identifier] (a video id or visitor data). */
    fun generatePoToken(identifier: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val posted = main.post {
            addPoTokenFuture(identifier, future)
            val u8Identifier = stringToU8(identifier)
            webView.evaluateJavascript(
                """try {
                    identifier = "$identifier"
                    u8Identifier = $u8Identifier
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }"""
            ) {}
        }
        if (!posted) future.completeExceptionally(PoTokenException("Could not run on main thread"))
        return future
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        popPoTokenFuture(identifier)?.completeExceptionally(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            popPoTokenFuture(identifier)?.completeExceptionally(t)
            return
        }
        popPoTokenFuture(identifier)?.complete(poToken)
    }

    fun isExpired(): Boolean = Instant.now().isAfter(expirationInstant)

    private fun addPoTokenFuture(identifier: String, f: CompletableFuture<String>) =
        synchronized(poTokenFutures) { poTokenFutures.add(identifier to f) }

    private fun popPoTokenFuture(identifier: String): CompletableFuture<String>? = synchronized(poTokenFutures) {
        poTokenFutures.indexOfFirst { it.first == identifier }.takeIf { it >= 0 }?.let { poTokenFutures.removeAt(it).second }
    }

    private fun popAllPoTokenFutures(): List<Pair<String, CompletableFuture<String>>> = synchronized(poTokenFutures) {
        val result = poTokenFutures.toList()
        poTokenFutures.clear()
        result
    }

    /** POSTs to the BotGuard service on a background thread and calls [handle] on the main thread. */
    private fun makeBotguardServiceRequest(url: String, data: String, handle: (String) -> Unit) {
        io.execute {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(data.toRequestBody("application/json+protobuf".toMediaType()))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("x-goog-api-key", GOOGLE_API_KEY)
                    .header("x-user-agent", "grpc-web-javascript/0.1")
                    .build()
                val body = Graph.http.newCall(request).execute().use { r ->
                    if (r.code != 200) throw PoTokenException("Invalid response code: ${r.code}")
                    r.body?.string().orEmpty()
                }
                main.post {
                    try {
                        handle(body)
                    } catch (t: Throwable) {
                        onInitializationErrorCloseAndCancel(t)
                    }
                }
            } catch (t: Throwable) {
                onInitializationErrorCloseAndCancel(t)
            }
        }
    }

    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        main.post {
            close()
            generatorFuture.completeExceptionally(error)
        }
    }

    @MainThread
    fun close() {
        if (closed) return
        closed = true
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        // Public API key used by BotGuard (as seen in youtube.com requests)
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newCachedThreadPool()

        fun newPoTokenGenerator(context: Context): CompletableFuture<PoTokenWebView> {
            val future = CompletableFuture<PoTokenWebView>()
            val posted = main.post {
                try {
                    PoTokenWebView(context, future).loadHtmlAndObtainBotguard(context)
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
            if (!posted) future.completeExceptionally(PoTokenException("Could not run on main thread"))
            return future
        }
    }
}
