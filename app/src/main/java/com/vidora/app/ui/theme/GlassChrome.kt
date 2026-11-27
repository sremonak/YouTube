package com.vidora.app.ui.theme

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/** Height of the glass header that content scrolls under (0 when glass is off / no header). */
val LocalTopChrome = staticCompositionLocalOf { 0.dp }

/** Top list padding so the first items start below the glass header but can scroll under it. */
@Composable
fun topChromePadding(extra: Dp = 0.dp): Dp = LocalTopChrome.current + extra

/** Transparent app-bar colours on glass (the glass header draws the background), solid otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun glassBarColors(): TopAppBarColors {
    val glass = LocalGlass.current.enabled
    val bg = if (glass) Color.Transparent else MaterialTheme.colorScheme.background
    return TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg)
}

/**
 * iOS-style page: [header] (app bar, chips…) floats as frosted glass over [content], which
 * scrolls underneath it. Content reads the header height through [topChromePadding] /
 * [LocalTopChrome] and should use it as the top content padding of its main list.
 *
 * Content is captured into its own glass source so the header blurs what scrolls behind it
 * (Android 12+; older phones get a frosted tint). With glass off this is a plain Column.
 */
@Composable
fun GlassPage(
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val prefs = LocalGlass.current
    if (!prefs.enabled) {
        Column(modifier.fillMaxSize()) {
            header()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalTopChrome provides 0.dp) { content() }
            }
        }
        return
    }
    val state = rememberGlassState()
    val bg = MaterialTheme.colorScheme.background
    val hairline = if (bg.luminance() < 0.5f) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val density = LocalDensity.current
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val loose = constraints.copy(minWidth = constraints.maxWidth, minHeight = 0)
        val headerPlaceables = subcompose("header") {
            CompositionLocalProvider(LocalGlass provides GlassPrefs(true, state)) {
                Column(
                    Modifier.fillMaxWidth()
                        .glass(RectangleShape, tint = bg, border = false, strength = 1f)
                        .drawBehind {
                            drawLine(hairline, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                        }
                ) {
                    CompositionLocalProvider(LocalGlass provides GlassPrefs(true, null)) { header() }
                }
            }
        }.map { it.measure(loose) }
        val headerHeight = headerPlaceables.maxOfOrNull { it.height } ?: 0
        val topDp = with(density) { headerHeight.toDp() }
        val contentPlaceables = subcompose("content") {
            Box(Modifier.fillMaxSize().glassSource(state, true).background(bg)) {
                CompositionLocalProvider(
                    LocalGlass provides GlassPrefs(true, null),
                    LocalTopChrome provides topDp,
                ) { content() }
            }
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.place(0, 0) }
            headerPlaceables.forEach { it.place(0, 0) }
        }
    }
}

/** Spacer for non-list content placed directly in a [GlassPage]. */
@Composable
fun TopChromeSpacer() {
    androidx.compose.foundation.layout.Spacer(Modifier.height(topChromePadding()))
}

/** Pull-to-refresh whose spinner appears below the glass header instead of behind it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val top = topChromePadding()
    PullToRefreshBox(
        isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier, state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state, isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
            )
        },
        content = content,
    )
}

/**
 * Blurs everything behind the dialog / bottom-sheet window (Android 12+ with cross-window
 * blur support) — gives sheets and dialogs the iOS frosted look. Call inside the dialog content.
 */
@Composable
fun GlassWindowBlur(radius: Dp = 28.dp) {
    if (!LocalGlass.current.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view) {
        runCatching {
            val window = findDialogWindow(view)
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = px }
            }
        }
        onDispose { }
    }
}

private fun findDialogWindow(view: View): android.view.Window? {
    var v: Any? = view
    while (v != null) {
        if (v is DialogWindowProvider) return v.window
        v = (v as? View)?.parent
    }
    return null
}

/** Translucent sheet/dialog fill used with [GlassWindowBlur]. */
@Composable
fun glassContainerColor(solid: Color = MaterialTheme.colorScheme.surfaceContainerLow): Color =
    if (LocalGlass.current.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) solid.copy(alpha = 0.80f) else solid

@Composable
private fun glassEdge(): Brush {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))
        else listOf(Color.White.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.05f))
    )
}

/** Drop-in [ModalBottomSheet] with frosted glass (window blur + translucent fill + light edge). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current.enabled
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = if (glass) modifier.border(0.7.dp, glassEdge(), shape) else modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor ?: glassContainerColor(),
        tonalElevation = if (glass) 0.dp else androidx.compose.material3.BottomSheetDefaults.Elevation,
        scrimColor = if (glass) Color.Black.copy(alpha = 0.22f) else androidx.compose.material3.BottomSheetDefaults.ScrimColor,
    ) {
        GlassWindowBlur()
        content()
    }
}

/** Drop-in [AlertDialog] with frosted glass. */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val glass = LocalGlass.current.enabled
    val shape = RoundedCornerShape(28.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { GlassWindowBlur(); confirmButton() },
        modifier = if (glass) modifier.border(0.7.dp, glassEdge(), shape) else modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = glassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        tonalElevation = if (glass) 0.dp else androidx.compose.material3.AlertDialogDefaults.TonalElevation,
    )
}

/** Card with a frosted fill and light edge (frosted tint inside scrolling content). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current.enabled
    val base = tint ?: MaterialTheme.colorScheme.surfaceContainer
    Column(
        if (glass) modifier.glass(shape, tint = base, fallbackAlpha = 0.72f)
        else modifier.background(base, shape),
        content = content,
    )
}
