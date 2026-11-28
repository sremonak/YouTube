package com.vidora.app.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vidora.app.Graph
import com.vidora.app.VidoraApp
import com.vidora.app.player.StreamInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Downloads one record. Stream URLs are resolved *inside* the worker so retries always get
 * fresh (non-expired) links. Audio and video are fetched in parallel, each over several
 * resumable 8 MB range connections; then SponsorBlock segments are cut (optional), the
 * tracks are merged losslessly and title/channel/cover art are embedded.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val client = Graph.http.newBuilder()
        .addInterceptor(StreamInterceptor())
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val notificationId get() = id.hashCode()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val record = Graph.downloads.get(recordId) ?: return@withContext Result.failure()
        val workDir = File(applicationContext.cacheDir, "dl/$recordId").apply { mkdirs() }

        try {
            runCatching { setForeground(foregroundInfo(record.video.title, 0, true)) }
            Graph.downloads.update(recordId, persist = true) { it.copy(status = DownloadStatus.RUNNING, error = null) }

            val details = Graph.repository.stream(record.video.url, forceRefresh = true)
            val settings = Graph.settingsState.value
            val videoId = Graph.repository.videoId(record.video.url)
            // SponsorBlock segments to cut out of the file (fetched while the download runs).
            val cutsJob = async {
                if (!settings.downloadCutSponsors || videoId == null) emptyList()
                else runCatching {
                    Graph.sponsorBlock.segments(videoId, settings.sponsorCategories)
                        .map { Muxer.Cut(it.startMs * 1000, it.endMs * 1000) }
                }.getOrDefault(emptyList())
            }
            val progress = Progress(recordId, record.video.title)
            var raw: File
            var video: File? = null
            var audio: File? = null
            var webm = false
            when (record.kind) {
                DownloadKind.AUDIO -> {
                    val a = details.audioStreams.firstOrNull { it.itag == record.audioItag }
                        ?: details.audioStreams.firstOrNull() ?: error("Audio stream unavailable")
                    raw = File(workDir, "audio.${a.suffix}")
                    progress.total = a.contentLength.coerceAtLeast(0)
                    fetch(a.url, raw, a.contentLength, settings.downloadConnections, progress)
                    audio = raw
                    webm = a.suffix == "webm"
                }
                DownloadKind.VIDEO -> {
                    val v = details.videoStreams.firstOrNull { it.itag == record.videoItag }
                        ?: details.videoStreams.firstOrNull() ?: error("Video stream unavailable")
                    val a = if (v.videoOnly) {
                        details.audioStreams.firstOrNull { it.itag == record.audioItag }
                            ?: details.audioStreams.firstOrNull { it.suffix == (if (v.suffix == "webm") "webm" else "m4a") }
                    } else null
                    webm = v.suffix == "webm"
                    val videoFile = File(workDir, "video.${v.suffix}")
                    raw = videoFile
                    video = videoFile
                    progress.total = v.contentLength.coerceAtLeast(0) + (a?.contentLength?.coerceAtLeast(0) ?: 0)
                    if (a == null) {
                        fetch(v.url, videoFile, v.contentLength, settings.downloadConnections, progress)
                        // Muxed stream (e.g. itag 18): its own audio track feeds the remux.
                        if (!v.videoOnly) audio = videoFile
                    } else {
                        val audioFile = File(workDir, "audio.${a.suffix}")
                        audio = audioFile
                        // Audio and video are fetched at the same time (like yt-dlp -N / aria2c).
                        coroutineScope {
                            val aJob = async { fetch(a.url, audioFile, a.contentLength, settings.downloadConnections, progress) }
                            val vJob = async { fetch(v.url, videoFile, v.contentLength, settings.downloadConnections, progress) }
                            aJob.await(); vJob.await()
                        }
                    }
                }
            }

            val cuts = cutsJob.await()
            val needsMux = video != null && audio != null && video != audio
            val wantsTags = settings.downloadEmbedMetadata && !webm
            var output = raw
            if (needsMux || cuts.isNotEmpty() || (wantsTags && raw.extension in setOf("mp4", "m4a"))) {
                Graph.downloads.update(recordId, persist = true) { it.copy(status = DownloadStatus.MUXING, speedBps = 0) }
                runCatching { setForeground(foregroundInfo((if (cuts.isNotEmpty()) "Removing sponsors · " else "Merging · ") + record.video.title, 100, true)) }
                val merged = File(workDir, "merged.${raw.extension}")
                val ok = runCatching { Muxer.merge(video, audio, merged, webm, cuts) }
                if (ok.isSuccess) {
                    output = merged
                    video?.delete(); audio?.delete()
                } else if (needsMux) {
                    // Retry without cuts before giving up — a merged file beats a failed download.
                    merged.delete()
                    Muxer.merge(video, audio, merged, webm)
                    output = merged
                    video?.delete(); audio?.delete()
                } else merged.delete()
            }
            if (wantsTags && output.extension in setOf("mp4", "m4a")) {
                runCatching {
                    val cover = videoId?.let { id -> download("https://i.ytimg.com/vi/$id/hqdefault.jpg") }
                    Mp4Tagger.tag(output, Mp4Tagger.Tags(
                        title = record.video.title,
                        artist = record.video.uploader,
                        comment = record.video.url,
                        date = details.item.uploadedEpoch.takeIf { it > 0 }?.let {
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(if (it < 10_000_000_000L) it * 1000 else it))
                        },
                        description = details.description.take(4000).ifBlank { null },
                        cover = cover,
                    ))
                }
            }
            if (settings.downloadSubtitles) runCatching { saveSubtitles(details, record) }

            // Preserve creator/licence/source metadata even for WebM, which our MP4 tagger cannot tag.
            if (com.vidora.app.data.GuestSources.supported(record.video.url)) {
                val attribution = "${details.item.title}\n${details.item.url}\n\n${details.description}\n\nPublic viewing does not grant unrestricted reuse. Check the original page's rights and licence.\n"
                Storage.publishSidecar(applicationContext, attribution.toByteArray(Charsets.UTF_8),
                    record.fileName + ".attribution.txt", "text/plain")
            }
            val uri = Storage.publish(applicationContext, output, record)
            Graph.downloads.update(recordId, persist = true) {
                it.copy(status = DownloadStatus.COMPLETED, progress = 1f, uri = uri.toString(), speedBps = 0,
                    downloadedBytes = it.totalBytes.coerceAtLeast(it.downloadedBytes))
            }
            notifyDone(record.video.title, success = true)
            workDir.deleteRecursively()
            Result.success()
        } catch (c: CancellationException) {
            Graph.downloads.update(recordId, persist = true) { it.copy(status = DownloadStatus.CANCELLED, speedBps = 0) }
            workDir.deleteRecursively()
            throw c
        } catch (t: Throwable) {
            Graph.downloads.update(recordId, persist = true) {
                it.copy(status = DownloadStatus.FAILED, error = t.message ?: t.javaClass.simpleName, speedBps = 0)
            }
            notifyDone(record.video.title, success = false)
            if (runAttemptCount < 2 && t is IOException) Result.retry() else Result.failure()
        }
    }

    /** Shared progress across parallel files/connections. */
    private inner class Progress(val recordId: String, val title: String) {
        @Volatile var total = 0L
        val done = AtomicLong(0)
        private var lastUi = 0L
        private var windowStart = System.currentTimeMillis()
        private var windowBytes = 0L
        private var speed = 0L
        private var lastPercent = -1

        fun add(n: Int) {
            val d = done.addAndGet(n.toLong())
            val update: Boolean
            synchronized(this) {
                windowBytes += n
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) { speed = windowBytes * 1000 / (now - windowStart); windowStart = now; windowBytes = 0 }
                update = now - lastUi > 400
                if (update) lastUi = now
            }
            if (!update) return
            val t = total.coerceAtLeast(d)
            val p = if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f
            Graph.downloads.update(recordId) { it.copy(progress = p, downloadedBytes = d, totalBytes = t, speedBps = speed) }
            val percent = (p * 100).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                runCatching { setForegroundAsync(foregroundInfo(title, percent, false)) }
            }
        }
    }

    /**
     * Downloads [url] into [target] with up to [connections] parallel 8 MB range requests.
     * Finished chunks are remembered in `<file>.parts`, so a retried worker resumes where it stopped.
     */
    private suspend fun fetch(url: String, target: File, knownLength: Long, connections: Int, progress: Progress) {
        val total = knownLength.takeIf { it > 0 } ?: probeLength(url)
        if (total <= 0) {
            fetchSequential(url, target, progress)
            return
        }
        if (knownLength <= 0) progress.total += total
        val chunks = ((total + CHUNK - 1) / CHUNK).toInt()
        val partsFile = File(target.path + ".parts")
        val finished = java.util.Collections.synchronizedSet(
            partsFile.takeIf { it.exists() }?.readLines()?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
        )
        RandomAccessFile(target, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
            val channel = raf.channel
            finished.forEach { i -> progress.add((minOf(total, (i + 1) * CHUNK) - i * CHUNK).toInt()) }
            val queue = java.util.concurrent.ConcurrentLinkedQueue((0 until chunks).filter { it !in finished })
            coroutineScope {
                repeat(connections.coerceIn(1, 8)) {
                    launch {
                        while (true) {
                            val i = queue.poll() ?: break
                            fetchChunk(url, channel, i * CHUNK, minOf(total, (i + 1) * CHUNK) - 1, progress)
                            finished += i
                            synchronized(partsFile) { partsFile.appendText("$i\n") }
                        }
                    }
                }
            }
        }
        partsFile.delete()
    }

    private suspend fun fetchChunk(url: String, channel: java.nio.channels.FileChannel, from: Long, to: Long, progress: Progress) {
        var attempt = 0
        var pos = from
        while (true) {
            coroutineContext.ensureActive()
            try {
                val request = Request.Builder().url(url).header("Range", "bytes=$pos-$to").build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 206) throw IOException("HTTP ${response.code}")
                    val buffer = ByteArray(64 * 1024)
                    response.body.byteStream().use { input ->
                        while (pos <= to) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), to - pos + 1).toInt())
                            if (n < 0) break
                            channel.write(java.nio.ByteBuffer.wrap(buffer, 0, n), pos)
                            pos += n
                            progress.add(n)
                        }
                    }
                }
                if (pos > to) return
                throw IOException("Connection closed early")
            } catch (e: IOException) {
                if (++attempt >= 4) throw e
                delay(1000L * attempt)
            }
        }
    }

    private fun probeLength(url: String): Long = runCatching {
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { r ->
            r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
        }
    }.getOrDefault(-1L)

    /** Fallback for servers that don't announce a length: one stream, start to end. */
    private suspend fun fetchSequential(url: String, target: File, progress: Progress) {
        target.delete()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.contentLength().takeIf { it > 0 }?.let { progress.total += it }
            val buffer = ByteArray(64 * 1024)
            target.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        progress.add(n)
                    }
                }
            }
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        Graph.http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }.getOrNull()

    /** Saves the preferred subtitle track as an .srt/.vtt next to the downloads (Download/Vidora). */
    private fun saveSubtitles(details: com.vidora.app.data.StreamDetails, record: DownloadRecord) {
        val lang = Graph.settingsState.value.captionLang.ifBlank { java.util.Locale.getDefault().language }
        val sub = details.subtitles.firstOrNull { it.languageCode.startsWith(lang) && !it.autoGenerated }
            ?: details.subtitles.firstOrNull { it.languageCode.startsWith(lang) }
            ?: details.subtitles.firstOrNull { !it.autoGenerated }
            ?: return
        val vttUrl = if ("fmt=" in sub.url) sub.url.replace(Regex("fmt=[^&]+"), "fmt=vtt") else sub.url + "&fmt=vtt"
        val bytes = download(vttUrl) ?: download(sub.url) ?: return
        val name = record.fileName.substringBeforeLast('.') + "." + sub.languageCode + ".vtt"
        Storage.publishSidecar(applicationContext, bytes, name, "text/vtt")
    }

    private fun foregroundInfo(title: String, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification: Notification = NotificationCompat.Builder(applicationContext, VidoraApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Preparing…" else "$progress%")
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }

    private fun notifyDone(title: String, success: Boolean) {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        val n = NotificationCompat.Builder(applicationContext, VidoraApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notificationId + 1, n) }
    }

    companion object {
        const val KEY_ID = "record_id"
        const val TAG = "vidora_download"
        private const val CHUNK = 8L * 1024 * 1024
    }
}
