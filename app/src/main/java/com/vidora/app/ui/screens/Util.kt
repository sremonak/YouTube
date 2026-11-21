package com.vidora.app.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun CoroutineScope.launchSafe(block: suspend CoroutineScope.() -> Unit) = launch {
    runCatching { block() }
}
