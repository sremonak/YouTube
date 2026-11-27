package com.vidora.app.ui.components

import com.vidora.app.ui.theme.GlassAlertDialog
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vidora.app.Graph
import com.vidora.app.util.CrashReporter
import com.vidora.app.util.UpdateInfo
import com.vidora.app.util.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vidora.app.util.UpdatePromptPolicy

/**
 * Shown on launch: the first-run welcome, a crash report from the previous session, then a
 * floating "update available" card (Update / Later).
 */
@Composable
fun StartupDialogs(onSignIn: () -> Unit = {}, showUpdates: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings by Graph.settings.flow.collectAsState(initial = null)
    val available by Updater.available.collectAsState()
    val requested by Updater.promptRequest.collectAsState()
    val prefs = remember(context) { context.getSharedPreferences("updater", Context.MODE_PRIVATE) }
    var welcome by rememberSaveable { mutableStateOf(shouldShowWelcome(context)) }
    var crash by remember { mutableStateOf(CrashReporter.pending(context)) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var lastSuccess by remember { mutableStateOf<Long?>(null) }
    var lastAttempt by remember { mutableStateOf<Long?>(null) }

    fun offerAutomatic(info: UpdateInfo?) {
        if (info == null || settings?.autoUpdateCheck != true) return
        if (Updater.state.value is com.vidora.app.util.UpdateState.Installing) return
        if (UpdatePromptPolicy.shouldPrompt(
                info.version, prefs.getString("later_version", null), prefs.getLong("later_until", 0), System.currentTimeMillis(),
            )) update = info
    }

    // Result discovery is shared with the worker and manual checks. Never throw away a known
    // update merely because the last network check was recent (the old 30-minute early return).
    LaunchedEffect(available, settings?.autoUpdateCheck) { offerAutomatic(available) }
    LaunchedEffect(requested) {
        requested?.let {
            update = it // Explicit user requests bypass Later and auto-check being off.
            Updater.consumePromptRequest()
        }
    }

    // Check again after returning to the app, and while it stays open. Offline failures retry
    // after 30 seconds; only SUCCESSFUL checks consume the five-minute throttle. No old
    // persisted `last_check` / `skipped` flags can silently hide a new release anymore.
    LaunchedEffect(lifecycle, settings?.autoUpdateCheck) {
        if (settings?.autoUpdateCheck != true) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(1500)
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                val busy = Updater.state.value is com.vidora.app.util.UpdateState.Downloading ||
                    Updater.state.value is com.vidora.app.util.UpdateState.Installing
                if (!busy && UpdatePromptPolicy.shouldCheck(now, lastSuccess, lastAttempt)) {
                    lastAttempt = now
                    try {
                        Updater.check()
                        lastSuccess = android.os.SystemClock.elapsedRealtime()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Offline / rate limit: preserve discovery and retry instead of marking success.
                    }
                }
                offerAutomatic(Updater.available.value)
                delay(UpdatePromptPolicy.RETRY_INTERVAL_MS)
            }
        }
    }

    if (welcome) {
        WelcomeCard(onSignIn = { welcome = false; onSignIn() }, onDone = { welcome = false })
        return
    }
    crash?.let { report ->
        CrashDialog(report) { CrashReporter.clear(context); crash = null }
        return
    }
    if (showUpdates) update?.let { info ->
        UpdateCardNotice(info) {
            prefs.edit().putString("later_version", info.version)
                .putLong("later_until", System.currentTimeMillis() + UpdatePromptPolicy.SNOOZE_MS).apply()
            update = null
        }
    }
}

@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_vidora_closed_unexpectedly)) },
        text = {
            Column {
                Text(stringResource(R.string.ui_sharing_this_report_helps_fix_the_proble))
                Spacer(Modifier.height(8.dp))
                Text(
                    report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vidora crash", report))
                Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Copy report") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Vidora crash", report))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.ui_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_dismiss)) }
            }
        }
    )
}

@Composable
fun UpdateDialog(info: UpdateInfo, @Suppress("UNUSED_PARAMETER") onSkip: (() -> Unit)? = null, onDismiss: () -> Unit) {
    UpdateCardNotice(info, onLater = onDismiss)
}
