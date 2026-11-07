package com.neiro.aftertone.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTrackSearchTest {
    private val tracks = listOf(
        Track(id = "zeta", title = "Zulu Lights", artist = "North", album = "Night Drive", filePath = "/music/zeta.mp3"),
        Track(id = "artist", title = "Blue Room", artist = "Amber Fields", album = "Live", filePath = "/music/artist.mp3"),
        Track(id = "album", title = "Quiet Lines", artist = "Low Tide", album = "AMBER sessions", filePath = "/music/album.mp3"),
        Track(id = "alpha", title = "Amber Morning", artist = "South", album = "First Light", filePath = "/music/alpha.mp3"),
    )

    @Test
    fun matchesTitleArtistAndAlbumWithoutCaseSensitivity() {
        val resultIds = filterLocalTracks(tracks, "aMbEr").map(Track::id)

        assertEquals(listOf("alpha", "artist", "album"), resultIds)
    }

    @Test
    fun blankQueryReturnsEveryTrackSortedByDisplayTitle() {
        val resultTitles = filterLocalTracks(tracks, "   ").map(Track::displayTitle)

        assertEquals(listOf("Amber Morning", "Blue Room", "Quiet Lines", "Zulu Lights"), resultTitles)
    }

    @Test
    fun unmatchedQueryReturnsNoTracks() {
        val result = filterLocalTracks(tracks, "ocean")

        assertEquals(emptyList<Track>(), result)
    }
}
