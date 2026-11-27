package com.vidora.app.data

/** Explainable local heuristics, not inferred location/language or a server tracking profile. */
object HomeRanking {
    val interests = listOf("science", "technology", "music", "nature", "travel", "cooking", "education", "gaming")
    val languages = listOf("" to "Any language", "bn" to "Bangla", "en" to "English", "hi" to "Hindi", "ur" to "Urdu", "ar" to "Arabic", "es" to "Spanish", "fr" to "French")
    fun languageTerm(code: String) = languages.firstOrNull { it.first == code && code.isNotBlank() }?.second.orEmpty()
    fun score(video: VideoItem, s: AppSettings, watched: Set<String>, now: Long): Int {
        val text = "${video.title} ${video.uploader.orEmpty()}".lowercase()
        val interests = s.homeInterests.count { text.contains(it.lowercase()) } * 15
        val hidden = if (video.url in s.lessLikeVideos) -200 else 0
        val seen = if (video.url in watched) -40 else 0
        val age = now - video.uploadedEpoch
        val fresh = if (video.uploadedEpoch > 0 && age in 0..(7 * 86_400_000L)) 10 else 0
        return interests + hidden + seen + fresh
    }
    fun rank(rows: List<Entry>, settings: AppSettings, watched: Set<String>, now: Long = System.currentTimeMillis()): List<Entry> {
        val sorted = rows.filterNot { it is Entry.Video && it.video.url in settings.lessLikeVideos }
            .sortedByDescending { (it as? Entry.Video)?.let { v -> score(v.video, settings, watched, now) } ?: 0 }
        val creators = hashMapOf<String, Int>()
        // Greedy diversity pass: no creator's second video before all available first choices.
        return sorted.map { row ->
            val v = (row as? Entry.Video)?.video
            val key = v?.uploaderUrl ?: "${v?.platform}:${v?.uploader}"
            val count = creators[key] ?: 0
            creators[key] = count + 1
            count to row
        }.sortedBy { it.first }.map { it.second }
    }
    fun why(country: String, s: AppSettings, video: VideoItem): String = buildString {
        append("Explore mixes ${GuestSources.countryName(country)}-topic results with public discovery. This is relevance, not the creator's location.")
        if (s.homeLanguage.isNotBlank()) append(" Preferred query language: ${languageTerm(s.homeLanguage)}; not a verified language filter.")
        if (s.homeInterests.isNotEmpty()) append(" Local interests: ${s.homeInterests.sorted().joinToString()}.")
        append(" Creator diversity, freshness and watched history influence order within incoming source batches. Source: ${video.platform}.")
    }
}
