package com.vidora.app.data

import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import android.os.Build
import android.util.LruCache
import com.vidora.app.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
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
    ACCOUNT("Recommended", null),
    FOR_YOU("Explore", null),
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
        val info = authFallback { SearchInfo.getInfo(service, handler) }
        PageResult(info.relatedItems.mapNotNull(::toEntry), nextSearch(handler, info.nextPage))
    }

    private fun nextSearch(handler: SearchQueryHandler, page: Page?): (suspend () -> PageResult)? {
        if (page == null || !Page.isValid(page)) return null
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
        if (page == null || !Page.isValid(page)) return null
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
                if (now - time < 90 * 60_000) return@io details
            }
        }
        // A forced refresh must not reuse an older prefetch's signed URL.
        if (forceRefresh) return@io loadStream(url).also { streamCache.put(url, System.currentTimeMillis() to it) }
        // Share one extraction between a touch-prefetch / next-video prefetch and the real tap.
        val job = synchronized(inFlight) {
            inFlight[url] ?: Graph.appScope.async(Dispatchers.IO) {
                try {
                    loadStream(url).also { streamCache.put(url, System.currentTimeMillis() to it) }
                } finally {
                    synchronized(inFlight) { inFlight.remove(url) }
                }
            }.also { inFlight[url] = it }
        }
        job.await()
    }

    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<StreamDetails>>()

    /** True when [url]'s streams are already resolved (used by the start-time metric). */
    fun isStreamCached(url: String): Boolean =
        streamCache.get(url)?.let { System.currentTimeMillis() - it.first < 90 * 60_000 } == true

    /** Until when anonymous extraction is known to be bot-blocked on this network. */
    @Volatile private var anonBlockedUntil = 0L

    private val _loadStatus = MutableStateFlow<String?>(null)
    /** Human-readable progress while a video is being prepared ("Starting in 4 s…"), null when idle. */
    val loadStatus: StateFlow<String?> = _loadStatus.asStateFlow()

    /**
     * 1. NewPipe Extractor (anonymous visionOS client) — the normal path.
     * 2. If YouTube refuses it: our TV client **with the signed-in account** (cookies), which is what
     *    actually gets past "confirm you're not a bot" on VPN / shared IPs (same as yt-dlp).
     * 3. The TV client anonymously, then the Safari web client (HLS) and mobile web (360p).
     * While the network is known to be blocked, signed-in users go straight to step 2.
     */
    private suspend fun loadStream(url: String): StreamDetails {
        if (com.vidora.app.BuildConfig.DEBUG) Graph.streamOverride?.let { return it(url) }
        if (GuestSources.supported(url)) return Graph.openVideos.stream(url)
        val signed = Graph.account.isSignedIn && useAccountForRestricted
        try {
            if (signed && System.currentTimeMillis() < anonBlockedUntil) {
                runCatching { return ytDetails(url, withAccount = true, YtClient.TV) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
            _loadStatus.value = "Loading video…"
            val anonError = try {
                return StreamInfo.getInfo(service, url).toDetails()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                t
            }
            if (isBotBlock(anonError)) anonBlockedUntil = System.currentTimeMillis() + 15 * 60_000
            var last: Throwable = anonError
            val attempts = buildList {
                if (signed) add(true to YtClient.TV)
                add(false to YtClient.TV)
                add(false to YtClient.WEB_SAFARI)
                add(false to YtClient.MWEB_POT)
                add(false to YtClient.MWEB)
            }
            for ((withAccount, client) in attempts) {
                try {
                    _loadStatus.value = "Trying ${client.label}${if (withAccount) " with your account" else ""}…"
                    return ytDetails(url, withAccount, client)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    android.util.Log.w("ContentRepository", "${client.name} (account=$withAccount) failed", t)
                    if (withAccount || last === anonError) last = t
                }
            }
            if (Graph.settingsState.value.altFrontends) {
                videoId(url)?.let { id ->
                    try {
                        _loadStatus.value = "Trying Piped / Invidious…"
                        return AltFrontends.load(id, url).details
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        android.util.Log.w("ContentRepository", "Piped/Invidious failed", t)
                    }
                }
            }
            throw StreamBlockedException(explain(anonError, last, signed), anonError)
        } finally {
            _loadStatus.value = null
        }
    }

    private fun isBotBlock(t: Throwable) = t is SignInConfirmNotBotException ||
        t is org.schabi.newpipe.extractor.exceptions.ReCaptchaException ||
        t.message?.contains("not a bot", true) == true

    private fun explain(anon: Throwable, last: Throwable, signed: Boolean): String {
        val bot = isBotBlock(anon) || (last as? PlayabilityException)?.isBotCheck == true
        val lastReason = (last as? PlayabilityException)?.reason ?: last.message
        return when {
            bot && signed ->
                "YouTube is blocking this network (very common with VPNs and public Wi-Fi). Vidora retried with your " +
                    "account, but YouTube answered: \"${lastReason ?: "blocked"}\". Switch VPN server or turn the VPN off, then tap Retry."
            bot ->
                "YouTube refused anonymous playback on this network. Public access is not guaranteed on every IP. " +
                    "Try another public video or use Find public alternatives; login is optional."
            anon is org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException ||
                (last as? PlayabilityException)?.reason?.contains("country", true) == true ->
                "This video isn't available in the country your connection comes from (your VPN location). " +
                    "Switch the VPN to another country or turn it off, then tap Retry."
            anon is AgeRestrictedContentException && !signed -> "This video is age-restricted. Sign in from Settings → Account to watch it."
            anon is AgeRestrictedContentException -> "This video is age-restricted and YouTube refused your account too: ${lastReason ?: ""}"
            else -> anon.message ?: lastReason ?: "Couldn't load this video"
        }
    }

    private suspend fun ytDetails(url: String, withAccount: Boolean, client: YtClient): StreamDetails = kotlinx.coroutines.coroutineScope {
        val handler = service.streamLHFactory.fromUrl(url)
        val id = handler.id
        // Watch page (title, channel, description, related) loads in parallel with the player response.
        val pageJob = async { runCatching { withTimeout(12_000) { Graph.accountApi.watchPage(id) } }.getOrNull() }
        val r = try { runInterruptible { TvPlayer.fetch(id, withAccount, client) } } catch (e: Throwable) { pageJob.cancel(); throw e }
        val page = pageJob.await()
        val related = page?.related.orEmpty()
        val meta = page?.meta
        // TV stream URLs answer 403 until YouTube's announced pre-roll time has passed (yt-dlp waits too).
        while (true) {
            val left = r.availableAt - System.currentTimeMillis()
            if (left <= 0) break
            _loadStatus.value = "Starting in ${(left + 999) / 1000} s…"
            delay(left.coerceAtMost(1000))
        }
        // TV player responses often come without title / channel / description → fill them from the watch page.
        val description = r.description.ifBlank { meta?.description.orEmpty() }
        val item = VideoItem(
            url = handler.url,
            title = r.title.ifBlank { meta?.title.orEmpty() },
            thumbnail = r.thumbnail?.takeIf { it.isNotBlank() } ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            uploader = r.author?.takeIf { it.isNotBlank() } ?: meta?.channel,
            uploaderUrl = r.channelId?.let { "https://www.youtube.com/channel/$it" } ?: meta?.channelUrl,
            uploaderAvatar = meta?.channelAvatar,
            duration = r.lengthSeconds,
            views = if (r.views > 0) r.views else meta?.views ?: -1,
            uploadedText = meta?.dateText,
            isLive = r.isLive,
        )
        StreamDetails(
            item = item,
            description = description,
            likes = meta?.likes ?: -1,
            subscriberCount = meta?.subscribers ?: -1,
            videoStreams = r.videos,
            audioStreams = mainAudios(r.audios),
            hlsUrl = r.hlsUrl,
            dashUrl = r.dashUrl,
            related = related.map { Entry.Video(it) },
            chapters = Chapters.fromDescription(description),
            tags = emptyList(),
            category = null,
            allAudioStreams = r.audios,
            subtitles = r.subtitles,
            source = r.client.label + if (r.signedIn) " · your account" else " · anonymous",
            preferHls = r.client == YtClient.WEB_SAFARI && r.hlsUrl != null,
            loudnessDb = r.loudnessDb,
        )
    }

    /** Original-language audio only (falls back to everything when YouTube doesn't label tracks). */
    private fun mainAudios(allAudios: List<AudioOption>): List<AudioOption> = allAudios
        .filter { a -> a.trackType == null || a.trackType == "ORIGINAL" || allAudios.none { it.trackType == "ORIGINAL" } }
        .let { list -> if (list.map { it.trackId }.distinct().size > 1) list.filter { it.trackId == list.first().trackId } else list }
        .distinctBy { it.itag }

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
        val allAudios = audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .map { it.toOption() }
            .distinctBy { it.itag to it.trackId }
            .sortedByDescending { it.bitrateKbps }
        val audios = mainAudios(allAudios)
        val subs = runCatching {
            subtitles.filter { it.isUrl }
                .map { st ->
                    SubtitleOption(
                        // YouTube's timedtext endpoint can serve any format; WebVTT is the best supported by Media3
                        url = st.content.replace(Regex("&fmt=[^&]*"), "") + "&fmt=vtt",
                        languageCode = st.languageTag,
                        label = st.displayLanguageName + if (st.isAutoGenerated) " (auto-generated)" else "",
                        autoGenerated = st.isAutoGenerated,
                        mime = "text/vtt",
                    )
                }
                .distinctBy { it.languageCode + it.autoGenerated }
                .sortedWith(compareBy<SubtitleOption> { it.autoGenerated }.thenBy { it.label })
        }.getOrDefault(emptyList())
        val frameSet = runCatching {
            previewFrames.maxByOrNull { it.frameWidth }?.let { f ->
                FrameSet(f.urls, f.frameWidth, f.frameHeight, f.framesPerPageX, f.framesPerPageY, f.durationPerFrame, f.totalCount)
            }
        }.getOrNull()

        return StreamDetails(
            item = item,
            description = description?.content().orEmpty(),
            likes = likeCount,
            subscriberCount = uploaderSubscriberCount,
            videoStreams = videos,
            audioStreams = audios,
            hlsUrl = hlsUrl?.takeIf { it.isNotBlank() },
            dashUrl = dashMpdUrl?.takeIf { it.isNotBlank() },
            related = relatedItems.mapNotNull(::toEntry).filterNot { e -> e is Entry.Video && e.video.title.contains("YouTube Premium", true) },
            chapters = streamSegments.map { Chapter(it.title, it.startTimeSeconds, it.previewUrl) },
            tags = tags ?: emptyList(),
            category = category,
            allAudioStreams = allAudios,
            subtitles = subs,
            frames = frameSet,
            liveChat = runCatching { liveChatContinuation?.takeIf { hasLiveChat() } }.getOrNull(),
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
            width = width,
            initRange = byteRange(initStart, initEnd),
            indexRange = byteRange(indexStart, indexEnd),
        )
    }

    private fun byteRange(start: Int, end: Int): String? = if (end > 0 && end > start && start >= 0) "$start-$end" else null

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
            trackId = audioTrackId,
            trackType = audioTrackType?.name,
            language = audioLocale?.displayLanguage,
            initRange = byteRange(initStart, initEnd),
            indexRange = byteRange(indexStart, indexEnd),
        )
    }

    // ---------------------------------------------------------------- comments

    /**
     * NewPipe Extractor first (bounded so the UI never spins forever), then our own web `next`
     * parser — which also uses the account when signed in.
     */
    suspend fun comments(url: String): CommentsPage = io {
        if (GuestSources.supported(url)) return@io CommentsPage(emptyList(), null, disabled = true)
        // Signed in: YouTube's own comments API first, so like / reply / write work like the app.
        if (Graph.account.isSignedIn) {
            videoId(url)?.let { id ->
                runCatching { withTimeout(15_000) { YtComments.first(id) } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it }
                    .getOrNull()?.let { return@io it }
            }
        }
        val npe = runCatching {
            withTimeout(15_000) { runInterruptible { authFallback { CommentsInfo.getInfo(service, url) } } }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it }
        val info = npe.getOrNull()
        if (info != null && (info.isCommentsDisabled || info.relatedItems.isNotEmpty())) {
            if (info.isCommentsDisabled) return@io CommentsPage(emptyList(), null, 0, disabled = true)
            return@io CommentsPage(
                info.relatedItems.map { it.toComment(url) },
                nextComments(url, info.nextPage),
                info.commentsCount,
            )
        }
        val id = runCatching { service.streamLHFactory.fromUrl(url).id }.getOrNull() ?: throw npe.exceptionOrNull() ?: IllegalArgumentException(url)
        try {
            withTimeout(20_000) { YtComments.first(id) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
            if (info != null) return@io CommentsPage(emptyList(), null, info.commentsCount)
            throw npe.exceptionOrNull()?.also { it.addSuppressed(t) } ?: t
        }
    }

    /**
     * One poll of a live chat. Returns new messages and the token for the next poll
     * (null when the chat has ended).
     */
    suspend fun liveChat(url: String, token: String): Pair<List<CommentItem>, String?> = io {
        val extractor = service.getCommentsExtractor(url)
        extractor.setLiveChatContinuation(token)
        val page = extractor.getPage(Page("live_chat", token))
        page.items.map { it.toComment(url) } to page.nextPage?.id
    }

    private fun nextComments(url: String, page: Page?): (suspend () -> CommentsPage)? {
        if (page == null || !Page.isValid(page)) return null
        return {
            io {
                val more = CommentsInfo.getMoreItems(service, url, page)
                CommentsPage(more.items.map { it.toComment(url) }, nextComments(url, more.nextPage))
            }
        }
    }

    private fun CommentsInfoItem.toComment(streamUrl: String): CommentItem = CommentItem(
        id = commentId ?: (uploaderName + textualUploadDate),
        author = uploaderName.orEmpty(),
        authorAvatar = uploaderAvatars.best(),
        authorUrl = uploaderUrl,
        text = runCatching { commentText.content() }.getOrDefault(""),
        likes = likeCount,
        published = textualUploadDate,
        replyCount = replyCount,
        pinned = isPinned,
        hearted = isHeartedByUploader,
        verified = isUploaderVerified,
        byCreator = isChannelOwner,
        loadReplies = nextComments(streamUrl, replies),
    )

    // ---------------------------------------------------------------- channel

    suspend fun channel(url: String): ChannelDetails = io {
        if (GuestSources.isPeerChannel(url)) return@io Graph.openVideos.channel(url)
        val info = authFallback { ChannelInfo.getInfo(service, url) }
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
        if (page == null || !Page.isValid(page)) return null
        return {
            io {
                val more = ChannelTabInfo.getMoreItems(service, handler, page)
                PageResult(more.items.mapNotNull(::toEntry), nextChannelTab(handler, more.nextPage))
            }
        }
    }

    /** Latest uploads of a channel — used by the subscriptions feed. */
    suspend fun latestUploads(channelUrl: String): List<VideoItem> = io {
        if (GuestSources.isPeerChannel(channelUrl)) return@io Graph.openVideos.channelVideos(channelUrl).items.filterIsInstance<Entry.Video>().map { it.video }
        val info = ChannelInfo.getInfo(service, channelUrl)
        val handler = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: return@io emptyList()
        ChannelTabInfo.getInfo(service, handler).relatedItems
            .mapNotNull { (toEntry(it) as? Entry.Video)?.video }
            .map { v -> if (v.uploaderAvatar == null) v.copy(uploaderAvatar = info.avatars.best()) else v }
    }

    // ---------------------------------------------------------------- playlist

    suspend fun playlist(url: String): PlaylistDetails {
        val listId = Regex("[?&]list=([\\w-]+)").find(url)?.groupValues?.get(1)
        // Private playlists, Liked videos (LL) and Watch later (WL) only exist for the account.
        val personal = listId != null && (listId == "LL" || listId == "WL" || listId.startsWith("LM"))
        if (personal && Graph.account.isSignedIn) return Graph.accountApi.playlistDetails(listId!!)
        return try {
            publicPlaylist(url)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException || listId == null || !Graph.account.isSignedIn) throw t
            runCatching { Graph.accountApi.playlistDetails(listId) }.getOrElse { throw t }
        }
    }

    private suspend fun publicPlaylist(url: String): PlaylistDetails = io {
        val info = authFallback { PlaylistInfo.getInfo(service, url) }
        PlaylistDetails(
            playlist = PlaylistItem(info.url, info.name, info.thumbnails.best(), info.uploaderName, info.streamCount),
            description = runCatching { info.description?.content().orEmpty() }.getOrDefault(""),
            first = PageResult(info.relatedItems.mapNotNull(::toEntry), nextPlaylist(info.url, info.nextPage)),
        )
    }

    private fun nextPlaylist(url: String, page: Page?): (suspend () -> PageResult)? {
        if (page == null || !Page.isValid(page)) return null
        return {
            io {
                val more: ListExtractor.InfoItemsPage<StreamInfoItem> = PlaylistInfo.getMoreItems(service, url, page)
                PageResult(more.items.mapNotNull(::toEntry), nextPlaylist(url, more.nextPage))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    fun isStreamUrl(url: String): Boolean = GuestSources.supported(url) ||
        runCatching { service.streamLHFactory.acceptUrl(url) }.getOrDefault(false)

    fun isChannelUrl(url: String): Boolean = GuestSources.isPeerChannel(url) ||
        runCatching { service.channelLHFactory.acceptUrl(url) }.getOrDefault(false)

    fun isPlaylistUrl(url: String): Boolean =
        runCatching { service.playlistLHFactory.acceptUrl(url) }.getOrDefault(false)

    fun videoId(url: String): String? = if (GuestSources.supported(url)) null else runCatching { service.streamLHFactory.getId(url) }.getOrNull()

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

    /**
     * Runs an extractor call anonymously first; if YouTube refuses it (age restriction,
     * "confirm you're not a bot", private/members content…) and the user is signed in,
     * transparently retries with the account attached.
     */
    private fun <T> authFallback(block: () -> T): T {
        val account = Graph.account
        return try {
            block()
        } catch (t: Throwable) {
            if (!account.isSignedIn || !useAccountForRestricted) throw t
            try {
                account.withAuth(block)
            } catch (second: Throwable) {
                second.addSuppressed(t)
                throw second
            }
        }
    }

    @Volatile
    var useAccountForRestricted: Boolean = true

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

/** A friendly, user-facing explanation of why a video couldn't be loaded. */
class StreamBlockedException(message: String, cause: Throwable) : java.io.IOException(message, cause)

/** Chapters parsed from "0:00 Intro" style timestamps in a description (used by the TV fallback). */
object Chapters {
    private val line = Regex("""^\s*[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?\s*[-–—:|]?\s*(.+?)\s*$""")

    fun fromDescription(description: String): List<Chapter> {
        val found = description.lines().mapNotNull { l ->
            val m = line.find(l) ?: return@mapNotNull null
            val secs = m.groupValues[1].split(':').fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: return@mapNotNull null) }
            Chapter(m.groupValues[2], secs, null)
        }
        // YouTube's own rule: first chapter at 0:00, at least 3, ascending
        if (found.size < 3 || found.first().startSeconds != 0) return emptyList()
        if (found.zipWithNext().any { (a, b) -> b.startSeconds <= a.startSeconds }) return emptyList()
        return found
    }
}
