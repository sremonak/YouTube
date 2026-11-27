package com.vidora.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vidora.app.BuildConfig
import com.vidora.app.ui.theme.GlassAlertDialog

/** Local legal information; no browser navigation or remote licence dependency. */
@Composable
fun LegalDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember {
        buildString {
            appendLine("Vidora ${BuildConfig.VERSION_NAME} — GPL-3.0")
            appendLine("Copyright Vidora contributors. No warranty. You may obtain, study, modify and redistribute the corresponding source under GPLv3.")
            appendLine("Corresponding source, build instructions and licence: https://github.com/sremonak/YouTube (release tag v${BuildConfig.VERSION_NAME})")
            appendLine("Release authenticity checks identify this distribution, not a restriction on GPL source rebuilds.")
            appendLine()
            appendLine("NewPipe Extractor — GPLv3, Team NewPipe contributors.")
            appendLine("AndroidX / Media3 / Compose — The Android Open Source Project, Apache-2.0.")
            appendLine("OkHttp / Okio — Square contributors; Coil — Coil contributors. Apache-2.0.")
            appendLine("Framework7 Icons — Copyright Vladimir Kharlampidi, MIT.")
            appendLine("Simple Icons brand marks — CC0 1.0. Brand trademarks belong to their respective owners.")
            appendLine()
            for (file in listOf("GPL-3.0.txt", "Apache-2.0.txt", "Framework7-MIT.txt")) {
                appendLine(context.assets.open("legal/$file").bufferedReader().use { it.readText() })
                appendLine()
            }
            appendLine(context.assets.open("ejs/LICENSE.txt").bufferedReader().use { it.readText() })
        }
    }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Licences & source information") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(notices, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
