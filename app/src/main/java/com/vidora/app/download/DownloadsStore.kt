package com.vidora.app.download

import android.content.Context
import com.vidora.app.data.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class DownloadKind { VIDEO, AUDIO }

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, MUXING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class DownloadRecord(
    val id: String,
    val video: VideoItem,
    val kind: DownloadKind,
    val videoItag: Int = -1,
    val audioItag: Int = -1,
    val qualityLabel: String,
    val fileName: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val uri: String? = null,
    val mime: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Persists download records; live progress lives in memory and is flushed on status changes. */
class DownloadsStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    private fun load(): List<DownloadRecord> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString(ListSerializer(DownloadRecord.serializer()), file.readText()).map {
            // anything interrupted by process death is marked failed (can be retried)
            if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.MUXING || it.status == DownloadStatus.QUEUED)
                it.copy(status = DownloadStatus.FAILED, error = "Interrupted") else it
        }
    }.getOrDefault(emptyList())

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    fun upsert(record: DownloadRecord, persist: Boolean = true) {
        _items.update { list ->
            if (list.any { it.id == record.id }) list.map { if (it.id == record.id) record else it }
            else listOf(record) + list
        }
        if (persist) save()
    }

    fun update(id: String, persist: Boolean = false, block: (DownloadRecord) -> DownloadRecord) {
        _items.update { list -> list.map { if (it.id == id) block(it) else it } }
        if (persist) save()
    }

    fun remove(id: String) {
        _items.update { list -> list.filter { it.id != id } }
        save()
    }

    private fun save() {
        val snapshot = _items.value
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(ListSerializer(DownloadRecord.serializer()), snapshot))
                tmp.renameTo(file)
            }
        }
    }
}
