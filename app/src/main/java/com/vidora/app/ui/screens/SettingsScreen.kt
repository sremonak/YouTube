package com.vidora.app.ui.screens

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.vidora.app.BuildConfig
import com.vidora.app.Graph
import com.vidora.app.data.AccentPalette
import com.vidora.app.data.AppSettings
import com.vidora.app.data.AudioPreference
import com.vidora.app.data.FeedLayout
import com.vidora.app.data.SponsorCategories
import com.vidora.app.data.ThemeMode
import com.vidora.app.ui.Routes
import com.vidora.app.ui.components.Avatar
import com.vidora.app.util.IconManager
import com.vidora.app.util.LauncherCatalog
import kotlinx.coroutines.launch

private val QualityOptions = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
private val Countries = listOf("" to "Automatic", "US" to "United States", "GB" to "United Kingdom", "IN" to "India",
    "BD" to "Bangladesh", "CA" to "Canada", "AU" to "Australia", "DE" to "Germany", "FR" to "France", "JP" to "Japan",
    "BR" to "Brazil", "PK" to "Pakistan", "AE" to "UAE", "SA" to "Saudi Arabia", "ID" to "Indonesia")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavHostController, s: AppSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun update(block: (AppSettings) -> AppSettings) = scope.launch { Graph.settings.update(block) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val account by Graph.account.session.collectAsState()
    var importing by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(Graph.library.exportJson().toByteArray()) } }
            .onSuccess { Toast.makeText(context, "Library exported", Toast.LENGTH_SHORT).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { Graph.library.importJson(it.readBytes().decodeToString()) } ?: false
        }.getOrDefault(false)
        Toast.makeText(context, if (ok) "Library restored" else "Invalid backup file", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.fillMaxWidth()) {
            // ------------------------------------------------------------ account
            item { Header("Account") }
            item {
                val session = account
                if (session == null) {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.AccountCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("Sign in with YouTube") },
                        supportingContent = { Text("Removes sign-in restrictions (age-restricted, \"not a bot\" checks) and adds your recommendations, subscriptions, likes & history") },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                        modifier = Modifier.clickable { nav.navigate(Routes.LOGIN) }
                    )
                } else {
                    ListItem(
                        leadingContent = {
                            if (session.avatar != null) Avatar(session.avatar, 40)
                            else Icon(Icons.Rounded.AccountCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        },
                        headlineContent = { Text(session.name ?: "Signed in") },
                        supportingContent = { Text(session.handle ?: "YouTube account connected") },
                        trailingContent = { TextButton(onClick = { dialog = "signout" }) { Text("Sign out") } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            }
            if (account != null) {
                item {
                    ClickRow("Your YouTube library", "Recommended · Subscriptions feed · Liked · Watch later · History") {
                        nav.navigate(Routes.LIBRARY) { launchSingleTop = true }
                    }
                }
                item {
                    ClickRow(
                        if (importing) "Importing subscriptions…" else "Import YouTube subscriptions",
                        "Copy the channels you follow into Vidora's subscriptions"
                    ) {
                        if (importing) return@ClickRow
                        importing = true
                        scope.launch {
                            val result = runCatching { Graph.library.importSubscriptions(Graph.accountApi.subscriptions()) }
                            importing = false
                            Toast.makeText(
                                context,
                                result.fold({ "Imported $it new channels" }, { "Import failed: ${it.message}" }),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                item {
                    SwitchRow("Use account for restricted videos", "Retry age-restricted / blocked videos with your account", s.useAccountForRestricted) { v ->
                        update { it.copy(useAccountForRestricted = v) }
                    }
                }
                item {
                    ClickRow("Refresh account info", "Update name and picture") {
                        scope.launch {
                            runCatching { Graph.accountApi.accountInfo() }
                                .onSuccess { it?.let(Graph.account::save); Toast.makeText(context, "Account updated", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
            // ------------------------------------------------------------ appearance
            item { Header("Appearance") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(selected = s.themeMode == m, onClick = { update { it.copy(themeMode = m) } }, label = { Text(m.label) })
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item { SwitchRow("Material You colours", "Match colours to your wallpaper", s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } } }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Accent colour", style = MaterialTheme.typography.titleSmall)
                    if (s.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Text("Turn off Material You to use a custom accent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccentPalette.forEachIndexed { i, c ->
                            val selected = s.accentIndex == i
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                                    .clickable { update { it.copy(accentIndex = i, dynamicColor = false) } },
                                contentAlignment = Alignment.Center
                            ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White) }
                        }
                    }
                }
            }
            item { ClickRow("App display name", s.displayName) { dialog = "name" } }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Feed layout", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedLayout.entries.forEach { l ->
                            FilterChip(selected = s.feedLayout == l, onClick = { update { it.copy(feedLayout = l) } }, label = { Text(l.label) })
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Corner roundness · ${s.cornerRadius} dp", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = s.cornerRadius.toFloat(), valueRange = 0f..28f, steps = 6,
                        onValueChange = { v -> update { it.copy(cornerRadius = v.toInt()) } }
                    )
                }
            }
            item { SwitchRow("Channel avatars in feed", null, s.showChannelAvatars) { v -> update { it.copy(showChannelAvatars = v) } } }

            // ------------------------------------------------------------ icon & name
            item { Header("App icon & launcher name") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LauncherCatalog.icons.forEach { icon ->
                            val selected = s.launcherIcon == icon.id
                            Column(
                                Modifier.width(72.dp).clickable {
                                    IconManager.apply(context, icon.id, s.launcherName)
                                    update { it.copy(launcherIcon = icon.id) }
                                    Toast.makeText(context, "Icon changed — your launcher may take a few seconds to update", Toast.LENGTH_SHORT).show()
                                },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.size(60.dp).clip(RoundedCornerShape(18.dp))
                                        .background(Brush.linearGradient(listOf(Color(icon.start), Color(icon.end))))
                                        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                                Text(icon.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Launcher name", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LauncherCatalog.names.forEach { n ->
                            FilterChip(selected = s.launcherName == n.id, onClick = {
                                IconManager.apply(context, s.launcherIcon, n.id)
                                update { it.copy(launcherName = n.id) }
                            }, label = { Text(n.label) })
                        }
                    }
                }
            }

            // ------------------------------------------------------------ player
            item { Header("Player") }
            item { ClickRow("Default video quality", "${s.defaultQuality}p") { dialog = "quality" } }
            item { ClickRow("Default playback speed", "${s.defaultSpeed}x") { dialog = "speed" } }
            item { SwitchRow("Background playback", "Keep playing when the app is closed or the screen is off", s.backgroundPlay) { v -> update { it.copy(backgroundPlay = v) } } }
            item { SwitchRow("Picture-in-picture", "Automatically shrink the video when you leave the app", s.autoPip) { v -> update { it.copy(autoPip = v) } } }
            item { SwitchRow("Autoplay next video", null, s.autoplayNext) { v -> update { it.copy(autoplayNext = v) } } }
            item { SwitchRow("Resume where you left off", null, s.resumePlayback) { v -> update { it.copy(resumePlayback = v) } } }
            item { SwitchRow("Start in audio-only mode", "Great for music and podcasts, saves data", s.audioOnlyDefault) { v -> update { it.copy(audioOnlyDefault = v) } } }
            item { SwitchRow("Skip silence", "Speeds through silent parts", s.skipSilence) { v -> update { it.copy(skipSilence = v) } } }

            // ------------------------------------------------------------ sponsorblock
            item { Header("SponsorBlock") }
            item { SwitchRow("Skip sponsored segments", "Community-submitted segments from sponsor.ajay.app", s.sponsorBlock) { v -> update { it.copy(sponsorBlock = v) } } }
            if (s.sponsorBlock) {
                item {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SponsorCategories.forEach { (id, label) ->
                            FilterChip(selected = id in s.sponsorCategories, onClick = {
                                update { st -> st.copy(sponsorCategories = if (id in st.sponsorCategories) st.sponsorCategories - id else st.sponsorCategories + id) }
                            }, label = { Text(label) })
                        }
                    }
                }
            }

            // ------------------------------------------------------------ downloads
            item { Header("Downloads") }
            item { SwitchRow("Ask quality every time", "Choose video/audio and quality before each download", s.askDownloadQuality) { v -> update { it.copy(askDownloadQuality = v) } } }
            item { ClickRow("Default download quality", "${s.downloadQuality}p") { dialog = "dlquality" } }
            item { ClickRow("Preferred audio format", s.downloadAudioPref.label) { dialog = "audiofmt" } }
            item { SwitchRow("Download on Wi-Fi only", null, s.downloadWifiOnly) { v -> update { it.copy(downloadWifiOnly = v) } } }

            // ------------------------------------------------------------ content & privacy
            item { Header("Content & privacy") }
            item { ClickRow("Content country", Countries.firstOrNull { it.first == s.contentCountry }?.second ?: s.contentCountry) { dialog = "country" } }
            item { SwitchRow("Hide Shorts", null, s.hideShorts) { v -> update { it.copy(hideShorts = v) } } }
            item { SwitchRow("Save watch history", "Stored only on this device", s.saveHistory) { v -> update { it.copy(saveHistory = v) } } }
            item { SwitchRow("Save search history", null, s.saveSearchHistory) { v -> update { it.copy(saveSearchHistory = v) } } }
            item { ClickRow("Clear search history", null) { Graph.library.clearSearchHistory(); Toast.makeText(context, "Search history cleared", Toast.LENGTH_SHORT).show() } }
            item { ClickRow("Clear watch history", null) { Graph.library.clearHistory(); Toast.makeText(context, "Watch history cleared", Toast.LENGTH_SHORT).show() } }
            item { ClickRow("Export library", "Back up subscriptions, likes, history") { exportLauncher.launch("vidora-backup.json") } }
            item { ClickRow("Import library", "Restore from a backup file") { importLauncher.launch(arrayOf("application/json", "*/*")) } }
            item { ClickRow("Reset all settings", null) { dialog = "reset" } }

            // ------------------------------------------------------------ about
            item { Header("About") }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${s.displayName} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Ad-free · background play · downloads · optional sign-in, no tracking. " +
                                "Powered by the open-source NewPipe Extractor (GPLv3), Media3/ExoPlayer and SponsorBlock.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        "signout" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Sign out?") },
            text = { Text("Your YouTube session will be removed from this device. Local library and downloads stay.") },
            confirmButton = { TextButton(onClick = { Graph.account.signOut(); dialog = null }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "name" -> TextDialog("App display name", s.displayName, onDismiss = { dialog = null }) { v -> update { it.copy(displayName = v.take(24)) } }
        "quality" -> ChoiceDialog("Default video quality", QualityOptions.map { it to "${it}p" }, s.defaultQuality, { dialog = null }) { v -> update { it.copy(defaultQuality = v) } }
        "dlquality" -> ChoiceDialog("Default download quality", QualityOptions.map { it to "${it}p" }, s.downloadQuality, { dialog = null }) { v -> update { it.copy(downloadQuality = v) } }
        "speed" -> ChoiceDialog("Default speed", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to "${it}x" }, s.defaultSpeed, { dialog = null }) { v ->
            update { it.copy(defaultSpeed = v) }; Graph.player.setSpeed(v)
        }
        "audiofmt" -> ChoiceDialog("Preferred audio format", AudioPreference.entries.map { it to it.label }, s.downloadAudioPref, { dialog = null }) { v -> update { it.copy(downloadAudioPref = v) } }
        "country" -> ChoiceDialog("Content country", Countries, s.contentCountry, { dialog = null }) { v ->
            update { it.copy(contentCountry = v) }
            Toast.makeText(context, "Restart the app to apply", Toast.LENGTH_SHORT).show()
        }
        "reset" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Reset all settings?") },
            text = { Text("Your library (history, likes, subscriptions) is kept.") },
            confirmButton = { TextButton(onClick = { scope.launch { Graph.settings.reset() }; dialog = null }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Header(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable { onChange(!checked) }
    )
}

@Composable
private fun ClickRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.widthIn(max = 420.dp)) {
                options.forEach { (value, label) ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(value); onDismiss() }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = value == selected, onClick = { onSelect(value); onDismiss() })
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { "Vidora" }); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
