package com.vidora.app.data

import kotlinx.serialization.Serializable

@Serializable
data class VideoItem(
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val uploader: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val duration: Long = 0,
    val views: Long = -1,
    val uploadedText: String? = null,
    val uploadedEpoch: Long = 0,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val verified: Boolean = false,
)

@Serializable
data class ChannelItem(
    val url: String,
    val name: String,
    val avatar: String? = null,
    val subscribers: Long = -1,
    val description: String? = null,
    val verified: Boolean = false,
)

@Serializable
data class PlaylistItem(
    val url: String,
    val name: String,
    val thumbnail: String? = null,
    val uploader: String? = null,
    val count: Long = -1,
)

/** A single row of any list returned by the content repository. */
sealed interface Entry {
    val key: String

    data class Video(val video: VideoItem) : Entry {
        override val key get() = "v:" + video.url
    }

    data class Channel(val channel: ChannelItem) : Entry {
        override val key get() = "c:" + channel.url
    }

    data class Playlist(val playlist: PlaylistItem) : Entry {
        override val key get() = "p:" + playlist.url
    }
}

/** Simple continuation-style pagination. */
class PageResult(
    val items: List<Entry>,
    val next: (suspend () -> PageResult)? = null,
)

data class VideoOption(
    val url: String,
    val itag: Int,
    val height: Int,
    val fps: Int,
    val resolution: String,
    val mime: String,
    val suffix: String,
    val codec: String?,
    val videoOnly: Boolean,
    val bitrate: Int,
    val contentLength: Long,
) {
    val label: String
        get() = buildString {
            append(if (height > 0) "${height}p" else resolution)
            if (fps > 30) append(fps)
        }
}

data class AudioOption(
    val url: String,
    val itag: Int,
    val bitrateKbps: Int,
    val mime: String,
    val suffix: String,
    val codec: String?,
    val trackName: String?,
    val contentLength: Long,
) {
    val label: String get() = "${bitrateKbps} kbps · ${suffix.uppercase()}"
}

data class Chapter(val title: String, val startSeconds: Int, val thumbnail: String?)

data class StreamDetails(
    val item: VideoItem,
    val description: String,
    val likes: Long,
    val subscriberCount: Long,
    val videoStreams: List<VideoOption>,
    val audioStreams: List<AudioOption>,
    val hlsUrl: String?,
    val dashUrl: String?,
    val related: List<Entry>,
    val chapters: List<Chapter>,
    val tags: List<String>,
    val category: String?,
) {
    /** Muxed (video + audio) progressive streams. */
    val muxed get() = videoStreams.filter { !it.videoOnly }
    val videoOnly get() = videoStreams.filter { it.videoOnly }
    val relatedVideos get() = related.filterIsInstance<Entry.Video>().map { it.video }
}

data class ChannelDetails(
    val channel: ChannelItem,
    val banner: String?,
    val tabs: List<ChannelTab>,
)

data class ChannelTab(val name: String, val load: suspend () -> PageResult)

data class PlaylistDetails(
    val playlist: PlaylistItem,
    val description: String,
    val first: PageResult,
)
