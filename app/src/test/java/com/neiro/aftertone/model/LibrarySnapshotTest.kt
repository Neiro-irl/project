package com.neiro.aftertone.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySnapshotTest {
    private val track = Track(
        id = "track-1",
        title = "Late Sun",
        artist = "North Window",
        filePath = "/private/audio/track-1.mp3",
    )
    private val secondTrack = Track(
        id = "track-2",
        title = "Early Light",
        artist = "North Window",
        filePath = "/private/audio/track-2.mp3",
    )

    @Test
    fun favoriteCanBeToggledOnlyForAnExistingTrack() {
        val library = LibrarySnapshot(tracks = listOf(track))

        assertEquals(setOf("track-1"), library.toggleFavorite("track-1").favoriteTrackIds)
        assertEquals(emptySet<String>(), library.toggleFavorite("missing").favoriteTrackIds)
    }

    @Test
    fun removingTrackCleansFavoritesAndEveryPlaylistReference() {
        val library = LibrarySnapshot(
            tracks = listOf(track),
            playlists = listOf(Playlist("mix", "Mix", listOf("track-1", "track-1"))),
            favoriteTrackIds = setOf("track-1"),
        )

        val updated = library.removeTrack("track-1")

        assertEquals(emptyList<Track>(), updated.tracks)
        assertEquals(emptyList<String>(), updated.playlists.single().trackIds)
        assertEquals(emptySet<String>(), updated.favoriteTrackIds)
    }

    @Test
    fun addsTrackToAnExistingPlaylistOnlyOnce() {
        val library = LibrarySnapshot(
            tracks = listOf(track),
            playlists = listOf(Playlist("mix", "Mix")),
        )

        val updated = library.addToPlaylist("mix", "track-1").addToPlaylist("mix", "track-1")

        assertEquals(listOf("track-1"), updated.playlists.single().trackIds)
    }

    @Test
    fun trimsPlaylistNamesAndRejectsDuplicatesIgnoringCase() {
        val library = LibrarySnapshot(tracks = listOf(track))
            .addPlaylist(Playlist("mix", "  Evening  "))
            .addPlaylist(Playlist("mix-2", "evening"))

        assertEquals(listOf("Evening"), library.playlists.map { it.name })
    }

    @Test
    fun removingTrackFromPlaylistKeepsTheAudioAndOtherPlaylistReferences() {
        val library = LibrarySnapshot(
            tracks = listOf(track, secondTrack),
            playlists = listOf(
                Playlist("mix", "Mix", listOf("track-1", "track-2")),
                Playlist("other", "Other", listOf("track-1")),
            ),
            favoriteTrackIds = setOf("track-1"),
        )

        val updated = library.removeFromPlaylist("mix", "track-1")

        assertEquals(listOf(track, secondTrack), updated.tracks)
        assertEquals(listOf("track-2"), updated.playlists.first().trackIds)
        assertEquals(listOf("track-1"), updated.playlists.last().trackIds)
        assertEquals(setOf("track-1"), updated.favoriteTrackIds)
    }

    @Test
    fun deletingPlaylistKeepsLocalAudioAndFavorites() {
        val library = LibrarySnapshot(
            tracks = listOf(track),
            playlists = listOf(Playlist("mix", "Mix", listOf("track-1")), Playlist("other", "Other")),
            favoriteTrackIds = setOf("track-1"),
        )

        val updated = library.removePlaylist("mix")

        assertEquals(listOf(track), updated.tracks)
        assertEquals(listOf(Playlist("other", "Other")), updated.playlists)
        assertEquals(setOf("track-1"), updated.favoriteTrackIds)
    }
}
