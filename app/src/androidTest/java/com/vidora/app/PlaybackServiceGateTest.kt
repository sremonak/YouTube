package com.vidora.app

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vidora.app.data.Diagnostics
import com.vidora.app.data.VideoItem
import com.vidora.app.player.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Real Android foreground deadlines, not a mocked service or just successful media extraction. */
class PlaybackServiceGateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var activity: ActivityScenario<MainActivity>? = null

    @Before fun prepare() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        ).close()
        context.getSharedPreferences("vidora_onboarding", 0).edit().putBoolean("welcome_done", true).commit()
        Graph.settings.update { it.copy(autoUpdateCheck = false, autoRegionByIp = false, persistQueue = false,
            incognito = false, sponsorBlock = false, prefetchNext = false, onboardingDone = true) }
        instrumentation.runOnMainSync { Graph.player.stop() }
        activity = ActivityScenario.launch(MainActivity::class.java)
        Diagnostics.enabled = true
        Diagnostics.clear()
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { Graph.player.stop() }
        activity?.close()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    private fun await(description: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $description")
    }

    private fun startThenImmediatelyStop() {
        instrumentation.runOnMainSync {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            Graph.player.stop() // empty before the queued service start reaches the main thread
        }
    }

    @Test fun emptyQueuedForegroundStartMeetsDeadlineAndDoesNotLeaveAnIdleNotification() {
        startThenImmediatelyStop()
        await("synchronous foreground acknowledgement") { Diagnostics.report().contains("foreground_started") }
        // Android's watchdog is real time. Sleeping the instrumentation thread does not
        // block the main looper; old code crashes the process after ~5 seconds here.
        SystemClock.sleep(8_000)
        val running = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        assertFalse("Idle service was left in foreground", running.any { it.service.className == PlaybackService::class.java.name && it.foreground })
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == PlaybackService.NOTIFICATION_ID })
    }

    @Test fun rapidPlayPauseStopAndRepeatedForegroundStartsSurviveTheWatchdog() {
        val media = File(context.cacheDir, "service-regression.mp4")
        instrumentation.context.assets.open("synthetic-long.mp4").use { input -> media.outputStream().use { input.copyTo(it) } }
        instrumentation.runOnMainSync {
            Graph.player.playLocal(VideoItem("https://www.youtube.com/watch?v=serviceTest", "Offline service fixture", duration = 40), android.net.Uri.fromFile(media), false)
        }
        await("offline playback") { Graph.player.state.value.isPlaying }
        await("one media notification") {
            context.getSystemService(NotificationManager::class.java).activeNotifications.count { it.id == PlaybackService.NOTIFICATION_ID } == 1
        }
        repeat(4) { startThenImmediatelyStop(); SystemClock.sleep(100) }
        SystemClock.sleep(8_000)
        assertNull(Graph.player.state.value.current)
        assertTrue(Diagnostics.report().contains("foreground_started"))
    }
}
