package com.vidora.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.data.Entry
import com.vidora.app.data.SearchFilter
import com.vidora.app.ui.Paged
import com.vidora.app.ui.components.EntryGrid
import com.vidora.app.ui.components.VideoActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun SearchScreen(nav: NavHostController, actions: VideoActions, initialQuery: String) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var text by rememberSaveable { mutableStateOf(initialQuery) }
    var submitted by rememberSaveable { mutableStateOf(initialQuery) }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    var editing by remember { mutableStateOf(initialQuery.isBlank()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val library by Graph.library.data.collectAsState()
    val settings by Graph.settings.flow.collectAsState(initial = null)

    val paged = remember(submitted, filter) {
        Paged(scope) { Graph.repository.search(submitted, filter) }
    }
    LaunchedEffect(paged) { if (submitted.isNotBlank()) paged.load() }
    LaunchedEffect(Unit) { if (editing) focusRequester.requestFocus() }
    LaunchedEffect(text, editing) {
        if (!editing) return@LaunchedEffect
        delay(220)
        suggestions = Graph.repository.suggestions(text)
    }

    fun submit(q: String) {
        if (q.isBlank()) return
        text = q
        submitted = q
        editing = false
        focus.clearFocus()
        scope.launchSafe {
            if (Graph.settings.flow.first().saveSearchHistory) Graph.library.addSearch(q)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            TextField(
                value = text,
                onValueChange = { text = it; editing = true },
                placeholder = { Text("Search videos, channels, music…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = ""; editing = true; focusRequester.requestFocus() }) {
                        Icon(Icons.Rounded.Close, "Clear")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focusRequester)
            )
        }

        if (editing) {
            val history = if (text.isBlank()) library.searchHistory else library.searchHistory.filter { it.contains(text, true) }.take(4)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(history.distinct(), key = { "h$it" }) { q ->
                    SuggestionRow(q, isHistory = true, onClick = { submit(q) }, onFill = { text = q }, onRemove = { Graph.library.removeSearch(q) })
                }
                items(suggestions.distinct().filter { s -> history.none { it.equals(s, true) } }, key = { "s$it" }) { q ->
                    SuggestionRow(q, isHistory = false, onClick = { submit(q) }, onFill = { text = "$q " })
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchFilter.entries) { f ->
                    FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            EntryGrid(
                paged = paged, actions = actions,
                filter = { e -> !(settings?.hideShorts == true && e is Entry.Video && e.video.isShort) }
            )
        }
    }
}

@Composable
private fun SuggestionRow(text: String, isHistory: Boolean, onClick: () -> Unit, onFill: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (isHistory) Icons.Rounded.History else Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove") }
        IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, "Fill") }
    }
}
