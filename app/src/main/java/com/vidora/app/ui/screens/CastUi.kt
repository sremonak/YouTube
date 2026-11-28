package com.vidora.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vidora.app.player.CastManager
import com.vidora.app.ui.icons.Cast
import com.vidora.app.ui.icons.CastConnected
import com.vidora.app.ui.icons.Forward10
import com.vidora.app.ui.icons.Pause
import com.vidora.app.ui.icons.PlayArrow
import com.vidora.app.ui.icons.Replay10
import com.vidora.app.ui.theme.GlassBottomSheet

/** Cast to a Chromecast / Google TV: device picker, then remote controls while casting. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CastSheet(onDismiss: () -> Unit) {
    val devices by CastManager.devices.collectAsState()
    val castingTo by CastManager.castingTo.collectAsState()
    val remote by CastManager.remote.collectAsState()
    DisposableEffect(Unit) {
        CastManager.startDiscovery()
        onDispose { CastManager.stopDiscovery() }
    }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(if (castingTo != null) "Casting to $castingTo" else "Cast to a TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (castingTo != null) {
                var scrub by remember { mutableStateOf<Float?>(null) }
                val dur = remote.durationMs.coerceAtLeast(1)
                Slider(
                    value = scrub ?: (remote.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { CastManager.seekTo((it * dur).toLong()) }; scrub = null },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatMs(remote.positionMs), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatMs(remote.durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { CastManager.seekBy(-10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
                    Spacer(Modifier.width(20.dp))
                    FilledTonalIconButton(onClick = { CastManager.togglePlay() }, modifier = Modifier.size(64.dp)) {
                        if (remote.buffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        else Icon(if (remote.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play / pause", Modifier.size(34.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                    IconButton(onClick = { CastManager.seekBy(10_000) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds") }
                }
                OutlinedButton(onClick = { CastManager.disconnect(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Stop casting · continue on phone") }
            } else {
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Looking for Chromecast / Google TV on this Wi-Fi…", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Your phone and the TV must be on the same Wi-Fi. Casting needs Google Play services.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { CastManager.select(d) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (d.selected) Icons.Rounded.CastConnected else Icons.Rounded.Cast, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge)
                            d.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Box(Modifier.height(4.dp))
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
