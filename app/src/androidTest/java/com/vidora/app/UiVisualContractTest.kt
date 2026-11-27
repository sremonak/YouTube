package com.vidora.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.vidora.app.data.*
import com.vidora.app.ui.components.*
import com.vidora.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Offline, fixed-content visual regression gate. Baselines are reviewed source assets, never auto-accepted. */
@OptIn(ExperimentalTestApi::class)
class UiVisualContractTest {
    @get:Rule val compose = createComposeRule()
    private data class Case(val name: String, val width: Int, val height: Int, val scale: Float, val theme: ThemeMode, val list: Boolean = false, val glass: Boolean = false)
    @Test fun reviewedThemeFontAndWindowSnapshots() {
        val cases = listOf(
            Case("light-phone", 360, 720, 1f, ThemeMode.LIGHT),
            Case("dark-large-font", 320, 720, 1.8f, ThemeMode.DARK, list = true),
            Case("amoled-glass", 360, 720, 1f, ThemeMode.AMOLED, glass = true),
            Case("landscape", 640, 360, 1f, ThemeMode.LIGHT, list = true),
            Case("tablet", 840, 800, 1f, ThemeMode.DARK),
            Case("light-compact", 360, 640, 1f, ThemeMode.LIGHT, list = true),
        )
        var current by mutableStateOf(cases.first())
        compose.setContent {
            val c = current
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(c.width.dp, c.height.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(c.scale)) {
                    VidoraTheme(AppSettings(themeMode = c.theme, feedLayout = if (c.list) FeedLayout.LIST else FeedLayout.GRID, dynamicColor = false)) {
                        CompositionLocalProvider(LocalGlass provides GlassPrefs(c.glass, null)) {
                            Surface(Modifier.fillMaxSize().testTag("visual-fixture")) {
                                GlassPage(header = { SheetHeading("Vidora", "Your videos · no account needed") }) {
                                Column(Modifier.fillMaxSize().padding(top = topChromePadding())) {
                                    VideoCard(VideoItem("https://example.org/visual-fixture", "A clear, readable video title · বাংলা", uploader = "Fixture creator", duration = 245, platform = "PeerTube"),
                                        VideoActions({}, {}, {}, {}), modifier = Modifier.widthIn(max = 520.dp))
                                    TextButton(onClick = {}, modifier = Modifier.heightIn(min = VidoraSpace.touch).testTag("refresh-target")) { Text("Refresh feed") }
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(target.getExternalFilesDir(null), "ui-snapshots").apply { mkdirs() }
        val missing = mutableListOf<String>()
        for (c in cases) {
            compose.runOnIdle { current = c }
            compose.waitForIdle()
            compose.onNodeWithTag("refresh-target").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("More").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val actual = compose.onNodeWithTag("visual-fixture").captureToImage().asAndroidBitmap()
            File(output, "${c.name}.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val baseline = runCatching { InstrumentationRegistry.getInstrumentation().context.assets.open("ui-goldens/${c.name}.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (baseline == null) { missing += c.name; continue }
            assertEquals("${c.name} width", baseline.width, actual.width)
            assertEquals("${c.name} height", baseline.height, actual.height)
            var changed = 0
            val pixels = IntArray(actual.width * actual.height)
            val expected = IntArray(pixels.size)
            actual.getPixels(pixels, 0, actual.width, 0, 0, actual.width, actual.height)
            baseline.getPixels(expected, 0, actual.width, 0, 0, actual.width, actual.height)
            for (index in pixels.indices) {
                val a = pixels[index]; val b = expected[index]
                if (abs((a and 255) - (b and 255)) > 12 ||
                    abs(((a shr 8) and 255) - ((b shr 8) and 255)) > 12 ||
                    abs(((a shr 16) and 255) - ((b shr 16) and 255)) > 12) changed++
            }
            val fraction = changed.toDouble() / (actual.width * actual.height)
            assertTrue("${c.name}: ${(fraction * 100)}% pixels changed; review the candidate before changing a baseline", fraction <= 0.01)
        }
        assertTrue("Review and check in missing golden candidates: $missing", missing.isEmpty())
    }
}
