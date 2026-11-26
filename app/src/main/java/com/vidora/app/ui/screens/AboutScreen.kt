package com.vidora.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.vidora.app.BuildConfig
import com.vidora.app.Graph
import com.vidora.app.data.AboutRepository
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.Refresh
import com.vidora.app.util.UpdateState
import com.vidora.app.util.Updater
import com.vidora.app.ui.theme.GlassCard
import com.vidora.app.ui.theme.GlassPage
import com.vidora.app.ui.theme.bottomChromePadding
import com.vidora.app.ui.theme.glassBarColors
import com.vidora.app.ui.theme.topChromePadding
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val about by Graph.about.state.collectAsState()
    val update by Updater.state.collectAsState()
    val settings by Graph.settings.flow.collectAsState(initial = null)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch { error = Graph.about.refresh(); loading = false }
    }
    LaunchedEffect(Unit) { refresh() }
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    GlassPage(header = {
        TopAppBar(
            title = { Text("About") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") } },
            colors = glassBarColors(),
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topChromePadding(8.dp), bottom = bottomChromePadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ------------------------------------------------------------ app card
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = remember {
                            runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
                        }
                        if (icon != null) Image(icon, null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(settings?.displayName ?: "Vidora", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ------------------------------------------------------------ updates
            item { UpdateCard(update, settings?.autoInstallUpdates ?: true, onAutoInstall = { v -> scope.launch { Graph.settings.update { it.copy(autoInstallUpdates = v) } } }, open = ::open) }

            // ------------------------------------------------------------ live page
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Markdown(about.markdown, onLink = { open(it) })
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        val label = when (about.source) {
                            AboutRepository.Source.LIVE -> "Live from GitHub · updated just now"
                            AboutRepository.Source.CACHE -> "Saved copy · " + if (about.fetchedAt > 0) DateUtils.getRelativeTimeSpanString(about.fetchedAt) else "offline"
                            AboutRepository.Source.BUILT_IN -> "Built-in copy · connect to the internet for the live page"
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (error != null && about.source != AboutRepository.Source.LIVE) {
                            Text("Couldn't refresh: $error", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // ------------------------------------------------------------ links
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    LinkRow("Source code", "github.com/${Updater.REPO}") { open("https://github.com/${Updater.REPO}") }
                    LinkRow("All releases & changelog", "Every version with its notes") { open("https://github.com/${Updater.REPO}/releases") }
                    LinkRow("Edit this About page", "Opens ABOUT.md on GitHub (owner only)") { open(AboutRepository.editUrl()) }
                    LinkRow("Open-source licences", "NewPipe Extractor GPLv3 · Media3 Apache 2.0 · Framework7 Icons MIT") {
                        open("https://github.com/${Updater.REPO}#licences")
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(st: UpdateState, autoInstall: Boolean, onAutoInstall: (Boolean) -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    var showNotes by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val info = when (st) {
                is UpdateState.Available -> st.info
                is UpdateState.NeedsPermission -> st.info
                is UpdateState.Downloading -> st.info
                is UpdateState.Installing -> st.info
                is UpdateState.Failed -> st.info
                else -> null
            }
            val status = when (st) {
                UpdateState.Idle -> "Tap to check GitHub for a newer version."
                UpdateState.Checking -> "Checking…"
                is UpdateState.UpToDate -> "You're on the latest version."
                is UpdateState.Available -> "Version ${st.info.version} is available" + (if (st.info.sizeBytes > 0) " · ${st.info.sizeBytes / 1_048_576} MB" else "")
                is UpdateState.NeedsPermission -> "Allow Vidora to install apps, then tap Update again."
                is UpdateState.Downloading -> "Downloading ${st.info.version}… ${(st.progress * 100).toInt()}%"
                is UpdateState.Installing -> "Installing ${st.info.version}…"
                is UpdateState.Failed -> st.message
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (st is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (st is UpdateState.Downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    info != null && st !is UpdateState.Downloading && st !is UpdateState.Installing -> Button(onClick = {
                        if (info.apkUrl == null) { open(info.pageUrl); return@Button }
                        if (!Updater.canInstall(context)) {
                            Toast.makeText(context, "Allow Vidora to install updates, then come back", Toast.LENGTH_LONG).show()
                            Updater.state.value = UpdateState.NeedsPermission(info)
                            Updater.openInstallPermission(context); return@Button
                        }
                        Graph.appScope.launch { Updater.downloadAndInstall(context.applicationContext, info) }
                    }) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Update now") }
                    st !is UpdateState.Checking && st !is UpdateState.Downloading && st !is UpdateState.Installing -> OutlinedButton(onClick = {
                        Graph.appScope.launch { Updater.checkAndMaybeInstall(context.applicationContext, install = false) }
                    }) { Text("Check for updates") }
                }
                if (info != null && info.notes.isNotBlank()) TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "Hide changes" else "What's new") }
            }
            if (showNotes && info != null) {
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        .verticalScroll(rememberScrollState()).padding(12.dp)
                ) { Markdown(info.notes, onLink = open) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clickable { onAutoInstall(!autoInstall) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download & install automatically", style = MaterialTheme.typography.bodyLarge)
                    Text("New versions download in the background. Android 12+ installs them silently after the first in-app update.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(autoInstall, onAutoInstall)
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------------------ tiny markdown

/**
 * A small Markdown renderer for the live About page and release notes — enough for text written
 * on a phone: headings, bullets, numbered lists, quotes, dividers, images, link buttons and inline
 * **bold**, *italic*, `code` and [links](https://…).
 */
@Composable
fun Markdown(source: String, onLink: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val linkStyles = remember(primary) { TextLinkStyles(SpanStyle(color = primary, fontWeight = FontWeight.Medium)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in source.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            val t = line.trimStart()
            when {
                t.isEmpty() -> Spacer(Modifier.height(6.dp))
                t == "---" || t == "***" || t == "___" -> HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                t.startsWith("<!--") -> Unit
                t.startsWith("### ") -> Text(inline(t.drop(4), linkStyles), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                t.startsWith("## ") -> Text(inline(t.drop(3), linkStyles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                t.startsWith("# ") -> Text(inline(t.drop(2), linkStyles), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IMAGE.matches(t) -> {
                    val m = IMAGE.matchEntire(t)!!
                    AsyncImage(m.groupValues[2], m.groupValues[1], Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)))
                }
                LINK_LINE.matches(t) -> {
                    val m = LINK_LINE.matchEntire(t)!!
                    OutlinedButton(onClick = { onLink(m.groupValues[2]) }, modifier = Modifier.fillMaxWidth()) { Text(m.groupValues[1]) }
                }
                t.startsWith("> ") || t == ">" -> Row(Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.width(3.dp).height(20.dp).background(primary, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(inline(t.removePrefix(">").trim(), linkStyles), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BULLET.containsMatchIn(line) -> {
                    val m = BULLET.find(line)!!
                    val depth = (m.groupValues[1].length / 2).coerceAtMost(3)
                    Row(Modifier.padding(start = (depth * 16).dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = primary, modifier = Modifier.width(16.dp))
                        Text(inline(line.substring(m.range.last + 1), linkStyles), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                NUMBER.containsMatchIn(t) -> {
                    val m = NUMBER.find(t)!!
                    Row {
                        Text(m.groupValues[1] + ".", style = MaterialTheme.typography.bodyMedium, color = primary, modifier = Modifier.width(22.dp))
                        Text(inline(t.substring(m.range.last + 1), linkStyles), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text(inline(t, linkStyles), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val IMAGE = Regex("""!\[([^\]]*)]\((\S+?)\)""")
private val LINK_LINE = Regex("""\[([^\]]+)]\((https?://\S+?)\)""")
private val BULLET = Regex("""^(\s*)[-*+] +""")
private val NUMBER = Regex("""^(\d+)[.)] +""")
private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|_(.+?)_|`(.+?)`|\[([^\]]+)]\((https?://[^)\s]+)\)|(https?://[^\s)]+)""")

private fun inline(text: String, links: TextLinkStyles): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(i, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() || g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3].ifEmpty { g[4] }) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))) { append(g[5]) }
            g[6].isNotEmpty() -> withLink(LinkAnnotation.Url(g[7], links)) { append(g[6]) }
            g[8].isNotEmpty() -> withLink(LinkAnnotation.Url(g[8], links)) { append(g[8]) }
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}
