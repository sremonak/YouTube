package com.vidora.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.vidora.app.R
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.vidora.app.Graph
import com.vidora.app.account.AccountManager
import com.vidora.app.account.AccountSession
import kotlinx.coroutines.launch

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"

/**
 * Sign in on Google's own page inside a WebView. Vidora never sees the password — it only keeps
 * the resulting youtube.com session cookies (encrypted on the device).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(nav: NavHostController) {
    var started by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (started) "Sign in with Google" else "Sign in") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (!started) Intro(onContinue = { started = true }, onCookiesPasted = { finishWithCookies(it, nav, context, scope) })
        else SignInWebView(nav)
    }
}

@Composable
private fun Intro(onContinue: () -> Unit, onCookiesPasted: (String) -> Unit) {
    var pasteDialog by remember { mutableStateOf(false) }
    if (pasteDialog) CookieDialog(onDismiss = { pasteDialog = false }, onSave = { pasteDialog = false; onCookiesPasted(it) })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.ui_sign_in_to_your_youtube_account), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlock age-restricted videos, fix \"confirm you're not a bot\" errors, and get your recommendations, " +
                    "subscriptions, liked videos, watch later and history — still with no ads.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Point(Icons.Rounded.Lock, "You type your password on Google's own sign-in page. Vidora never sees it.")
            Point(Icons.Rounded.Shield, "Only the session is kept, encrypted with the Android Keystore, and sent only to youtube.com.")
            Point(Icons.Rounded.VerifiedUser, "Sign out any time from Settings — the session is wiped from the device.")
            Spacer(Modifier.height(12.dp))
            Text(
                "Tip: third-party apps are not officially supported by Google. If you're worried about your main " +
                    "account, use a secondary Google account.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.ui_continue_to_google_sign_in)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { pasteDialog = true }) { Text(stringResource(R.string.ui_google_sign_in_blocked_paste_cookies_ins)) }
        }
    }
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
    AlertDialog(
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
private fun SignInWebView(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }

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
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return
        val host = uri.host.orEmpty()
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
        AndroidView(
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().removeAllCookies(null)
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // Present a regular mobile Chrome user agent (Google rejects the "wv" WebView marker).
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv", "")
                        .replace(Regex("Version/\\d+\\.\\d+ "), "")
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            tryFinish(url, pageDone = false)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            tryFinish(url, pageDone = true)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme
                            // keep everything inside the WebView; block intent:// / app links
                            return scheme != "https" && scheme != "http"
                        }
                    }
                    loadUrl(SIGN_IN_URL)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
            modifier = Modifier.fillMaxSize()
        )
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
