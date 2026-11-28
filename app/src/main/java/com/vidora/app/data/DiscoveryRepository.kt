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
    private val freshness: FeedFreshness = FeedFreshness(),
) {
    private data class Feed(val name: String, val load: suspend () -> PageResult)
    private data class Result(val feed: Feed, val page: PageResult?)

    // Bounded, process-only cursors. Repeating page two on every refresh used to
    // leave a single-interest Home stuck forever despite making network requests.
    private val candidateLock = Mutex()
    private val candidateNext = LinkedHashMap<String, (suspend () -> PageResult)?>()
    private val shown = LinkedHashMap<String, Set<String>>()

    private suspend fun youtubeCandidates(plan: FeedFreshness.Plan, privateSession: Boolean): PageResult {
        val first = youtubeSearch(plan.query, SearchFilter.VIDEOS)
        if (plan.round == 0L) return first
        return candidateLock.withLock {
            val continuation = if (privateSession) first.next else candidateNext[plan.query] ?: first.next
            val more = try { continuation?.invoke() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (!privateSession && more != null) {
                candidateNext.remove(plan.query)
                candidateNext[plan.query] = more.next
                while (candidateNext.size > 16) candidateNext.remove(candidateNext.keys.first())
            }
            if (more == null) first else PageResult((more.items + first.items).distinctBy { it.key }, more.next,
                notices = first.notices + more.notices, retries = first.retries + more.retries)
        }
    }

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
        val plan = freshness.next(country, settings)
        val language = HomeRanking.languageTerm(settings.homeLanguage)
        val historyKey = "$country:${settings.homeLanguage}:${settings.homeInterests.sorted()}"
        return flow {
            val seen = if (settings.incognito) emptySet() else synchronized(shown) { shown[historyKey].orEmpty() }
            var delivered: List<Entry> = emptyList()
            emitAll(updates(listOf(
                Feed("Regional YouTube") { youtubeCandidates(plan, settings.incognito) },
                Feed("Regional Commons") { commonsSearch(listOf(GuestSources.countryName(country), plan.topic).filter { it.isNotBlank() }.joinToString(" ")) },
                Feed("PeerTube") { if (plan.topic.isBlank()) peerHome() else peerSearch(listOf(language, plan.topic).filter { it.isNotBlank() }.joinToString(" ")) },
            ), rank = { rows -> HomeRanking.rank(rows, settings, watched).sortedBy { it.key in seen } })
                .onEach { delivered = it.items })
            if (!settings.incognito) synchronized(shown) {
                shown.remove(historyKey)
                shown[historyKey] = (seen + delivered.map { it.key }).toList().takeLast(600).toSet()
                while (shown.size > 8) shown.remove(shown.keys.first())
            }
        }
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
        feeds.map { feed -> launch {
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
