package com.vidora.app.data

import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** Search filters exposed in the UI (map onto the YouTube content filters). */
enum class SearchFilter(val label: String, val id: String?) {
    ALL("All", null),
    VIDEOS("Videos", "videos"),
    CHANNELS("Channels", "channels"),
    PLAYLISTS("Playlists", "playlists"),
    MUSIC("Music", "music_songs"),
}

/** Home feed sections. kioskId == null means "personalised mix". */
enum class HomeSection(val label: String, val kioskId: String?) {
    FOR_YOU("For you", null),
    LIVE("Live", "live"),
    MUSIC("Music", "trending_music"),
    GAMING("Gaming", "trending_gaming"),
    PODCASTS("Podcasts", "trending_podcasts_episodes"),
    MOVIES("Movies & trailers", "trending_movies_and_shows"),
}

class ContentRepository {

    private val service = ServiceList.YouTube
    private val streamCache = LruCache<String, Pair<Long, StreamDetails>>(24)

    // ---------------------------------------------------------------- search

    suspend fun suggestions(query: String): List<String> = io {
        if (query.isBlank()) emptyList()
        else runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): PageResult = io {
        val handler = service.searchQHFactory.fromQuery(
            query,
            filter.id?.let { listOf(it) } ?: emptyList(),
            ""
        )
        val info = SearchInfo.getInfo(service, handler)
        PageResult(info.relatedItems.mapNotNull(::toEntry), nextSearch(handler, info.nextPage))
    }

    private fun nextSearch(handler: SearchQueryHandler, page: Page?): (suspend () -> PageResult)? {
        if (!Page.isValid(page)) return null
        return {
            io {
                val more = SearchInfo.getMoreItems(service, handler, page)
                PageResult(more.items.mapNotNull(::toEntry), nextSearch(handler, more.nextPage))
            }
        }
    }

    // ---------------------------------------------------------------- kiosks

    suspend fun kiosk(kioskId: String): PageResult = io {
        val extractor = service.kioskList.getExtractorById(kioskId, null)
        extractor.fetchPage()
        val info = KioskInfo.getInfo(extractor)
        PageResult(info.relatedItems.mapNotNull(::toEntry), nextKiosk(info.url, info.nextPage))
    }

    private fun nextKiosk(url: String, page: Page?): (suspend () -> PageResult)? {
        if (!Page.isValid(page)) return null
        return {
            io {
                val more = KioskInfo.getMoreItems(service, url, page)
                PageResult(more.items.mapNotNull(::toEntry), nextKiosk(url, more.nextPage))
            }
        }
    }

    // ---------------------------------------------------------------- stream

    suspend fun stream(url: String, forceRefresh: Boolean = false): StreamDetails = io {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            streamCache.get(url)?.let { (time, details) ->
                // Stream URLs expire after a few hours; keep the cache short.
                if (now - time < 30 * 60_000) return@io details
            }
        }
        val info = StreamInfo.getInfo(service, url)
        val details = info.toDetails()
        streamCache.put(url, now to details)
        details
    }

    private fun StreamInfo.toDetails(): StreamDetails {
        val live = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM
        val item = VideoItem(
            url = url,
            title = name,
            thumbnail = thumbnails.best(),
            uploader = uploaderName,
            uploaderUrl = uploaderUrl,
            uploaderAvatar = uploaderAvatars.best(),
            duration = duration,
            views = viewCount,
            uploadedText = textualUploadDate,
            uploadedEpoch = runCatching { uploadDate?.instant?.toEpochMilli() ?: 0L }.getOrDefault(0L),
            isLive = live,
        )
        val videos = (videoStreams + videoOnlyStreams)
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .map { it.toOption() }
            .distinctBy { Triple(it.itag, it.videoOnly, it.url) }
            .sortedWith(compareByDescending<VideoOption> { it.height }.thenByDescending { it.fps })
        val audios = audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .filter { s ->
                val type = s.audioTrackType?.name
                type == null || type == "ORIGINAL" || audioStreams.none { o -> o.audioTrackType?.name == "ORIGINAL" }
            }
            .map { it.toOption() }
            .distinctBy { it.itag }
            .sortedByDescending { it.bitrateKbps }

        return StreamDetails(
            item = item,
            description = description?.content().orEmpty(),
            likes = likeCount,
            subscriberCount = uploaderSubscriberCount,
            videoStreams = videos,
            audioStreams = audios,
            hlsUrl = hlsUrl?.takeIf { it.isNotBlank() },
            dashUrl = dashMpdUrl?.takeIf { it.isNotBlank() },
            related = relatedItems.mapNotNull(::toEntry),
            chapters = streamSegments.map { Chapter(it.title, it.startTimeSeconds, it.previewUrl) },
            tags = tags ?: emptyList(),
            category = category,
        )
    }

    private fun VideoStream.toOption(): VideoOption {
        val fmt: MediaFormat? = format
        return VideoOption(
            url = content,
            itag = itag,
            height = height.takeIf { it > 0 } ?: resolution.filter(Char::isDigit).take(4).toIntOrNull() ?: 0,
            fps = fps,
            resolution = resolution,
            mime = fmt?.mimeType ?: "video/mp4",
            suffix = fmt?.suffix ?: "mp4",
            codec = codec,
            videoOnly = isVideoOnly,
            bitrate = bitrate,
            contentLength = itagItem?.contentLength ?: -1L,
        )
    }

    private fun AudioStream.toOption(): AudioOption {
        val fmt: MediaFormat? = format
        val kbps = (averageBitrate.takeIf { it > 0 } ?: (bitrate / 1000)).coerceAtLeast(0)
        return AudioOption(
            url = content,
            itag = itag,
            bitrateKbps = kbps,
            mime = fmt?.mimeType ?: "audio/mp4",
            suffix = fmt?.suffix ?: "m4a",
            codec = codec,
            trackName = audioTrackName,
            contentLength = itagItem?.contentLength ?: -1L,
        )
    }

    // ---------------------------------------------------------------- channel

    suspend fun channel(url: String): ChannelDetails = io {
        val info = ChannelInfo.getInfo(service, url)
        val channel = ChannelItem(
            url = info.url,
            name = info.name,
            avatar = info.avatars.best(),
            subscribers = info.subscriberCount,
            description = info.description,
            verified = runCatching { info.isVerified }.getOrDefault(false),
        )
        val wanted = listOf(
            ChannelTabs.VIDEOS to "Videos",
            ChannelTabs.SHORTS to "Shorts",
            ChannelTabs.LIVESTREAMS to "Live",
            ChannelTabs.PLAYLISTS to "Playlists",
        )
        val tabs = wanted.mapNotNull { (id, label) ->
            val handler = info.tabs.firstOrNull { it.contentFilters.contains(id) } ?: return@mapNotNull null
            ChannelTab(label) { channelTab(handler) }
        }
        ChannelDetails(channel, info.banners.best(), tabs)
    }

    private suspend fun channelTab(handler: ListLinkHandler): PageResult = io {
        val info = ChannelTabInfo.getInfo(service, handler)
        PageResult(info.relatedItems.mapNotNull(::toEntry), nextChannelTab(handler, info.nextPage))
    }

    private fun nextChannelTab(handler: ListLinkHandler, page: Page?): (suspend () -> PageResult)? {
        if (!Page.isValid(page)) return null
        return {
            io {
                val more = ChannelTabInfo.getMoreItems(service, handler, page)
                PageResult(more.items.mapNotNull(::toEntry), nextChannelTab(handler, more.nextPage))
            }
        }
    }

    /** Latest uploads of a channel — used by the subscriptions feed. */
    suspend fun latestUploads(channelUrl: String): List<VideoItem> = io {
        val info = ChannelInfo.getInfo(service, channelUrl)
        val handler = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: return@io emptyList()
        ChannelTabInfo.getInfo(service, handler).relatedItems
            .mapNotNull { (toEntry(it) as? Entry.Video)?.video }
            .map { v -> if (v.uploaderAvatar == null) v.copy(uploaderAvatar = info.avatars.best()) else v }
    }

    // ---------------------------------------------------------------- playlist

    suspend fun playlist(url: String): PlaylistDetails = io {
        val info = PlaylistInfo.getInfo(service, url)
        PlaylistDetails(
            playlist = PlaylistItem(info.url, info.name, info.thumbnails.best(), info.uploaderName, info.streamCount),
            description = runCatching { info.description?.content().orEmpty() }.getOrDefault(""),
            first = PageResult(info.relatedItems.mapNotNull(::toEntry), nextPlaylist(info.url, info.nextPage)),
        )
    }

    private fun nextPlaylist(url: String, page: Page?): (suspend () -> PageResult)? {
        if (!Page.isValid(page)) return null
        return {
            io {
                val more: ListExtractor.InfoItemsPage<StreamInfoItem> = PlaylistInfo.getMoreItems(service, url, page)
                PageResult(more.items.mapNotNull(::toEntry), nextPlaylist(url, more.nextPage))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    fun isStreamUrl(url: String): Boolean =
        runCatching { service.streamLHFactory.acceptUrl(url) }.getOrDefault(false)

    fun isChannelUrl(url: String): Boolean =
        runCatching { service.channelLHFactory.acceptUrl(url) }.getOrDefault(false)

    fun isPlaylistUrl(url: String): Boolean =
        runCatching { service.playlistLHFactory.acceptUrl(url) }.getOrDefault(false)

    fun videoId(url: String): String? = runCatching { service.streamLHFactory.getId(url) }.getOrNull()

    private fun toEntry(item: InfoItem): Entry? = when (item) {
        is StreamInfoItem -> Entry.Video(
            VideoItem(
                url = item.url,
                title = item.name,
                thumbnail = item.thumbnails.best(),
                uploader = item.uploaderName,
                uploaderUrl = item.uploaderUrl,
                uploaderAvatar = item.uploaderAvatars.best(),
                duration = item.duration,
                views = item.viewCount,
                uploadedText = item.textualUploadDate,
                uploadedEpoch = runCatching { item.uploadDate?.instant?.toEpochMilli() ?: 0L }.getOrDefault(0L),
                isLive = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM,
                isShort = item.isShortFormContent,
                verified = item.isUploaderVerified,
            )
        )
        is ChannelInfoItem -> Entry.Channel(
            ChannelItem(
                url = item.url,
                name = item.name,
                avatar = item.thumbnails.best(),
                subscribers = item.subscriberCount,
                description = item.description,
                verified = item.isVerified,
            )
        )
        is PlaylistInfoItem -> Entry.Playlist(
            PlaylistItem(item.url, item.name, item.thumbnails.best(), item.uploaderName, item.streamCount)
        )
        else -> null
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** True when the device can mux WebM (VP9 + Opus) downloads. */
        val canMuxWebm: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}

/** Picks the highest resolution image URL of a list. */
fun List<Image>?.best(): String? {
    if (this.isNullOrEmpty()) return null
    return maxByOrNull { if (it.height > 0) it.height else 0 }?.url ?: first().url
}
