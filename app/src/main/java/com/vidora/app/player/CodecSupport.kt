package com.vidora.app.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Which efficient video codecs this phone decodes in hardware. AV1 saves ~30 % and VP9 ~20 % of
 * data versus H.264 at the same picture quality, but software decoding them costs battery and
 * drops frames, so they are only preferred when a hardware decoder exists.
 */
object CodecSupport {
    data class Hw(val maxHeight: Int)

    private const val AV1 = "video/av01"
    private const val VP9 = "video/x-vnd.on2.vp9"

    val av1: Hw? by lazy { find(AV1) }
    val vp9: Hw? by lazy { find(VP9) }

    private fun MediaCodecInfo.isHardware(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isHardwareAccelerated && !isSoftwareOnly
        else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.") && !name.startsWith("c2.google.")

    private fun find(mime: String): Hw? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } && it.isHardware() }
            .mapNotNull { info ->
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper ?: 1080 }.getOrNull()
            }
            .maxOrNull()?.let { Hw(it) }
    }.getOrNull()

    fun family(codec: String?): String = when {
        codec == null -> "?"
        codec.startsWith("avc") -> "avc"
        codec.startsWith("vp9") || codec.startsWith("vp09") -> "vp9"
        codec.startsWith("av01") -> "av1"
        else -> codec.substringBefore('.')
    }

    /** Codec families to try, best first, for the given resolution cap. */
    fun preference(efficient: Boolean, height: Int): List<String> = buildList {
        if (efficient) {
            av1?.takeIf { it.maxHeight >= minOf(height, 1080) }?.let { add("av1") }
            vp9?.takeIf { it.maxHeight >= minOf(height, 1080) }?.let { add("vp9") }
        }
        add("avc")
        if ("vp9" !in this) add("vp9")
    }

    /** Short description for Stats for nerds. */
    val summary: String by lazy {
        listOfNotNull(
            av1?.let { "AV1 ≤${it.maxHeight}p" },
            vp9?.let { "VP9 ≤${it.maxHeight}p" },
        ).joinToString(" · ").ifEmpty { "H.264 only" }
    }
}
