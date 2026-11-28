package com.vidora.app.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.vidora.app.data.AppSettings

data class EqBand(val index: Int, val centerHz: Int)

/** System audio effects (equaliser, bass boost, volume boost) bound to the player's audio session. */
class AudioEffects {
    private var sessionId = 0
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var loud: LoudnessEnhancer? = null

    var bands: List<EqBand> = emptyList()
        private set
    var levelRange: IntRange = -1500..1500
        private set
    var presets: List<String> = emptyList()
        private set
    val supported: Boolean get() = eq != null

    fun attach(audioSessionId: Int, s: AppSettings) {
        if (audioSessionId == sessionId && eq != null) {
            apply(s); return
        }
        release()
        sessionId = audioSessionId
        if (audioSessionId <= 0) return
        eq = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        loud = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        eq?.let { e ->
            runCatching {
                bands = (0 until e.numberOfBands).map { EqBand(it, e.getCenterFreq(it.toShort()) / 1000) }
                val r = e.bandLevelRange
                levelRange = r[0].toInt()..r[1].toInt()
                presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
            }
        }
        apply(s)
    }

    fun apply(s: AppSettings) {
        runCatching {
            eq?.let { e ->
                e.enabled = s.eqEnabled
                if (s.eqEnabled) {
                    if (s.eqPreset >= 0 && s.eqPreset < presets.size) {
                        e.usePreset(s.eqPreset.toShort())
                    } else {
                        parseBands(s.eqBands).forEachIndexed { i, level ->
                            if (i < bands.size) e.setBandLevel(i.toShort(), level.coerceIn(levelRange).toShort())
                        }
                    }
                }
            }
        }
        runCatching {
            bass?.let { b ->
                b.enabled = s.eqEnabled && s.bassBoost > 0
                if (b.strengthSupported && s.bassBoost > 0) b.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
            }
        }
        runCatching {
            loud?.let { l ->
                l.enabled = s.volumeBoost > 0
                if (s.volumeBoost > 0) l.setTargetGain(s.volumeBoost.coerceIn(0, 2000))
            }
        }
    }

    /** Current per-band levels (mB), used to seed the custom sliders from a preset. */
    fun currentLevels(): List<Int> = runCatching { bands.map { eq!!.getBandLevel(it.index.toShort()).toInt() } }.getOrDefault(emptyList())

    fun release() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        runCatching { loud?.release() }
        eq = null; bass = null; loud = null
    }

    companion object {
        fun parseBands(s: String): List<Int> = s.split(',').mapNotNull { it.trim().toIntOrNull() }
        fun encodeBands(levels: List<Int>): String = levels.joinToString(",")
    }
}
