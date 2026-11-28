package com.vidora.app.ui.icons

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which icon set the app draws. Backed by snapshot state, so every `Icon(Icons.Rounded.X)` that
 * read it recomposes when the user flips the "iPhone-style icons" setting.
 */
object IconStyle {
    var ios by mutableStateOf(true)
}
