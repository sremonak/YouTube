package com.vidora.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.vidora.app.ui.Paged
import com.vidora.app.ui.theme.VidoraSpace

/** A compact summary by default; technical/provider details remain available on demand. */
@Composable
fun ProviderStatus(paged: Paged) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val issueCount = paged.retries.size
    val hasDetails = paged.notices.isNotEmpty() || paged.error != null || issueCount > 0
    Column(Modifier.fillMaxWidth().padding(horizontal = VidoraSpace.page, vertical = VidoraSpace.xs)) {
        val summary = when {
            paged.fromDisk -> "Saved feed · checking for updates"
            paged.refreshMessage != null -> paged.refreshMessage!!
            paged.items.isNotEmpty() && paged.error != null -> "Offline or unavailable · saved results kept"
            issueCount > 0 -> "$issueCount source(s) unavailable · other results remain"
            else -> null
        }
        if (summary != null) Text(summary, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (hasDetails) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide source details" else "Source details & retry") }
        if (expanded) {
            if (paged.notices.isNotEmpty()) Text(paged.notices.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            if (paged.error != null) Text(paged.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
            paged.retries.forEach { retry -> TextButton(enabled = !paged.loading && !paged.refreshing, onClick = { paged.retrySource(retry) }) { Text("Retry ${retry.label}") } }
        }
    }
}
