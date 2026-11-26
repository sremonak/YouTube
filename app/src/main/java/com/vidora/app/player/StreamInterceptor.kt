package com.vidora.app.player

import com.vidora.app.data.NetworkDownloader
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Makes requests to googlevideo `videoplayback` URLs look like the official clients:
 *  - sends the client-matching User-Agent (required for some stream types)
 *  - adds the `rn` request counter
 *  - adds web Origin/Referer headers where needed
 *  - uses POST with the tiny protobuf body YouTube clients send
 * Shared by the player and the downloader.
 */
class StreamInterceptor : Interceptor {
    private val requestNumber = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        if (!url.encodedPath.startsWith("/videoplayback")) return chain.proceed(original)
        // HLS/DASH segment URLs use path parameters (/videoplayback/id/...) and must not get `rn`
        val progressive = url.encodedPath == "/videoplayback"

        val urlString = url.toString()
        val ownClient = url.queryParameter("c").let { it != null && (it.startsWith("TVHTML5") || it == "MWEB") }
        val newUrl = if (progressive && !ownClient && url.queryParameter("rn") == null) {
            url.newBuilder().addQueryParameter("rn", requestNumber.getAndIncrement().toString()).build()
        } else url

        val builder = original.newBuilder().url(newUrl)
        // URLs from our own InnerTube fallbacks (TV / mobile web / Safari web, incl. HLS segments with
        // path parameters) are fetched like yt-dlp does: plain GET + Range with the client's User-Agent.
        val c = url.queryParameter("c") ?: Regex("/c/([A-Z_0-9]+)/").find(url.encodedPath)?.groupValues?.get(1)
        val own = when {
            c == null -> null
            c.startsWith("TVHTML5") -> com.vidora.app.data.YtClient.TV
            c == "MWEB" -> com.vidora.app.data.YtClient.MWEB
            c == "WEB" && !progressive -> com.vidora.app.data.YtClient.WEB_SAFARI
            else -> null
        }
        val userAgent = when {
            own != null -> own.userAgent.removeSuffix(",gzip(gfe)")
            YoutubeParsingHelper.isVisionOsStreamingUrl(urlString) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            else -> NetworkDownloader.USER_AGENT
        }
        builder.header("User-Agent", userAgent)
        if (own != null || !progressive) return chain.proceed(builder.build())

        if (YoutubeParsingHelper.isWebStreamingUrl(urlString)) {
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Referer", "https://www.youtube.com/")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "cross-site")
        }
        if (original.method == "GET") {
            builder.post(POST_BODY.toRequestBody())
        }
        return chain.proceed(builder.build())
    }

    private companion object {
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
