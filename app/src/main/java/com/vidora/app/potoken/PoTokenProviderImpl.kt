// Adapted from NewPipe (GPL-3.0): org.schabi.newpipe.util.potoken.PoTokenProviderImpl
package com.vidora.app.potoken

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import com.vidora.app.Graph
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Supplies WEB-client poTokens to NewPipe Extractor so YouTube treats Vidora like a real browser.
 * This avoids most "Sign in to confirm you're not a bot" and HTTP 403 playback failures.
 */
object PoTokenProviderImpl : PoTokenProvider {
    private const val TAG = "PoTokenProvider"
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance(); true }.getOrDefault(false) }
    @Volatile private var webViewBadImpl = false
    @Volatile var enabled = true

    private val lock = Any()
    private var visitorData: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenWebView? = null

    /** Last status, shown in Settings → Advanced. */
    @Volatile var lastStatus: String = "Not used yet"
        private set

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!enabled || !webViewSupported || webViewBadImpl) return null
        return try {
            getWebClientPoToken(videoId, forceRecreate = false).also { lastStatus = "OK" }
        } catch (t: Throwable) {
            val cause = (t as? ExecutionException)?.cause ?: t
            if (cause is BadWebViewException) {
                Log.e(TAG, "WebView is broken, disabling poTokens", cause)
                webViewBadImpl = true
            }
            lastStatus = "Failed: ${cause.message ?: cause.javaClass.simpleName}"
            null // let the extractor fall back to clients that don't need a poToken
        }
    }

    /**
     * Anonymous session for our own player requests: the visitorData plus the GVS (streaming)
     * PoToken bound to it. Null when PoTokens are disabled or the WebView can't run BotGuard.
     */
    fun anonymousSession(): Pair<String, String>? {
        if (!enabled || !webViewSupported || webViewBadImpl) return null
        return runCatching {
            val (_, vd, spot) = ensureGenerator(forceRecreate = false)
            lastStatus = "OK"
            vd to spot
        }.onFailure { lastStatus = "Failed: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
    }

    /**
     * Mints a PoToken bound to an arbitrary identifier (video id, or the account's dataSyncId
     * for signed-in GVS tokens — the binding yt-dlp/bgutil use).
     */
    fun mint(identifier: String): String? {
        if (!enabled || !webViewSupported || webViewBadImpl) return null
        return try {
            val gen = ensureGenerator(forceRecreate = false).first
            try {
                gen.generatePoToken(identifier).get(15, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                // The WebView may have been killed in the background — rebuild once.
                ensureGenerator(forceRecreate = true).first.generatePoToken(identifier).get(15, TimeUnit.SECONDS)
            }.also { lastStatus = "OK" }
        } catch (t: Throwable) {
            val cause = (t as? ExecutionException)?.cause ?: t
            if (cause is BadWebViewException) webViewBadImpl = true
            lastStatus = "Failed: ${cause.message ?: cause.javaClass.simpleName}"
            null
        }
    }

    private fun ensureGenerator(forceRecreate: Boolean): Triple<PoTokenWebView, String, String> = synchronized(lock) {
        val shouldRecreate = generator == null || forceRecreate || generator!!.isExpired()
        if (shouldRecreate) {
            val info = InnertubeClientRequestInfo.ofWebClient()
            info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                info,
                NewPipe.getPreferredLocalization(),
                NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false
            )
            generator?.let { old -> Handler(Looper.getMainLooper()).post { old.close() } }
            generator = PoTokenWebView.newPoTokenGenerator(Graph.app).get(30, TimeUnit.SECONDS)
            streamingPot = generator!!.generatePoToken(visitorData!!).get(15, TimeUnit.SECONDS)
        }
        Triple(generator!!, visitorData!!, streamingPot!!)
    }

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val gen: PoTokenWebView
        val vd: String
        val spot: String
        val recreated: Boolean
        synchronized(lock) {
            val shouldRecreate = generator == null || forceRecreate || generator!!.isExpired()
            if (shouldRecreate) {
                val info = InnertubeClientRequestInfo.ofWebClient()
                info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    info,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false
                )
                generator?.let { old -> Handler(Looper.getMainLooper()).post { old.close() } }
                generator = PoTokenWebView.newPoTokenGenerator(Graph.app).get(30, TimeUnit.SECONDS)
                // The streaming poToken must be generated exactly once before any player token.
                streamingPot = generator!!.generatePoToken(visitorData!!).get(15, TimeUnit.SECONDS)
            }
            gen = generator!!
            vd = visitorData!!
            spot = streamingPot!!
            recreated = shouldRecreate
        }
        val playerPot = try {
            gen.generatePoToken(videoId).get(15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            if (recreated) throw t
            // The WebView may have been killed while the app was in the background — rebuild once.
            return getWebClientPoToken(videoId, forceRecreate = true)
        }
        return PoTokenResult(vd, playerPot, spot)
    }
}
