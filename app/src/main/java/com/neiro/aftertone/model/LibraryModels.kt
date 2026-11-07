package com.neiro.aftertone.model

private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "3gp")

private val audioExtensionsByMime = mapOf(
    "audio/mpeg" to "mp3",
    "audio/mp3" to "mp3",
    "audio/mp4" to "m4a",
    "audio/x-m4a" to "m4a",
    "audio/aac" to "aac",
    "audio/flac" to "flac",
    "audio/x-flac" to "flac",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/ogg" to "ogg",
    "application/ogg" to "ogg",
    "audio/opus" to "opus",
    "audio/amr" to "amr",
    "audio/3gpp" to "3gp",
)

private val safeFileId = Regex("[A-Za-z0-9_-]{1,80}")

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String,
    val artworkPath: String? = null,
) {
    val displayTitle: String
        get() = title.trim().ifEmpty { "Без названия" }

    val displayArtist: String
        get() = artist.trim().ifEmpty { "Неизвестный исполнитель" }
}

data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
) {
    fun withTrack(trackId: String): Playlist {
        val normalizedId = trackId.trim()
        return if (normalizedId.isEmpty() || normalizedId in trackIds) this
        else copy(trackIds = trackIds + normalizedId)
    }

    fun withoutTrack(trackId: String): Playlist = copy(trackIds = trackIds.filterNot { it == trackId })
}

data class LibrarySnapshot(
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val favoriteTrackIds: Set<String> = emptySet(),
) {
    fun toggleFavorite(trackId: String): LibrarySnapshot {
        if (tracks.none { it.id == trackId }) return this
        val favorites = if (trackId in favoriteTrackIds) favoriteTrackIds - trackId else favoriteTrackIds + trackId
        return copy(favoriteTrackIds = favorites)
    }

    fun removeTrack(trackId: String): LibrarySnapshot = copy(
        tracks = tracks.filterNot { it.id == trackId },
        playlists = playlists.map { it.withoutTrack(trackId) },
        favoriteTrackIds = favoriteTrackIds - trackId,
    )

    fun addPlaylist(playlist: Playlist): LibrarySnapshot {
        val name = playlist.name.trim()
        if (playlist.id.isBlank() || name.isBlank()) return this
        if (playlists.any { it.id == playlist.id || it.name.equals(name, ignoreCase = true) }) return this
        val knownTrackIds = tracks.mapTo(hashSetOf()) { it.id }
        val validTrackIds = playlist.trackIds.filter { it in knownTrackIds }.distinct()
        return copy(playlists = playlists + playlist.copy(name = name, trackIds = validTrackIds))
    }

    fun addToPlaylist(playlistId: String, trackId: String): LibrarySnapshot {
        if (tracks.none { it.id == trackId }) return this
        return copy(playlists = playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.withTrack(trackId) else playlist
        })
    }

    fun removeFromPlaylist(playlistId: String, trackId: String): LibrarySnapshot = copy(
        playlists = playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.withoutTrack(trackId) else playlist
        },
    )

    fun removePlaylist(playlistId: String): LibrarySnapshot = copy(
        playlists = playlists.filterNot { it.id == playlistId },
    )
}

object TrackFileName {
    fun forImport(id: String, displayName: String, mimeType: String?): String? {
        if (!safeFileId.matches(id)) return null

        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        val isAudioMime = normalizedMime?.startsWith("audio/") == true
        val isGenericMime = normalizedMime == null || normalizedMime in setOf(
            "application/octet-stream",
            "binary/octet-stream",
        )
        if (!isAudioMime && !isGenericMime) return null

        val nameOnly = displayName.substringAfterLast('/').substringAfterLast('\\')
        val extensionFromName = nameOnly.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val extension = audioExtensionsByMime[normalizedMime]
            ?: extensionFromName.takeIf { it in audioExtensions }
            ?: return null

        return "$id.$extension"
    }

    fun isGeneratedFileName(id: String, fileName: String): Boolean {
        if (!safeFileId.matches(id) || !fileName.startsWith("$id.")) return false
        val extension = fileName.removePrefix("$id.")
        return '.' !in extension && extension in audioExtensions
    }
}
