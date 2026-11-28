package com.vidora.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vidora.app.data.CollectionLength
import com.vidora.app.data.CollectionOrder

@Composable
fun ChoiceMenu(label: String, choices: List<String>, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { index, title ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onChoose(index) })
            }
        }
    }
}

@Composable
fun CollectionControls(query: String, onQuery: (String) -> Unit, order: CollectionOrder,
                       onOrder: (CollectionOrder) -> Unit, length: CollectionLength,
                       onLength: (CollectionLength) -> Unit, count: Int) {
    Column {
        OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Search this collection") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
            item { ChoiceMenu(order.label, CollectionOrder.entries.map { it.label }) { onOrder(CollectionOrder.entries[it]) } }
            item { ChoiceMenu(length.label, CollectionLength.entries.map { it.label }) { onLength(CollectionLength.entries[it]) } }
            if (query.isNotBlank() || order != CollectionOrder.SAVED || length != CollectionLength.ALL) item {
                TextButton(onClick = { onQuery(""); onOrder(CollectionOrder.SAVED); onLength(CollectionLength.ALL) }) { Text("Reset filters") }
            }
        }
        Text("$count matching videos", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}
