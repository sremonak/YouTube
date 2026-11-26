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
    0xFFFF0033, // YouTube red
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
    val dynamicColor: Boolean = false,
    val accentIndex: Int = 0,
    val displayName: String = "Vidora",
    val feedLayout: FeedLayout = FeedLayout.GRID,
    val cornerRadius: Int = 12,
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
    val useAccountForRestricted: Boolean = true,
    val poToken: Boolean = true,
    val autoUpdateCheck: Boolean = true,
    val gestures: Boolean = true,
    val captionsEnabled: Boolean = false,
    val captionLang: String = "",
    /** SAF tree uri for downloads (any folder / SD card); empty = Movies/Vidora + Music/Vidora. */
    val downloadFolder: String = "",
    /** Incognito: nothing is added to watch or search history. */
    val incognito: Boolean = false,
    val captionTranslate: Boolean = false,
    val captionScale: Float = 1f,
    val preferredAudioLang: String = "",
    val eqEnabled: Boolean = false,
    val eqPreset: Int = -1,
    val eqBands: String = "",
    val bassBoost: Int = 0,
    val volumeBoost: Int = 0,
    val stableVolume: Boolean = true,
    val downloadCutSponsors: Boolean = false,
    val downloadEmbedMetadata: Boolean = true,
    val downloadSubtitles: Boolean = false,
    val altFrontends: Boolean = true,
    val glassUi: Boolean = true,
    val ambientMode: Boolean = true,
    /** Adaptive (DASH) quality: starts fast on slow networks and adapts like the official app. */
    val smartQuality: Boolean = true,
    /** Max quality on mobile data / metered networks (0 = same as Wi-Fi). */
    val mobileQuality: Int = 480,
    /** Fall back to audio-only when the network is too slow even for the lowest video quality. */
    val autoAudioOnSlow: Boolean = true,
    /** Resolve the next video in the background so it starts instantly. */
    val prefetchNext: Boolean = true,
    val downloadConnections: Int = 3,
    val returnDislike: Boolean = true,
    val deArrow: Boolean = false,
    val blockedChannels: Set<String> = emptySet(),
    val blockedKeywords: Set<String> = emptySet(),
    val hideWatched: Boolean = false,
    val shortsPlayer: Boolean = true,
    val notifyHours: Int = 0,
    val syncAccountActions: Boolean = true,
    /** Report what you watch to the YouTube account's watch history (like the official app). */
    val syncWatchHistory: Boolean = true,
    val persistQueue: Boolean = true,
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
        val useAccount = booleanPreferencesKey("use_account")
        val poToken = booleanPreferencesKey("po_token")
        val autoUpdateCheck = booleanPreferencesKey("auto_update")
        val gestures = booleanPreferencesKey("gestures")
        val captionsEnabled = booleanPreferencesKey("captions")
        val captionLang = stringPreferencesKey("caption_lang")
        val downloadFolder = stringPreferencesKey("download_folder")
        val incognito = booleanPreferencesKey("incognito")
        val captionTranslate = booleanPreferencesKey("caption_translate")
        val captionScale = floatPreferencesKey("caption_scale")
        val preferredAudioLang = stringPreferencesKey("audio_lang")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val bassBoost = intPreferencesKey("bass_boost")
        val volumeBoost = intPreferencesKey("volume_boost")
        val stableVolume = booleanPreferencesKey("stable_volume")
        val downloadCutSponsors = booleanPreferencesKey("dl_cut_sponsors")
        val downloadEmbedMetadata = booleanPreferencesKey("dl_metadata")
        val downloadSubtitles = booleanPreferencesKey("dl_subs")
        val altFrontends = booleanPreferencesKey("alt_frontends")
        val glassUi = booleanPreferencesKey("glass_ui")
        val ambientMode = booleanPreferencesKey("ambient_mode")
        val smartQuality = booleanPreferencesKey("smart_quality")
        val mobileQuality = intPreferencesKey("mobile_quality")
        val autoAudioOnSlow = booleanPreferencesKey("auto_audio_slow")
        val prefetchNext = booleanPreferencesKey("prefetch_next")
        val downloadConnections = intPreferencesKey("dl_connections")
        val returnDislike = booleanPreferencesKey("ryd")
        val deArrow = booleanPreferencesKey("dearrow")
        val blockedChannels = stringSetPreferencesKey("blocked_channels")
        val blockedKeywords = stringSetPreferencesKey("blocked_keywords")
        val hideWatched = booleanPreferencesKey("hide_watched")
        val shortsPlayer = booleanPreferencesKey("shorts_player")
        val notifyHours = intPreferencesKey("notify_hours")
        val syncAccountActions = booleanPreferencesKey("sync_account")
        val syncWatchHistory = booleanPreferencesKey("sync_watch_history")
        val persistQueue = booleanPreferencesKey("persist_queue")
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
            useAccountForRestricted = this[K.useAccount] ?: d.useAccountForRestricted,
            poToken = this[K.poToken] ?: d.poToken,
            autoUpdateCheck = this[K.autoUpdateCheck] ?: d.autoUpdateCheck,
            gestures = this[K.gestures] ?: d.gestures,
            captionsEnabled = this[K.captionsEnabled] ?: d.captionsEnabled,
            captionLang = this[K.captionLang] ?: d.captionLang,
            downloadFolder = this[K.downloadFolder] ?: d.downloadFolder,
            incognito = this[K.incognito] ?: d.incognito,
            captionTranslate = this[K.captionTranslate] ?: d.captionTranslate,
            captionScale = this[K.captionScale] ?: d.captionScale,
            preferredAudioLang = this[K.preferredAudioLang] ?: d.preferredAudioLang,
            eqEnabled = this[K.eqEnabled] ?: d.eqEnabled,
            eqPreset = this[K.eqPreset] ?: d.eqPreset,
            eqBands = this[K.eqBands] ?: d.eqBands,
            bassBoost = this[K.bassBoost] ?: d.bassBoost,
            volumeBoost = this[K.volumeBoost] ?: d.volumeBoost,
            stableVolume = this[K.stableVolume] ?: d.stableVolume,
            downloadCutSponsors = this[K.downloadCutSponsors] ?: d.downloadCutSponsors,
            downloadEmbedMetadata = this[K.downloadEmbedMetadata] ?: d.downloadEmbedMetadata,
            downloadSubtitles = this[K.downloadSubtitles] ?: d.downloadSubtitles,
            altFrontends = this[K.altFrontends] ?: d.altFrontends,
            glassUi = this[K.glassUi] ?: d.glassUi,
            ambientMode = this[K.ambientMode] ?: d.ambientMode,
            smartQuality = this[K.smartQuality] ?: d.smartQuality,
            mobileQuality = this[K.mobileQuality] ?: d.mobileQuality,
            autoAudioOnSlow = this[K.autoAudioOnSlow] ?: d.autoAudioOnSlow,
            prefetchNext = this[K.prefetchNext] ?: d.prefetchNext,
            downloadConnections = this[K.downloadConnections] ?: d.downloadConnections,
            returnDislike = this[K.returnDislike] ?: d.returnDislike,
            deArrow = this[K.deArrow] ?: d.deArrow,
            blockedChannels = this[K.blockedChannels] ?: d.blockedChannels,
            blockedKeywords = this[K.blockedKeywords] ?: d.blockedKeywords,
            hideWatched = this[K.hideWatched] ?: d.hideWatched,
            shortsPlayer = this[K.shortsPlayer] ?: d.shortsPlayer,
            notifyHours = this[K.notifyHours] ?: d.notifyHours,
            syncAccountActions = this[K.syncAccountActions] ?: d.syncAccountActions,
            syncWatchHistory = this[K.syncWatchHistory] ?: d.syncWatchHistory,
            persistQueue = this[K.persistQueue] ?: d.persistQueue,
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
            p[K.useAccount] = s.useAccountForRestricted
            p[K.poToken] = s.poToken
            p[K.autoUpdateCheck] = s.autoUpdateCheck
            p[K.gestures] = s.gestures
            p[K.captionsEnabled] = s.captionsEnabled
            p[K.captionLang] = s.captionLang
            p[K.downloadFolder] = s.downloadFolder
            p[K.incognito] = s.incognito
            p[K.captionTranslate] = s.captionTranslate
            p[K.captionScale] = s.captionScale
            p[K.preferredAudioLang] = s.preferredAudioLang
            p[K.eqEnabled] = s.eqEnabled
            p[K.eqPreset] = s.eqPreset
            p[K.eqBands] = s.eqBands
            p[K.bassBoost] = s.bassBoost
            p[K.volumeBoost] = s.volumeBoost
            p[K.stableVolume] = s.stableVolume
            p[K.downloadCutSponsors] = s.downloadCutSponsors
            p[K.downloadEmbedMetadata] = s.downloadEmbedMetadata
            p[K.downloadSubtitles] = s.downloadSubtitles
            p[K.altFrontends] = s.altFrontends
            p[K.glassUi] = s.glassUi
            p[K.ambientMode] = s.ambientMode
            p[K.smartQuality] = s.smartQuality
            p[K.mobileQuality] = s.mobileQuality
            p[K.autoAudioOnSlow] = s.autoAudioOnSlow
            p[K.prefetchNext] = s.prefetchNext
            p[K.downloadConnections] = s.downloadConnections
            p[K.returnDislike] = s.returnDislike
            p[K.deArrow] = s.deArrow
            p[K.blockedChannels] = s.blockedChannels
            p[K.blockedKeywords] = s.blockedKeywords
            p[K.hideWatched] = s.hideWatched
            p[K.shortsPlayer] = s.shortsPlayer
            p[K.notifyHours] = s.notifyHours
            p[K.syncAccountActions] = s.syncAccountActions
            p[K.syncWatchHistory] = s.syncWatchHistory
            p[K.persistQueue] = s.persistQueue
        }
    }

    /**
     * One-time switch to the YouTube look (1.0.39): YouTube red accent, YouTube neutrals instead of
     * Material You wallpaper colours, 12 dp corners. Users can change all of it back in Settings.
     */
    suspend fun migrateDesign() {
        val key = intPreferencesKey("design_version")
        context.dataStore.edit { p ->
            if ((p[key] ?: 0) >= 2) return@edit
            p[key] = 2
            p[K.dynamic] = false
            p[K.accent] = 0
            p[K.corner] = 12
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
