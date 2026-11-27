package com.vidora.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidora.app.data.AccentPalette
import com.vidora.app.data.AppSettings
import com.vidora.app.data.ThemeMode

/** User design preferences exposed to every composable. */
data class DesignPrefs(val corner: Int, val showAvatars: Boolean, val compact: Boolean)

val LocalDesign = staticCompositionLocalOf { DesignPrefs(16, true, false) }

/**
 * YouTube-style neutrals (#0F0F0F / #272727 / #AAAAAA in dark, white / #F2F2F2 / #606060 in light)
 * with the user's accent as primary (defaults to YouTube red).
 */
private fun schemeFromSeed(seed: Color, dark: Boolean, amoled: Boolean): ColorScheme {
    val secondary = lerp(seed, Color(0xFF8E7CC3), 0.45f)
    val tertiary = lerp(seed, Color(0xFF3EA6FF), 0.5f)
    return if (dark) {
        val bg = if (amoled) Color.Black else Color(0xFF0F0F0F)
        darkColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = lerp(seed, Color.Black, 0.55f),
            onPrimaryContainer = lerp(seed, Color.White, 0.8f),
            secondary = lerp(secondary, Color.White, 0.25f),
            secondaryContainer = Color(0xFF3F3F3F),
            onSecondaryContainer = Color(0xFFF1F1F1),
            tertiary = Color(0xFF3EA6FF),
            background = bg,
            onBackground = Color(0xFFF1F1F1),
            surface = bg,
            onSurface = Color(0xFFF1F1F1),
            surfaceVariant = if (amoled) Color(0xFF121212) else Color(0xFF272727),
            onSurfaceVariant = Color(0xFFAAAAAA),
            surfaceContainerLowest = bg,
            surfaceContainerLow = if (amoled) Color(0xFF070707) else Color(0xFF181818),
            surfaceContainer = if (amoled) Color(0xFF0C0C0C) else Color(0xFF212121),
            surfaceContainerHigh = if (amoled) Color(0xFF161616) else Color(0xFF272727),
            surfaceContainerHighest = if (amoled) Color(0xFF1F1F1F) else Color(0xFF303030),
            outline = Color(0xFF4D4D4D),
            outlineVariant = Color(0xFF303030),
            inverseSurface = Color(0xFFF1F1F1),
            inverseOnSurface = Color(0xFF0F0F0F),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = lerp(seed, Color.White, 0.82f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.6f),
            secondary = lerp(secondary, Color.Black, 0.15f),
            secondaryContainer = Color(0xFFE5E5E5),
            onSecondaryContainer = Color(0xFF0F0F0F),
            tertiary = Color(0xFF065FD4),
            background = Color.White,
            onBackground = Color(0xFF0F0F0F),
            surface = Color.White,
            onSurface = Color(0xFF0F0F0F),
            surfaceVariant = Color(0xFFF2F2F2),
            onSurfaceVariant = Color(0xFF606060),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF9F9F9),
            surfaceContainer = Color(0xFFF2F2F2),
            surfaceContainerHigh = Color(0xFFF2F2F2),
            surfaceContainerHighest = Color(0xFFE5E5E5),
            outline = Color(0xFFCCCCCC),
            outlineVariant = Color(0xFFE5E5E5),
            inverseSurface = Color(0xFF0F0F0F),
            inverseOnSurface = Color.White,
        )
    }
}

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val BrandTitle = TextStyle(fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = (-0.6).sp)

@Composable
fun isDarkTheme(settings: AppSettings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

@Composable
fun VidoraTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val amoled = settings.themeMode == ThemeMode.AMOLED
    val context = LocalContext.current
    var scheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        schemeFromSeed(Color(AccentPalette.getOrElse(settings.accentIndex) { AccentPalette[0] }), dark, amoled)
    }
    if (amoled) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF070707),
            surfaceContainer = Color(0xFF0C0C0C),
        )
    }
    val r = settings.cornerRadius
    val shapes = Shapes(
        extraSmall = RoundedCornerShape((r / 3).dp),
        small = RoundedCornerShape((r / 2).dp),
        medium = RoundedCornerShape(r.dp),
        large = RoundedCornerShape((r * 1.4f).dp),
        extraLarge = RoundedCornerShape((r * 1.8f).dp),
    )
    CompositionLocalProvider(
        LocalDesign provides DesignPrefs(r, settings.showChannelAvatars, settings.feedLayout.name == "LIST")
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = shapes) {
            // Text outside a Surface/Scaffold falls back to LocalContentColor (black by default),
            // which made the title/description under the video invisible in dark mode.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
