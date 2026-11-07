package com.neiro.aftertone.model

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAX_SUPPORTED_STATE_CHARS = 5 * 1024 * 1024

class LibraryPersistenceTest {
    @Test
    fun codecRoundTripsNonAsciiMetadataAndCollections() {
        val track = Track(
            id = "track-1",
            title = "Тёплый вечер: side A",
            artist = "Mira & North",
            album = "First Light",
            durationMs = 182_250,
            filePath = "/private/audio/track-1.mp3",
            artworkPath = "/private/audio/track-1.img",
        )
        val snapshot = LibrarySnapshot(
            tracks = listOf(track),
            playlists = listOf(Playlist("mix-1", "Evening set", listOf("track-1"))),
            favoriteTrackIds = setOf("track-1"),
        )

        assertEquals(snapshot, LibraryStateCodec.decode(LibraryStateCodec.encode(snapshot)))
    }

    @Test
    fun corruptedStateIsRejectedInsteadOfBecomingAnEmptyLibrary() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode("not a library file")
        }
    }

    @Test
    fun blankStoredStateIsRejectedInsteadOfBecomingAnEmptyLibrary() {
        listOf("", " \t\n").forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                LibraryStateCodec.decode(encoded)
            }
        }
    }

    @Test
    fun oversizedStateIsRejectedInsteadOfBecomingAnEmptyLibrary() {
        val oversized = "x".repeat(MAX_SUPPORTED_STATE_CHARS + 1)

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(oversized)
        }
    }

    @Test
    fun oversizedSnapshotCannotBeEncodedForPersistence() {
        val oversizedTitle = "x".repeat(MAX_SUPPORTED_STATE_CHARS + 1)
        val snapshot = LibrarySnapshot(
            tracks = listOf(
                Track(
                    id = "track-1",
                    title = oversizedTitle,
                    artist = "Artist",
                    filePath = "/private/audio/track-1.mp3",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.encode(snapshot)
        }
    }

    @Test
    fun pathGuardAllowsOnlyDirectChildrenOfThePrivateAudioDirectory() {
        val root = Files.createTempDirectory("aftertone-audio-").toFile()
        try {
            assertTrue(PrivateAudioPath.isDirectChild(root, File(root, "track.mp3")))
            assertFalse(PrivateAudioPath.isDirectChild(root, File(root, "nested/track.mp3")))
            assertFalse(PrivateAudioPath.isDirectChild(root, File(root, "../outside.mp3")))
            assertFalse(PrivateAudioPath.isDirectChild(root, root))
            val realFile = File(root, "owned.mp3").apply { writeText("audio") }
            val link = File(root, "claimed.mp3")
            Files.createSymbolicLink(link.toPath(), realFile.toPath())
            assertFalse(PrivateAudioPath.isDirectChild(root, link))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun decoderRejectsDuplicateTrackRecordsInsteadOfDroppingOne() {
        val track = Track("track-1", "First", "Artist", filePath = "/private/audio/track-1.mp3")
        val damaged = LibrarySnapshot(tracks = listOf(track, track.copy(title = "Second copy")))

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(LibraryStateCodec.encode(damaged))
        }
    }

    @Test
    fun decoderRejectsDanglingPlaylistReferenceInsteadOfDroppingIt() {
        val track = Track("track-1", "First", "Artist", filePath = "/private/audio/track-1.mp3")
        val damaged = LibrarySnapshot(
            tracks = listOf(track),
            playlists = listOf(Playlist("mix", "Mix", listOf("missing-track"))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(LibraryStateCodec.encode(damaged))
        }
    }

    @Test
    fun decoderRejectsFavoriteReferenceWithoutAnAudioTrack() {
        val damaged = LibrarySnapshot(favoriteTrackIds = setOf("missing-track"))

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.decode(LibraryStateCodec.encode(damaged))
        }
    }

    @Test
    fun refusesNormalizationThatWouldDiscardAStoredTrack() {
        val stored = LibrarySnapshot(
            tracks = listOf(Track("track-1", "First", "Artist", filePath = "/private/audio/track-1.mp3")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            LibraryStateCodec.requireNoDataLoss(stored, LibrarySnapshot())
        }
    }
}
