package com.vidora.app.data

import com.vidora.app.Graph
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** OkHttp backed [Downloader] used by the extractor for all metadata requests. */
class NetworkDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .header("User-Agent", USER_AGENT)

        val body = request.dataToSend()?.toRequestBody()
        when (request.httpMethod()) {
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody())
            "HEAD" -> builder.head()
            else -> builder.get()
        }

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        // Signed-in mode: attach the account session to youtube.com requests (only when the
        // repository explicitly asks for it, e.g. to unlock a restricted video).
        val account = Graph.account
        val host = runCatching { java.net.URI(request.url()).host }.getOrNull().orEmpty()
        if (account.authActiveOnThisThread() && host in ACCOUNT_HOSTS) {
            val auth = account.authHeaders()
            val existingCookie = request.headers().entries
                .firstOrNull { it.key.equals("Cookie", true) }?.value?.joinToString("; ")
            // SAPISIDHASH is only valid for web-family clients; other InnerTube clients just get cookies.
            val clientName = request.headers().entries
                .firstOrNull { it.key.equals("X-YouTube-Client-Name", true) }?.value?.firstOrNull()
            val webClient = clientName == null || clientName in WEB_CLIENT_NAMES
            auth.forEach { (k, v) ->
                when {
                    k == "Cookie" -> builder.header("Cookie", listOfNotNull(existingCookie, v).joinToString("; "))
                    webClient -> builder.header(k, v)
                }
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            val responseBody = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString()
            )
        }
    }

    companion object {
        private val WEB_CLIENT_NAMES = setOf("1", "62", "67")
        private val ACCOUNT_HOSTS = setOf("www.youtube.com", "m.youtube.com", "music.youtube.com", "youtube.com")

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        fun create(base: OkHttpClient): NetworkDownloader = NetworkDownloader(
            base.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        )
    }
}
