package com.neiro.aftertone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDomainTest {
    @Test
    fun addingAnExistingTrackDoesNotDuplicateIt() {
        val playlist = Playlist(id = "mix", name = "Mix", trackIds = listOf("one"))

        assertEquals(listOf("one", "two"), playlist.withTrack("two").withTrack("two").trackIds)
    }

    @Test
    fun removingATrackRemovesEveryReference() {
        val playlist = Playlist(id = "mix", name = "Mix", trackIds = listOf("one", "two", "two"))

        assertEquals(listOf("one"), playlist.withoutTrack("two").trackIds)
    }

    @Test
    fun importedFilenameNeverUsesTheDocumentPath() {
        val result = TrackFileName.forImport(
            id = "track-01",
            displayName = "../../private/song.mp3",
            mimeType = "audio/mpeg",
        )

        assertEquals("track-01.mp3", result)
    }

    @Test
    fun rejectsNonAudioDocuments() {
        assertNull(TrackFileName.forImport("doc-01", "notes.txt", "text/plain"))
    }

    @Test
    fun mapsAudioMimeWhenTheExtensionIsMisleading() {
        assertEquals("track-02.m4a", TrackFileName.forImport("track-02", "audio.exe", "audio/mp4"))
    }

    @Test
    fun rejectsAnUnsafeGeneratedIdentifier() {
        assertNull(TrackFileName.forImport("../outside", "song.mp3", "audio/mpeg"))
    }

    @Test
    fun persistedFileNameMustMatchTrackIdAndAnAudioExtension() {
        assertTrue(TrackFileName.isGeneratedFileName("track-1", "track-1.m4a"))
        assertFalse(TrackFileName.isGeneratedFileName("track-1", "track-2.m4a"))
        assertFalse(TrackFileName.isGeneratedFileName("track-1", "track-1.exe"))
    }
}
