package com.neiro.aftertone.ui.components

import com.neiro.aftertone.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerStateTest {
    @Test
    fun idleMiniPlayerShowsClearPlaceholderTitle() {
        assertEquals("Пока ничего не играет", miniPlayerTitle(null))
    }

    @Test
    fun activeMiniPlayerShowsTheSelectedTrackTitle() {
        val track = Track(
            id = "track-1",
            title = "Moonlit Drive",
            artist = "North",
            filePath = "/music/moonlit-drive.mp3",
        )

        assertEquals("Moonlit Drive", miniPlayerTitle(track))
    }
}
