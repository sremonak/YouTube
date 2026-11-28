package com.vidora.app.account

import java.net.URI
import java.net.URLEncoder

/** Official Google pages only; an email hint is not authorization or a passwordless credential. */
internal object GoogleSignInLinks {
    private const val SIGN_IN = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"
    const val FIND_EMAIL = "https://accounts.google.com/signin/usernamerecovery"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    fun signIn(email: String?): String = email?.trim()?.takeIf { it.isNotEmpty() }?.let {
        SIGN_IN + "&Email=" + encode(it) + "&login_hint=" + encode(it)
    } ?: SIGN_IN

    fun recovery(email: String?): String = "https://accounts.google.com/signin/recovery" +
        (email?.trim()?.takeIf { it.isNotEmpty() }?.let { "?Email=" + encode(it) } ?: "")

    private val hosts = setOf(
        "accounts.google.com", "www.google.com", "myaccount.google.com", "consent.google.com", "policies.google.com", "support.google.com",
        "accounts.youtube.com", "youtube.com", "www.youtube.com", "m.youtube.com", "consent.youtube.com",
    )

    fun trustedHost(url: String?): String? = runCatching {
        val uri = URI(url ?: return null)
        uri.host?.lowercase()?.takeIf {
            uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && it in hosts
        }
    }.getOrNull()
}
