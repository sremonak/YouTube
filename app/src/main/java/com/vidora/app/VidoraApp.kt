package com.vidora.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
import kotlinx.coroutines.flow.first
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

    fun init(application: Application) {
        app = application
    }
}

class VidoraApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)

        val country = runCatching {
            runBlocking { Graph.settings.flow.first().contentCountry }
        }.getOrDefault("")
        val locale = Locale.getDefault()
        NewPipe.init(
            NetworkDownloader.create(Graph.http),
            Localization.fromLocale(locale),
            ContentCountry(country.ifBlank { locale.country.ifBlank { "US" } })
        )
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

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Graph.http)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .build()

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
