package com.vidora.app.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared dimensions: visual glyphs can be small, interactive targets cannot. */
object VidoraSpace {
    val xs = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val page = 16.dp
    val section = 24.dp
    val touch = 48.dp
    val sheetCorner = 24.dp
}
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Pick the higher contrast foreground, including very light custom accents. */
fun accentForeground(background: Color): Color {
    val light = 1.05f / (background.luminance() + 0.05f)
    val dark = (background.luminance() + 0.05f) / 0.05f
    return if (light >= dark) Color.White else Color.Black
}

@Composable
fun SheetHeading(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = VidoraSpace.section, vertical = VidoraSpace.medium)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
