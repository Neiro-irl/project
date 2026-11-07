package com.neiro.aftertone.model

fun filterLocalTracks(tracks: List<Track>, query: String): List<Track> {
    val normalizedQuery = query.trim()
    return tracks
        .asSequence()
        .filter { track ->
            normalizedQuery.isEmpty() || listOf(track.displayTitle, track.displayArtist, track.album)
                .any { value -> value.contains(normalizedQuery, ignoreCase = true) }
        }
        .sortedWith(
            compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayArtist }
                .thenBy { it.id },
        )
        .toList()
}
