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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads one record. Stream URLs are resolved *inside* the worker so retries always get
 * fresh (non-expired) links. Data is fetched in resumable 8 MB chunks, which avoids
 * server-side throttling of long single connections.
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
            val output: File
            when (record.kind) {
                DownloadKind.AUDIO -> {
                    val audio = details.audioStreams.firstOrNull { it.itag == record.audioItag }
                        ?: details.audioStreams.firstOrNull() ?: error("Audio stream unavailable")
                    output = File(workDir, "audio.${audio.suffix}")
                    fetch(audio.url, output, recordId, record.video.title, 0L, audio.contentLength)
                }
                DownloadKind.VIDEO -> {
                    val video = details.videoStreams.firstOrNull { it.itag == record.videoItag }
                        ?: details.videoStreams.firstOrNull() ?: error("Video stream unavailable")
                    val audio = if (video.videoOnly) {
                        details.audioStreams.firstOrNull { it.itag == record.audioItag }
                            ?: details.audioStreams.firstOrNull { it.suffix == (if (video.suffix == "webm") "webm" else "m4a") }
                    } else null
                    val videoFile = File(workDir, "video.${video.suffix}")
                    if (audio == null) {
                        fetch(video.url, videoFile, recordId, record.video.title, 0L, video.contentLength)
                        output = videoFile
                    } else {
                        val audioFile = File(workDir, "audio.${audio.suffix}")
                        val audioSize = audio.contentLength.takeIf { it > 0 } ?: 0L
                        fetch(audio.url, audioFile, recordId, record.video.title, 0L, audioSize, combinedTotal = video.contentLength + audioSize)
                        fetch(video.url, videoFile, recordId, record.video.title, audioFile.length(), video.contentLength, combinedTotal = video.contentLength + audioFile.length())
                        Graph.downloads.update(recordId, persist = true) { it.copy(status = DownloadStatus.MUXING, speedBps = 0) }
                        runCatching { setForeground(foregroundInfo("Merging · " + record.video.title, 100, true)) }
                        output = File(workDir, "merged.${video.suffix}")
                        Muxer.merge(videoFile, audioFile, output, webm = video.suffix == "webm")
                        videoFile.delete()
                        audioFile.delete()
                    }
                }
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

    private suspend fun fetch(
        url: String,
        target: File,
        recordId: String,
        title: String,
        offsetBytes: Long,
        knownLength: Long,
        combinedTotal: Long = knownLength,
    ) {
        var total = knownLength
        var position = if (target.exists()) target.length() else 0L
        var lastUiUpdate = 0L
        var windowStart = System.currentTimeMillis()
        var windowBytes = 0L
        var speed = 0L
        var lastPercent = -1
        val buffer = ByteArray(64 * 1024)

        RandomAccessFile(target, "rw").use { raf ->
            raf.seek(position)
            while (total <= 0 || position < total) {
                coroutineContext.ensureActive()
                val end = if (total > 0) minOf(position + CHUNK - 1, total - 1) else position + CHUNK - 1
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 416) { total = position; return@use }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    if (response.code == 200 && position > 0) {
                        // server ignored the range → start over to avoid corrupting the file
                        raf.setLength(0); raf.seek(0); position = 0
                    }
                    if (total <= 0) {
                        total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: response.body?.contentLength()?.takeIf { response.code == 200 } ?: -1
                    }
                    val body = response.body ?: throw IOException("Empty body")
                    var readInChunk = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            raf.write(buffer, 0, n)
                            position += n
                            readInChunk += n
                            windowBytes += n
                            val now = System.currentTimeMillis()
                            if (now - windowStart >= 1000) {
                                speed = windowBytes * 1000 / (now - windowStart)
                                windowStart = now
                                windowBytes = 0
                            }
                            if (now - lastUiUpdate > 400) {
                                lastUiUpdate = now
                                val grandTotal = if (combinedTotal > 0) combinedTotal else offsetBytes + total
                                val done = offsetBytes + position
                                val progress = if (grandTotal > 0) (done.toFloat() / grandTotal).coerceIn(0f, 1f) else 0f
                                Graph.downloads.update(recordId) {
                                    it.copy(progress = progress, downloadedBytes = done, totalBytes = grandTotal.coerceAtLeast(done), speedBps = speed)
                                }
                                val percent = (progress * 100).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    runCatching { setForeground(foregroundInfo(title, percent, false)) }
                                }
                            }
                        }
                    }
                    if (readInChunk == 0L) total = position // server sent nothing more → done
                    if (response.code == 200) total = position // full body returned at once
                }
            }
        }
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
