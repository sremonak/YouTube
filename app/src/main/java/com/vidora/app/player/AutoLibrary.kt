package com.vidora.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.vidora.app.Graph
import com.vidora.app.data.Entry
import com.vidora.app.data.VideoItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Browse tree for Android Auto / Wear / other MediaBrowser clients:
 *   root → Continue · Liked · Watch later · Playlists → (playlist) · Trending music
 * Leaf media ids are "v:<url>"; the player routes them through PlayerManager.play().
 */
object AutoLibrary {
    const val ROOT = "root"
    private const val CONTINUE = "continue"
    private const val LIKED = "liked"
    private const val LATER = "later"
    private const val PLAYLISTS = "playlists"
    private const val PLAYLIST = "pl:"
    private const val TRENDING = "trending"
    const val VIDEO = "v:"

    private val known = ConcurrentHashMap<String, VideoItem>()

    fun rootItem(): MediaItem = folder(ROOT, "Vidora")

    fun isVidoraId(id: String?) = id?.startsWith(VIDEO) == true

    suspend fun children(parentId: String): List<MediaItem> {
        val lib = Graph.library.data.value
        return when {
            parentId == ROOT -> listOf(
                folder(CONTINUE, "Continue watching"),
                folder(LIKED, "Liked"),
                folder(LATER, "Watch later"),
                folder(PLAYLISTS, "Playlists"),
                folder(TRENDING, "Trending music"),
            )
            parentId == CONTINUE -> lib.history.take(50).map { it.video }.map(::playable)
            parentId == LIKED -> lib.favorites.take(200).map(::playable)
            parentId == LATER -> lib.watchLater.take(200).map(::playable)
            parentId == PLAYLISTS -> lib.playlists.map { folder(PLAYLIST + it.id, it.name, "${it.videos.size} videos") }
            parentId.startsWith(PLAYLIST) -> Graph.library.playlist(parentId.removePrefix(PLAYLIST))?.videos.orEmpty().map(::playable)
            parentId == TRENDING -> runCatching { Graph.repository.kiosk("trending_music").items }
                .getOrDefault(emptyList()).filterIsInstance<Entry.Video>().map { playable(it.video) }
            else -> emptyList()
        }
    }

    /** Siblings of a leaf, so picking one item in a list queues the rest. */
    fun queueFor(item: VideoItem): List<VideoItem> {
        val lib = Graph.library.data.value
        return listOf(lib.favorites, lib.watchLater, lib.history.map { it.video })
            .plus(lib.playlists.map { it.videos })
            .firstOrNull { list -> list.any { it.url == item.url } } ?: listOf(item)
    }

    fun item(mediaId: String): MediaItem? = videoFor(mediaId)?.let(::playable)

    fun videoFor(mediaItem: MediaItem): VideoItem? = videoFor(mediaItem.mediaId) ?: run {
        val url = mediaItem.mediaId.removePrefix(VIDEO).takeIf { mediaItem.mediaId.startsWith(VIDEO) } ?: return null
        val md = mediaItem.mediaMetadata
        VideoItem(url = url, title = md.title?.toString() ?: url, thumbnail = md.artworkUri?.toString(), uploader = md.artist?.toString())
    }

    private fun videoFor(mediaId: String): VideoItem? = if (!mediaId.startsWith(VIDEO)) null else known[mediaId.removePrefix(VIDEO)]

    /** Placeholder media item: resolved to real streams by PlayerManager when set on the player. */
    fun resolved(item: VideoItem): MediaItem = playable(item).buildUpon().setUri(Uri.parse(item.url)).build()

    private fun playable(v: VideoItem): MediaItem {
        known[v.url] = v
        return MediaItem.Builder()
            .setMediaId(VIDEO + v.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(v.title)
                    .setArtist(v.uploader)
                    .setArtworkUri(v.thumbnail?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                    .build()
            ).build()
    }

    private fun folder(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            ).build()
}
