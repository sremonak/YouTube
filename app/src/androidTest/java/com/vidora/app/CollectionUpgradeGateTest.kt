package com.vidora.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import com.vidora.app.data.*
import com.vidora.app.download.*
import com.vidora.app.player.PlayerUiState
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.VideoActions
import com.vidora.app.ui.screens.*
import com.vidora.app.ui.theme.VidoraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Actual Compose interactions + local persistence/player APIs; no private YouTube claims. */
class CollectionUpgradeGateTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var originalLibrary: String
    private lateinit var originalSettings: AppSettings
    private var originalDownloads = emptyList<DownloadRecord>()
    private val alpha = VideoItem("https://www.youtube.com/watch?v=abcdefghijk", "Alpha piano lesson", uploader = "Calm creator", duration = 40)
    private val beta = alpha.copy(url = "https://www.youtube.com/watch?v=bcdefghijkl", title = "Beta guitar lesson", duration = 600)
    private val gamma = alpha.copy(url = "https://www.youtube.com/watch?v=cdefghijklm", title = "Gamma documentary", duration = 1400)
    private val actions = VideoActions({}, {}, {}, {})

    @Before fun prepare() = runBlocking {
        originalLibrary = Graph.library.exportJson()
        originalSettings = Graph.settings.flow.first()
        originalDownloads = Graph.downloads.items.value
        Graph.settings.update { it.copy(incognito = false, autoRegionByIp = false, autoUpdateCheck = false,
            sponsorBlock = false, prefetchNext = false, persistQueue = false, saveHistory = false, saveSearchHistory = true, shortsFit = false, glassUi = false) }
        instrumentation.runOnMainSync { Graph.player.stop() }
        Graph.library.importJson("{}")
        originalDownloads.forEach { Graph.downloads.remove(it.id) }
        val media = File(context.cacheDir, "collection-long.mp4")
        instrumentation.context.assets.open("synthetic-long.mp4").use { input -> media.outputStream().use { input.copyTo(it) } }
        Graph.streamOverride = { url ->
            val item = listOf(alpha, beta, gamma).first { it.url == url }
            StreamDetails(item, "Offline collection fixture", -1, -1,
                listOf(VideoOption(media.toURI().toString(), 999, 90, 15, "90p", "video/mp4", "mp4", "avc1.42E01E", false, 0, media.length())),
                emptyList(), null, null, emptyList(), emptyList(), emptyList(), null)
        }
    }
    @After fun cleanup() = runBlocking {
        instrumentation.runOnMainSync { Graph.player.stop() }
        Graph.library.importJson(originalLibrary)
        Graph.downloads.items.value.forEach { Graph.downloads.remove(it.id) }
        originalDownloads.forEach { Graph.downloads.upsert(it) }
        Graph.streamOverride = null
        Graph.settings.update { originalSettings }
    }
    private fun screen(content: @Composable (NavHostController) -> Unit) {
        compose.setContent {
            VidoraTheme(AppSettings(dynamicColor = false, glassUi = false)) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "fixture") {
                    composable("fixture") { content(nav) }
                    composable(Routes.PLAYER) { Text("Player destination") }
                    composable(Routes.LOCAL_PLAYLIST) { LocalPlaylistScreen(nav, actions, it.arguments?.getString("id").orEmpty()) }
                }
            }
        }
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
        }
        shell("mkdir -p /sdcard/Download/vidora-ui-snapshots")
        shell("screencap -p /sdcard/Download/vidora-ui-snapshots/$name.png")
        assertTrue(shell("ls /sdcard/Download/vidora-ui-snapshots/$name.png").trim().endsWith("/$name.png"))
    }

    @Test fun libraryFiltersSelectMatchingAndConfirmationNeverRemoveOtherCollections() {
        val id = Graph.library.createPlaylistFrom("Kept playlist", listOf(alpha, beta))
        Graph.library.toggleWatchLater(alpha); Graph.library.toggleWatchLater(beta); Graph.library.toggleWatchLater(gamma)
        Graph.library.addHistory(alpha)
        screen { ManagedLibraryScreen(it, actions, "later") }
        compose.onNodeWithText("Search this collection").performTextReplacement("alpha calm")
        compose.onNodeWithText("1 matching videos").assertIsDisplayed()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select matching").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        snapshot("upgrade-library")
        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Remove locally").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(3, Graph.library.data.value.watchLater.size)
        compose.onNodeWithText("Actions").performClick()
        compose.onNodeWithText("Remove locally").performClick()
        compose.onNodeWithText("Remove locally").performClick()
        compose.waitUntil(5000) { Graph.library.data.value.watchLater.size == 2 }
        assertTrue(Graph.library.data.value.watchLater.none { it.url == alpha.url })
        assertEquals(2, Graph.library.playlist(id)!!.videos.size)
        assertEquals(1, Graph.library.data.value.history.size)
    }

    @Test fun bookmarkCreationPrivacyRenameAndBackupRoundTrip() {
        screen { BookmarkSheet(PlayerUiState(current = alpha), {}) }
        compose.onNodeWithText("Save current moment").performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Important intro")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { Graph.library.data.value.bookmarks.size == 1 }
        val bookmark = Graph.library.data.value.bookmarks.single()
        assertEquals("Important intro", bookmark.label)
        assertTrue(Graph.library.data.value.history.isEmpty())
        val backup = Graph.library.exportJson()
        Graph.library.removeBookmark(bookmark.id)
        assertTrue(Graph.library.importJson(backup))
        Graph.library.renameBookmark(bookmark.id, "Renamed intro")
        compose.onNodeWithText("Renamed intro").assertIsDisplayed()
        runBlocking { Graph.settings.update { it.copy(incognito = true) } }
        compose.onNodeWithText("Bookmarks are hidden and cannot be saved in incognito.").assertIsDisplayed()
        compose.onNodeWithText("Save current moment").assertDoesNotExist()
        assertEquals(1, Graph.library.data.value.bookmarks.size)
    }

    @Test fun savedMomentStartsAtRequestedTimeAndQueueToolsKeepCurrentPlayback() {
        screen { Text("Player fixture") }
        instrumentation.runOnMainSync { Graph.player.play(alpha, listOf(alpha, beta, gamma), startPositionMs = 12_000) }
        compose.waitUntil(20_000) { Graph.player.state.value.isPlaying }
        instrumentation.runOnMainSync {
            assertTrue("Requested timestamp was lost to automatic resume", Graph.player.exoPlayer.currentPosition >= 11_500)
            Graph.player.moveQueueItem(0, 1)
            assertEquals(alpha.url, Graph.player.state.value.current?.url)
            assertEquals(1, Graph.player.state.value.queueIndex)
            Graph.player.shuffleUpcoming()
            Graph.player.clearUpcoming()
            assertEquals(listOf(beta.url, alpha.url), Graph.player.state.value.queue.map { it.url })
            assertTrue(Graph.player.exoPlayer.isPlaying)
            Graph.player.enqueueAll(listOf(beta, gamma, gamma))
            assertEquals(3, Graph.player.state.value.queue.size)
        }
    }

    @Test fun downloadsSearchAndBatchCancelProtectCompletedFiles() {
        fun record(id: String, status: DownloadStatus, kind: DownloadKind = DownloadKind.VIDEO) =
            DownloadRecord(id, alpha, kind, qualityLabel = "720p", fileName = "$id.mp4", status = status, totalBytes = 1024)
        Graph.downloads.upsert(record("fixture-completed", DownloadStatus.COMPLETED, DownloadKind.AUDIO))
        Graph.downloads.upsert(record("fixture-active", DownloadStatus.QUEUED))
        Graph.downloads.upsert(record("fixture-failed", DownloadStatus.FAILED))
        screen { DownloadsScreen(it) }
        compose.onNodeWithText("Search downloads").performTextReplacement("fixture-active")
        compose.onNodeWithTag("download-details-fixture-active", useUnmergedTree = true).assertWidthIsAtLeast(140.dp)
        snapshot("upgrade-downloads")
        compose.onNodeWithText("Batch actions").performClick()
        compose.onNodeWithText("Cancel matching active").performClick()
        compose.onNodeWithText("Keep downloading").performClick()
        assertEquals(DownloadStatus.QUEUED, Graph.downloads.get("fixture-active")!!.status)
        compose.onNodeWithText("Batch actions").performClick()
        compose.onNodeWithText("Cancel matching active").performClick()
        compose.onNodeWithText("Cancel downloads").performClick()
        compose.waitUntil(5000) { Graph.downloads.get("fixture-active")?.status == DownloadStatus.CANCELLED }
        assertEquals(DownloadStatus.COMPLETED, Graph.downloads.get("fixture-completed")!!.status)
        assertEquals(DownloadStatus.FAILED, Graph.downloads.get("fixture-failed")!!.status)
        Downloads.cancel(context, "fixture-completed")
        assertEquals(DownloadStatus.COMPLETED, Graph.downloads.get("fixture-completed")!!.status)
        compose.onNodeWithText("Reset filters").performClick()
        compose.onNodeWithText("All statuses").performClick()
        compose.onNodeWithText("Completed").performClick()
        compose.onNodeWithText("Audio · 720p · ${com.vidora.app.util.Format.bytes(1024)}").assertExists()
    }

    @Test fun playlistSearchAndDuplicateDoNotMutateTheOriginal() {
        val id = Graph.library.createPlaylistFrom("Original playlist", listOf(beta, alpha, gamma))
        screen { LocalPlaylistScreen(it, actions, id) }
        compose.onNodeWithText("Search this collection").performTextReplacement("alpha")
        compose.onNodeWithText("1 matching videos").assertIsDisplayed()
        compose.onNodeWithText("Playlist tools").performClick()
        compose.onNodeWithText("Duplicate playlist").performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Independent copy")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5000) { Graph.library.data.value.playlists.size == 2 }
        val copy = Graph.library.data.value.playlists.first { it.id != id }
        assertEquals(listOf(beta, alpha, gamma), copy.videos)
        Graph.library.reorderPlaylist(copy.id, CollectionOrder.TITLE)
        Graph.library.removeFromPlaylist(copy.id, beta.url)
        assertEquals(listOf(beta, alpha, gamma), Graph.library.playlist(id)!!.videos)
        assertEquals(listOf(alpha, gamma), Graph.library.playlist(copy.id)!!.videos)
    }

    @Test fun searchHistoryClearRequiresConfirmationAndLeavesWatchHistory() {
        Graph.library.addSearch("A saved search"); Graph.library.addHistory(alpha)
        screen { SearchScreen(it, actions, "") }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Clear search history").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Clear search history").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, Graph.library.data.value.searchHistory.size)
        compose.onNodeWithText("Clear search history").performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.waitUntil(5000) { Graph.library.data.value.searchHistory.isEmpty() }
        assertEquals(1, Graph.library.data.value.history.size)
    }
    @Test fun orvixaBrandKeepsLauncherAliasesAndMigratesOnlyTheDefaultName() {
        assertEquals("Orvixa", context.applicationInfo.loadLabel(context.packageManager).toString())
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        val aliases = info.activities.orEmpty().filter { it.name.contains(".alias.L_") }
        assertEquals(24, aliases.size)
        assertEquals("Orvixa", aliases.first { it.name.endsWith("L_classic_vidora") }.loadLabel(context.packageManager).toString())
        assertTrue(context.getString(R.string.widget_label).startsWith("Orvixa"))
        runBlocking {
            Graph.settings.update { it.copy(displayName = "Vidora") }
            assertEquals("Orvixa", Graph.settings.flow.first().displayName)
            Graph.settings.update { it.copy(displayName = "My theatre") }
            assertEquals("My theatre", Graph.settings.flow.first().displayName)
        }
    }

    @Test fun studyNoteCanBeEditedSearchedAndBackedUpWithoutChangingTimestamp() {
        val id = Graph.library.saveBookmark(alpha, 12_345, "Opening example")
        screen { BookmarksScreen(it) }
        compose.onNodeWithText("Moment actions").performClick()
        compose.onNodeWithText("Edit note").performClick()
        compose.onNodeWithText("Notes for this moment").performTextReplacement("Quadratic equations: revisit this example")
        compose.onNodeWithText("Save note").performClick()
        compose.waitUntil(5000) { Graph.library.data.value.bookmarks.single().note.isNotBlank() }
        compose.onNodeWithText("Search saved moments").performTextReplacement("quadratic example")
        compose.onNodeWithText("Opening example").assertIsDisplayed()
        assertEquals(12_345L, Graph.library.data.value.bookmarks.single().positionMs)
        val backup = Graph.library.exportJson()
        Graph.library.updateBookmarkNote(id, "")
        assertTrue(Graph.library.importJson(backup))
        assertTrue(Graph.library.data.value.bookmarks.single().note.contains("Quadratic"))
        snapshot("orvixa-study-notes")
    }

    @Test fun playlistPinAppearsInQuickAccessAndSurvivesBackup() {
        val id = Graph.library.createPlaylistFrom("Practice playlist", listOf(alpha, beta))
        var library by androidx.compose.runtime.mutableStateOf(false)
        screen { if (library) LibraryScreen(it, actions) else LocalPlaylistScreen(it, actions, id) }
        compose.onNodeWithText("Playlist tools").performClick()
        compose.onNodeWithText("Pin to Quick access").performClick()
        compose.waitUntil(5000) { Graph.library.playlist(id)!!.pinned }
        val backup = Graph.library.exportJson()
        Graph.library.togglePlaylistPin(id)
        Graph.library.importJson(backup)
        assertTrue(Graph.library.playlist(id)!!.pinned)
        compose.runOnIdle { library = true }
        compose.onNodeWithText("Quick access").assertIsDisplayed()
        compose.onAllNodesWithText("Practice playlist").onFirst().assertIsDisplayed()
        snapshot("orvixa-quick-access")
    }

    @Test fun customSleepDurationIsValidatedExtendableAndSurvivesLocalPlayback() {
        var open by androidx.compose.runtime.mutableStateOf(true)
        screen { if (open) CustomSleepDialog({ open = false }) { Graph.player.setSleepTimer(it); open = false } else Text("Timer set") }
        compose.onNodeWithText("Minutes (1–240)").performTextReplacement("0")
        compose.onNodeWithText("Start timer").assertIsNotEnabled()
        compose.onNodeWithText("Minutes (1–240)").performTextReplacement("17")
        compose.onNodeWithText("Start timer").assertIsEnabled().performClick()
        instrumentation.runOnMainSync {
            val deadline = Graph.player.state.value.sleepTimerElapsedAt
            assertTrue(deadline > android.os.SystemClock.elapsedRealtime() + 16 * 60_000)
            assertTrue(Graph.player.extendSleepTimer(5))
            assertTrue(Graph.player.state.value.sleepTimerElapsedAt >= deadline + 5 * 60_000 - 1000)
            val extended = Graph.player.state.value.sleepTimerElapsedAt
            Graph.player.playLocal(alpha, android.net.Uri.fromFile(File(context.cacheDir, "collection-long.mp4")), false)
            assertEquals(extended, Graph.player.state.value.sleepTimerElapsedAt)
            Graph.player.setSleepTimer(0)
            assertEquals(0L, Graph.player.state.value.sleepTimerElapsedAt)
            assertFalse(Graph.player.extendSleepTimer(5))
        }
    }

    @Test fun sectionRepeatReallySeeksBackAndClearsOutsideTheRangeOrOnAnotherVideo() {
        screen { Text("Offline A–B fixture") }
        instrumentation.runOnMainSync { Graph.player.play(alpha) }
        compose.waitUntil(20_000) { Graph.player.state.value.isPlaying }
        val loops = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs in 11_900L..12_100L) loops.incrementAndGet()
            }
        }
        try {
            instrumentation.runOnMainSync {
                Graph.player.exoPlayer.pause()
                Graph.player.seekTo(12_000)
                assertTrue(Graph.player.setSectionStart())
                Graph.player.seekTo(14_000)
                Graph.player.exoPlayer.addListener(listener)
                assertTrue(Graph.player.setSectionEnd())
                Graph.player.exoPlayer.play()
            }
            compose.waitUntil(12_000) { loops.get() >= 2 }
            instrumentation.runOnMainSync {
                assertEquals(alpha.url, Graph.player.state.value.current?.url)
                Graph.player.seekTo(25_000)
                assertEquals(-1L, Graph.player.state.value.sectionEndMs)
                assertTrue(Graph.player.setSectionStart())
                Graph.player.seekTo(27_000)
                assertTrue(Graph.player.setSectionEnd())
                Graph.player.play(beta)
                assertEquals(-1L, Graph.player.state.value.sectionStartMs)
            }
        } finally { instrumentation.runOnMainSync { Graph.player.exoPlayer.removeListener(listener) } }
    }

}
