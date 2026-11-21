package com.vidora.app.player

import android.content.Context
import com.vidora.app.data.VideoItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PersistedSession(val queue: List<VideoItem>, val index: Int)

/** Remembers the play queue across app restarts. */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "session.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PersistedSession? = runCatching {
        if (file.exists()) json.decodeFromString(PersistedSession.serializer(), file.readText()) else null
    }.getOrNull()?.takeIf { it.queue.isNotEmpty() && it.index in it.queue.indices }

    fun save(session: PersistedSession?) {
        runCatching {
            if (session == null || session.queue.isEmpty()) file.delete()
            else file.writeText(json.encodeToString(PersistedSession.serializer(), PersistedSession(session.queue.take(200), session.index)))
        }
    }
}
