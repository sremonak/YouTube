package com.vidora.app.ui

/** Cosmetic UI policy only: endpoints still exist in the binary and on the network. */
object PublicText {
    private val repositoryMention = Regex("github|githubusercontent|githubassets", RegexOption.IGNORE_CASE)
    private val url = Regex("https?://[^\\s)<>]+", RegexOption.IGNORE_CASE)

    fun isRepository(value: String): Boolean = repositoryMention.containsMatchIn(value)

    // Also applies to cached/older live About content so a release doesn't depend on a remote edit.
    fun about(value: String): String = value.lineSequence().filterNot(::isRepository).joinToString("\n")

    fun releaseNotes(value: String): String = about(value).replace(url, "").trim()
}
