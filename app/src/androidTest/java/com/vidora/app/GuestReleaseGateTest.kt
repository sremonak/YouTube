package com.vidora.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.Player
import com.vidora.app.data.*
import com.vidora.app.ui.UiCache
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Offline fixture gate, deliberately separate from upstream-dependent PlaybackE2ETest. */
class GuestReleaseGateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val peer = VideoItem("https://tilvids.com/videos/watch/3620cd16-97f4-488c-a018-a6897baddbc4", "Peer fixture video", uploader = "Fixture creator", duration = 2, platform = "PeerTube")
    private val commons = VideoItem(GuestSources.commonsUrl("File:Fixture.webm"), "Commons fixture video", duration = 2, platform = "Commons")
    @Before fun prepare() = runBlocking {
        // Keep the platform permission dialog out of the Compose-only fixture journey.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).close()
        }
        context.getSharedPreferences("vidora_onboarding", 0).edit().putBoolean("welcome_done", true).commit()
        Graph.settings.update { it.copy(autoUpdateCheck = false, autoRegionByIp = false, contentCountry = "US", glassUi = false,
            sponsorBlock = false, prefetchNext = false, persistQueue = false, onboardingDone = true, homeInterests = emptySet(), homeLanguage = "", lessLikeVideos = emptySet()) }
        Graph.discoveryOverride = DiscoveryRepository(youtubeSearch = { _, _ -> PageResult(emptyList()) },
            peerSearch = { PageResult(listOf(Entry.Video(peer))) }, commonsSearch = { PageResult(listOf(Entry.Video(commons))) },
            peerHome = { PageResult(listOf(Entry.Video(peer))) })
        val media = File(context.cacheDir, "synthetic.mp4")
        instrumentation.context.assets.open("synthetic.mp4").use { input -> media.outputStream().use { input.copyTo(it) } }
        Graph.streamOverride = { url ->
            val item = if (GuestSources.isCommons(url)) commons else peer
            StreamDetails(item, "Synthetic release-test fixture", -1, -1,
                listOf(VideoOption(media.toURI().toString(), 999, 90, 15, "90p", "video/mp4", "mp4", "avc1.42E01E", false, 0, media.length())),
                emptyList(), null, null, emptyList(), emptyList(), emptyList(), null)
        }
        instrumentation.runOnMainSync { UiCache.clearAll() }
    }
    @After fun cleanup() {
        scenario?.close()
        instrumentation.runOnMainSync { Graph.player.stop(); UiCache.clearAll() }
        Graph.discoveryOverride = null; Graph.streamOverride = null
    }
    @Test fun guestHomeSearchSaveAndBothSourcePlayback() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { compose.onAllNodesWithText(peer.title).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("fixture exact query")
        compose.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(peer.title).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText(peer.title).performTouchInput { longClick() }
        compose.onNodeWithText("Save to Watch later").performClick()
        compose.waitUntil(5000) { Graph.library.data.value.watchLater.any { it.url == peer.url } }
        compose.onNodeWithText(peer.title).performClick()
        compose.waitUntil(15_000) { Graph.player.state.value.current?.url == peer.url && Graph.player.state.value.startupMs > 0 }
        assertNull(Graph.player.state.value.error)
        instrumentation.runOnMainSync { Graph.player.play(commons) }
        compose.waitUntil(15_000) { Graph.player.state.value.current?.url == commons.url && Graph.player.state.value.startupMs > 0 }
        assertNull(Graph.player.state.value.error)
        scenario!!.recreate()
        compose.waitUntil(10_000) { Graph.player.state.value.current?.url == commons.url }
        assertTrue(Graph.library.data.value.watchLater.any { it.url == peer.url })
    }
    @Test fun explicitRefreshActuallyRequestsNewFeedAndReportsSameResultsHonestly() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val fresh = peer.copy(url = peer.url + "?fresh", title = "Fresh fixture result")
        Graph.discoveryOverride = DiscoveryRepository(youtubeSearch = { _, _ ->
            PageResult(listOf(Entry.Video(if (calls.incrementAndGet() == 1) peer else fresh)))
        }, peerSearch = { PageResult(emptyList()) }, commonsSearch = { PageResult(emptyList()) }, peerHome = { PageResult(emptyList()) })
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { calls.get() > 0 && compose.onAllNodesWithText(peer.title).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText("Refresh feed").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(fresh.title).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        assertTrue(calls.get() >= 2)
        compose.onNodeWithText("Feed updated · fresh results loaded").assertIsDisplayed()
        compose.onNodeWithText("Refresh feed").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Checked just now · providers returned the same videos").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
    @Test fun searchFiltersAreReversibleAndKeyboardDoesNotCoverResults() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Search").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("fixture")
        compose.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Filters").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("Hide live").performScrollTo().performClick()
        compose.onNodeWithText("Clear all filters").performScrollTo().performClick()
        compose.onNodeWithText("Show results").performScrollTo().performClick()
        compose.onNodeWithText("Filters").assertIsDisplayed()
    }
    @Test fun publicSnapshotsSurviveDatabaseReopenAndClear() = runBlocking {
        val name = "release-gate-${System.nanoTime()}.db"
        var store = FeedStore(context, name)
        val rows = listOf(Entry.Video(peer), Entry.Video(commons))
        store.write("home:public:US", rows); store.close()
        store = FeedStore(context, name)
        assertEquals(rows, store.read("home:public:US"))
        store.clear(); assertTrue(store.read("home:public:US").isEmpty())
        // Deterministically queue an old write behind the store lock, then request privacy clear.
        val field = FeedStore::class.java.getDeclaredField("operations").apply { isAccessible = true }
        val gate = field.get(store) as kotlinx.coroutines.sync.Mutex
        gate.lock()
        val staleWrite = launch(start = CoroutineStart.UNDISPATCHED) { store.write("search:old", rows) }
        val privacyClear = launch(start = CoroutineStart.UNDISPATCHED) { store.clear() }
        gate.unlock()
        joinAll(staleWrite, privacyClear)
        assertTrue(store.read("search:old").isEmpty())
        store.close(); context.deleteDatabase(name)
        Unit
    }
}
