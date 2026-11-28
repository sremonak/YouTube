package com.vidora.app.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.heightIn

import com.vidora.app.ui.theme.GlassAlertDialog
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
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
import com.vidora.app.ui.icons.ArrowBack
import com.vidora.app.ui.icons.KeyboardArrowRight
import com.vidora.app.ui.icons.Search
import com.vidora.app.ui.icons.Close
import com.vidora.app.ui.icons.AutoAwesome
import com.vidora.app.ui.icons.Edit
import com.vidora.app.ui.icons.ClosedCaption
import com.vidora.app.ui.icons.ThumbUp
import com.vidora.app.ui.icons.NotificationsActive
import com.vidora.app.ui.icons.Shield
import com.vidora.app.ui.icons.Download
import com.vidora.app.ui.icons.Lock
import com.vidora.app.ui.icons.Settings
import com.vidora.app.ui.icons.Info
import com.vidora.app.ui.icons.AccountCircle
import com.vidora.app.ui.icons.Check
import com.vidora.app.ui.icons.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
private val Countries = listOf("" to "Automatic") + java.util.Locale.getISOCountries()
    .map { it to java.util.Locale("", it).getDisplayCountry(java.util.Locale.ENGLISH) }.sortedBy { it.second }


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavHostController, s: AppSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun update(block: (AppSettings) -> AppSettings) = scope.launch { Graph.settings.update(block) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val account by Graph.account.session.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var fileBusy by remember { mutableStateOf(false) }
    var fileJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var restoreTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    fun fileTask(success: String, work: () -> Unit) {
        if (fileBusy) return
        fileBusy = true
        fileJob = scope.launch {
            try {
                kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { work() }
                Toast.makeText(context, success, Toast.LENGTH_SHORT).show()
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { Toast.makeText(context, "File operation failed: ${failure.message ?: "Choose a readable JSON file"}", Toast.LENGTH_LONG).show() }
            finally { fileBusy = false }
        }
    }
    fun readBackup(uri: android.net.Uri): String = context.contentResolver.openInputStream(uri)?.use {
        com.vidora.app.data.BackupFiles.readText(it)
    } ?: error("Could not open this file")
    fun writeBackup(uri: android.net.Uri, text: String) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not write to this location")
        output.use { it.write(text.toByteArray()) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        fileTask("Library exported") { writeBackup(uri, Graph.library.exportJson()) }
    }
    val checkingUpdate by com.vidora.app.util.Updater.checking.collectAsState()
    var showEq by remember { mutableStateOf(false) }
    val subsImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        fileTask("Subscriptions imported") {
            val channels = com.vidora.app.data.SubscriptionImport.parse(readBackup(uri))
            require(channels.isNotEmpty()) { "No channels found in this file" }
            Graph.library.importSubscriptions(channels)
        }
    }
    val subsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        fileTask("Subscriptions exported") {
            writeBackup(uri, com.vidora.app.data.SubscriptionImport.exportNewPipe(Graph.library.data.value.subscriptions))
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onSuccess { update { it.copy(downloadFolder = uri.toString()) } }
            .onFailure { Toast.makeText(context, "Couldn't use that folder: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        restoreTarget = uri
    }

    if (restoreTarget != null) GlassAlertDialog(
        onDismissRequest = { restoreTarget = null }, title = { Text("Replace local library?") },
        text = { Text("Restore replaces this device's saved library. Export a backup first. Downloads are not deleted. Maximum file size: 8 MiB.") },
        dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            val uri = restoreTarget!!; restoreTarget = null
            fileTask("Library restored") { require(Graph.library.importJson(readBackup(uri))) { "Invalid backup file; library was not changed" } }
        }) { Text("Restore") } },
    )

    // Settings are grouped into categories (iOS-style) with a search box on top.
    var openName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val open = openName?.let { n -> Sec.entries.firstOrNull { it.name == n } }
    val q = query.trim()
    val labels = remember { Sec.entries.associateWith { context.getString(it.title) } }
    androidx.activity.compose.BackHandler(enabled = open != null || q.isNotEmpty()) {
        if (q.isNotEmpty()) query = "" else openName = null
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(openName) { listState.scrollToItem(0) }

    com.vidora.app.ui.theme.GlassPage(header = {
        TopAppBar(
            title = { Text(open?.let { labels[it] } ?: stringResource(R.string.settings)) },
            navigationIcon = {
                IconButton(onClick = { if (open != null) openName = null else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            },
            colors = com.vidora.app.ui.theme.glassBarColors()
        )
        if (fileBusy) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Working on your file…", modifier = Modifier.weight(1f))
                TextButton(onClick = { fileJob?.cancel() }) { Text("Cancel") }
            }
        }
        if (open == null) SettingsSearchField(query) { query = it }
    }) {
        LazyColumn(state = listState, contentPadding = PaddingValues(top = com.vidora.app.ui.theme.topChromePadding(), bottom = com.vidora.app.ui.theme.bottomChromePadding()), modifier = Modifier.fillMaxWidth()) {
            if (open == null && q.isEmpty()) {
                settingsHome(s, account?.name, account?.avatar, labels, onOpen = { openName = it.name }) { block -> update(block) }
            }
            if (q.isNotEmpty()) item {
                Text("Results for \"$q\"", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            with(SettingsBuilder(this, open, q, labels)) {
            // ------------------------------------------------------------ account
            section(Sec.ACCOUNT)
            item("account sign in login google youtube sign out") {
                val session = account
                if (session == null) {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.AccountCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(stringResource(R.string.ui_sign_in_with_youtube)) },
                        supportingContent = { Text("Optional: connect your YouTube recommendations, subscriptions, likes and watch history. Some videos may still be restricted.") },
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
                        trailingContent = { TextButton(onClick = { dialog = "signout" }) { Text(stringResource(R.string.ui_sign_out)) } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            }
            if (account != null) {
                item {
                    ClickRow(stringResource(R.string.ui_your_youtube_library), stringResource(R.string.ui_recommended_subscriptions_feed_liked_wat)) {
                        nav.navigate(Routes.LIBRARY) { launchSingleTop = true }
                    }
                }
                item {
                    ClickRow(
                        if (importing) "Importing subscriptions…" else "Import YouTube subscriptions",
                        "Copy the channels you follow into Orvixa's subscriptions"
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
                    SwitchRow(stringResource(R.string.ui_sync_likes_subscriptions_to_youtube), stringResource(R.string.ui_liking_disliking_or_subscribing_in_vidor), s.syncAccountActions) { v ->
                        update { it.copy(syncAccountActions = v) }
                    }
                }
                item {
                    SwitchRow(
                        "Add watched videos to YouTube history",
                        "Send measured playback while signed in. YouTube may pause/reject history; check the result below.",
                        s.syncWatchHistory
                    ) { v -> update { it.copy(syncWatchHistory = v) } }
                }
                item {
                    val status by com.vidora.app.account.AccountSync.watchStatus.collectAsStateWithLifecycle()
                    ClickRow("Sync recorded device history", status) { com.vidora.app.account.AccountSync.syncRecordedHistory() }
                }
                item {
                    ClickRow("Manage YouTube history", "Pause, clear or auto-delete your YouTube watch & search history (Google My Activity)") {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://myactivity.google.com/product/youtube/controls")))
                        }
                    }
                }
                item {
                    var checking by remember { mutableStateOf(false) }
                    var report by remember { mutableStateOf<String?>(null) }
                    ClickRow(if (checking) "Checking your account…" else "Account check", "Tests if YouTube accepts your sign-in and whether home, history and subscriptions load") {
                        if (checking) return@ClickRow
                        checking = true
                        scope.launch {
                            val r = runCatching { Graph.accountApi.diagnose() }.getOrElse { "Check failed: ${it.message}" }
                            report = r + "\nWatch history sync: " + com.vidora.app.account.AccountSync.lastWatchSync
                            checking = false
                        }
                    }
                    report?.let { text ->
                        GlassAlertDialog(
                            onDismissRequest = { report = null },
                            title = { Text("Account check") },
                            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
                            confirmButton = {
                                TextButton(onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Orvixa account check", text))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                }) { Text("Copy") }
                            },
                            dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } },
                        )
                    }
                }
                item {
                    SwitchRow(stringResource(R.string.ui_use_account_for_restricted_videos), stringResource(R.string.ui_retry_age_restricted_blocked_videos_with), s.useAccountForRestricted) { v ->
                        update { it.copy(useAccountForRestricted = v) }
                    }
                }
                item {
                    ClickRow(stringResource(R.string.ui_refresh_account_info), stringResource(R.string.ui_update_name_and_picture)) {
                        scope.launch {
                            runCatching { Graph.accountApi.accountInfo() }
                                .onSuccess { it?.let(Graph.account::save); Toast.makeText(context, "Account updated", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
            // ------------------------------------------------------------ appearance
            section(Sec.APPEARANCE)
            item("theme dark light black amoled night mode") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.ui_theme), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(selected = s.themeMode == m, onClick = { update { it.copy(themeMode = m) } }, label = { Text(m.label) })
                        }
                    }
                }
            }
            item {
                SwitchRow(
                    "Glass UI",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Frosted, see-through navigation bar, mini player and player controls (real blur)"
                    else "Frosted, see-through bars (blur needs Android 12+, a tint is used here)",
                    s.glassUi
                ) { v -> update { it.copy(glassUi = v) } }
            }
            item { SwitchRow("iPhone-style icons", "Clean iOS-style line icons everywhere (Framework7 Icons). Turn off for Material icons", s.iosIcons) { v -> update { it.copy(iosIcons = v) } } }
            item { SwitchRow("Ambient mode", "Soft glow of the video's colours around the player, like YouTube", s.ambientMode) { v -> update { it.copy(ambientMode = v) } } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item { SwitchRow(stringResource(R.string.ui_material_you_colours), stringResource(R.string.ui_match_colours_to_your_wallpaper), s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } } }
            }
            item("accent colour color") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.ui_accent_colour), style = MaterialTheme.typography.titleSmall)
                    if (s.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Text(stringResource(R.string.ui_turn_off_material_you_to_use_a_custom_ac), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            item { ClickRow(stringResource(R.string.ui_app_display_name), s.displayName) { dialog = "name" } }
            item("feed layout grid list compact") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.ui_feed_layout), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedLayout.entries.forEach { l ->
                            FilterChip(selected = s.feedLayout == l, onClick = { update { it.copy(feedLayout = l) } }, label = { Text(l.label) })
                        }
                    }
                }
            }
            item("corner roundness radius rounded") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Corner roundness · ${s.cornerRadius} dp", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = s.cornerRadius.toFloat(), valueRange = 0f..28f, steps = 6,
                        onValueChange = { v -> update { it.copy(cornerRadius = v.toInt()) } }
                    )
                }
            }
            item { SwitchRow(stringResource(R.string.ui_channel_avatars_in_feed), null, s.showChannelAvatars) { v -> update { it.copy(showChannelAvatars = v) } } }

            // ------------------------------------------------------------ icon & name
            section(Sec.ICON)
            item("app icon launcher name logo home screen") {
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
                    Text(stringResource(R.string.ui_launcher_name), style = MaterialTheme.typography.titleSmall)
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
            section(Sec.PLAYER)
            item { ClickRow(if (s.smartQuality) "Max video quality (Wi-Fi)" else stringResource(R.string.ui_default_video_quality), "${s.defaultQuality}p") { dialog = "quality" } }
            item { SwitchRow("Smart quality (Auto)", "Starts instantly at a low quality on slow networks, then sharpens automatically like YouTube", s.smartQuality) { v -> update { it.copy(smartQuality = v) } } }
            item { ClickRow("Mobile data quality limit", if (s.mobileQuality == 0) "No limit" else "${s.mobileQuality}p") { dialog = "mobilequality" } }
            item { SwitchRow("Audio-only on very slow network", "When even 144p keeps buffering, keep listening with audio and bring the video back once the network recovers", s.autoAudioOnSlow) { v -> update { it.copy(autoAudioOnSlow = v) } } }
            item { SwitchRow("Preload next video", "Gets the next video ready in the background so it starts instantly", s.prefetchNext) { v -> update { it.copy(prefetchNext = v) } } }
            item { SwitchRow("Data saver", "Caps video at 360p, smaller thumbnails, lower-bitrate audio and no preloading — on mobile data", s.dataSaver) { v -> update { it.copy(dataSaver = v) } } }
            item { SwitchRow("Video cache", "Keeps recently watched parts on disk (up to 512 MB) so replays and seeking back don't use data", s.mediaCache) { v -> update { it.copy(mediaCache = v) } } }
            item {
                ClickRow("Clear video cache", "%.0f MB used".format(com.vidora.app.player.MediaCache.usedBytes() / 1_048_576f)) {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.vidora.app.player.MediaCache.clear(context.applicationContext) }
                    Toast.makeText(context, "Video cache cleared", Toast.LENGTH_SHORT).show()
                }
            }
            item { SwitchRow("Efficient codecs (AV1 / VP9)", "Uses AV1 or VP9 when your phone decodes them in hardware — sharper picture with less data and battery", s.efficientCodecs) { v -> update { it.copy(efficientCodecs = v) } } }
            item { ClickRow(stringResource(R.string.ui_default_playback_speed), "${s.defaultSpeed}x") { dialog = "speed" } }
            item { SwitchRow(stringResource(R.string.ui_background_playback), stringResource(R.string.ui_keep_playing_when_the_app_is_closed_or_t), s.backgroundPlay) { v -> update { it.copy(backgroundPlay = v) } } }
            item { SwitchRow(stringResource(R.string.ui_picture_in_picture), stringResource(R.string.ui_automatically_shrink_the_video_when_you), s.autoPip) { v -> update { it.copy(autoPip = v) } } }
            item { SwitchRow(stringResource(R.string.ui_autoplay_next_video), null, s.autoplayNext) { v -> update { it.copy(autoplayNext = v) } } }
            item { SwitchRow(stringResource(R.string.ui_resume_where_you_left_off), null, s.resumePlayback) { v -> update { it.copy(resumePlayback = v) } } }
            item { SwitchRow(stringResource(R.string.ui_start_in_audio_only_mode), stringResource(R.string.ui_great_for_music_and_podcasts_saves_data), s.audioOnlyDefault) { v -> update { it.copy(audioOnlyDefault = v) } } }
            item { SwitchRow(stringResource(R.string.ui_skip_silence), stringResource(R.string.ui_speeds_through_silent_parts), s.skipSilence) { v -> update { it.copy(skipSilence = v) } } }
            item { SwitchRow("Fit Shorts video", "Show the whole frame instead of cropping to fill; also changeable in Shorts", s.shortsFit) { v -> update { it.copy(shortsFit = v) } } }
            item { SwitchRow(stringResource(R.string.ui_gestures), stringResource(R.string.ui_double_tap_to_seek_hold_for_2_swipe_for), s.gestures) { v -> update { it.copy(gestures = v) } } }
            item { SwitchRow(stringResource(R.string.ui_remember_queue), stringResource(R.string.ui_restore_what_you_were_watching_after_res), s.persistQueue) { v -> update { it.copy(persistQueue = v) } } }
            item { SwitchRow("Piped / Invidious fallback", "If YouTube blocks your network, play through public privacy servers as a last resort", s.altFrontends) { v -> update { it.copy(altFrontends = v) } } }
            item { SwitchRow("Stable volume", "Evens out loud and quiet videos using YouTube's loudness data", s.stableVolume) { v -> update { it.copy(stableVolume = v) } } }
            item { ClickRow(stringResource(R.string.ui_equalizer_audio_boost), if (s.eqEnabled || s.volumeBoost > 0) "On" else "Off") { showEq = true } }

            // ------------------------------------------------------------ captions & audio language
            section(Sec.CAPTIONS)
            item { SwitchRow(stringResource(R.string.ui_show_captions_by_default), null, s.captionsEnabled) { v -> update { it.copy(captionsEnabled = v) } } }
            item { ClickRow(stringResource(R.string.ui_caption_language), s.captionLang.ifBlank { "Device language (${java.util.Locale.getDefault().displayLanguage})" }) { dialog = "caplang" } }
            item { SwitchRow(stringResource(R.string.ui_auto_translate_captions), stringResource(R.string.ui_translate_into_your_device_language_when), s.captionTranslate) { v -> update { it.copy(captionTranslate = v) } } }
            item("caption subtitle size text bigger smaller") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Caption size · ${(s.captionScale * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    Slider(value = s.captionScale, valueRange = 0.6f..2f, steps = 6, onValueChange = { v -> update { it.copy(captionScale = v) } })
                }
            }
            item { ClickRow(stringResource(R.string.ui_preferred_audio_track), s.preferredAudioLang.ifBlank { "Original" }) { update { it.copy(preferredAudioLang = "") }; Toast.makeText(context, "Reset to original audio. Pick a dubbed track from the player to remember it.", Toast.LENGTH_LONG).show() } }

            // ------------------------------------------------------------ community
            section(Sec.COMMUNITY)
            item { SwitchRow(stringResource(R.string.ui_return_youtube_dislike), stringResource(R.string.ui_show_dislike_counts_from_returnyoutubedi), s.returnDislike) { v -> update { it.copy(returnDislike = v) } } }
            item { SwitchRow(stringResource(R.string.ui_dearrow), stringResource(R.string.ui_replace_clickbait_titles_thumbnails_with), s.deArrow) { v -> update { it.copy(deArrow = v) } } }
            item { SwitchRow(stringResource(R.string.ui_vertical_shorts_player), stringResource(R.string.ui_swipe_through_shorts_full_screen), s.shortsPlayer) { v -> update { it.copy(shortsPlayer = v) } } }
            item { ClickRow(stringResource(R.string.ui_content_filters), "${s.blockedChannels.size} blocked channels · ${s.blockedKeywords.size} keywords") { nav.navigate(Routes.FILTERS) } }

            // ------------------------------------------------------------ notifications
            section(Sec.NOTIFICATIONS)
            item {
                ClickRow(stringResource(R.string.ui_new_video_notifications), if (s.notifyHours <= 0) "Off" else "Check subscriptions every ${s.notifyHours} h") { dialog = "notify" }
            }

            // ------------------------------------------------------------ sponsorblock
            section(Sec.SPONSORBLOCK)
            item { SwitchRow(stringResource(R.string.ui_skip_sponsored_segments), stringResource(R.string.ui_community_submitted_segments_from_sponso), s.sponsorBlock) { v -> update { it.copy(sponsorBlock = v) } } }
            if (s.sponsorBlock) {
                item("sponsor categories segments intro outro selfpromo") {
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
            section(Sec.DOWNLOADS)
            item { SwitchRow(stringResource(R.string.ui_ask_quality_every_time), stringResource(R.string.ui_choose_video_audio_and_quality_before_ea), s.askDownloadQuality) { v -> update { it.copy(askDownloadQuality = v) } } }
            item { ClickRow(stringResource(R.string.ui_default_download_quality), "${s.downloadQuality}p") { dialog = "dlquality" } }
            item { ClickRow(stringResource(R.string.ui_preferred_audio_format), s.downloadAudioPref.label) { dialog = "audiofmt" } }
            item { SwitchRow(stringResource(R.string.ui_download_on_wi_fi_only), null, s.downloadWifiOnly) { v -> update { it.copy(downloadWifiOnly = v) } } }
            item {
                ClickRow("Parallel connections", "${s.downloadConnections} per file · audio and video download at the same time") {
                    val steps = listOf(1, 2, 3, 4, 6, 8)
                    update { it.copy(downloadConnections = steps[(steps.indexOf(it.downloadConnections) + 1) % steps.size]) }
                }
            }
            item { SwitchRow("Embed title, channel & thumbnail", "Writes metadata and cover art into MP4 / M4A files", s.downloadEmbedMetadata) { v -> update { it.copy(downloadEmbedMetadata = v) } } }
            item { SwitchRow("Save subtitles", "Saves captions as a .vtt file in Download/Vidora", s.downloadSubtitles) { v -> update { it.copy(downloadSubtitles = v) } } }
            item { SwitchRow("Remove sponsors from downloads", "Cuts SponsorBlock segments (your skip categories) out of the file, no re-encoding", s.downloadCutSponsors) { v -> update { it.copy(downloadCutSponsors = v) } } }
            item { ClickRow(stringResource(R.string.ui_download_folder), com.vidora.app.download.Storage.folderLabel(s.downloadFolder) + " · tap to choose (SD card supported)") { folderPicker.launch(null) } }
            if (s.downloadFolder.isNotBlank()) {
                item {
                    ClickRow(stringResource(R.string.ui_use_default_folders), stringResource(R.string.ui_movies_vidora_and_music_vidora)) {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                android.net.Uri.parse(s.downloadFolder),
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }
                        update { it.copy(downloadFolder = "") }
                    }
                }
            }

            // ------------------------------------------------------------ content & privacy
            section(Sec.CONTENT)
            item { SwitchRow("Continue watching on Home", "Show saved playback below the feed categories; hide it from the section itself", s.showContinueWatching) { v -> update { it.copy(showContinueWatching = v) } } }
            item {
                val region by Graph.region.state.collectAsStateWithLifecycle()
                val sync by com.vidora.app.account.AccountSync.watchStatus.collectAsStateWithLifecycle()
                ClickRow("Home feed & sync status", "Region ${region.code} · ${region.method} · $sync") {
                    Toast.makeText(context, "Pull down on Home or tap Home again to refresh. Public Explore is separate from signed-in YouTube recommendations.", Toast.LENGTH_LONG).show()
                }
            }
            item { SwitchRow("Use IP country for Home", "Country-only lookup via country.is (sees your public IP). No GPS. Used only when Content country is Automatic; Search stays query-based.", s.autoRegionByIp) { v -> update { it.copy(autoRegionByIp = v) } } }
            item { ClickRow(stringResource(R.string.ui_content_country), Countries.firstOrNull { it.first == s.contentCountry }?.second ?: s.contentCountry) { dialog = "country" } }
            item { ClickRow("Home language", com.vidora.app.data.HomeRanking.languages.firstOrNull { it.first == s.homeLanguage }?.second ?: "Any language") { dialog = "homelang" } }
            item { ClickRow("Home interests", s.homeInterests.sorted().joinToString().ifBlank { "None · general discovery" }) { dialog = "interests" } }
            item { ClickRow("Reset Home interests", "Clears local interests and hidden Explore videos; keeps your library") {
                update { it.copy(homeLanguage = "", homeInterests = emptySet(), lessLikeVideos = emptySet()) }
                com.vidora.app.ui.UiCache.invalidate("home:public:")
            } }
            item { ClickRow("Clear saved feeds", "Public Home/search snapshots only · bounded to 4 MiB payload; keeps downloads and library") {
                scope.launch { com.vidora.app.ui.UiCache.invalidate("home:"); com.vidora.app.ui.UiCache.invalidate("search:"); Graph.feedStore.clear()
                    Toast.makeText(context, "Saved feeds cleared", Toast.LENGTH_SHORT).show() }
            } }
            item { ClickRow("Local playback diagnostics", "Review before sharing · no URLs, searches, IPs, cookies or automatic uploads") { dialog = "diagnostics" } }
            item { ClickRow("Clear local diagnostics", "In-memory events only") { com.vidora.app.data.Diagnostics.clear() } }
            item { SwitchRow(stringResource(R.string.ui_hide_shorts), null, s.hideShorts) { v -> update { it.copy(hideShorts = v) } } }
            item { ClickRow(stringResource(R.string.ui_import_subscriptions), stringResource(R.string.ui_from_newpipe_libretube_freetube_json_goo)) { subsImportLauncher.launch(arrayOf("*/*")) } }
            item { ClickRow(stringResource(R.string.ui_export_subscriptions), stringResource(R.string.ui_newpipe_compatible_json)) { subsExportLauncher.launch("vidora-subscriptions.json") } }
            item { SwitchRow(stringResource(R.string.ui_incognito_mode), stringResource(R.string.ui_pause_watch_search_history_also_in_the_l), s.incognito) { v -> update { it.copy(incognito = v) } } }
            item { SwitchRow(stringResource(R.string.ui_save_watch_history), stringResource(R.string.ui_stored_only_on_this_device), s.saveHistory) { v -> update { it.copy(saveHistory = v) } } }
            item { SwitchRow(stringResource(R.string.ui_save_search_history), null, s.saveSearchHistory) { v -> update { it.copy(saveSearchHistory = v) } } }
            item { ClickRow("Clear watch history", "On this device") { Graph.library.clearHistory(); com.vidora.app.ui.UiCache.invalidate("you:history"); Toast.makeText(context, "Watch history cleared", Toast.LENGTH_SHORT).show() } }
            item { ClickRow(stringResource(R.string.ui_clear_search_history), null) { Graph.library.clearSearchHistory(); Toast.makeText(context, "Search history cleared", Toast.LENGTH_SHORT).show() } }
            item { ClickRow(stringResource(R.string.ui_clear_watch_history_2), null) { Graph.library.clearHistory(); Toast.makeText(context, "Watch history cleared", Toast.LENGTH_SHORT).show() } }
            item { ClickRow(stringResource(R.string.ui_export_library), stringResource(R.string.ui_back_up_subscriptions_likes_history)) { exportLauncher.launch("vidora-backup.json") } }
            item { ClickRow(stringResource(R.string.ui_import_library), stringResource(R.string.ui_restore_from_a_backup_file)) { importLauncher.launch(arrayOf("application/json", "*/*")) } }
            item { ClickRow(stringResource(R.string.ui_reset_all_settings_2), null) { dialog = "reset" } }

            // ------------------------------------------------------------ advanced
            section(Sec.ADVANCED)
            item {
                SwitchRow(stringResource(R.string.ui_use_potoken_botguard), "Generates YouTube integrity tokens locally. Used when YouTube blocks the normal player (VPN / \"not a bot\" fallback). Status: ${com.vidora.app.potoken.PoTokenProviderImpl.lastStatus}", s.poToken) { v ->
                    update { it.copy(poToken = v) }
                }
            }
            item { SwitchRow(stringResource(R.string.ui_check_for_updates_automatically), "Check for new Orvixa versions in the app", s.autoUpdateCheck) { v -> update { it.copy(autoUpdateCheck = v) } } }
            item { SwitchRow("Download & install updates automatically", "New versions download in the background and install on their own (Android 12+ after the first in-app update)", s.autoInstallUpdates) { v -> update { it.copy(autoInstallUpdates = v) } } }
            item {
                ClickRow(if (checkingUpdate) "Checking…" else "Check for updates now", "Current version ${BuildConfig.VERSION_NAME}") {
                    if (checkingUpdate) return@ClickRow
                    scope.launch { com.vidora.app.util.Updater.checkForUser(context.applicationContext) }
                }
            }
            item {
                ClickRow(stringResource(R.string.ui_report_a_problem), "Copy a report to send to the developer") {
                    val body = com.vidora.app.util.CrashReporter.report(null, "Describe the problem:")
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Orvixa report", body))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                }
            }

            // ------------------------------------------------------------ about
            section(Sec.ABOUT)
            item { ClickRow("About ${s.displayName}", "Version ${BuildConfig.VERSION_NAME} · updates · live news from the developer") { nav.navigate(com.vidora.app.ui.Routes.ABOUT) } }
            item("about version licence license open source credits") {
                com.vidora.app.ui.theme.GlassCard(
                    Modifier.fillMaxWidth().padding(16.dp),
                    tint = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${s.displayName} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Ad-free · background play · downloads · optional sign-in, no tracking. " +
                                "Powered by the open-source NewPipe Extractor (GPLv3), Media3/ExoPlayer, SponsorBlock, " +
                                "DeArrow, Return YouTube Dislike (returnyoutubedislike.com) and Framework7 Icons (MIT).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            }
            if (q.isNotEmpty()) item {
                Text("Can't find it? Try another word — e.g. quality, dark, download, captions, sponsor.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
    }

    if (showEq) EqualizerSheet { showEq = false }

    when (dialog) {
        "caplang" -> ChoiceDialog(
            "Caption language",
            listOf("" to "Device language") + CaptionLanguages,
            s.captionLang, { dialog = null }
        ) { v -> update { it.copy(captionLang = v) } }
        "notify" -> ChoiceDialog(
            "Check for new videos",
            listOf(0 to "Off", 1 to "Every hour", 3 to "Every 3 hours", 6 to "Every 6 hours", 12 to "Every 12 hours", 24 to "Once a day"),
            s.notifyHours, { dialog = null }
        ) { v ->
            update { it.copy(notifyHours = v) }
            if (v > 0 && Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        "signout" -> GlassAlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.ui_sign_out_2)) },
            text = { Text(stringResource(R.string.ui_your_youtube_session_will_be_removed_fro)) },
            confirmButton = { TextButton(onClick = { Graph.account.signOut(); com.vidora.app.ui.UiCache.clearAll(); dialog = null }) { Text(stringResource(R.string.ui_sign_out)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.ui_cancel)) } },
        )
        "name" -> TextDialog("App display name", s.displayName, onDismiss = { dialog = null }) { v -> update { it.copy(displayName = v.take(24)) } }
        "quality" -> ChoiceDialog("Default video quality", QualityOptions.map { it to "${it}p" }, s.defaultQuality, { dialog = null }) { v -> update { it.copy(defaultQuality = v) } }
        "mobilequality" -> ChoiceDialog("Mobile data quality limit", listOf(0 to "No limit") + QualityOptions.take(6).map { it to "${it}p" }, s.mobileQuality, { dialog = null }) { v -> update { it.copy(mobileQuality = v) } }
        "dlquality" -> ChoiceDialog("Default download quality", QualityOptions.map { it to "${it}p" }, s.downloadQuality, { dialog = null }) { v -> update { it.copy(downloadQuality = v) } }
        "speed" -> ChoiceDialog("Default speed", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to "${it}x" }, s.defaultSpeed, { dialog = null }) { v ->
            update { it.copy(defaultSpeed = v) }; Graph.player.setSpeed(v)
        }
        "audiofmt" -> ChoiceDialog("Preferred audio format", AudioPreference.entries.map { it to it.label }, s.downloadAudioPref, { dialog = null }) { v -> update { it.copy(downloadAudioPref = v) } }
        "homelang" -> ChoiceDialog("Home query language", com.vidora.app.data.HomeRanking.languages, s.homeLanguage, { dialog = null }) { v -> update { it.copy(homeLanguage = v) } }
        "interests" -> GlassAlertDialog(
            onDismissRequest = { dialog = null }, title = { Text("Local Home interests") },
            text = { Column { com.vidora.app.data.HomeRanking.interests.forEach { topic ->
                androidx.compose.material3.FilterChip(selected = topic in s.homeInterests, label = { Text(topic) }, onClick = {
                    update { it.copy(homeInterests = if (topic in it.homeInterests) it.homeInterests - topic else it.homeInterests + topic) }
                })
            } } },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
        )
        "diagnostics" -> GlassAlertDialog(
            onDismissRequest = { dialog = null }, title = { Text("Local diagnostics · review") },
            text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) { item { Text(com.vidora.app.data.Diagnostics.report(), style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, com.vidora.app.data.Diagnostics.report())
                context.startActivity(android.content.Intent.createChooser(send, "Share reviewed diagnostics"))
            }) { Text("Share report") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        "country" -> ChoiceDialog("Content country", Countries, s.contentCountry, { dialog = null }) { v ->
            update { it.copy(contentCountry = v) }
            Toast.makeText(context, "Home region updated. Some YouTube category charts may need an app restart.", Toast.LENGTH_LONG).show()
        }
        "reset" -> GlassAlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.ui_reset_all_settings)) },
            text = { Text(stringResource(R.string.ui_your_library_history_likes_subscriptions)) },
            confirmButton = { TextButton(onClick = { scope.launch { Graph.settings.reset() }; dialog = null }) { Text(stringResource(R.string.ui_reset)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.ui_cancel)) } }
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
    val search = LocalSettingsSearch.current
    if (!search.matches(title, subtitle)) return
    ListItem(
        overlineContent = search.overline(),
        headlineContent = { Text(highlight(title, search.query)) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable { onChange(!checked) }
    )
}

@Composable
private fun ClickRow(title: String, subtitle: String?, onClick: () -> Unit) {
    val search = LocalSettingsSearch.current
    if (!search.matches(title, subtitle)) return
    ListItem(
        overlineContent = search.overline(),
        headlineContent = { Text(highlight(title, search.query)) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    GlassAlertDialog(
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } }
    )
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { com.vidora.app.util.AppBrand.NAME }); onDismiss() }) { Text(stringResource(R.string.ui_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

private val CaptionLanguages = listOf(
    "en" to "English", "bn" to "বাংলা (Bengali)", "hi" to "हिन्दी (Hindi)", "ur" to "اردو (Urdu)", "ar" to "العربية (Arabic)",
    "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português", "ru" to "Русский",
    "ja" to "日本語", "ko" to "한국어", "zh-Hans" to "中文 (简体)", "id" to "Bahasa Indonesia", "tr" to "Türkçe",
    "it" to "Italiano", "ta" to "தமிழ்", "te" to "తెలుగు", "ml" to "മലയാളം", "vi" to "Tiếng Việt",
)

// ====================================================================== categories + search

/** The setting categories, shown as an iOS-style grouped list on the Settings home page. */
private enum class Sec(val title: Int, val group: Int, val summary: String) {
    ACCOUNT(R.string.h_account, 0, "YouTube sign-in, sync, history"),
    APPEARANCE(R.string.h_appearance, 1, "Theme, glass, colours, layout"),
    ICON(R.string.h_icon, 1, "Home-screen icon and name"),
    PLAYER(R.string.h_player, 2, "Quality, background play, gestures, speed"),
    CAPTIONS(R.string.h_captions, 2, "Subtitles, size, translation, dubbed audio"),
    SPONSORBLOCK(R.string.h_sponsorblock, 2, "Skip sponsors, intros and reminders"),
    COMMUNITY(R.string.h_community, 2, "Dislikes, DeArrow, Shorts, filters"),
    DOWNLOADS(R.string.h_downloads, 3, "Quality, format, folder, subtitles"),
    NOTIFICATIONS(R.string.h_notifications, 3, "New videos from your subscriptions"),
    CONTENT(R.string.h_content, 3, "Country, history, incognito, backup"),
    ADVANCED(R.string.h_advanced, 4, "Updates, PoToken, report a problem"),
    ABOUT(R.string.h_about, 4, "Version, news, credits"),
}

/** The same glyphs iOS Settings uses (Framework7's SF-style set), always iOS regardless of the icon style. */
private val Sec.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        Sec.ACCOUNT -> com.vidora.app.ui.icons.IosGlyphs.personFill
        Sec.APPEARANCE -> com.vidora.app.ui.icons.IosGlyphs.sunMaxFill            // Display & Brightness
        Sec.ICON -> com.vidora.app.ui.icons.IosGlyphs.squareGrid2x2Fill           // Home Screen & App Library
        Sec.PLAYER -> com.vidora.app.ui.icons.IosGlyphs.playRectangleFill
        Sec.CAPTIONS -> com.vidora.app.ui.icons.IosGlyphs.captionsBubbleFill
        Sec.SPONSORBLOCK -> com.vidora.app.ui.icons.IosGlyphs.shieldLefthalfFill
        Sec.COMMUNITY -> com.vidora.app.ui.icons.IosGlyphs.handThumbsupFill
        Sec.DOWNLOADS -> com.vidora.app.ui.icons.IosGlyphs.arrowDownToLine
        Sec.NOTIFICATIONS -> com.vidora.app.ui.icons.IosGlyphs.bellFill            // Notifications
        Sec.CONTENT -> com.vidora.app.ui.icons.IosGlyphs.handRaisedFill            // Privacy & Security
        Sec.ADVANCED -> com.vidora.app.ui.icons.IosGlyphs.gearAltFill              // General
        Sec.ABOUT -> com.vidora.app.ui.icons.IosGlyphs.infoCircleFill
    }

/** iOS system colours, as on the iPhone Settings tiles. */
private val Sec.tile: Color
    get() = when (this) {
        Sec.ACCOUNT -> Color(0xFF8E8E93)
        Sec.APPEARANCE -> Color(0xFF007AFF)
        Sec.ICON -> Color(0xFF5856D6)
        Sec.PLAYER -> Color(0xFFFF2D55)
        Sec.CAPTIONS -> Color(0xFF32ADE6)
        Sec.SPONSORBLOCK -> Color(0xFF34C759)
        Sec.COMMUNITY -> Color(0xFFFF9500)
        Sec.DOWNLOADS -> Color(0xFF30B0C7)
        Sec.NOTIFICATIONS -> Color(0xFFFF3B30)
        Sec.CONTENT -> Color(0xFF007AFF)
        Sec.ADVANCED -> Color(0xFF8E8E93)
        Sec.ABOUT -> Color(0xFF8E8E93)
    }

/** iOS-style app tile: rounded square with a white glyph (or the profile photo for the account). */
@Composable
private fun SecTile(sec: Sec, avatar: String?) {
    if (sec == Sec.ACCOUNT && avatar != null) {
        Avatar(avatar, 32)
        return
    }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(sec.tile.copy(alpha = 0.92f), sec.tile))),
        contentAlignment = Alignment.Center,
    ) { Icon(sec.icon, null, Modifier.size(21.dp), tint = Color.White) }
}

/** What the rows of the current list should show: everything (inside a category) or only search matches. */
private data class SettingsSearch(val query: String = "", val section: String? = null, val keywords: String? = null) {
    private val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    fun matches(vararg texts: String?): Boolean {
        if (words.isEmpty()) return true
        val hay = (texts.toList() + section + keywords).filterNotNull().joinToString(" ").lowercase()
        return words.all { hay.contains(it) }
    }
    fun overline(): (@Composable () -> Unit)? = if (words.isEmpty() || section == null) null else ({
        Text(section, color = MaterialTheme.colorScheme.primary)
    })
}

private val LocalSettingsSearch = androidx.compose.runtime.compositionLocalOf { SettingsSearch() }

/**
 * Wraps the LazyColumn so the long settings list can be shown one category at a time, or
 * filtered by a search query. `section()` replaces the old headers; `item {}` keeps the old call sites.
 */
private class SettingsBuilder(
    private val lazy: androidx.compose.foundation.lazy.LazyListScope,
    private val open: Sec?,
    private val query: String,
    private val labels: Map<Sec, String>,
) {
    private var current = Sec.ACCOUNT
    fun section(sec: Sec) { current = sec }

    fun item(keywords: String? = null, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) {
        val sec = current
        val label = labels[sec]
        if (query.isEmpty()) {
            if (open != sec) return
            lazy.item { content() }
            return
        }
        // Custom widgets (chips, sliders…) are found through their keywords; plain rows filter themselves.
        if (keywords != null && !SettingsSearch(query, label, keywords).matches()) return
        lazy.item {
            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsSearch provides SettingsSearch(query, label, keywords)) { content() }
        }
    }
}

@Composable
private fun highlight(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val lower = text.lowercase()
        words.forEach { w ->
            var i = lower.indexOf(w)
            while (i >= 0) {
                addStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = FontWeight.Bold), i, i + w.length)
                i = lower.indexOf(w, i + w.length)
            }
        }
    }
}

@Composable
private fun SettingsSearchField(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).fillMaxWidth().heightIn(min = 48.dp)
            .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search settings", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Rounded.Close, "Clear", Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") },
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Settings home: quick toggles, then the categories in iOS-style groups. */
@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.settingsHome(
    s: AppSettings,
    accountName: String?,
    accountAvatar: String?,
    labels: Map<Sec, String>,
    onOpen: (Sec) -> Unit,
    update: ((AppSettings) -> AppSettings) -> Unit,
) {
    item(key = "quick") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Quick settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp))
            com.vidora.app.ui.theme.GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip("Background play", s.backgroundPlay) { v -> update { it.copy(backgroundPlay = v) } }
                    QuickChip("Data saver", s.dataSaver) { v -> update { it.copy(dataSaver = v) } }
                    QuickChip("Incognito", s.incognito) { v -> update { it.copy(incognito = v) } }
                    QuickChip("SponsorBlock", s.sponsorBlock) { v -> update { it.copy(sponsorBlock = v) } }
                    QuickChip("Captions", s.captionsEnabled) { v -> update { it.copy(captionsEnabled = v) } }
                    QuickChip("Hide Shorts", s.hideShorts) { v -> update { it.copy(hideShorts = v) } }
                    QuickChip("Glass UI", s.glassUi) { v -> update { it.copy(glassUi = v) } }
                    QuickChip("iPhone icons", s.iosIcons) { v -> update { it.copy(iosIcons = v) } }
                }
            }
        }
    }
    Sec.entries.groupBy { it.group }.forEach { (group, secs) ->
        item(key = "group$group") {
            com.vidora.app.ui.theme.GlassCard(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(18.dp)
            ) {
                secs.forEachIndexed { i, sec ->
                    val summary = when (sec) {
                        Sec.ACCOUNT -> accountName?.let { "Signed in as $it" } ?: "Not signed in · tap to sign in"
                        Sec.PLAYER -> "${if (s.smartQuality) "Smart quality" else "${s.defaultQuality}p"} · ${s.defaultSpeed}x" +
                            if (s.backgroundPlay) " · background play on" else ""
                        Sec.DOWNLOADS -> "${s.downloadQuality}p · ${s.downloadAudioPref.label}"
                        Sec.ABOUT -> "Version ${BuildConfig.VERSION_NAME}"
                        else -> sec.summary
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(sec) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SecTile(sec, accountAvatar)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(labels[sec].orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i < secs.lastIndex) androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(start = 60.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on, onClick = { onChange(!on) }, label = { Text(label) },
        leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null,
    )
}
