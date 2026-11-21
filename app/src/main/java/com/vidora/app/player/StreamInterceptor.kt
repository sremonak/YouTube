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

        val urlString = url.toString()
        val newUrl = if (url.queryParameter("rn") == null) {
            url.newBuilder().addQueryParameter("rn", requestNumber.getAndIncrement().toString()).build()
        } else url

        val builder = original.newBuilder().url(newUrl)
        val userAgent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(urlString)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else NetworkDownloader.USER_AGENT
        builder.header("User-Agent", userAgent)

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
