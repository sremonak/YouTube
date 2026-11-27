package com.vidora.app.data

import com.vidora.app.Graph
import kotlinx.coroutines.*

/** Independent public providers: partial failures never discard other providers' results. */
class DiscoveryRepository(
    private val youtubeSearch: suspend (String, SearchFilter) -> PageResult = { q, f -> Graph.repository.search(q, f) },
    private val peerSearch: suspend (String?) -> PageResult = { q -> Graph.openVideos.peerSearch(q) },
    private val commonsSearch: suspend (String) -> PageResult = { q -> Graph.openVideos.commonsSearch(q) },
    private val peerHome: suspend () -> PageResult = { Graph.openVideos.peerSearch(null, count = 4) },
) {
    private data class Feed(val name: String, val load: suspend () -> PageResult)
    private data class Result(val name: String, val page: PageResult?, val failed: Boolean)

    suspend fun search(query: String, source: VideoSource = VideoSource.ALL, filter: SearchFilter = SearchFilter.ALL): PageResult {
        if (query.isBlank()) return PageResult(emptyList())
        val videoFilter = filter == SearchFilter.ALL || filter == SearchFilter.VIDEOS
        if (!videoFilter && source !in listOf(VideoSource.ALL, VideoSource.YOUTUBE)) {
            return PageResult(emptyList(), notices = listOf("${source.label} currently supports video search only. Choose All or Videos."))
        }
        val feeds = buildList {
            if (source == VideoSource.ALL || source == VideoSource.YOUTUBE) add(Feed("YouTube") { youtubeSearch(query, filter) })
            if (videoFilter && (source in listOf(VideoSource.ALL, VideoSource.PUBLIC, VideoSource.PEERTUBE))) add(Feed("PeerTube") { peerSearch(query) })
            if (videoFilter && (source in listOf(VideoSource.ALL, VideoSource.PUBLIC, VideoSource.COMMONS))) add(Feed("Commons") { commonsSearch(query) })
        }
        // Deliberately no region lookup or country-term injection in search.
        return combine(feeds)
    }

    suspend fun home(country: String): PageResult = combine(listOf(
        Feed("Regional YouTube") { youtubeSearch(GuestSources.countryName(country), SearchFilter.VIDEOS) },
        Feed("Regional Commons") { commonsSearch(GuestSources.countryName(country)) },
        Feed("PeerTube") { peerHome() },
    ))

    suspend fun publicAlternatives(query: String): PageResult = combine(listOf(
        Feed("PeerTube") { peerSearch(query) }, Feed("Commons") { commonsSearch(query) },
    ))

    private suspend fun combine(feeds: List<Feed>): PageResult = supervisorScope {
        val results = feeds.map { feed -> async {
            try { Result(feed.name, withTimeout(20_000) { feed.load() }, false) }
            catch (_: TimeoutCancellationException) { Result(feed.name, null, true) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Result(feed.name, null, true) }
        } }.awaitAll()
        if (results.all { it.failed }) error("Video sources could not be reached. Check your connection and refresh. No sign-in is required for these public sources.")
        val next = results.mapNotNull { r -> r.page?.next?.let { Feed(r.name, it) } }
        PageResult(GuestSources.interleave(results.map { it.page?.items.orEmpty() }),
            next = if (next.isEmpty()) null else ({ combine(next) }),
            notices = results.filter { it.failed }.map { "${it.name} is temporarily unavailable; showing other sources." } +
                results.flatMap { it.page?.notices.orEmpty() })
    }
}
