package com.vidora.app.ui

import kotlinx.coroutines.flow.MutableSharedFlow

/** Tapping the tab you're already on scrolls its list back to the top (like YouTube / iOS). */
object TabReselect {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 1)
}
