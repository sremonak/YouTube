package com.vidora.app.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vidora.app.Graph
import com.vidora.app.data.AudioOption
import com.vidora.app.data.AudioPreference
import com.vidora.app.data.ContentRepository
import com.vidora.app.data.StreamDetails
import com.vidora.app.data.VideoItem
import com.vidora.app.data.VideoOption
import kotlinx.coroutines.flow.first
import java.util.UUID

/** A downloadable choice presented in the download sheet. */
sealed class DownloadChoice {
    abstract val label: String
    abstract val detail: String
    abstract val approxBytes: Long

    data class Video(val video: VideoOption, val audio: AudioOption?, override val approxBytes: Long) : DownloadChoice() {
        override val label get() = video.label
        override val detail get() = buildString {
            append(if (video.suffix == "webm") "WebM" else "MP4")
            video.codec?.substringBefore('.')?.let { append(" · ").append(it.uppercase()) }
            if (audio == null && video.videoOnly) append(" · no audio")
        }
    }

    data class Audio(val audio: AudioOption, override val approxBytes: Long) : DownloadChoice() {
        override val label get() = "${audio.bitrateKbps} kbps"
        override val detail get() = buildString {
            append(if (audio.suffix == "webm") "Opus (WebM)" else "AAC (M4A)")
            audio.trackName?.let { append(" · ").append(it) }
        }
    }
}

object Downloads {

    /** Builds the list of video qualities that can be produced on this device. */
    fun videoChoices(details: StreamDetails): List<DownloadChoice.Video> {
        val seconds = details.item.duration.coerceAtLeast(1)
        val m4a = details.audioStreams.filter { it.suffix == "m4a" }.maxByOrNull { it.bitrateKbps }
        val webmAudio = details.audioStreams.filter { it.suffix == "webm" }.maxByOrNull { it.bitrateKbps }

        val choices = mutableListOf<DownloadChoice.Video>()
        details.videoOnly.groupBy { it.height }.toSortedMap(compareByDescending { it }).forEach { (_, options) ->
            val mp4 = options.filter { it.suffix == "mp4" && it.codec?.startsWith("avc") == true }.maxByOrNull { it.fps * 10_000 + it.bitrate / 1000 }
            val webm = options.filter { it.suffix == "webm" }.maxByOrNull { it.fps * 10_000 + it.bitrate / 1000 }
            when {
                mp4 != null && m4a != null -> choices += DownloadChoice.Video(mp4, m4a, size(mp4.contentLength, mp4.bitrate, seconds) + size(m4a.contentLength, m4a.bitrateKbps * 1000, seconds))
                webm != null && webmAudio != null && ContentRepository.canMuxWebm ->
                    choices += DownloadChoice.Video(webm, webmAudio, size(webm.contentLength, webm.bitrate, seconds) + size(webmAudio.contentLength, webmAudio.bitrateKbps * 1000, seconds))
            }
        }
        details.muxed.forEach { muxed ->
            if (choices.none { it.video.height == muxed.height }) {
                choices += DownloadChoice.Video(muxed, null, size(muxed.contentLength, muxed.bitrate, seconds))
            }
        }
        return choices.sortedByDescending { it.video.height * 1000 + it.video.fps }
    }

    fun audioChoices(details: StreamDetails): List<DownloadChoice.Audio> {
        val seconds = details.item.duration.coerceAtLeast(1)
        return details.audioStreams
            .sortedWith(compareByDescending<AudioOption> { it.bitrateKbps })
            .map { DownloadChoice.Audio(it, size(it.contentLength, it.bitrateKbps * 1000, seconds)) }
    }

    private fun size(contentLength: Long, bitrate: Int, seconds: Long) =
        if (contentLength > 0) contentLength else bitrate.toLong() * seconds / 8

    /** Used for one-tap downloads when "ask every time" is off. */
    suspend fun defaultChoice(details: StreamDetails, audioOnly: Boolean): DownloadChoice? {
        val s = Graph.settings.flow.first()
        if (audioOnly) {
            val audio = audioChoices(details)
            return when (s.downloadAudioPref) {
                AudioPreference.M4A -> audio.firstOrNull { it.audio.suffix == "m4a" }
                AudioPreference.WEBM -> audio.firstOrNull { it.audio.suffix == "webm" }
                AudioPreference.BEST -> null
            } ?: audio.firstOrNull()
        }
        val videos = videoChoices(details)
        return videos.firstOrNull { it.video.height <= s.downloadQuality } ?: videos.lastOrNull()
    }

    suspend fun enqueue(context: Context, video: VideoItem, choice: DownloadChoice) {
        val id = UUID.randomUUID().toString()
        val safeTitle = video.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(120).trim()
        val record = when (choice) {
            is DownloadChoice.Video -> DownloadRecord(
                id = id, video = video, kind = DownloadKind.VIDEO,
                videoItag = choice.video.itag, audioItag = choice.audio?.itag ?: -1,
                qualityLabel = choice.label,
                fileName = "$safeTitle [${choice.label}].${choice.video.suffix}",
                totalBytes = choice.approxBytes,
                mime = if (choice.video.suffix == "webm") "video/webm" else "video/mp4",
            )
            is DownloadChoice.Audio -> DownloadRecord(
                id = id, video = video, kind = DownloadKind.AUDIO,
                audioItag = choice.audio.itag,
                qualityLabel = choice.label,
                fileName = "$safeTitle.${choice.audio.suffix}",
                totalBytes = choice.approxBytes,
                mime = if (choice.audio.suffix == "webm") "audio/webm" else "audio/mp4",
            )
        }
        Graph.downloads.upsert(record)
        start(context, id)
    }

    suspend fun start(context: Context, id: String) {
        val wifiOnly = Graph.settings.flow.first().downloadWifiOnly
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .addTag(DownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun retry(context: Context, id: String) {
        Graph.downloads.update(id, persist = true) {
            it.copy(status = DownloadStatus.QUEUED, error = null, progress = 0f, downloadedBytes = 0)
        }
        start(context, id)
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(id)
        Graph.downloads.update(id, persist = true) { it.copy(status = DownloadStatus.CANCELLED, speedBps = 0) }
    }

    fun delete(context: Context, id: String, deleteFile: Boolean) {
        val record = Graph.downloads.get(id) ?: return
        WorkManager.getInstance(context).cancelUniqueWork(id)
        if (deleteFile) Storage.delete(context, record)
        Graph.downloads.remove(id)
    }
}
