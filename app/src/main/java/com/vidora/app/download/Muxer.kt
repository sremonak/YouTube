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

    fun merge(video: File, audio: File, output: File, webm: Boolean) {
        val format = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val muxer = MediaMuxer(output.absolutePath, format)
        val videoEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        try {
            val vTrack = selectTrack(videoEx, "video/")
            val aTrack = selectTrack(audioEx, "audio/")
            require(vTrack >= 0) { "No video track found" }
            require(aTrack >= 0) { "No audio track found" }
            val vFormat = videoEx.getTrackFormat(vTrack)
            val aFormat = audioEx.getTrackFormat(aTrack)
            val outV = muxer.addTrack(vFormat)
            val outA = muxer.addTrack(aFormat)
            muxer.start()
            copy(videoEx, muxer, outV, bufferSize(vFormat))
            copy(audioEx, muxer, outA, bufferSize(aFormat))
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            videoEx.release()
            audioEx.release()
        }
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

    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int, size: Int) {
        var buffer = ByteBuffer.allocate(size)
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (ex.sampleSize > buffer.capacity()) buffer = ByteBuffer.allocate(ex.sampleSize.toInt() + 1024)
            buffer.clear()
            val read = ex.readSampleData(buffer, 0)
            if (read < 0) break
            info.offset = 0
            info.size = read
            info.presentationTimeUs = ex.sampleTime
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(track, buffer, info)
            ex.advance()
        }
    }
}
