package com.vidora.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun UiDevice.enterGuestHome() {
    // Match the shipping welcome copy; no account or test-only release backdoor.
    wait(Until.hasObject(By.text("Continue without signing in")), 2_000)
    findObject(By.text("Continue without signing in"))?.click()
    check(wait(Until.hasObject(By.desc("Search")), 10_000)) { "Guest Home search control missing" }
}

/** Release-target measurements, not debug timings. Network variability must be reported separately. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PerformanceBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun coldStartup() = rule.measureRepeated(
        packageName = "com.vidora.app", metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 5, startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
        setupBlock = {
            device.executeShellCommand("pm grant com.vidora.app android.permission.POST_NOTIFICATIONS")
            startActivityAndWait(); device.enterGuestHome(); pressHome()
        },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.desc("Search")), 10_000))
    }
    @Test fun homeScroll() = rule.measureRepeated(
        packageName = "com.vidora.app", metrics = listOf(FrameTimingMetric()),
        iterations = 3, startupMode = StartupMode.WARM,
        setupBlock = {
            device.executeShellCommand("pm grant com.vidora.app android.permission.POST_NOTIFICATIONS")
            startActivityAndWait(); device.enterGuestHome()
        },
    ) {
        val x = device.displayWidth / 2
        repeat(3) { device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 20) }
    }
}

@RunWith(AndroidJUnit4::class)
@LargeTest
class ProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()
    @Test fun guestJourney() = rule.collect(packageName = "com.vidora.app", maxIterations = 5, stableIterations = 2, includeInStartupProfile = true) {
        device.executeShellCommand("pm grant com.vidora.app android.permission.POST_NOTIFICATIONS")
        pressHome(); startActivityAndWait()
        device.enterGuestHome()
        val x = device.displayWidth / 2
        device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 20)
        checkNotNull(device.findObject(By.desc("Search"))).click()
        val query = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        checkNotNull(query) { "Search input missing" }.text = "nature"
        device.pressEnter()
        device.waitForIdle(2_000)
        device.pressBack()
    }
}
