package com.vidora.app.data

import com.vidora.app.Graph
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Comments straight from the web InnerTube `next` endpoint (signed in when possible). Used when
 * NewPipe Extractor's comments request is blocked or hangs (VPN / bot checks), so the watch page
 * never stays on "Loading…".
 *
 * Current layout: `commentThreadRenderer → commentViewModel` holds only ids; the text, author and
 * counts live in `frameworkUpdates.entityBatchUpdate.mutations[].payload.commentEntityPayload`.
 */
object YtComments {

    suspend fun first(videoId: String): CommentsPage {
        val api = Graph.accountApi
        val res = api.webPost("next", buildJsonObject {
            put("context", api.webContext()); put("videoId", videoId)
            put("contentCheckOk", true); put("racyCheckOk", true)
        })
        val section = findSection(res) ?: throw IOException("This video has no comments section")
        val token = findToken(section)
        if (token == null) {
            // A messageRenderer instead of a continuation means "Comments are turned off".
            if (findFirst(section, "messageRenderer") != null) return CommentsPage(emptyList(), null, 0, disabled = true)
            throw IOException("Comments unavailable")
        }
        return page(token, replies = false)
    }

    private suspend fun page(token: String, replies: Boolean): CommentsPage {
        val api = Graph.accountApi
        val res = api.webPost("next", buildJsonObject { put("context", api.webContext()); put("continuation", token) })

        val entities = HashMap<String, JsonObject>()
        val surfaces = HashMap<String, JsonObject>()
        val states = HashMap<String, JsonObject>()
        ((res.obj("frameworkUpdates").obj("entityBatchUpdate")["mutations"]) as? JsonArray)?.forEach { m ->
            val mo = m as? JsonObject ?: return@forEach
            val key = mo.str("entityKey")
            if (key != null) {
                mo.obj("payload")["engagementToolbarSurfaceEntityPayload"]?.let { surfaces[key] = it as JsonObject }
                mo.obj("payload")["engagementToolbarStateEntityPayload"]?.let { states[key] = it as JsonObject }
            }
            val payload = mo.obj("payload").obj("commentEntityPayload").takeIf { it.isNotEmpty() } ?: return@forEach
            val id = payload.obj("properties").str("commentId") ?: return@forEach
            entities[id] = payload
        }

        val items = mutableListOf<CommentItem>()
        var total = -1
        var next: String? = null
        val actions = (res["onResponseReceivedEndpoints"] as? JsonArray).orEmpty() + (res["onResponseReceivedActions"] as? JsonArray).orEmpty()
        for (a in actions) {
            val ao = a as? JsonObject ?: continue
            val list = (ao.obj("reloadContinuationItemsCommand")["continuationItems"] as? JsonArray)
                ?: (ao.obj("appendContinuationItemsAction")["continuationItems"] as? JsonArray)
                ?: continue
            for (e in list) {
                val o = e as? JsonObject ?: continue
                when {
                    o["commentsHeaderRenderer"] != null -> {
                        total = parseCount(text(o.obj("commentsHeaderRenderer")["countText"]) ?: text(o.obj("commentsHeaderRenderer")["commentsCount"])).toInt()
                    }
                    o["commentThreadRenderer"] != null -> {
                        val thread = o.obj("commentThreadRenderer")
                        val vm = thread.obj("commentViewModel").obj("commentViewModel")
                        val repliesToken = (thread.obj("replies").obj("commentRepliesRenderer")["contents"] as? JsonArray)
                            ?.firstNotNullOfOrNull { findToken(it) }
                        build(vm, entities, repliesToken, surfaces, states)?.let(items::add)
                    }
                    o["commentViewModel"] != null -> build(o.obj("commentViewModel"), entities, null, surfaces, states)?.let(items::add)
                    o["continuationItemRenderer"] != null -> next = findToken(o)
                }
            }
        }
        val createParams = findFirst(res, "createCommentEndpoint")?.str("createCommentParams")
        if (items.isEmpty() && next == null && !replies && total <= 0) return CommentsPage(emptyList(), null, 0, createParams = createParams)
        val nextToken = next
        val nextFn: (suspend () -> CommentsPage)? = nextToken?.let { t -> suspend { page(t, replies) } }
        return CommentsPage(items, nextFn, total, createParams = createParams)
    }

    private fun build(
        vm: JsonObject, entities: Map<String, JsonObject>, repliesToken: String?,
        surfaces: Map<String, JsonObject> = emptyMap(), states: Map<String, JsonObject> = emptyMap(),
    ): CommentItem? {
        val surface = vm.str("toolbarSurfaceKey")?.let { surfaces[it] }
        val state = vm.str("toolbarStateKey")?.let { states[it] }
        fun action(cmd: String) = surface?.get(cmd)?.let { findFirst(it, "performCommentActionEndpoint") }?.str("action")
        val id = vm.str("commentId") ?: return null
        val e = entities[id] ?: return null
        val props = e.obj("properties")
        val author = e.obj("author")
        val toolbar = e.obj("toolbar")
        val replyCount = parseCount(toolbar.str("replyCount")).toInt()
        val repliesFn: (suspend () -> CommentsPage)? = repliesToken?.let { t -> suspend { page(t, replies = true) } }
        return CommentItem(
            id = id,
            author = author.str("displayName").orEmpty(),
            authorAvatar = author.str("avatarThumbnailUrl")
                ?: ((e.obj("avatar").obj("image")["sources"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("url"),
            authorUrl = author.str("channelId")?.let { "https://www.youtube.com/channel/$it" },
            text = props.obj("content").str("content").orEmpty(),
            likes = parseCount(toolbar.str("likeCountNotliked") ?: toolbar.str("likeCountLiked")).toInt(),
            published = props.str("publishedTime"),
            replyCount = replyCount,
            pinned = vm["pinnedText"] != null,
            hearted = toolbar.str("heartState")?.contains("HEARTED") == true && toolbar.str("heartState")?.contains("UN") != true,
            verified = author.bool("isVerified") == true,
            byCreator = author.bool("isCreator") == true,
            loadReplies = repliesFn,
            likeAction = action("likeCommand"),
            unlikeAction = action("unlikeCommand"),
            replyParams = surface?.let { findFirst(it, "createCommentReplyEndpoint") }?.str("createReplyParams"),
            liked = state?.str("likeState") == "TOOLBAR_LIKE_STATE_LIKED",
        )
    }

    private fun findSection(res: JsonObject): JsonObject? {
        var found: JsonObject? = null
        fun rec(e: JsonElement?) {
            if (found != null) return
            when (e) {
                is JsonObject -> {
                    val isr = e["itemSectionRenderer"] as? JsonObject
                    if (isr != null && isr.str("sectionIdentifier") == "comment-item-section") { found = isr; return }
                    e.values.forEach(::rec)
                }
                is JsonArray -> e.forEach(::rec)
                else -> {}
            }
        }
        rec(res.obj("contents"))
        if (found == null) {
            // Some layouts only expose the comments continuation in the engagement panel.
            (res["engagementPanels"] as? JsonArray)?.forEach { p ->
                val ep = (p as? JsonObject)?.obj("engagementPanelSectionListRenderer") ?: return@forEach
                if (ep.str("panelIdentifier")?.contains("comment") == true) found = found ?: ep
            }
        }
        return found
    }

    private fun findToken(e: JsonElement?): String? = when (e) {
        is JsonObject -> (e["continuationCommand"] as? JsonObject)?.str("token")
            ?: e.values.firstNotNullOfOrNull { findToken(it) }
        is JsonArray -> e.firstNotNullOfOrNull { findToken(it) }
        else -> null
    }

    private fun findFirst(e: JsonElement?, key: String): JsonObject? = when (e) {
        is JsonObject -> (e[key] as? JsonObject) ?: e.values.firstNotNullOfOrNull { findFirst(it, key) }
        is JsonArray -> e.firstNotNullOfOrNull { findFirst(it, key) }
        else -> null
    }

    /** "1.2K", "3,456", "12 comments", "2.1M" → number. */
    internal fun parseCount(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val m = Regex("""([0-9][0-9.,]*)\s*([KkMmBb])?""").find(text) ?: return 0
        val raw = m.groupValues[1]
        val mult = when (m.groupValues[2].lowercase()) { "k" -> 1_000.0; "m" -> 1_000_000.0; "b" -> 1_000_000_000.0; else -> 1.0 }
        val num = if (mult == 1.0) raw.replace(",", "").replace(".", "").toDoubleOrNull() else raw.replace(",", ".").toDoubleOrNull()
        return ((num ?: 0.0) * mult).toLong()
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun text(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return null
        return o.str("simpleText") ?: (o["runs"] as? JsonArray)?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }
}
