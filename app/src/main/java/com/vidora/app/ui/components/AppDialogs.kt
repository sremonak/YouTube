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

/**
 * Shown on launch: the first-run welcome, a crash report from the previous session, then a
 * floating "update available" card (Update / Later).
 */
@Composable
fun StartupDialogs(onSignIn: () -> Unit = {}) {
    val context = LocalContext.current
    var welcome by rememberSaveable { mutableStateOf(shouldShowWelcome(context)) }
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
        if (System.currentTimeMillis() - last < 30 * 60_000L) return@LaunchedEffect
        val info = runCatching { Updater.check() }.getOrNull()
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        if (info == null || prefs.getString("skipped", null) == info.version) return@LaunchedEffect
        Updater.state.value = com.vidora.app.util.UpdateState.Available(info)
        val s = Graph.settingsState.value
        if (s.autoInstallUpdates && info.apkUrl != null && Updater.canInstall(context)) {
            // The user opted into silent updates → download in the background and install.
            Toast.makeText(context, "Downloading Vidora ${info.version}…", Toast.LENGTH_SHORT).show()
            Graph.appScope.launch { Updater.downloadAndInstall(context.applicationContext, info) }
            return@LaunchedEffect
        }
        // "Later" hides the card for a few hours, then it comes back on the next launch.
        val laterVersion = prefs.getString("later_version", null)
        val laterUntil = prefs.getLong("later_until", 0)
        if (laterVersion == info.version && System.currentTimeMillis() < laterUntil) return@LaunchedEffect
        while (welcome) delay(500) // never stack on top of the welcome card
        update = info
    }

    if (welcome) {
        WelcomeCard(onSignIn = { welcome = false; onSignIn() }, onDone = { welcome = false })
        return
    }
    crash?.let { report ->
        CrashDialog(report) { CrashReporter.clear(context); crash = null }
        return
    }
    update?.let { info ->
        UpdateCardNotice(info) {
            context.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                .putString("later_version", info.version).putLong("later_until", System.currentTimeMillis() + 6 * 3600_000L).apply()
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
