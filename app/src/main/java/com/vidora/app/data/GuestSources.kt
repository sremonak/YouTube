package com.vidora.app.data

import java.net.URI
import java.util.Locale

/** Public content providers, not mirrors of a blocked YouTube video. */
enum class VideoSource(val label: String) { ALL("All sources"), PUBLIC("Public alternatives"), YOUTUBE("YouTube"), PEERTUBE("PeerTube"), COMMONS("Commons") }

object GuestSources {
    val peerBases = listOf("https://tilvids.com", "https://framatube.org", "https://video.blender.org")
    private val peerHosts = peerBases.map { URI(it).host }.toSet()
    fun isPeer(url: String) = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host in peerHosts && u.userInfo == null && u.port in listOf(-1, 443) &&
            u.path.matches(Regex("/(w|videos/watch)/[A-Za-z0-9-]{10,64}/?")) && peerId(url) != null
    }.getOrDefault(false)
    fun isPeerChannel(url: String) = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host in peerHosts && u.userInfo == null && u.port in listOf(-1, 443) &&
            u.path.matches(Regex("/video-channels/[A-Za-z0-9_.@-]+/?"))
    }.getOrDefault(false)
    fun peerId(url: String): String? = runCatching { URI(url).path.trimEnd('/').substringAfterLast('/') }
        .getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{10,64}")) }
    fun commonsTitle(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme != "https" || u.host != "commons.wikimedia.org" || u.userInfo != null || u.port !in listOf(-1, 443)) return@runCatching null
        if (!u.rawPath.startsWith("/wiki/File:")) return@runCatching null
        // URI.path already percent-decodes; preserve literal + in a file name.
        u.path.removePrefix("/wiki/").takeIf { it.startsWith("File:") && it.length > 5 }
    }.getOrNull()
    fun isCommons(url: String) = commonsTitle(url) != null
    fun supported(url: String) = isPeer(url) || isCommons(url)
    fun commonsUrl(title: String): String = "https://commons.wikimedia.org/wiki/" +
        java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%3A", ":").replace("+", "%20")

    fun country(value: String?): String? = value?.uppercase(Locale.ROOT)?.takeIf { it in Locale.getISOCountries().toSet() }
    fun countryName(code: String): String = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { "World" }
    fun regionalQuery(code: String, topic: String = "culture nature music"): String = "${countryName(code)} $topic"

    /** Fair interleave without pretending one source's video is a copy of another. */
    fun interleave(pages: List<List<Entry>>): List<Entry> = buildList {
        val seen = hashSetOf<String>()
        for (i in 0 until (pages.maxOfOrNull { it.size } ?: 0)) {
            pages.forEach { rows -> rows.getOrNull(i)?.let { if (seen.add(it.key)) add(it) } }
        }
    }
}
