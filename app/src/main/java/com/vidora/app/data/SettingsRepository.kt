package com.vidora.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vidora.app.util.LauncherCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "vidora_settings")

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED black") }

enum class FeedLayout(val label: String) { GRID("Cards"), LIST("Compact list") }

enum class AudioPreference(val label: String) { M4A("M4A (AAC)"), WEBM("WebM (Opus)"), BEST("Best available") }

/** Accent palettes used when Material You dynamic colour is off. */
val AccentPalette = listOf(
    0xFFFF3B5C, // Vidora red
    0xFF7C4DFF, // violet
    0xFF2979FF, // blue
    0xFF00BFA5, // teal
    0xFF00C853, // green
    0xFFFFAB00, // amber
    0xFFFF6D00, // orange
    0xFFF50057, // pink
)

val SponsorCategories = linkedMapOf(
    "sponsor" to "Sponsor",
    "selfpromo" to "Self promotion",
    "interaction" to "Interaction reminder",
    "intro" to "Intro / intermission",
    "outro" to "Endcards / credits",
    "preview" to "Preview / recap",
    "music_offtopic" to "Non-music section",
    "filler" to "Filler tangent",
)

data class AppSettings(
    // appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentIndex: Int = 0,
    val displayName: String = "Vidora",
    val feedLayout: FeedLayout = FeedLayout.GRID,
    val cornerRadius: Int = 16,
    val showChannelAvatars: Boolean = true,
    val launcherIcon: String = LauncherCatalog.DEFAULT_ICON,
    val launcherName: String = LauncherCatalog.DEFAULT_NAME,
    // player
    val defaultQuality: Int = 720,
    val defaultSpeed: Float = 1f,
    val backgroundPlay: Boolean = true,
    val autoPip: Boolean = true,
    val autoplayNext: Boolean = true,
    val resumePlayback: Boolean = true,
    val skipSilence: Boolean = false,
    val audioOnlyDefault: Boolean = false,
    val sponsorBlock: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction"),
    // downloads
    val askDownloadQuality: Boolean = true,
    val downloadQuality: Int = 1080,
    val downloadAudioPref: AudioPreference = AudioPreference.M4A,
    val downloadWifiOnly: Boolean = false,
    // content / privacy
    val contentCountry: String = "",
    val saveHistory: Boolean = true,
    val saveSearchHistory: Boolean = true,
    val hideShorts: Boolean = false,
    val onboardingDone: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val accent = intPreferencesKey("accent")
        val name = stringPreferencesKey("display_name")
        val layout = stringPreferencesKey("layout")
        val corner = intPreferencesKey("corner")
        val avatars = booleanPreferencesKey("avatars")
        val icon = stringPreferencesKey("launcher_icon")
        val launcherName = stringPreferencesKey("launcher_name")
        val quality = intPreferencesKey("quality")
        val speed = floatPreferencesKey("speed")
        val bg = booleanPreferencesKey("bg_play")
        val pip = booleanPreferencesKey("auto_pip")
        val autoplay = booleanPreferencesKey("autoplay")
        val resume = booleanPreferencesKey("resume")
        val silence = booleanPreferencesKey("skip_silence")
        val audioOnly = booleanPreferencesKey("audio_only")
        val sb = booleanPreferencesKey("sponsorblock")
        val sbCats = stringSetPreferencesKey("sponsorblock_cats")
        val askDl = booleanPreferencesKey("ask_dl")
        val dlQuality = intPreferencesKey("dl_quality")
        val dlAudio = stringPreferencesKey("dl_audio")
        val dlWifi = booleanPreferencesKey("dl_wifi")
        val country = stringPreferencesKey("country")
        val history = booleanPreferencesKey("history")
        val searchHistory = booleanPreferencesKey("search_history")
        val hideShorts = booleanPreferencesKey("hide_shorts")
        val onboarding = booleanPreferencesKey("onboarding")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = this[K.dynamic] ?: d.dynamicColor,
            accentIndex = this[K.accent] ?: d.accentIndex,
            displayName = this[K.name] ?: d.displayName,
            feedLayout = this[K.layout]?.let { runCatching { FeedLayout.valueOf(it) }.getOrNull() } ?: d.feedLayout,
            cornerRadius = this[K.corner] ?: d.cornerRadius,
            showChannelAvatars = this[K.avatars] ?: d.showChannelAvatars,
            launcherIcon = this[K.icon] ?: d.launcherIcon,
            launcherName = this[K.launcherName] ?: d.launcherName,
            defaultQuality = this[K.quality] ?: d.defaultQuality,
            defaultSpeed = this[K.speed] ?: d.defaultSpeed,
            backgroundPlay = this[K.bg] ?: d.backgroundPlay,
            autoPip = this[K.pip] ?: d.autoPip,
            autoplayNext = this[K.autoplay] ?: d.autoplayNext,
            resumePlayback = this[K.resume] ?: d.resumePlayback,
            skipSilence = this[K.silence] ?: d.skipSilence,
            audioOnlyDefault = this[K.audioOnly] ?: d.audioOnlyDefault,
            sponsorBlock = this[K.sb] ?: d.sponsorBlock,
            sponsorCategories = this[K.sbCats] ?: d.sponsorCategories,
            askDownloadQuality = this[K.askDl] ?: d.askDownloadQuality,
            downloadQuality = this[K.dlQuality] ?: d.downloadQuality,
            downloadAudioPref = this[K.dlAudio]?.let { runCatching { AudioPreference.valueOf(it) }.getOrNull() } ?: d.downloadAudioPref,
            downloadWifiOnly = this[K.dlWifi] ?: d.downloadWifiOnly,
            contentCountry = this[K.country] ?: d.contentCountry,
            saveHistory = this[K.history] ?: d.saveHistory,
            saveSearchHistory = this[K.searchHistory] ?: d.saveSearchHistory,
            hideShorts = this[K.hideShorts] ?: d.hideShorts,
            onboardingDone = this[K.onboarding] ?: d.onboardingDone,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.theme] = s.themeMode.name
            p[K.dynamic] = s.dynamicColor
            p[K.accent] = s.accentIndex
            p[K.name] = s.displayName
            p[K.layout] = s.feedLayout.name
            p[K.corner] = s.cornerRadius
            p[K.avatars] = s.showChannelAvatars
            p[K.icon] = s.launcherIcon
            p[K.launcherName] = s.launcherName
            p[K.quality] = s.defaultQuality
            p[K.speed] = s.defaultSpeed
            p[K.bg] = s.backgroundPlay
            p[K.pip] = s.autoPip
            p[K.autoplay] = s.autoplayNext
            p[K.resume] = s.resumePlayback
            p[K.silence] = s.skipSilence
            p[K.audioOnly] = s.audioOnlyDefault
            p[K.sb] = s.sponsorBlock
            p[K.sbCats] = s.sponsorCategories
            p[K.askDl] = s.askDownloadQuality
            p[K.dlQuality] = s.downloadQuality
            p[K.dlAudio] = s.downloadAudioPref.name
            p[K.dlWifi] = s.downloadWifiOnly
            p[K.country] = s.contentCountry
            p[K.history] = s.saveHistory
            p[K.searchHistory] = s.saveSearchHistory
            p[K.hideShorts] = s.hideShorts
            p[K.onboarding] = s.onboardingDone
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
