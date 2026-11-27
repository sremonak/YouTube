package com.vidora.app.ui

import com.vidora.app.ui.theme.GlassBottomSheet
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.Audiotrack
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vidora.app.Graph
import com.vidora.app.data.StreamDetails
import com.vidora.app.data.VideoItem
import com.vidora.app.download.DownloadChoice
import com.vidora.app.download.Downloads
import com.vidora.app.ui.components.Thumbnail
import com.vidora.app.util.Format
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Bottom sheet that lets the user choose Video/Audio and the exact quality to download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(video: VideoItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var details by remember { mutableStateOf<StreamDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<DownloadChoice?>(null) }

    LaunchedEffect(video.url) {
        runCatching { Graph.repository.stream(video.url) }
            .onSuccess { d ->
                val settings = Graph.settings.flow.first()
                if (!settings.askDownloadQuality) {
                    Downloads.defaultChoice(d, audioOnly = false)?.let {
                        Downloads.enqueue(context, d.item, it)
                        Toast.makeText(context, "Download started · ${it.label}", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                    return@onSuccess
                }
                details = d
                selected = Downloads.videoChoices(d).firstOrNull { it.video.height <= settings.downloadQuality }
                    ?: Downloads.videoChoices(d).firstOrNull()
            }
            .onFailure { error = it.message ?: "Could not load streams" }
    }

    GlassBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(video, Modifier.width(112.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_download), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            val d = details
            if (com.vidora.app.data.GuestSources.supported(video.url)) {
                Text("Public viewing does not mean unrestricted reuse. Check creator/licence in the video description. Downloads retain an attribution text file in Download/Vidora or your chosen folder.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
            when {
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 24.dp))
                d == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val videoChoices = remember(d) { Downloads.videoChoices(d) }
                    val audioChoices = remember(d) { Downloads.audioChoices(d) }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = tab == 0, onClick = { tab = 0; selected = videoChoices.firstOrNull() },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = { Icon(Icons.Rounded.Movie, null, Modifier.size(18.dp)) }
                        ) { Text(stringResource(R.string.ui_video)) }
                        SegmentedButton(
                            selected = tab == 1, onClick = { tab = 1; selected = audioChoices.firstOrNull() },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = { Icon(Icons.Rounded.Audiotrack, null, Modifier.size(18.dp)) }
                        ) { Text(stringResource(R.string.ui_audio_only)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    val list: List<DownloadChoice> = if (tab == 0) videoChoices else audioChoices
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.ui_no_downloadable_streams_found), modifier = Modifier.padding(24.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(list) { choice ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = choice }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected == choice, onClick = { selected = choice })
                                Column(Modifier.weight(1f)) {
                                    Text(choice.label, style = MaterialTheme.typography.titleSmall)
                                    Text(choice.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("≈ " + Format.bytes(choice.approxBytes), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val choice = selected ?: return@Button
                            scope.launch {
                                Downloads.enqueue(context, d.item, choice)
                                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        },
                        enabled = selected != null,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download " + (selected?.label ?: ""))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
