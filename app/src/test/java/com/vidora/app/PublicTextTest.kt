package com.vidora.app

import com.vidora.app.ui.PublicText
import org.junit.Assert.*
import org.junit.Test

class PublicTextTest {
    @Test fun cachedAndLiveRepositoryLinksHidden() {
        val result = PublicText.about("# Vidora\n[Code](https://github.com/owner/repo)\nEdit on GitHub\nBackground play")
        assertEquals("# Vidora\nBackground play", result)
    }

    @Test fun rawContentAndProfileUrlsRecognized() {
        assertTrue(PublicText.isRepository("https://raw.githubusercontent.com/owner/repo/main/pic.jpg"))
        assertTrue(PublicText.isRepository("https://GITHUB.COM/owner"))
        assertFalse(PublicText.isRepository("https://wa.me/8801700000000"))
    }

    @Test fun updateNotesDoNotExposeLinks() {
        val notes = PublicText.releaseNotes("New player\nhttps://example.com/details\nhttps://github.com/owner/repo/releases")
        assertEquals("New player", notes)
    }
    @Test fun distributionSourceFooterIsNotAnInAppChangelog() {
        val notes = "Easier Google sign-in\n\n## Corresponding source (GPLv3)\nSource archive: https://github.com/owner/repo/archive/v1.zip"
        assertEquals("Easier Google sign-in", PublicText.releaseNotes(notes))
    }
}
