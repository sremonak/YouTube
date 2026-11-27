package com.vidora.app.data

import com.vidora.app.Graph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Emits each completed source immediately. Later sources append, never reorder visible rows. */
class DiscoveryRepository(
    private val youtubeSearch: suspend (String, SearchFilter) -> PageResult = { q, f -> Graph.repository.search(q, f) },
    private val peerSearch: suspend (String?) -> PageResult = { q -> Graph.openVideos.peerSearch(q) },
    private val commonsSearch: suspend (String) -> PageResult = { q -> Graph.openVideos.commonsSearch(q) },
    private val peerHome: suspend () -> PageResult = { Graph.openVideos.peerSearch(null, count = 4) },
    private val health: ProviderHealth = ProviderHealth(),
) {
    private data class Feed(val name: String, val load: suspend () -> PageResult)
    private data class Result(val feed: Feed, val page: PageResult?)

    fun searchUpdates(query: String, source: VideoSource = VideoSource.ALL, filter: SearchFilter = SearchFilter.ALL): Flow<PageResult> {
        if (query.isBlank()) return flowOf(PageResult(emptyList()))
        val videoFilter = filter == SearchFilter.ALL || filter == SearchFilter.VIDEOS
        if (!videoFilter && source !in listOf(VideoSource.ALL, VideoSource.YOUTUBE)) return flowOf(
            PageResult(emptyList(), notices = listOf("${source.label} supports video search only. Choose All or Videos.")))
        return updates(buildList {
            if (source in listOf(VideoSource.ALL, VideoSource.YOUTUBE)) add(Feed("YouTube") { youtubeSearch(query, filter) })
            if (videoFilter && source in listOf(VideoSource.ALL, VideoSource.PUBLIC, VideoSource.PEERTUBE)) add(Feed("PeerTube") { peerSearch(query) })
            if (videoFilter && source in listOf(VideoSource.ALL, VideoSource.PUBLIC, VideoSource.COMMONS)) add(Feed("Commons") { commonsSearch(query) })
        }) // No region lookup or country-term injection in Search.
    }
    suspend fun search(query: String, source: VideoSource = VideoSource.ALL, filter: SearchFilter = SearchFilter.ALL) = searchUpdates(query, source, filter).last()

    fun homeUpdates(country: String, settings: AppSettings = AppSettings(), watched: Set<String> = emptySet()): Flow<PageResult> {
        val topic = settings.homeInterests.sorted().firstOrNull().orEmpty()
        val language = HomeRanking.languageTerm(settings.homeLanguage)
        val regional = listOf(GuestSources.countryName(country), language, topic).filter { it.isNotBlank() }.joinToString(" ")
        return updates(listOf(
            Feed("Regional YouTube") { youtubeSearch(regional, SearchFilter.VIDEOS) },
            Feed("Regional Commons") { commonsSearch(GuestSources.countryName(country)) },
            Feed("PeerTube") { if (topic.isBlank()) peerHome() else peerSearch(listOf(language, topic).filter { it.isNotBlank() }.joinToString(" ")) },
        ), rank = { HomeRanking.rank(it, settings, watched) })
    }
    suspend fun home(country: String) = homeUpdates(country).last()
    suspend fun publicAlternatives(query: String) = search(query, VideoSource.PUBLIC)

    private suspend fun run(feed: Feed): Result {
        if (health.remaining(feed.name) > 0) return Result(feed, null)
        return try {
            Result(feed, withTimeout(20_000) { feed.load() }).also { health.success(feed.name) }
        } catch (_: TimeoutCancellationException) { health.failure(feed.name); Result(feed, null) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { health.failure(feed.name); Result(feed, null) }
    }

    private fun retry(feed: Feed) = ProviderRetry(feed.name) {
        val result = run(feed)
        val page = result.page ?: error("${feed.name} is unavailable or cooling down. Try again later.")
        page
    }

    private fun updates(feeds: List<Feed>, rank: (List<Entry>) -> List<Entry> = { it }): Flow<PageResult> = channelFlow {
        val mutex = Mutex()
        val results = mutableListOf<Result>()
        val visible = LinkedHashMap<String, Entry>()
        feeds.forEach { feed -> launch {
            val result = try { run(feed) } catch (e: CancellationException) {
                this@channelFlow.cancel("Provider request cancelled", e); throw e
            }
            mutex.withLock {
                results += result
                rank(result.page?.items.orEmpty()).forEach { visible.putIfAbsent(it.key, it) }
                val next = results.mapNotNull { r -> r.page?.next?.let { Feed(r.feed.name, it) } }
                val failures = results.filter { it.page == null }
                send(PageResult(visible.values.toList(),
                    next = if (next.isEmpty()) null else ({ updates(next, rank).last() }),
                    notices = failures.map { "${it.feed.name} unavailable; other sources continue." } + results.flatMap { it.page?.notices.orEmpty() },
                    pending = results.size < feeds.size,
                    retries = failures.map { retry(it.feed) }))
            }
        } }.joinAll()
        if (results.all { it.page == null }) error("Sources could not be reached. Cached results stay available. Retry a source or check your connection; no login is required.")
    }
}
