package com.vidora.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vidora.app.ui.Paged

@Composable
fun ProviderStatus(paged: Paged) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (paged.fromDisk) Text("Saved feed · refreshing when online", style = MaterialTheme.typography.labelSmall)
        if (paged.notices.isNotEmpty()) Text(paged.notices.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (paged.items.isNotEmpty() && paged.error != null) Text(paged.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
        paged.retries.forEach { retry -> TextButton(enabled = !paged.loading, onClick = { paged.retrySource(retry) }) { Text("Retry ${retry.label}") } }
    }
}
