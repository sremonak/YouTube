package com.vidora.app.data

/** Global, user-configurable filters applied to every feed / search / channel list. */
object ContentFilter {
    fun encodeChannel(url: String, name: String) = "$url\t$name"
    fun channelUrl(encoded: String) = encoded.substringBefore('\t')
    fun channelName(encoded: String) = encoded.substringAfter('\t', encoded)

    fun allows(e: Entry, s: AppSettings, watchedUrls: Set<String>): Boolean {
        val blockedUrls = if (s.blockedChannels.isEmpty()) emptySet() else s.blockedChannels.map(::channelUrl).toHashSet()
        return when (e) {
            is Entry.Video -> {
                val v = e.video
                !(s.hideShorts && v.isShort) &&
                    !(s.hideWatched && v.url in watchedUrls) &&
                    (v.uploaderUrl == null || v.uploaderUrl !in blockedUrls) &&
                    s.blockedKeywords.none { k -> k.isNotBlank() && v.title.contains(k, ignoreCase = true) }
            }
            is Entry.Channel -> e.channel.url !in blockedUrls
            is Entry.Playlist -> s.blockedKeywords.none { k -> k.isNotBlank() && e.playlist.name.contains(k, ignoreCase = true) }
        }
    }
}
