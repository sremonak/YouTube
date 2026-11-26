package com.vidora.app.ui.screens

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
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
    var updateInfo by remember { mutableStateOf<com.vidora.app.util.UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    val subsImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val result = runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
            val channels = com.vidora.app.data.SubscriptionImport.parse(text)
            if (channels.isEmpty()) error("No channels found in this file")
            Graph.library.importSubscriptions(channels) to channels.size
        }
        Toast.makeText(
            context,
            result.fold({ (added, total) -> "Imported $added new of $total channels" }, { "Import failed: ${it.message}" }),
            Toast.LENGTH_LONG
        ).show()
    }
    val subsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = com.vidora.app.data.SubscriptionImport.exportNewPipe(Graph.library.data.value.subscriptions)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.onSuccess { Toast.makeText(context, "Subscriptions exported", Toast.LENGTH_SHORT).show() }
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
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { Graph.library.importJson(it.readBytes().decodeToString()) } ?: false
        }.getOrDefault(false)
        Toast.makeText(context, if (ok) "Library restored" else "Invalid backup file", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.fillMaxWidth()) {
            // ------------------------------------------------------------ account
            item { Header(stringResource(R.string.h_account)) }
            item {
                val session = account
                if (session == null) {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.AccountCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(stringResource(R.string.ui_sign_in_with_youtube)) },
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
                    SwitchRow(stringResource(R.string.ui_sync_likes_subscriptions_to_youtube), stringResource(R.string.ui_liking_disliking_or_subscribing_in_vidor), s.syncAccountActions) { v ->
                        update { it.copy(syncAccountActions = v) }
                    }
                }
                item {
                    SwitchRow(
                        "Add watched videos to YouTube history",
                        "Like the official app: what you watch in Vidora shows up in your YouTube history and improves your recommendations",
                        s.syncWatchHistory
                    ) { v -> update { it.copy(syncWatchHistory = v) } }
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
                        AlertDialog(
                            onDismissRequest = { report = null },
                            title = { Text("Account check") },
                            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
                            confirmButton = {
                                TextButton(onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Vidora account check", text))
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
            item { Header(stringResource(R.string.h_appearance)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.ui_theme), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(selected = s.themeMode == m, onClick = { update { it.copy(themeMode = m) } }, label = { Text(m.label) })
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item { SwitchRow(stringResource(R.string.ui_material_you_colours), stringResource(R.string.ui_match_colours_to_your_wallpaper), s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } } }
            }
            item {
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
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.ui_feed_layout), style = MaterialTheme.typography.titleSmall)
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
            item { SwitchRow(stringResource(R.string.ui_channel_avatars_in_feed), null, s.showChannelAvatars) { v -> update { it.copy(showChannelAvatars = v) } } }

            // ------------------------------------------------------------ icon & name
            item { Header(stringResource(R.string.h_icon)) }
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
            item { Header(stringResource(R.string.h_player)) }
            item { ClickRow(stringResource(R.string.ui_default_video_quality), "${s.defaultQuality}p") { dialog = "quality" } }
            item { ClickRow(stringResource(R.string.ui_default_playback_speed), "${s.defaultSpeed}x") { dialog = "speed" } }
            item { SwitchRow(stringResource(R.string.ui_background_playback), stringResource(R.string.ui_keep_playing_when_the_app_is_closed_or_t), s.backgroundPlay) { v -> update { it.copy(backgroundPlay = v) } } }
            item { SwitchRow(stringResource(R.string.ui_picture_in_picture), stringResource(R.string.ui_automatically_shrink_the_video_when_you), s.autoPip) { v -> update { it.copy(autoPip = v) } } }
            item { SwitchRow(stringResource(R.string.ui_autoplay_next_video), null, s.autoplayNext) { v -> update { it.copy(autoplayNext = v) } } }
            item { SwitchRow(stringResource(R.string.ui_resume_where_you_left_off), null, s.resumePlayback) { v -> update { it.copy(resumePlayback = v) } } }
            item { SwitchRow(stringResource(R.string.ui_start_in_audio_only_mode), stringResource(R.string.ui_great_for_music_and_podcasts_saves_data), s.audioOnlyDefault) { v -> update { it.copy(audioOnlyDefault = v) } } }
            item { SwitchRow(stringResource(R.string.ui_skip_silence), stringResource(R.string.ui_speeds_through_silent_parts), s.skipSilence) { v -> update { it.copy(skipSilence = v) } } }
            item { SwitchRow(stringResource(R.string.ui_gestures), stringResource(R.string.ui_double_tap_to_seek_hold_for_2_swipe_for), s.gestures) { v -> update { it.copy(gestures = v) } } }
            item { SwitchRow(stringResource(R.string.ui_remember_queue), stringResource(R.string.ui_restore_what_you_were_watching_after_res), s.persistQueue) { v -> update { it.copy(persistQueue = v) } } }
            item { ClickRow(stringResource(R.string.ui_equalizer_audio_boost), if (s.eqEnabled || s.volumeBoost > 0) "On" else "Off") { showEq = true } }

            // ------------------------------------------------------------ captions & audio language
            item { Header(stringResource(R.string.h_captions)) }
            item { SwitchRow(stringResource(R.string.ui_show_captions_by_default), null, s.captionsEnabled) { v -> update { it.copy(captionsEnabled = v) } } }
            item { ClickRow(stringResource(R.string.ui_caption_language), s.captionLang.ifBlank { "Device language (${java.util.Locale.getDefault().displayLanguage})" }) { dialog = "caplang" } }
            item { SwitchRow(stringResource(R.string.ui_auto_translate_captions), stringResource(R.string.ui_translate_into_your_device_language_when), s.captionTranslate) { v -> update { it.copy(captionTranslate = v) } } }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Caption size · ${(s.captionScale * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                    Slider(value = s.captionScale, valueRange = 0.6f..2f, steps = 6, onValueChange = { v -> update { it.copy(captionScale = v) } })
                }
            }
            item { ClickRow(stringResource(R.string.ui_preferred_audio_track), s.preferredAudioLang.ifBlank { "Original" }) { update { it.copy(preferredAudioLang = "") }; Toast.makeText(context, "Reset to original audio. Pick a dubbed track from the player to remember it.", Toast.LENGTH_LONG).show() } }

            // ------------------------------------------------------------ community
            item { Header(stringResource(R.string.h_community)) }
            item { SwitchRow(stringResource(R.string.ui_return_youtube_dislike), stringResource(R.string.ui_show_dislike_counts_from_returnyoutubedi), s.returnDislike) { v -> update { it.copy(returnDislike = v) } } }
            item { SwitchRow(stringResource(R.string.ui_dearrow), stringResource(R.string.ui_replace_clickbait_titles_thumbnails_with), s.deArrow) { v -> update { it.copy(deArrow = v) } } }
            item { SwitchRow(stringResource(R.string.ui_vertical_shorts_player), stringResource(R.string.ui_swipe_through_shorts_full_screen), s.shortsPlayer) { v -> update { it.copy(shortsPlayer = v) } } }
            item { ClickRow(stringResource(R.string.ui_content_filters), "${s.blockedChannels.size} blocked channels · ${s.blockedKeywords.size} keywords") { nav.navigate(Routes.FILTERS) } }

            // ------------------------------------------------------------ notifications
            item { Header(stringResource(R.string.h_notifications)) }
            item {
                ClickRow(stringResource(R.string.ui_new_video_notifications), if (s.notifyHours <= 0) "Off" else "Check subscriptions every ${s.notifyHours} h") { dialog = "notify" }
            }

            // ------------------------------------------------------------ sponsorblock
            item { Header(stringResource(R.string.h_sponsorblock)) }
            item { SwitchRow(stringResource(R.string.ui_skip_sponsored_segments), stringResource(R.string.ui_community_submitted_segments_from_sponso), s.sponsorBlock) { v -> update { it.copy(sponsorBlock = v) } } }
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
            item { Header(stringResource(R.string.h_downloads)) }
            item { SwitchRow(stringResource(R.string.ui_ask_quality_every_time), stringResource(R.string.ui_choose_video_audio_and_quality_before_ea), s.askDownloadQuality) { v -> update { it.copy(askDownloadQuality = v) } } }
            item { ClickRow(stringResource(R.string.ui_default_download_quality), "${s.downloadQuality}p") { dialog = "dlquality" } }
            item { ClickRow(stringResource(R.string.ui_preferred_audio_format), s.downloadAudioPref.label) { dialog = "audiofmt" } }
            item { SwitchRow(stringResource(R.string.ui_download_on_wi_fi_only), null, s.downloadWifiOnly) { v -> update { it.copy(downloadWifiOnly = v) } } }
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
            item { Header(stringResource(R.string.h_content)) }
            item { ClickRow(stringResource(R.string.ui_content_country), Countries.firstOrNull { it.first == s.contentCountry }?.second ?: s.contentCountry) { dialog = "country" } }
            item { SwitchRow(stringResource(R.string.ui_hide_shorts), null, s.hideShorts) { v -> update { it.copy(hideShorts = v) } } }
            item { ClickRow(stringResource(R.string.ui_import_subscriptions), stringResource(R.string.ui_from_newpipe_libretube_freetube_json_goo)) { subsImportLauncher.launch(arrayOf("*/*")) } }
            item { ClickRow(stringResource(R.string.ui_export_subscriptions), stringResource(R.string.ui_newpipe_compatible_json)) { subsExportLauncher.launch("vidora-subscriptions.json") } }
            item { SwitchRow(stringResource(R.string.ui_incognito_mode), stringResource(R.string.ui_pause_watch_search_history_also_in_the_l), s.incognito) { v -> update { it.copy(incognito = v) } } }
            item { SwitchRow(stringResource(R.string.ui_save_watch_history), stringResource(R.string.ui_stored_only_on_this_device), s.saveHistory) { v -> update { it.copy(saveHistory = v) } } }
            item { SwitchRow(stringResource(R.string.ui_save_search_history), null, s.saveSearchHistory) { v -> update { it.copy(saveSearchHistory = v) } } }
            item { ClickRow(stringResource(R.string.ui_clear_search_history), null) { Graph.library.clearSearchHistory(); Toast.makeText(context, "Search history cleared", Toast.LENGTH_SHORT).show() } }
            item { ClickRow(stringResource(R.string.ui_clear_watch_history_2), null) { Graph.library.clearHistory(); Toast.makeText(context, "Watch history cleared", Toast.LENGTH_SHORT).show() } }
            item { ClickRow(stringResource(R.string.ui_export_library), stringResource(R.string.ui_back_up_subscriptions_likes_history)) { exportLauncher.launch("vidora-backup.json") } }
            item { ClickRow(stringResource(R.string.ui_import_library), stringResource(R.string.ui_restore_from_a_backup_file)) { importLauncher.launch(arrayOf("application/json", "*/*")) } }
            item { ClickRow(stringResource(R.string.ui_reset_all_settings_2), null) { dialog = "reset" } }

            // ------------------------------------------------------------ advanced
            item { Header(stringResource(R.string.h_advanced)) }
            item {
                SwitchRow(stringResource(R.string.ui_use_potoken_botguard), "Generates YouTube integrity tokens locally. Used when YouTube blocks the normal player (VPN / \"not a bot\" fallback). Status: ${com.vidora.app.potoken.PoTokenProviderImpl.lastStatus}", s.poToken) { v ->
                    update { it.copy(poToken = v) }
                }
            }
            item { SwitchRow(stringResource(R.string.ui_check_for_updates_automatically), "From github.com/${com.vidora.app.util.Updater.REPO} releases", s.autoUpdateCheck) { v -> update { it.copy(autoUpdateCheck = v) } } }
            item {
                ClickRow(if (checkingUpdate) "Checking…" else "Check for updates now", "Current version ${BuildConfig.VERSION_NAME}") {
                    if (checkingUpdate) return@ClickRow
                    checkingUpdate = true
                    scope.launch {
                        val r = runCatching { com.vidora.app.util.Updater.check() }
                        checkingUpdate = false
                        r.onSuccess { info ->
                            if (info == null) Toast.makeText(context, "You're on the latest version", Toast.LENGTH_SHORT).show() else updateInfo = info
                        }.onFailure { Toast.makeText(context, "Update check failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            item {
                ClickRow(stringResource(R.string.ui_report_a_problem), stringResource(R.string.ui_opens_a_github_issue_with_device_details)) {
                    val body = com.vidora.app.util.CrashReporter.report(null, "Describe the problem:")
                    val url = "https://github.com/${com.vidora.app.util.Updater.REPO}/issues/new?body=" + android.net.Uri.encode(body)
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                }
            }

            // ------------------------------------------------------------ about
            item { Header(stringResource(R.string.h_about)) }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${s.displayName} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Ad-free · background play · downloads · optional sign-in, no tracking. " +
                                "Powered by the open-source NewPipe Extractor (GPLv3), Media3/ExoPlayer, SponsorBlock, " +
                                "DeArrow and Return YouTube Dislike (returnyoutubedislike.com).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    updateInfo?.let { info -> com.vidora.app.ui.components.UpdateDialog(info) { updateInfo = null } }
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
        "signout" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.ui_sign_out_2)) },
            text = { Text(stringResource(R.string.ui_your_youtube_session_will_be_removed_fro)) },
            confirmButton = { TextButton(onClick = { Graph.account.signOut(); dialog = null }) { Text(stringResource(R.string.ui_sign_out)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.ui_cancel)) } },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } }
    )
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { "Vidora" }); onDismiss() }) { Text(stringResource(R.string.ui_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}

private val CaptionLanguages = listOf(
    "en" to "English", "bn" to "বাংলা (Bengali)", "hi" to "हिन्दी (Hindi)", "ur" to "اردو (Urdu)", "ar" to "العربية (Arabic)",
    "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português", "ru" to "Русский",
    "ja" to "日本語", "ko" to "한국어", "zh-Hans" to "中文 (简体)", "id" to "Bahasa Indonesia", "tr" to "Türkçe",
    "it" to "Italiano", "ta" to "தமிழ்", "te" to "తెలుగు", "ml" to "മലയാളം", "vi" to "Tiếng Việt",
)
