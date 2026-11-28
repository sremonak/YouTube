package com.vidora.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
