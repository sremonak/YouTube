package com.vidora.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Losslessly merges a video-only and an audio-only file into one container using the
 * platform [MediaMuxer] (no re-encoding, no FFmpeg, tiny APK).
 */
object Muxer {

    /** A removed time range in microseconds. */
    data class Cut(val startUs: Long, val endUs: Long)

    /**
     * Merges [video] + [audio] (either may be null for single-track remuxing). [cuts] are removed
     * (SponsorBlock): video is cut on keyframes so nothing has to be re-encoded, audio follows the
     * same boundaries, and later timestamps are shifted back.
     */
    fun merge(video: File?, audio: File?, output: File, webm: Boolean, cuts: List<Cut> = emptyList()) {
        require(video != null || audio != null)
        val format = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val muxer = MediaMuxer(output.absolutePath, format)
        val videoEx = video?.let { MediaExtractor().apply { setDataSource(it.absolutePath) } }
        val audioEx = audio?.let { MediaExtractor().apply { setDataSource(it.absolutePath) } }
        try {
            val vTrack = videoEx?.let { selectTrack(it, "video/") } ?: -1
            val aTrack = audioEx?.let { selectTrack(it, "audio/") } ?: -1
            if (videoEx != null) require(vTrack >= 0) { "No video track found" }
            if (audioEx != null) require(aTrack >= 0) { "No audio track found" }
            // Keyframe-aligned ranges, decided on the video when there is one.
            val ranges = when {
                cuts.isEmpty() -> emptyList()
                video != null -> keyframeRanges(video, cuts)
                else -> normalize(cuts)
            }
            val vFormat = videoEx?.getTrackFormat(vTrack)
            val aFormat = audioEx?.getTrackFormat(aTrack)
            val outV = vFormat?.let { muxer.addTrack(it) } ?: -1
            val outA = aFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()
            if (videoEx != null) copy(videoEx, muxer, outV, bufferSize(vFormat!!), ranges, keyframeCut = true)
            if (audioEx != null) copy(audioEx, muxer, outA, bufferSize(aFormat!!), ranges, keyframeCut = false)
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            videoEx?.release()
            audioEx?.release()
        }
    }

    internal fun normalize(cuts: List<Cut>): List<Cut> {
        val sorted = cuts.filter { it.endUs > it.startUs }.sortedBy { it.startUs }
        val out = mutableListOf<Cut>()
        for (c in sorted) {
            val last = out.lastOrNull()
            if (last != null && c.startUs <= last.endUs) out[out.size - 1] = Cut(last.startUs, maxOf(last.endUs, c.endUs))
            else out += c
        }
        return out
    }

    /** Moves each cut to [first keyframe ≥ start, first keyframe ≥ end) so GOPs stay intact. */
    private fun keyframeRanges(video: File, cuts: List<Cut>): List<Cut> {
        val ex = MediaExtractor().apply { setDataSource(video.absolutePath) }
        try {
            val t = selectTrack(ex, "video/")
            if (t < 0) return emptyList()
            fun nextKey(us: Long): Long {
                ex.seekTo(us, MediaExtractor.SEEK_TO_NEXT_SYNC)
                val st = ex.sampleTime
                return if (st < 0) Long.MAX_VALUE else st
            }
            return normalize(cuts.map { Cut(nextKey(it.startUs), nextKey(it.endUs)) })
        } finally {
            ex.release()
        }
    }

    /** Microseconds removed before [us] by [ranges]; null if [us] falls inside a removed range. */
    internal fun shiftFor(us: Long, ranges: List<Cut>): Long? {
        var removed = 0L
        for (r in ranges) {
            if (us < r.startUs) break
            if (us < r.endUs) return null
            removed += r.endUs - r.startUs
        }
        return removed
    }

    private fun selectTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) {
                ex.selectTrack(i)
                return i
            }
        }
        return -1
    }

    private fun bufferSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
        } else 4 * 1024 * 1024

    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int, size: Int, ranges: List<Cut> = emptyList(), keyframeCut: Boolean = false) {
        var buffer = ByteBuffer.allocate(size)
        val info = MediaCodec.BufferInfo()
        // Video: drop whole GOPs — decided at each keyframe, so B-frames never lose their references.
        var dropping = false
        var shift = 0L
        while (true) {
            if (ex.sampleSize > buffer.capacity()) buffer = ByteBuffer.allocate(ex.sampleSize.toInt() + 1024)
            buffer.clear()
            val read = ex.readSampleData(buffer, 0)
            if (read < 0) break
            val time = ex.sampleTime
            val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            if (ranges.isNotEmpty()) {
                if (keyframeCut) {
                    if (key) {
                        val s = shiftFor(time, ranges)
                        dropping = s == null
                        if (s != null) shift = s
                    }
                } else {
                    val s = shiftFor(time, ranges)
                    dropping = s == null
                    if (s != null) shift = s
                }
                if (dropping) { ex.advance(); continue }
            }
            info.offset = 0
            info.size = read
            info.presentationTimeUs = (time - shift).coerceAtLeast(0)
            info.flags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(track, buffer, info)
            ex.advance()
        }
    }
}
