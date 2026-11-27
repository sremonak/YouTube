package com.vidora.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Release-target measurements, not debug timings. Network variability must be reported separately. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PerformanceBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun coldStartup() = rule.measureRepeated(
        packageName = "com.vidora.app", metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 5, startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Explore")), 5_000)
    }
    @Test fun homeScroll() = rule.measureRepeated(
        packageName = "com.vidora.app", metrics = listOf(FrameTimingMetric()),
        iterations = 3, startupMode = StartupMode.WARM,
        setupBlock = { startActivityAndWait(); device.wait(Until.hasObject(By.text("Explore")), 5_000) },
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
        pressHome(); startActivityAndWait()
        // Dismiss optional first-install welcome, if shown. Never authenticates an account.
        device.findObject(By.textContains("Continue as guest"))?.click()
        device.wait(Until.hasObject(By.text("Explore")), 5_000)
        val x = device.displayWidth / 2
        device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 20)
        device.findObject(By.desc("Search"))?.click()
        device.waitForIdle(2_000)
        device.pressBack()
    }
}
