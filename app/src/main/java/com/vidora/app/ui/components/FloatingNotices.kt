package com.vidora.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vidora.app.Graph
import com.vidora.app.ui.icons.BrandGlyphs
import com.vidora.app.ui.icons.IosGlyphs
import com.vidora.app.ui.theme.GlassWindowBlur
import com.vidora.app.ui.theme.glassEdge
import com.vidora.app.util.UpdateInfo
import com.vidora.app.util.UpdateState
import com.vidora.app.util.Updater
import kotlinx.coroutines.launch

/**
 * A floating glass card over the app (its own window, so it sits above the player, sheets and
 * the navigation bar). [bottom] = slides up from the bottom like an iOS action card; otherwise
 * it pops in at the centre.
 */
@Composable
fun FloatingCard(
    onDismiss: () -> Unit,
    bottom: Boolean = true,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOnOutside),
    ) {
        GlassWindowBlur()
        Box(
            Modifier.fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null) { if (dismissOnOutside) onDismiss() }
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            contentAlignment = if (bottom) Alignment.BottomCenter else Alignment.Center,
        ) {
            AnimatedVisibility(
                shown,
                enter = if (bottom) slideInVertically { it / 2 } + fadeIn() else scaleIn(initialScale = 0.9f) + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
            ) {
                val shape = RoundedCornerShape(28.dp)
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                        .border(0.7.dp, glassEdge(), shape)
                        .clickable(remember { MutableInteractionSource() }, null) { } // swallow taps
                        .padding(20.dp),
                    content = content,
                )
            }
        }
    }
}

/** iOS-style rounded icon tile. */
@Composable
fun NoticeTile(icon: ImageVector, color: Color, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.26f).dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size((size * 0.62f).dp), tint = Color.White) }
}

// ====================================================================== welcome (first launch)

private const val PREFS = "vidora_onboarding"

fun shouldShowWelcome(context: Context): Boolean =
    !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("welcome_done", false)

private fun markWelcomeDone(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("welcome_done", true).apply()

/** Hands the Google account chosen on the welcome card to the sign-in page. */
object LoginHint {
    var email: String? = null
    /** Skip the explanation page and go straight to Google. */
    var direct: Boolean = false
}

/**
 * First-launch welcome. "Sign in with Google" shows the phone's own Google-account picker, then
 * opens Google's sign-in page with that account already filled in — Google then usually just asks
 * to confirm on the phone ("Yes, it's me") or for the password once.
 */
@Composable
fun WelcomeCard(onSignIn: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        LoginHint.email = r.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.takeIf { r.resultCode == Activity.RESULT_OK }
        LoginHint.direct = true
        markWelcomeDone(context)
        onSignIn()
    }
    fun skip() { markWelcomeDone(context); onDone() }
    FloatingCard(onDismiss = ::skip, bottom = false, dismissOnOutside = false) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF375F), Color(0xFFFF2D55), Color(0xFFBF1E5A)))),
                contentAlignment = Alignment.Center,
            ) { Icon(IosGlyphs.playFill, null, Modifier.size(42.dp), tint = Color.White) }
            Spacer(Modifier.height(14.dp))
            Text("Welcome to Vidora", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                "YouTube the way it should be — no ads, plays in the background, downloads anything.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            WelcomePoint(IosGlyphs.shieldFill, Color(0xFF34C759), "No ads, ever", "Plus SponsorBlock skips sponsor segments")
            WelcomePoint(IosGlyphs.playCircleFill, Color(0xFFFF2D55), "Background & PiP", "Keep listening with the screen off")
            WelcomePoint(IosGlyphs.arrowDownCircleFill, Color(0xFF30B0C7), "Downloads", "Video or audio in the quality you pick")
            WelcomePoint(IosGlyphs.personCropCircleFill, Color(0xFF007AFF), "Your YouTube", "Subscriptions, likes, history & recommendations")
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    val intent = runCatching {
                        android.accounts.AccountManager.newChooseAccountIntent(
                            null, null, arrayOf("com.google"), null, null, null, null
                        )
                    }.getOrNull()
                    if (intent != null) runCatching { picker.launch(intent) }.onFailure {
                        LoginHint.direct = true; markWelcomeDone(context); onSignIn()
                    } else { LoginHint.direct = true; markWelcomeDone(context); onSignIn() }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).border(1.dp, Color(0xFFDADCE0), RoundedCornerShape(26.dp)),
            ) {
                Image(rememberVectorPainter(BrandGlyphs.googleG), null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = ::skip, modifier = Modifier.fillMaxWidth()) { Text("Continue without signing in") }
            Text(
                "You sign in on Google's own page — Vidora never sees your password.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WelcomePoint(icon: ImageVector, color: Color, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NoticeTile(icon, color, 36)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ====================================================================== exit confirmation

@Composable
fun ExitCard(activity: Activity, onDismiss: () -> Unit) {
    val playing = Graph.player.state.collectAsState().value.let { it.current != null && it.isPlaying }
    FloatingCard(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoticeTile(IosGlyphs.squareArrowRight, Color(0xFFFF3B30))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Exit Vidora?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (playing) "Exiting stops the video. Keep it playing in the background instead?" else "See you soon!",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (playing) {
            FilledTonalButton(onClick = { onDismiss(); activity.moveTaskToBack(true) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Keep playing in background")
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
            Button(
                onClick = { onDismiss(); Graph.player.stop(); activity.finishAndRemoveTask() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30), contentColor = Color.White),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Exit", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ====================================================================== update available

/** Floating "new version" card with Update / Later (and download progress). */
@Composable
fun UpdateCardNotice(info: UpdateInfo, onLater: () -> Unit) {
    val context = LocalContext.current
    val st by Updater.state.collectAsState()
    val progress = (st as? UpdateState.Downloading)?.progress
    val error = (st as? UpdateState.Failed)?.message
    var notes by remember { mutableStateOf(false) }
    LaunchedEffect(st) { if (st is UpdateState.Installing) onLater() }
    FloatingCard(onDismiss = { if (progress == null) onLater() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoticeTile(IosGlyphs.arrowDownCircleFill, Color(0xFF007AFF))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Update available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Vidora ${info.version}" + (if (info.sizeBytes > 0) " · ${info.sizeBytes / 1_048_576} MB" else "") + " is ready to install",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (info.notes.isNotBlank()) {
            Text(
                if (notes) "Hide what's new" else "What's new",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { notes = !notes }.padding(vertical = 4.dp),
            )
            if (notes) Text(
                com.vidora.app.ui.PublicText.releaseNotes(info.notes).take(1500), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            )
        }
        progress?.let {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
            Text("Downloading… ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(enabled = progress == null, onClick = onLater, modifier = Modifier.weight(1f).height(48.dp)) { Text("Later") }
            Button(
                enabled = progress == null,
                onClick = {
                    if (info.apkUrl == null) {
                        Toast.makeText(context, "Update file is not available yet. Please try later.", Toast.LENGTH_LONG).show(); return@Button
                    }
                    if (!Updater.canInstall(context)) {
                        Toast.makeText(context, "Allow Vidora to install updates, then tap Update again", Toast.LENGTH_LONG).show()
                        Updater.openInstallPermission(context); return@Button
                    }
                    Graph.appScope.launch { Updater.downloadAndInstall(context.applicationContext, info) }
                },
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(if (progress != null) "Downloading…" else "Update", fontWeight = FontWeight.SemiBold) }
        }
    }
}
