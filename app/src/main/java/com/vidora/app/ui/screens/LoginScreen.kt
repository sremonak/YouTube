package com.vidora.app.ui.screens

import com.vidora.app.ui.theme.GlassAlertDialog
import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.vidora.app.ui.icons.ArrowBack
import com.vidora.app.ui.icons.Lock
import com.vidora.app.ui.icons.Shield
import com.vidora.app.ui.icons.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.account.AccountManager
import com.vidora.app.account.AccountSession
import com.vidora.app.account.GoogleSignInLinks
import com.vidora.app.ui.components.LoginHint
import com.vidora.app.ui.components.rememberGoogleAccountPicker
import com.vidora.app.ui.icons.BrandGlyphs
import kotlinx.coroutines.launch

/**
 * Sign in on Google's own page inside a WebView. Vidora never sees the password — it only keeps
 * the resulting youtube.com session cookies (encrypted on the device).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(nav: NavHostController) {
    var selectedEmail by rememberSaveable { mutableStateOf(LoginHint.email.also { LoginHint.email = null }) }
    var started by rememberSaveable { mutableStateOf(LoginHint.direct.also { LoginHint.direct = false }) }
    var help by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooseAccount = rememberGoogleAccountPicker { email ->
        selectedEmail = email
        started = true
    }
    BackHandler(enabled = started) { started = false }
    if (help) SignInHelpDialog(selectedEmail, onDismiss = { help = false })
    com.vidora.app.ui.theme.GlassPage(header = {
        TopAppBar(
            title = { Text(if (started) "Sign in with Google" else "Sign in") },
            navigationIcon = { IconButton(onClick = { if (started) started = false else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { help = true }) { Text("Help") } },
            colors = com.vidora.app.ui.theme.glassBarColors()
        )
    }) {
        Column(Modifier.fillMaxSize()) {
            com.vidora.app.ui.theme.TopChromeSpacer()
            if (!started) Intro(
                onGoogle = chooseAccount,
                onManual = { selectedEmail = null; started = true },
                onHelp = { help = true },
                onGuest = { nav.popBackStack() },
                onCookiesPasted = { finishWithCookies(it, nav, context, scope) },
            ) else key(selectedEmail) {
                SignInWebView(nav, email = selectedEmail)
            }
        }
    }
}

@Composable
private fun Intro(onGoogle: () -> Unit, onManual: () -> Unit, onHelp: () -> Unit, onGuest: () -> Unit, onCookiesPasted: (String) -> Unit) {
    var pasteDialog by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (pasteDialog) CookieDialog(onDismiss = { pasteDialog = false }, onSave = { pasteDialog = false; onCookiesPasted(it) })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Use your Google account", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose an account already on this phone — no need to remember or type its email address.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onGoogle,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).border(1.dp, Color(0xFFDADCE0), RoundedCornerShape(26.dp)),
            ) {
                Image(BrandGlyphs.googleG, null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Sign in with Google")
            }
            TextButton(onClick = onManual) { Text("Use another account") }
            Spacer(Modifier.height(12.dp))
            com.vidora.app.ui.theme.GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Don't remember your password?", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "On Google's page, tap Try another way if offered. Google may let you confirm on your phone or use a passkey. Available options depend on your account and device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onHelp) { Text("Forgot email or password? Get help") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Point(Icons.Rounded.Lock, "Google verifies your identity. Choosing a phone account is not automatic authorization; Google may still request a password.")
            Point(Icons.Rounded.Shield, "Your YouTube session is stored encrypted on this phone. Vidora does not ask for your password in its own form.")
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onGuest) { Text("Continue without signing in") }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced options" else "Advanced options") }
            if (advanced) {
                Text("Google may block embedded sign-in in third-party apps. Cookie import is for experienced users only; never share session cookies with anyone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { pasteDialog = true }) { Text("Import YouTube session cookies") }
            }
        }
    }
}

@Composable
private fun SignInHelpDialog(email: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "No browser available. Open Google account recovery in your browser.", Toast.LENGTH_LONG).show() }
    }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google sign-in help") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("1. Tap Sign in with Google and choose an account on this phone. This fills its email; it does not grant access by itself.")
                Spacer(Modifier.height(10.dp))
                Text("2. On Google's page, look for Try another way. Use a phone confirmation, recovery number/email or passkey only if Google offers it. These options may not work in an embedded browser.")
                Spacer(Modifier.height(10.dp))
                Text("3. If you forgot the email, use Find my email. If you forgot the password, use Account recovery. Only Google can restore access.")
                Spacer(Modifier.height(10.dp))
                Text("Recovery opens in your browser. After recovery, return to Vidora to finish signing in. Signing in in your browser does not automatically sign Vidora in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { open(GoogleSignInLinks.recovery(email)) }) { Text("Account recovery") }
                TextButton(onClick = { open(GoogleSignInLinks.FIND_EMAIL) }) { Text("Find my email") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CookieDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val valid = AccountManager.hasSessionCookies(text.trim())
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_paste_youtube_cookies)) },
        text = {
            Column {
                Text(
                    "Sign in to youtube.com in a desktop browser, open developer tools → Network, select any request to " +
                        "www.youtube.com and copy the full \"Cookie\" request header here. It must contain SAPISID.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3, maxLines = 6,
                    placeholder = { Text("SID=…; HSID=…; SAPISID=…") },
                    isError = text.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(text.trim().removePrefix("Cookie:").trim()) }) { Text(stringResource(R.string.ui_sign_in)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

private fun finishWithCookies(cookies: String, nav: NavHostController, context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    com.vidora.app.ui.UiCache.clearAll()
    Graph.account.save(AccountSession(cookies = cookies))
    scope.launch {
        val info = runCatching { Graph.accountApi.accountInfo() }
        info.getOrNull()?.let { Graph.account.save(it) }
        if (info.isSuccess) com.vidora.app.account.AccountSync.importSubscriptionsIfStale(force = true)
        val name = Graph.account.session.value?.name
        val msg = when {
            name != null -> "Signed in as $name"
            info.isFailure -> "Saved, but YouTube didn't accept the cookies: ${info.exceptionOrNull()?.message}"
            else -> "Signed in"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        nav.popBackStack()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInWebView(nav: NavHostController, email: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("accounts.google.com") }

    var fallbackScheduled by remember { mutableStateOf(false) }

    fun finishNow(cookies: String) {
        if (finishing) return
        finishing = true
        CookieManager.getInstance().flush()
        finishWithCookies(cookies, nav, context, scope)
    }

    // Only finish once YouTube itself (www / m) has completed the sign-in hand-off: accounts.youtube.com
    // sets SID/SAPISID first, but LOGIN_INFO — without which YouTube treats requests as signed out —
    // is only set by www.youtube.com/signin.
    fun tryFinish(url: String?, pageDone: Boolean) {
        if (finishing || url == null) return
        val host = GoogleSignInLinks.trustedHost(url) ?: return
        if (host != "www.youtube.com" && host != "m.youtube.com" && host != "youtube.com") return
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return
        if (!AccountManager.hasSessionCookies(cookies)) return
        if (cookies.split(';').any { it.trim().startsWith("LOGIN_INFO=") }) { finishNow(cookies); return }
        if (pageDone && !fallbackScheduled) {
            fallbackScheduled = true
            scope.launch {
                kotlinx.coroutines.delay(4_000)
                CookieManager.getInstance().getCookie("https://www.youtube.com")
                    ?.takeIf { AccountManager.hasSessionCookies(it) }?.let(::finishNow)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text("Sign-in page · $host", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            AndroidView(
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        // Present a regular mobile Chrome user agent (Google rejects the "wv" WebView marker).
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "")
                            .replace(Regex("Version/\\d+\\.\\d+ "), "")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                val trusted = GoogleSignInLinks.trustedHost(url)
                                if (trusted == null) { view?.stopLoading(); return }
                                host = trusted
                                loading = true
                                tryFinish(url, pageDone = false)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                tryFinish(url, pageDone = true)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // Only restrict top-level navigation; Google challenges can use subframes.
                                if (request?.isForMainFrame == false) return false
                                return GoogleSignInLinks.trustedHost(request?.url?.toString()) == null
                            }
                        }
                        // Reuse this app's WebView session when possible; sign-out clears it.
                        // Device-account/browser sessions are separate and cannot be copied here.
                        loadUrl(GoogleSignInLinks.signIn(email))
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
        if (loading && !finishing) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        if (finishing) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.ui_finishing_sign_in))
                }
            }
        }
    }
}
