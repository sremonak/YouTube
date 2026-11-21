package com.vidora.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class SponsorSegment(val segment: List<Double>, val category: String, val UUID: String = "") {
    val startMs: Long get() = ((segment.getOrNull(0) ?: 0.0) * 1000).toLong()
    val endMs: Long get() = ((segment.getOrNull(1) ?: 0.0) * 1000).toLong()
}

/** Client for the community driven SponsorBlock API (https://sponsor.ajay.app). */
class SponsorBlock(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun segments(videoId: String, categories: Set<String>): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            if (categories.isEmpty()) return@withContext emptyList()
            val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
                .addQueryParameter("videoID", videoId)
                .addQueryParameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
                .build()
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    json.decodeFromString(ListSerializer(SponsorSegment.serializer()), r.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
        }
}
