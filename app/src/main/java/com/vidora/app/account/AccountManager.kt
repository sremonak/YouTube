package com.vidora.app.account

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class AccountSession(
    val cookies: String,
    val name: String? = null,
    val handle: String? = null,
    val avatar: String? = null,
    val signedInAt: Long = System.currentTimeMillis(),
)

/**
 * Holds the signed-in YouTube session (cookies captured from the official Google sign-in page).
 * Cookies are encrypted at rest and are only ever sent to youtube.com.
 */
class AccountManager(private val context: Context, private val scope: CoroutineScope) {
    private val store = SecureStore(context, "account")
    private val json = Json { ignoreUnknownKeys = true }

    private val _session = MutableStateFlow(load())
    val session: StateFlow<AccountSession?> = _session.asStateFlow()

    val isSignedIn: Boolean get() = _session.value != null

    /** When true for the current thread, extractor requests to youtube.com carry the account. */
    private val authForThread = ThreadLocal<Boolean>()

    private fun load(): AccountSession? = store.read()?.let {
        runCatching { json.decodeFromString(AccountSession.serializer(), it) }.getOrNull()
    }

    fun save(session: AccountSession) {
        _session.value = session
        scope.launch(Dispatchers.IO) { store.write(json.encodeToString(AccountSession.serializer(), session)) }
    }

    /**
     * Google rotates session cookies (SIDCC, __Secure-*PSIDTS…). Browsers keep up by applying
     * Set-Cookie headers; we do the same for cookies we already hold, so the sign-in doesn't
     * silently expire after a few days. Deletions are ignored on purpose.
     */
    fun mergeSetCookies(setCookies: List<String>) {
        val current = _session.value ?: return
        if (setCookies.isEmpty()) return
        val jar = LinkedHashMap<String, String>()
        current.cookies.split(';').forEach { c ->
            val i = c.indexOf('=')
            if (i > 0) jar[c.substring(0, i).trim()] = c.substring(i + 1).trim()
        }
        var changed = false
        for (header in setCookies) {
            val pair = header.substringBefore(';')
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val name = pair.substring(0, i).trim()
            val value = pair.substring(i + 1).trim()
            val attrs = header.lowercase()
            val deleting = value.isEmpty() || value == "EXPIRED" || value == "deleted" ||
                attrs.contains("max-age=0") || attrs.contains("expires=thu, 01 jan 1970") || attrs.contains("expires=thu, 01-jan-1970")
            if (deleting || name !in jar || jar[name] == value) continue
            jar[name] = value
            changed = true
        }
        if (changed) save(current.copy(cookies = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }))
    }

    fun signOut() {
        _session.value = null
        store.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** Runs [block] (blocking extractor code) with the account attached to youtube.com requests. */
    fun <T> withAuth(block: () -> T): T {
        val previous = authForThread.get()
        authForThread.set(true)
        try {
            return block()
        } finally {
            authForThread.set(previous)
        }
    }

    fun authActiveOnThisThread(): Boolean = authForThread.get() == true && isSignedIn

    fun cookieValue(name: String): String? = _session.value?.cookies
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

    /**
     * Builds the request headers YouTube's web client sends when signed in:
     * the session cookies plus the SAPISIDHASH authorization derived from them.
     */
    fun authHeaders(origin: String = "https://www.youtube.com"): Map<String, String> {
        val s = _session.value ?: return emptyMap()
        val headers = linkedMapOf("Cookie" to s.cookies, "X-Origin" to origin, "X-Goog-AuthUser" to "0")
        val ts = System.currentTimeMillis() / 1000
        val parts = buildList {
            (cookieValue("SAPISID") ?: cookieValue("__Secure-3PAPISID"))?.let { add("SAPISIDHASH ${ts}_${sha1("$ts $it $origin")}") }
            cookieValue("__Secure-1PAPISID")?.let { add("SAPISID1PHASH ${ts}_${sha1("$ts $it $origin")}") }
            cookieValue("__Secure-3PAPISID")?.let { add("SAPISID3PHASH ${ts}_${sha1("$ts $it $origin")}") }
        }
        if (parts.isNotEmpty()) headers["Authorization"] = parts.joinToString(" ")
        return headers
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        fun hasSessionCookies(cookies: String?): Boolean {
            if (cookies.isNullOrBlank()) return false
            val names = cookies.split(';').map { it.substringBefore('=').trim() }.toSet()
            return ("SAPISID" in names || "__Secure-3PAPISID" in names) && ("SID" in names || "__Secure-3PSID" in names)
        }
    }
}
