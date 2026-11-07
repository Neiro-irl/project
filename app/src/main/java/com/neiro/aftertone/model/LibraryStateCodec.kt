package com.neiro.aftertone.model

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

object LibraryStateCodec {
    private const val VERSION = "1"
    private const val MAX_STATE_CHARS = 5 * 1024 * 1024
    private const val MAX_TRACKS = 20_000
    private const val MAX_PLAYLISTS = 2_000

    fun encode(snapshot: LibrarySnapshot): String {
        require(snapshot.tracks.size <= MAX_TRACKS) { "The local library contains too many tracks." }
        require(snapshot.playlists.size <= MAX_PLAYLISTS) { "The local library contains too many playlists." }
        require(snapshot.favoriteTrackIds.size <= MAX_TRACKS) { "The local library contains too many favorites." }
        require(snapshot.playlists.all { it.trackIds.size <= MAX_TRACKS }) { "A playlist contains too many tracks." }
        require(estimatedSize(snapshot) <= MAX_STATE_CHARS * 2L) { "The local library state exceeds the supported size." }

        val properties = Properties().apply {
            setProperty("version", VERSION)
            setProperty("tracks.count", snapshot.tracks.size.toString())
            snapshot.tracks.forEachIndexed { index, track ->
                val prefix = "track.$index."
                setProperty(prefix + "id", track.id)
                setProperty(prefix + "title", track.title)
                setProperty(prefix + "artist", track.artist)
                setProperty(prefix + "album", track.album)
                setProperty(prefix + "duration", track.durationMs.coerceAtLeast(0).toString())
                setProperty(prefix + "file", track.filePath)
                track.artworkPath?.let { setProperty(prefix + "artwork", it) }
            }

            setProperty("playlists.count", snapshot.playlists.size.toString())
            snapshot.playlists.forEachIndexed { index, playlist ->
                val prefix = "playlist.$index."
                setProperty(prefix + "id", playlist.id)
                setProperty(prefix + "name", playlist.name)
                setProperty(prefix + "tracks.count", playlist.trackIds.size.toString())
                playlist.trackIds.forEachIndexed { trackIndex, trackId ->
                    setProperty(prefix + "track.$trackIndex", trackId)
                }
            }

            val favorites = snapshot.favoriteTrackIds.sorted()
            setProperty("favorites.count", favorites.size.toString())
            favorites.forEachIndexed { index, trackId -> setProperty("favorite.$index", trackId) }
        }

        val encoded = StringWriter().also { properties.store(it, null) }.toString()
        require(encoded.length <= MAX_STATE_CHARS) { "The local library state exceeds the supported size." }
        return encoded
    }

    fun decode(encoded: String): LibrarySnapshot {
        require(encoded.isNotBlank()) { "The saved local library state is empty and was left unchanged." }
        require(encoded.length <= MAX_STATE_CHARS) { "The saved local library state exceeds the supported size." }

        val properties = try {
            Properties().apply { load(StringReader(encoded)) }
        } catch (error: Exception) {
            throw IllegalArgumentException("The saved local library state is unreadable.", error)
        }
        require(properties.getProperty("version") == VERSION) { "The saved local library version is unsupported." }

        val trackCount = properties.readCount("tracks.count", MAX_TRACKS)
        val tracks = (0 until trackCount).map { index ->
            val prefix = "track.$index."
            val id = properties.requiredValue(prefix + "id")
            val filePath = properties.requiredValue(prefix + "file")
            Track(
                id = id,
                title = properties.presentValue(prefix + "title"),
                artist = properties.presentValue(prefix + "artist"),
                album = properties.presentValue(prefix + "album"),
                durationMs = properties.requiredValue(prefix + "duration").toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("The saved local library contains an invalid duration."),
                filePath = filePath,
                artworkPath = properties.getProperty(prefix + "artwork")?.takeIf(String::isNotBlank),
            )
        }
        require(tracks.map(Track::id).distinct().size == tracks.size) {
            "The saved local library contains duplicate track records."
        }

        val knownTrackIds = tracks.mapTo(hashSetOf(), Track::id)
        val playlistCount = properties.readCount("playlists.count", MAX_PLAYLISTS)
        val playlists = (0 until playlistCount).map { index ->
            val prefix = "playlist.$index."
            val id = properties.requiredValue(prefix + "id")
            val name = properties.requiredValue(prefix + "name")
            val count = properties.readCount(prefix + "tracks.count", MAX_TRACKS)
            val trackIds = (0 until count).map { trackIndex ->
                properties.requiredValue(prefix + "track.$trackIndex")
            }
            require(trackIds.all { it in knownTrackIds }) {
                "The saved local library contains a playlist reference without an audio track."
            }
            require(trackIds.distinct().size == trackIds.size) {
                "The saved local library contains duplicate playlist entries."
            }
            Playlist(id, name, trackIds)
        }
        require(playlists.map(Playlist::id).distinct().size == playlists.size) {
            "The saved local library contains duplicate playlists."
        }

        val favoriteCount = properties.readCount("favorites.count", MAX_TRACKS)
        val favoriteEntries = (0 until favoriteCount).map { index ->
            properties.requiredValue("favorite.$index")
        }
        require(favoriteEntries.all { it in knownTrackIds }) {
            "The saved local library contains a favorite without an audio track."
        }
        require(favoriteEntries.distinct().size == favoriteEntries.size) {
            "The saved local library contains duplicate favorites."
        }
        val favorites = favoriteEntries.toSet()

        return LibrarySnapshot(tracks, playlists, favorites)
    }

    internal fun requireNoDataLoss(stored: LibrarySnapshot, normalized: LibrarySnapshot): LibrarySnapshot {
        require(stored == normalized) {
            "The saved local library needs recovery; its original state was left unchanged."
        }
        return stored
    }

    private fun estimatedSize(snapshot: LibrarySnapshot): Long {
        var estimate = 512L
        fun add(value: String) {
            estimate += value.length.toLong() * 2L + 32L
        }

        snapshot.tracks.forEach { track ->
            estimate += 256L
            add(track.id)
            add(track.title)
            add(track.artist)
            add(track.album)
            add(track.filePath)
            add(track.artworkPath.orEmpty())
        }
        snapshot.playlists.forEach { playlist ->
            estimate += 256L + playlist.trackIds.size * 32L
            add(playlist.id)
            add(playlist.name)
            playlist.trackIds.forEach(::add)
        }
        snapshot.favoriteTrackIds.forEach(::add)
        return estimate
    }

    private fun Properties.requiredValue(key: String): String =
        getProperty(key)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("The saved local library is missing a required field.")

    private fun Properties.presentValue(key: String): String =
        getProperty(key) ?: throw IllegalArgumentException("The saved local library is missing a required field.")

    private fun Properties.readCount(key: String, maximum: Int): Int {
        val count = getProperty(key)?.toIntOrNull()
        return count?.takeIf { it in 0..maximum }
            ?: throw IllegalArgumentException("The saved local library contains an invalid count.")
    }
}
