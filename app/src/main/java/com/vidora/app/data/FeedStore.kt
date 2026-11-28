package com.vidora.app.data

import android.content.Context
import androidx.room.Room
import com.vidora.app.data.cache.FeedDatabase
import com.vidora.app.data.cache.FeedRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/** Disposable public discovery snapshots, not stream URLs or account feeds. Max 32 x 128 KiB. */
class FeedStore(context: Context, private val name: String = "discovery-cache.db") {
    private val db by lazy { Room.databaseBuilder(context, FeedDatabase::class.java, name).build() }
    private val operations = Mutex()
    private val clearGeneration = AtomicLong()
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun read(key: String): List<Entry> = withContext(Dispatchers.IO) {
        runCatching {
            val row = db.feeds().find(digest(key)) ?: return@runCatching emptyList()
            if (System.currentTimeMillis() - row.savedAt !in 0..MAX_AGE) emptyList()
            else json.decodeFromString<List<Entry>>(row.payload)
        }.getOrDefault(emptyList())
    }
    suspend fun write(key: String, entries: List<Entry>) {
        val generation = clearGeneration.get()
        withContext(Dispatchers.IO) {
            operations.withLock {
                // A queued pre-clear write must never restore a snapshot after privacy clearing.
                if (generation != clearGeneration.get()) return@withLock
                val payload = json.encodeToString(entries.take(60))
                if (payload.toByteArray().size > 128 * 1024) return@withLock
                val row = FeedRecord().apply { cacheKey = digest(key); this.payload = payload; savedAt = System.currentTimeMillis() }
                db.runInTransaction { db.feeds().put(row); db.feeds().prune(System.currentTimeMillis() - MAX_AGE) }
            }
        }
    }
    suspend fun clear() {
        clearGeneration.incrementAndGet()
        withContext(Dispatchers.IO) { operations.withLock { db.feeds().clear() } }
    }
    fun close() = db.close()
    companion object {
        const val MAX_AGE = 7 * 24 * 60 * 60_000L
        internal fun digest(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
