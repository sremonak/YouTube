package com.vidora.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.data.AppSettings
import com.vidora.app.data.ContentFilter
import kotlinx.coroutines.launch

/** Blocked channels, blocked keywords and other feed filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersScreen(nav: NavHostController) {
    val s by Graph.settingsState.collectAsState()
    val scope = rememberCoroutineScope()
    fun update(block: (AppSettings) -> AppSettings) = scope.launch { Graph.settings.update(block) }
    var keyword by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Content filters") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                ListItem(
                    headlineContent = { Text("Hide Shorts") },
                    trailingContent = { Switch(s.hideShorts, { v -> update { it.copy(hideShorts = v) } }) }
                )
                ListItem(
                    headlineContent = { Text("Hide watched videos") },
                    supportingContent = { Text("Videos you've watched at least 90% of") },
                    trailingContent = { Switch(s.hideWatched, { v -> update { it.copy(hideWatched = v) } }) }
                )
            }
            item {
                Text("Blocked keywords", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                Text("Videos whose titles contain these words are hidden everywhere.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(keyword, { keyword = it }, singleLine = true, label = { Text("Keyword") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(enabled = keyword.isNotBlank(), onClick = {
                        val k = keyword.trim(); keyword = ""
                        update { it.copy(blockedKeywords = it.blockedKeywords + k) }
                    }) { Text("Add") }
                }
            }
            items(s.blockedKeywords.sorted(), key = { "k:$it" }) { k ->
                ListItem(
                    headlineContent = { Text(k) },
                    trailingContent = { IconButton(onClick = { update { it.copy(blockedKeywords = it.blockedKeywords - k) } }) { Icon(Icons.Rounded.Close, "Remove") } }
                )
            }
            item {
                Text("Blocked channels", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                if (s.blockedChannels.isEmpty()) {
                    Text("Use “Don't recommend channel” in any video's ⋮ menu.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            items(s.blockedChannels.sortedBy { ContentFilter.channelName(it).lowercase() }, key = { "c:$it" }) { c ->
                ListItem(
                    headlineContent = { Text(ContentFilter.channelName(c).ifBlank { ContentFilter.channelUrl(c) }) },
                    supportingContent = { Text(ContentFilter.channelUrl(c), maxLines = 1) },
                    trailingContent = { IconButton(onClick = { update { it.copy(blockedChannels = it.blockedChannels - c) } }) { Icon(Icons.Rounded.Close, "Unblock") } }
                )
            }
        }
    }
}
