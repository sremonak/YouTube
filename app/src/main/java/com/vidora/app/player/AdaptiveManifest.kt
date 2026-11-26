package com.vidora.app.player

import com.vidora.app.data.AudioOption
import com.vidora.app.data.VideoOption

/**
 * Builds an on-demand DASH manifest (SegmentBase / sidx addressing) from the separate
 * video-only and audio streams YouTube gives us — the same trick NewPipe and LibreTube use.
 *
 * With a manifest, ExoPlayer's adaptive track selection can start at a low bitrate when the
 * network is slow (fast first frame) and step the quality up/down every few seconds like the
 * official app, instead of downloading one fixed progressive file.
 */
object AdaptiveManifest {

    data class Plan(val xml: String, val videos: List<VideoOption>, val audios: List<AudioOption>)

    private fun VideoOption.usable() =
        videoOnly && initRange != null && indexRange != null && !codec.isNullOrBlank() && height > 0 && url.isNotBlank()

    private fun AudioOption.usable() =
        initRange != null && indexRange != null && !codec.isNullOrBlank() && url.isNotBlank()

    private fun family(codec: String?) = CodecSupport.family(codec)

    /**
     * @param maxHeight upper limit chosen by the user (Wi-Fi quality / mobile-data cap)
     * @return null when the streams lack the byte ranges needed for DASH (caller falls back to progressive)
     */
    fun plan(
        allVideos: List<VideoOption>,
        allAudios: List<AudioOption>,
        durationSec: Long,
        maxHeight: Int,
        /** Codec families best-first (see [CodecSupport.preference]); default H.264 then VP9. */
        families: List<String> = listOf("avc", "vp9"),
        /** Data Saver: only the smallest audio stream. */
        lowAudio: Boolean = false,
    ): Plan? {
        if (durationSec <= 0) return null
        val usableVideos = allVideos.filter { it.usable() }
        if (usableVideos.isEmpty()) return null
        // One codec family only. An efficient family (AV1/VP9) is used only when it reaches the
        // same top resolution as H.264 would, so preferring it never lowers the quality.
        fun top(f: String) = usableVideos.filter { family(it.codec) == f && it.height <= maxHeight }.maxOfOrNull { it.height }
            ?: if (usableVideos.any { family(it.codec) == f }) 0 else -1
        val reference = top("avc").takeIf { it >= 0 } ?: top("vp9")
        val fam = families.firstOrNull { f -> top(f) >= 0 && top(f) >= reference }
            ?: families.firstOrNull { f -> top(f) >= 0 } ?: return null
        val videos = usableVideos
            .filter { family(it.codec) == fam }
            .let { list -> list.filter { it.height <= maxHeight }.ifEmpty { listOf(list.minBy { it.height }) } }
            .groupBy { it.height to it.fps }
            .map { (_, l) -> l.maxBy { it.bitrate } }
            .sortedBy { it.height * 1000 + it.fps }
        val usableAudios = allAudios.filter { it.usable() }
        val audios = (usableAudios.filter { it.suffix == "m4a" }.ifEmpty { usableAudios })
            .let { l -> l.filter { it.mime == l.firstOrNull()?.mime } }
            .distinctBy { it.itag }
            .sortedBy { it.bitrateKbps }
            .let { if (lowAudio) it.take(1) else it }
        if (videos.isEmpty() || audios.isEmpty()) return null
        return Plan(build(videos, audios, durationSec), videos, audios)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun build(videos: List<VideoOption>, audios: List<AudioOption>, durationSec: Long): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
        append("type=\"static\" minBufferTime=\"PT1.000S\" mediaPresentationDuration=\"PT${durationSec}.000S\">\n")
        append("<Period start=\"PT0S\" duration=\"PT${durationSec}.000S\">\n")
        append("<AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"${esc(videos.first().mime)}\" subsegmentAlignment=\"true\" startWithSAP=\"1\">\n")
        videos.forEach { v ->
            val width = if (v.width > 0) v.width else v.height * 16 / 9
            append("<Representation id=\"v${v.itag}\" codecs=\"${esc(v.codec!!)}\" bandwidth=\"${v.bitrate.coerceAtLeast(v.height * 1000)}\" ")
            append("width=\"$width\" height=\"${v.height}\" frameRate=\"${v.fps.coerceAtLeast(1)}\">\n")
            append("<BaseURL>${esc(v.url)}</BaseURL>\n")
            append("<SegmentBase indexRange=\"${v.indexRange}\"><Initialization range=\"${v.initRange}\"/></SegmentBase>\n")
            append("</Representation>\n")
        }
        append("</AdaptationSet>\n")
        append("<AdaptationSet id=\"1\" contentType=\"audio\" mimeType=\"${esc(audios.first().mime)}\" subsegmentAlignment=\"true\" startWithSAP=\"1\">\n")
        audios.forEach { a ->
            append("<Representation id=\"a${a.itag}\" codecs=\"${esc(a.codec!!)}\" bandwidth=\"${(a.bitrateKbps.coerceAtLeast(32)) * 1000}\" audioSamplingRate=\"48000\">\n")
            append("<AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n")
            append("<BaseURL>${esc(a.url)}</BaseURL>\n")
            append("<SegmentBase indexRange=\"${a.indexRange}\"><Initialization range=\"${a.initRange}\"/></SegmentBase>\n")
            append("</Representation>\n")
        }
        append("</AdaptationSet>\n</Period>\n</MPD>\n")
    }
}
