package com.vidora.app.data

import android.content.Context
import com.vidora.app.Graph
import com.vidora.app.util.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * The live "About" page. Its text lives in the GitHub repo as `about/ABOUT.md`, so the owner can
 * edit it from the GitHub app / website on a phone and every installed copy shows the change the
 * next time About is opened — no app update needed.
 *
 * Source order: GitHub contents API on `main` (fresh, no CDN delay) → the development branch
 * (until the PR is merged) → raw.githubusercontent.com. The last good copy is cached on disk and a
 * built-in copy is used when the device has never been online.
 */
class AboutRepository(private val context: Context) {
    data class About(val markdown: String, val fetchedAt: Long, val source: Source)
    enum class Source { LIVE, CACHE, BUILT_IN }

    private val cacheFile get() = File(context.filesDir, "about_live.md")
    private val prefs get() = context.getSharedPreferences("about_live", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadCached())
    val state: StateFlow<About> = _state

    // ------------------------------------------------------------ developer profile card
    /** The owner's card (photo, name, bio, WhatsApp, email…) from `about/developer.txt`. */
    data class Developer(val fields: Map<String, String>, val ref: String, val fetchedAt: Long) {
        operator fun get(key: String): String? = fields[key]?.trim()?.takeIf { it.isNotEmpty() }
        val name get() = get("name")
        /** `photo: photo.jpg` (a file uploaded next to developer.txt) or any https link. */
        val photoUrl: String?
            get() = get("photo")?.let { p ->
                if (p.startsWith("http://") || p.startsWith("https://")) p
                else "https://raw.githubusercontent.com/${Updater.REPO}/$ref/about/${p.removePrefix("/").removePrefix("about/")}" +
                    "?v=${fetchedAt / 3_600_000}"
            }
    }

    private val devFile get() = File(context.filesDir, "about_developer.txt")
    private val _developer = MutableStateFlow(loadDeveloperCached())
    val developer: StateFlow<Developer?> = _developer

    private fun loadDeveloperCached(): Developer? {
        val f = devFile
        if (!f.exists() || f.length() == 0L) return null
        return Developer(parseFields(f.readText()), prefs.getString("dev_ref", REFS.last()) ?: REFS.last(), prefs.getLong("dev_at", 0))
    }

    private suspend fun refreshDeveloper() = withContext(Dispatchers.IO) {
        for (ref in REFS) {
            val text = runCatching { fetchApi(DEV_PATH, ref, devFile, "dev") }.getOrNull()
                ?: runCatching { fetchRaw(DEV_PATH, ref) }.getOrNull()
                ?: continue
            devFile.writeText(text)
            val now = System.currentTimeMillis()
            prefs.edit().putString("dev_ref", ref).putLong("dev_at", now).apply()
            _developer.value = Developer(parseFields(text), ref, now)
            return@withContext
        }
    }

    private fun loadCached(): About {
        val f = cacheFile
        return if (f.exists() && f.length() > 0) About(f.readText(), prefs.getLong("at", 0), Source.CACHE)
        else About(BUILT_IN, 0, Source.BUILT_IN)
    }

    /** Fetches the newest text. Returns null on success, or an error message (the cache stays). */
    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        runCatching { refreshDeveloper() }
        var lastError: String? = null
        for (ref in REFS) {
            try {
                val text = fetchApi(PATH, ref, cacheFile, "") ?: continue
                save(text, ref)
                return@withContext null
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        for (ref in REFS) {
            try {
                val text = fetchRaw(PATH, ref) ?: continue
                save(text, ref)
                return@withContext null
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        lastError ?: "About page not found on GitHub"
    }

    private fun save(text: String, ref: String) {
        cacheFile.writeText(text)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("at", now).putString("ref", ref).apply()
        _state.value = About(text, now, Source.LIVE)
    }

    /** GitHub contents API with the raw media type; uses ETags so unchanged pages cost nothing. */
    private fun fetchApi(path: String, ref: String, cache: File, key: String): String? {
        val etagKey = "etag$key:$ref"
        val refKey = if (key.isEmpty()) "ref" else "${key}_ref"
        val req = Request.Builder()
            .url("https://api.github.com/repos/${Updater.REPO}/contents/$path?ref=" + android.net.Uri.encode(ref))
            .header("Accept", "application/vnd.github.raw")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { prefs.getString(etagKey, null)?.takeIf { prefs.getString(refKey, null) == ref && cache.exists() }?.let { header("If-None-Match", it) } }
            .build()
        Graph.http.newCall(req).execute().use { r ->
            if (r.code == 304) return cache.readText()
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("GitHub HTTP ${r.code}")
            val body = r.body?.string().orEmpty()
            if (body.isBlank()) return null
            r.header("ETag")?.let { prefs.edit().putString(etagKey, it).apply() }
            return body
        }
    }

    private fun fetchRaw(path: String, ref: String): String? {
        val req = Request.Builder()
            .url("https://raw.githubusercontent.com/${Updater.REPO}/$ref/$path?t=${System.currentTimeMillis() / 60_000}")
            .header("Cache-Control", "no-cache")
            .build()
        Graph.http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        const val PATH = "about/ABOUT.md"
        const val DEV_PATH = "about/developer.txt"
        fun editDeveloperUrl(ref: String = REFS.last()) = "https://github.com/${Updater.REPO}/edit/$ref/$DEV_PATH"
        fun uploadPhotoUrl(ref: String = REFS.last()) = "https://github.com/${Updater.REPO}/upload/$ref/about"

        /** `key: value` lines; `#` lines are notes. Keys are case-insensitive. */
        fun parseFields(text: String): Map<String, String> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
            .associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
        val REFS = listOf("main", "arena/4ab58c16-youtube")
        fun editUrl(ref: String = "main") = "https://github.com/${Updater.REPO}/edit/$ref/$PATH"

        private val BUILT_IN = """
            # Vidora
            Ad-free YouTube with background play, downloads and a clean glass design.

            ## Features
            - No ads, SponsorBlock, DeArrow and Return YouTube Dislike
            - Background and picture-in-picture playback
            - Download video or audio in any quality
            - Optional YouTube sign-in for your own feeds

            ## Open source
            Powered by NewPipe Extractor (GPLv3), Media3/ExoPlayer and Framework7 Icons (MIT).

            [Source code on GitHub](https://github.com/sremonak/YouTube)
        """.trimIndent()
    }
}
