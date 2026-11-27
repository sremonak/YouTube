package com.vidora.app.ui.components

import android.accounts.AccountManager
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** User-consented email hint only. This does NOT obtain Google tokens or YouTube cookies. */
@Composable
fun rememberGoogleAccountPicker(onSelected: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val selected by rememberUpdatedState(onSelected)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Cancelling must not start sign-in or complete first-run onboarding.
        if (result.resultCode == Activity.RESULT_OK) {
            selected(result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
    return {
        try {
            launcher.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null))
        } catch (_: Exception) {
            Toast.makeText(context, "Account picker unavailable. Continue on Google's sign-in page.", Toast.LENGTH_LONG).show()
            selected(null)
        }
    }
}
