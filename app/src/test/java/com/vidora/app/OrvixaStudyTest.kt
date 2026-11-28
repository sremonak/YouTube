package com.vidora.app

import com.vidora.app.data.*
import com.vidora.app.player.StudyTools
import com.vidora.app.util.AppBrand
import com.vidora.app.util.LauncherCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OrvixaStudyTest {
    @Test fun brandMigratesOnlyTheOldDefaultAndKeepsCustomNames() {
        assertEquals("Orvixa", AppBrand.displayName("Vidora"))
        assertEquals("Orvixa", AppBrand.displayName(null))
        assertEquals("My cinema", AppBrand.displayName("My cinema"))
        assertEquals("Orvixa", AppSettings().displayName)
        assertEquals("classic", LauncherCatalog.DEFAULT_ICON)
        assertEquals("vidora", LauncherCatalog.DEFAULT_NAME) // stable Android component identifiers
        assertEquals("Orvixa", LauncherCatalog.names.first { it.id == "vidora" }.label)
        assertEquals(24, LauncherCatalog.icons.size * LauncherCatalog.names.size)
    }
    @Test fun sectionRequiresOrderedFiniteNonNegativePositionsAndOneSecond() {
        assertFalse(StudyTools.validSection(-1, 2000))
        assertFalse(StudyTools.validSection(2000, 1000))
        assertFalse(StudyTools.validSection(1000, 1999))
        assertTrue(StudyTools.validSection(1000, 2000))
        assertTrue(StudyTools.validSection(Long.MAX_VALUE - 1000, Long.MAX_VALUE))
    }
    @Test fun customSleepRejectsInvalidInputInsteadOfSilentlyChangingIt() {
        listOf("", "0", "241", "-1", "1.5", "hello", "999999999999").forEach { assertNull(StudyTools.customMinutes(it)) }
        assertEquals(17, StudyTools.customMinutes(" 17 "))
        assertEquals(240, StudyTools.customMinutes("240"))
        assertEquals(0L, StudyTools.remaining(100, 200))
        assertEquals(100L, StudyTools.remaining(200, 100))
    }
    @Test fun notesParticipateInTokenSearchWithoutInventingHistory() {
        val moment = PlaybackBookmark("one", VideoItem("video", "Algebra lesson", uploader = "Teacher"), 1200, "First example", note = "Quadratic equations and practice")
        assertTrue(StudyTools.matches(moment, " QUADRATIC   teacher "))
        assertTrue(StudyTools.matches(moment, "example equations"))
        assertTrue(StudyTools.matches(moment, ""))
        assertFalse(StudyTools.matches(moment, "missing teacher"))
    }
    @Test fun oldMomentAndPlaylistBackupsGetEmptyNotesAndUnpinnedDefaults() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"bookmarks":[{"id":"m","video":{"url":"v","title":"Video"},"positionMs":1000,"label":"Moment"}],"playlists":[{"id":"p","name":"Playlist"}]}"""
        val library = json.decodeFromString(LibraryData.serializer(), old)
        assertEquals("", library.bookmarks.single().note)
        assertFalse(library.playlists.single().pinned)
        val updated = library.copy(bookmarks = library.bookmarks.map { it.copy(note = "My note") }, playlists = library.playlists.map { it.copy(pinned = true) })
        assertEquals(updated, json.decodeFromString(LibraryData.serializer(), json.encodeToString(LibraryData.serializer(), updated)))
        assertTrue(updated.history.isEmpty())
    }
}
