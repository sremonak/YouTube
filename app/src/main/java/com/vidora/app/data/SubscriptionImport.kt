package com.vidora.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Reads subscription lists exported by other apps:
 *  - NewPipe / Tubular / PipePipe JSON  (`{"subscriptions":[{"url":..,"name":..}]}`)
 *  - LibreTube JSON backups              (`subscriptions` / `localSubscriptions` with `channelId`)
 *  - FreeTube / Invidious JSON           (`{"subscriptions":["UC..."]}` or objects with `id`)
 *  - Google Takeout CSV                  (`Channel Id,Channel Url,Channel Title`)
 *  - OPML (Invidious / RSS readers)
 *  - Vidora's own library backup
 */
object SubscriptionImport {
    private val channelIdRegex = Regex("(UC[\\w-]{22})")

    fun parse(text: String): List<ChannelItem> {
        val t = text.trim().removePrefix("\uFEFF")
        val result = when {
            t.startsWith("{") || t.startsWith("[") -> parseJson(t)
            t.startsWith("<") -> parseOpml(t)
            else -> parseCsv(t)
        }
        return result.filter { it.url.isNotBlank() }.distinctBy { normalise(it.url) }
    }

    private fun normalise(url: String) = url.lowercase().removeSuffix("/").replace("://m.", "://www.").replace("://youtube.com", "://www.youtube.com")

    private fun channelUrl(id: String) = "https://www.youtube.com/channel/$id"

    // ---------------------------------------------------------------- CSV (Takeout)

    private fun parseCsv(text: String): List<ChannelItem> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val cols = splitCsv(line)
            val id = cols.firstNotNullOfOrNull { c -> channelIdRegex.find(c)?.value } ?: return@mapNotNull null
            val name = cols.getOrNull(2)?.takeIf { it.isNotBlank() } ?: cols.lastOrNull { !it.contains(id) && it.isNotBlank() } ?: id
            ChannelItem(channelUrl(id), name)
        }
        .toList()

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    // ---------------------------------------------------------------- OPML

    private fun parseOpml(text: String): List<ChannelItem> {
        val outline = Regex("<outline\\b[^>]*>", RegexOption.IGNORE_CASE)
        fun attr(tag: String, name: String) = Regex("\\b$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        return outline.findAll(text).mapNotNull { m ->
            val tag = m.value
            val id = (attr(tag, "xmlUrl") ?: attr(tag, "htmlUrl"))?.let { channelIdRegex.find(it)?.value } ?: return@mapNotNull null
            ChannelItem(channelUrl(id), unescape(attr(tag, "title") ?: attr(tag, "text") ?: id))
        }.toList()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    // ---------------------------------------------------------------- JSON

    private fun parseJson(text: String): List<ChannelItem> {
        val root = Json.parseToJsonElement(text)
        val out = mutableListOf<ChannelItem>()
        collect(root, out, inSubscriptions = root is JsonArray)
        return out
    }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

    private fun collect(e: JsonElement, out: MutableList<ChannelItem>, inSubscriptions: Boolean) {
        when (e) {
            is JsonObject -> {
                val url = e.str("url", "channelUrl", "channel_url")
                val id = e.str("channelId", "channel_id", "id", "authorId")?.takeIf { channelIdRegex.matches(it) }
                val name = e.str("name", "title", "channelName", "author", "uploaderName")
                val serviceId = (e["service_id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                val isYoutube = serviceId == null || serviceId == 0
                val looksLikeChannel = (url != null && (url.contains("youtube.com/") && (url.contains("/channel/") || url.contains("/@") || url.contains("/c/") || url.contains("/user/")))) || id != null
                if (inSubscriptions && looksLikeChannel && isYoutube) {
                    out += ChannelItem(
                        url = url?.takeIf { it.contains("youtube.com/") } ?: channelUrl(id!!),
                        name = name ?: id ?: url!!,
                        avatar = e.str("avatar", "thumbnail", "avatarUrl"),
                    )
                    return
                }
                for ((k, v) in e) {
                    val sub = inSubscriptions || k.contains("subscri", ignoreCase = true) || k.equals("channels", true)
                    collect(v, out, sub)
                }
            }
            is JsonArray -> e.forEach { child ->
                if (inSubscriptions && child is JsonPrimitive) {
                    child.contentOrNull?.let { channelIdRegex.find(it)?.value }?.let { out += ChannelItem(channelUrl(it), it) }
                } else collect(child, out, inSubscriptions)
            }
            else -> Unit
        }
    }

    /** Exports in NewPipe's format — readable by NewPipe, Tubular, PipePipe, LibreTube and Vidora. */
    fun exportNewPipe(channels: List<ChannelItem>): String = buildJsonObject {
        put("app_version", "0.27.1")
        put("app_version_int", 1000)
        putJsonArray("subscriptions") {
            channels.forEach { c ->
                addJsonObject {
                    put("service_id", 0)
                    put("url", c.url)
                    put("name", c.name)
                }
            }
        }
    }.toString()
}
