package com.neiro.aftertone.model

import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.CancellationException

object LibraryStateCodec {
    private const val VERSION = "1"
    internal const val MAX_STATE_CHARS = 5 * 1024 * 1024
    internal const val MAX_STATE_UTF8_BYTES = 16 * 1024 * 1024
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
        require(encodeUtf8Strict(encoded).size <= MAX_STATE_UTF8_BYTES) {
            "The local library state exceeds the supported UTF-8 size."
        }
        return encoded
    }

    fun decode(encoded: String): LibrarySnapshot = decode(encoded) {}

    internal fun decode(encoded: String, checkCancelled: () -> Unit): LibrarySnapshot {
        checkCancelled()
        require(encoded.isNotBlank()) { "The saved local library state is empty and was left unchanged." }
        require(encoded.length <= MAX_STATE_CHARS) { "The saved local library state exceeds the supported size." }
        require(encodeUtf8Strict(encoded).size <= MAX_STATE_UTF8_BYTES) {
            "The saved local library state exceeds the supported UTF-8 size."
        }

        val properties = try {
            Properties().apply { load(CancellationCheckingReader(StringReader(encoded), checkCancelled)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("The saved local library state is unreadable.", error)
        }
        checkCancelled()
        require(properties.getProperty("version") == VERSION) { "The saved local library version is unsupported." }

        val trackCount = properties.readCount("tracks.count", MAX_TRACKS)
        val tracks = ArrayList<Track>(trackCount)
        val knownTrackIds = linkedSetOf<String>()
        for (index in 0 until trackCount) {
            checkCancelled()
            val prefix = "track.$index."
            val id = properties.requiredValue(prefix + "id")
            val filePath = properties.requiredValue(prefix + "file")
            require(knownTrackIds.add(id)) { "The saved local library contains duplicate track records." }
            tracks += Track(
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

        val playlistCount = properties.readCount("playlists.count", MAX_PLAYLISTS)
        val playlists = ArrayList<Playlist>(playlistCount)
        val playlistIds = hashSetOf<String>()
        for (index in 0 until playlistCount) {
            checkCancelled()
            val prefix = "playlist.$index."
            val id = properties.requiredValue(prefix + "id")
            require(playlistIds.add(id)) { "The saved local library contains duplicate playlists." }
            val name = properties.requiredValue(prefix + "name")
            val count = properties.readCount(prefix + "tracks.count", MAX_TRACKS)
            val trackIds = ArrayList<String>(count)
            val uniqueTrackIds = hashSetOf<String>()
            for (trackIndex in 0 until count) {
                checkCancelled()
                val trackId = properties.requiredValue(prefix + "track.$trackIndex")
                require(trackId in knownTrackIds) {
                    "The saved local library contains a playlist reference without an audio track."
                }
                require(uniqueTrackIds.add(trackId)) {
                    "The saved local library contains duplicate playlist entries."
                }
                trackIds += trackId
            }
            playlists += Playlist(id, name, trackIds)
        }

        val favoriteCount = properties.readCount("favorites.count", MAX_TRACKS)
        val favorites = linkedSetOf<String>()
        for (index in 0 until favoriteCount) {
            checkCancelled()
            val trackId = properties.requiredValue("favorite.$index")
            require(trackId in knownTrackIds) {
                "The saved local library contains a favorite without an audio track."
            }
            require(favorites.add(trackId)) {
                "The saved local library contains duplicate favorites."
            }
        }

        return LibrarySnapshot(tracks, playlists, favorites)
    }

    internal fun requireNoDataLoss(stored: LibrarySnapshot, normalized: LibrarySnapshot): LibrarySnapshot {
        require(stored == normalized) {
            "The saved local library needs recovery; its original state was left unchanged."
        }
        return stored
    }

    internal fun encodeUtf8Strict(value: String): ByteArray = try {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("The local library contains invalid Unicode text.", error)
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

    private class CancellationCheckingReader(
        private val delegate: StringReader,
        private val checkCancelled: () -> Unit,
    ) : Reader() {
        private var charsUntilCheck = 0

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (charsUntilCheck <= 0) {
                checkCancelled()
                charsUntilCheck = 8 * 1024
            }
            val count = delegate.read(buffer, offset, minOf(length, charsUntilCheck))
            if (count > 0) charsUntilCheck -= count
            return count
        }

        override fun close() = delegate.close()
    }
}
