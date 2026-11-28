package com.vidora.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache of watched video/audio segments (LRU, 512 MB). Replays, seeking back and re-opening
 * a video recently watched are served from disk instead of the network.
 *
 * YouTube stream URLs carry expiring signatures, so the cache key is built from the stable parts
 * only: stream id + itag + length (+ audio-track tag), which survive a URL refresh.
 */
@OptIn(UnstableApi::class)
object MediaCache {
    private const val MAX_BYTES = 512L * 1024 * 1024
    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context),
        ).also { instance = it }
    }

    fun usedBytes(): Long = runCatching { instance?.cacheSpace ?: 0L }.getOrDefault(0L)

    fun clear(context: Context) {
        val c = get(context)
        runCatching { c.keys.toList().forEach { c.removeResource(it) } }
    }

    val keyFactory = CacheKeyFactory { spec -> key(spec.uri) ?: spec.key ?: spec.uri.toString() }

    /** Stable cache key for googlevideo.com stream URLs, null for anything else. */
    fun key(uri: Uri): String? {
        if (uri.host?.endsWith("googlevideo.com") != true) return null
        val id = uri.getQueryParameter("id") ?: return null
        val itag = uri.getQueryParameter("itag") ?: return null
        val clen = uri.getQueryParameter("clen").orEmpty()
        val xtags = uri.getQueryParameter("xtags").orEmpty()
        return "yt:$id:$itag:$clen:$xtags"
    }

    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(keyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
