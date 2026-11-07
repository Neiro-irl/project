package com.neiro.aftertone.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.neiro.aftertone.model.Track

@Composable
fun SquareTrackArtwork(track: Track?, modifier: Modifier = Modifier) {
    TrackArtwork(
        track = track,
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
        cornerRadius = 28,
    )
}
