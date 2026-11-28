package com.vidora.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vidora.app.Graph
import com.vidora.app.player.PlayerUiState
import com.vidora.app.player.StudyTools
import com.vidora.app.ui.theme.*
import com.vidora.app.util.Format
import kotlinx.coroutines.delay

private fun time(ms: Long) = if (ms < 0) "Not set" else Format.duration(ms / 1000).ifBlank { "0:00" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionRepeatSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    var position by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.current?.url) { while (true) { position = Graph.player.exoPlayer.currentPosition; delay(250) } }
    GlassBottomSheet(onDismissRequest = onDismiss) {
        SheetHeading("A–B repeat", "Practice a section without replaying the whole video")
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("Now ${time(position)} · A ${time(state.sectionStartMs)} · B ${time(state.sectionEndMs)}")
            Text("Choose A, let the video play or seek ahead, then choose B at least 1 second later. Seeking outside a completed section stops A–B repeat.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            Row {
                TextButton(enabled = state.current != null && !state.loading && !state.restored && state.current?.isLive == false,
                    onClick = { if (Graph.player.setSectionStart()) error = null }) { Text("Set A here") }
                TextButton(enabled = state.sectionStartMs >= 0, onClick = {
                    error = if (Graph.player.setSectionEnd()) null else "B must be at least 1 second after A."
                }) { Text("Set B here") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(enabled = StudyTools.validSection(state.sectionStartMs, state.sectionEndMs), onClick = {
                    Graph.player.seekTo(state.sectionStartMs); Graph.player.exoPlayer.play()
                }) { Text("Play section") }
                TextButton(enabled = state.sectionStartMs >= 0, onClick = { Graph.player.clearSectionRepeat(); error = null }) { Text("Clear section") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun CustomSleepDialog(onDismiss: () -> Unit, onStart: (Int) -> Unit) {
    var input by remember { mutableStateOf("") }
    val minutes = StudyTools.customMinutes(input)
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text("Custom sleep timer") },
        text = { Column {
            OutlinedTextField(input, { input = it.take(4) }, label = { Text("Minutes (1–240)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (input.isNotBlank() && minutes == null) Text("Enter a whole number from 1 to 240.", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = minutes != null, onClick = { minutes?.let(onStart) }) { Text("Start timer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun MomentNoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf(initial) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text("Study note") },
        text = { Column {
            OutlinedTextField(note, { note = it.take(4000) }, label = { Text("Notes for this moment") }, minLines = 3, maxLines = 7)
            Text("${note.length}/4000 · local, included in library backup", style = MaterialTheme.typography.labelSmall)
        } },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Save note") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
