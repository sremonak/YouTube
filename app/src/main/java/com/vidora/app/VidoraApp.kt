package com.vidora.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import com.vidora.app.account.AccountApi
import com.vidora.app.account.AccountManager
import com.vidora.app.data.ContentRepository
import com.vidora.app.data.LibraryStore
import com.vidora.app.data.NetworkDownloader
import com.vidora.app.data.SettingsRepository
import com.vidora.app.data.SponsorBlock
import com.vidora.app.download.DownloadsStore
import com.vidora.app.player.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.vidora.app.data.AppSettings
import com.vidora.app.potoken.PoTokenProviderImpl
import com.vidora.app.util.CrashReporter
import com.vidora.app.notify.NewVideosWorker
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Tiny manual dependency container — keeps the app free of DI frameworks
 * while still giving every screen access to the same singletons.
 */
object Graph {
    lateinit var app: Application
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val settings by lazy { SettingsRepository(app) }
    val library by lazy { LibraryStore(app, appScope) }
    val downloads by lazy { DownloadsStore(app, appScope) }
    val repository by lazy { ContentRepository() }
    val sponsorBlock by lazy { SponsorBlock(http) }
    val player by lazy { PlayerManager(app, appScope) }
    val account by lazy { AccountManager(app, appScope) }
    val accountApi by lazy { AccountApi() }
    val about by lazy { com.vidora.app.data.AboutRepository(app) }

    /** Always-current settings snapshot for non-UI code (filters, player, workers). */
    val settingsState: StateFlow<AppSettings> by lazy {
        settings.flow.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }

    fun init(application: Application) {
        app = application
    }
}

class VidoraApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        CrashReporter.install(this)

        val country = runCatching {
            runBlocking { Graph.settings.flow.first().contentCountry }
        }.getOrDefault("")
        val locale = Locale.getDefault()
        NewPipe.init(
            NetworkDownloader.create(Graph.http),
            Localization.fromLocale(locale),
            ContentCountry(country.ifBlank { locale.country.ifBlank { "US" } })
        )
        // Browser-grade poTokens (BotGuard) → fewer "not a bot" checks and 403s
        // No-op in NPE 0.27.x (visionOS client needs no token); our own InnerTube fallbacks (TvPlayer) need no token.
        YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl)
        Graph.appScope.launch {
            Graph.settingsState.collect { PoTokenProviderImpl.enabled = it.poToken }
        }
        Graph.appScope.launch { runCatching { Graph.settings.migrateDesign() } }
        NewVideosWorker.schedule(this)
        com.vidora.app.util.UpdateWorker.schedule(this)
        if (com.vidora.app.remote.NowPlayingWidget.hasWidgets(this)) com.vidora.app.remote.NowPlayingWidget.observe(this)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                getString(R.string.channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    // Coil 3: OkHttp fetcher shares the app's connection pool; the default cache strategy ignores
    // Cache-Control headers (= Coil 2's respectCacheHeaders(false)), so thumbnails stay cached offline.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Graph.http })) }
        .crossfade(true)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache_v3").toOkioPath())
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
