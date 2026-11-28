package com.vidora.app.ui.theme

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Liquid glass" UI without extra libraries.
 *
 * The screen content draws itself into a [GraphicsLayer] ([glassSource]); glass surfaces
 * ([glass]) redraw the part of that layer that lies behind them through a GPU blur
 * (RenderEffect, Android 12+), then add a tint, a top highlight and a hairline edge.
 * Below Android 12 the same surfaces fall back to a translucent frosted tint.
 *
 * Glass surfaces must live *outside* the source (bottom bar, mini player) — otherwise they
 * would try to draw themselves.
 */
@Stable
class GlassState internal constructor(internal val layer: GraphicsLayer) {
    internal var sourceOffset = Offset.Zero
    /** Bumped every time the source redraws so glass surfaces re-blur the fresh content. */
    internal var generation by mutableIntStateOf(0)
}

@Composable
fun rememberGlassState(): GlassState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassState(layer) }
}

data class GlassPrefs(val enabled: Boolean, val state: GlassState?)

val LocalGlass = staticCompositionLocalOf { GlassPrefs(false, null) }

val glassBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Records this node's content so glass surfaces elsewhere can blur it. */
fun Modifier.glassSource(state: GlassState, enabled: Boolean): Modifier =
    if (!enabled || !glassBlurSupported) this
    else this
        .onGloballyPositioned { state.sourceOffset = it.positionInRoot() }
        .drawWithContent {
            state.layer.record { this@drawWithContent.drawContent() }
            drawLayer(state.layer)
            // Write without observing, or the source would invalidate itself in a loop.
            Snapshot.withoutReadObservation { state.generation++ }
        }

/**
 * Frosted-glass background for bars, pills and cards.
 * @param tint base colour (defaults to the theme surface); its alpha is managed here.
 * @param blur blur radius of what's behind.
 */
fun Modifier.glass(
    shape: Shape,
    tint: Color? = null,
    blur: Dp = 18.dp,
    border: Boolean = true,
    strength: Float = 1f,
    fallbackAlpha: Float = 0.94f,
): Modifier = composed {
    val prefs = LocalGlass.current
    val base = tint ?: MaterialTheme.colorScheme.surface
    val dark = base.luminance() < 0.5f
    val state = prefs.state
    val canBlur = prefs.enabled && state != null && glassBlurSupported
    val blurLayer = rememberGraphicsLayer()
    val pos = remember { FloatArray(2) }
    val blurPx = with(LocalDensity.current) { blur.toPx() }
    val fillAlpha = when {
        !prefs.enabled -> 1f
        // Opaque enough that text scrolling underneath never competes with the bar's own
        // text, even on phones where the backdrop blur is unavailable or switched off.
        canBlur -> (if (dark) 0.78f else 0.82f) * strength + (1 - strength) * 0.3f
        else -> fallbackAlpha
    }
    val highlight = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.55f)
    val edgeTop = if (dark) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.9f)
    val edgeBottom = if (dark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.06f)

    this
        .onGloballyPositioned { val p = it.positionInRoot(); pos[0] = p.x; pos[1] = p.y }
        .clip(shape)
        .drawWithContent {
            if (canBlur) {
                @Suppress("UNUSED_VARIABLE") val tick = state!!.generation // re-blur when the content changes
                blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                blurLayer.record {
                    translate(state.sourceOffset.x - pos[0], state.sourceOffset.y - pos[1]) { drawLayer(state.layer) }
                }
                drawLayer(blurLayer)
            }
            drawRect(base.copy(alpha = fillAlpha))
            if (prefs.enabled) drawRect(Brush.verticalGradient(listOf(highlight, Color.Transparent), endY = size.height * 0.6f))
            drawContent()
        }
        .then(
            if (border && prefs.enabled) Modifier.border(0.7.dp, Brush.verticalGradient(listOf(edgeTop, edgeBottom)), shape)
            else Modifier
        )
}

/**
 * Glass for controls floating over video or artwork (no backdrop capture is possible over a
 * SurfaceView): dark smoked tint, highlight and edge — the look of iOS / visionOS player pills.
 */
fun Modifier.glassOverlay(shape: Shape, alpha: Float = 0.38f): Modifier = this
    .clip(shape)
    .drawWithContent {
        drawRect(Color.Black.copy(alpha = alpha))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.03f))))
        drawContent()
    }
    .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f))), shape)

/** Height of the floating bottom chrome (mini player + navigation bar) that content scrolls under. */
val LocalBottomChrome = staticCompositionLocalOf { 0.dp }

/** Bottom list padding so the last items can scroll clear of the glass bars. */
@Composable
fun bottomChromePadding(extra: Dp = 24.dp): Dp = LocalBottomChrome.current + extra
