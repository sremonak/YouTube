package com.vidora.app.data

import android.util.LruCache
import com.vidora.app.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

data class Votes(val likes: Long, val dislikes: Long, val rating: Double)

data class Branding(val title: String?, val thumbnail: String?)

/**
 * Community-powered extras:
 *  - Return YouTube Dislike (https://returnyoutubedislike.com) — dislike counts.
 *  - DeArrow (https://dearrow.ajay.app) — crowd-sourced, non-clickbait titles & thumbnails.
 */
object Community {
    private val json = Json { ignoreUnknownKeys = true }
    private val votesCache = LruCache<String, Votes>(200)
    private val brandingCache = LruCache<String, Branding>(500)

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "Vidora (Android)").build()
        return Graph.http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    suspend fun votes(videoId: String): Votes? = withContext(Dispatchers.IO) {
        votesCache.get(videoId)?.let { return@withContext it }
        runCatching {
            val o = json.parseToJsonElement(get("https://returnyoutubedislikeapi.com/votes?videoId=$videoId") ?: return@runCatching null).jsonObject
            Votes(
                likes = (o["likes"] as? JsonPrimitive)?.longOrNull ?: -1,
                dislikes = (o["dislikes"] as? JsonPrimitive)?.longOrNull ?: -1,
                rating = (o["rating"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }.getOrNull()?.also { votesCache.put(videoId, it) }
    }

    fun cachedBranding(videoId: String): Branding? = brandingCache.get(videoId)

    suspend fun branding(videoId: String): Branding = withContext(Dispatchers.IO) {
        brandingCache.get(videoId)?.let { return@withContext it }
        val result = runCatching {
            val text = get("https://sponsor.ajay.app/api/branding?videoID=$videoId") ?: return@runCatching Branding(null, null)
            val o = json.parseToJsonElement(text).jsonObject
            val title = (o["titles"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { t ->
                    val original = (t["original"] as? JsonPrimitive)?.booleanOrNull == true
                    val votes = (t["votes"] as? JsonPrimitive)?.intOrNull ?: 0
                    val locked = (t["locked"] as? JsonPrimitive)?.booleanOrNull == true
                    !original && (votes >= 0 || locked)
                }
                ?.let { (it["title"] as? JsonPrimitive)?.contentOrNull }
                ?.let(::cleanTitle)
            val thumb = (o["thumbnails"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { t ->
                    (t["original"] as? JsonPrimitive)?.booleanOrNull != true &&
                        ((t["votes"] as? JsonPrimitive)?.intOrNull ?: 0) >= 0
                }
                ?.let { (it["timestamp"] as? JsonPrimitive)?.doubleOrNull }
                ?.let { ts -> "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$videoId&time=$ts" }
            Branding(title, thumb)
        }.getOrDefault(Branding(null, null))
        brandingCache.put(videoId, result)
        result
    }

    /** DeArrow marks titles that must not be auto-formatted with a leading '>'. */
    private fun cleanTitle(t: String) = t.removePrefix(">").trim().ifBlank { null }
}
