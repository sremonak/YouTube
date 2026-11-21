package com.vidora.app.ui.components

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

/** Shown once per launch: crash report from the previous session, then an available update. */
@Composable
fun StartupDialogs() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashReporter.pending(context)) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        delay(2500) // let the first feed load first
        if (!Graph.settingsState.value.autoUpdateCheck) return@LaunchedEffect
        val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0)
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return@LaunchedEffect
        val info = runCatching { Updater.check() }.getOrNull()
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        if (info != null && prefs.getString("skipped", null) != info.version) update = info
    }

    crash?.let { report ->
        CrashDialog(report) { CrashReporter.clear(context); crash = null }
        return
    }
    update?.let { info ->
        UpdateDialog(info, onSkip = {
            context.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putString("skipped", info.version).apply()
            update = null
        }) { update = null }
    }
}

@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
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
                val title = "Crash: " + report.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }.orEmpty().take(90)
                val url = "https://github.com/${Updater.REPO}/issues/new?title=" + Uri.encode(title) +
                    "&body=" + Uri.encode("```\n" + report.take(6000) + "\n```")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                onDismiss()
            }) { Text(stringResource(R.string.ui_report_on_github)) }
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
fun UpdateDialog(info: UpdateInfo, onSkip: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Update available · ${info.version}") },
        text = {
            Column {
                Text(
                    info.notes.ifBlank { "A new version of Vidora is available." }.take(1500),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                )
                progress?.let {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                if (info.apkUrl == null) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl))); onDismiss(); return@TextButton
                }
                if (!Updater.canInstall(context)) {
                    Toast.makeText(context, "Allow Vidora to install updates, then tap Update again", Toast.LENGTH_LONG).show()
                    Updater.openInstallPermission(context); return@TextButton
                }
                error = null
                progress = 0f
                scope.launch {
                    runCatching { Updater.download(context, info) { p -> progress = p } }
                        .onSuccess { apk -> progress = null; Updater.install(context, apk); onDismiss() }
                        .onFailure { progress = null; error = it.message ?: "Download failed" }
                }
            }) { Text(if (progress != null) "Downloading…" else "Update") }
        },
        dismissButton = {
            if (progress == null) {
                Column {
                    if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.ui_skip_this_version)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_later)) }
                }
            }
        }
    )
}
