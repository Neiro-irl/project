package com.neiro.aftertone.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.storage.StorageManager
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackFileName
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

class LocalLibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val audioDirectory = File(appContext.filesDir, "audio")
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val importer = LocalAudioImporter(
        audioDirectory,
        AndroidAudioMetadataReader(),
        availableBytes = {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(audioDirectory))
        },
    )

    @Synchronized
    fun load(): LibrarySnapshot {
        val encoded = preferences.getString(STATE_KEY, null) ?: return LibrarySnapshot()
        val stored = LibraryStateCodec.decode(encoded)
        val normalized = normalize(stored)
        return LibraryStateCodec.requireNoDataLoss(stored, normalized)
    }

    @Synchronized
    fun import(uri: Uri): Track {
        val resolver = appContext.contentResolver
        val displayName = queryDisplayName(uri)
        val mimeType = resolver.getType(uri)
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the selected audio document.")
        val track = importer.import(input, displayName, mimeType)

        try {
            val current = load()
            persist(current.copy(tracks = current.tracks + track))
            return track
        } catch (error: Exception) {
            deleteOwnedFiles(track)
            throw error
        }
    }

    @Synchronized
    fun toggleFavorite(trackId: String): LibrarySnapshot = persist(load().toggleFavorite(trackId))

    @Synchronized
    fun addPlaylist(name: String): LibrarySnapshot = persist(
        load().addPlaylist(Playlist(UUID.randomUUID().toString(), name)),
    )

    @Synchronized
    fun addToPlaylist(playlistId: String, trackId: String): LibrarySnapshot =
        persist(load().addToPlaylist(playlistId, trackId))

    @Synchronized
    fun removeFromPlaylist(playlistId: String, trackId: String): LibrarySnapshot =
        persist(load().removeFromPlaylist(playlistId, trackId))

    @Synchronized
    fun removePlaylist(playlistId: String): LibrarySnapshot =
        persist(load().removePlaylist(playlistId))

    @Synchronized
    fun removeTrack(trackId: String): LibrarySnapshot {
        val current = load()
        val ownedTrack = current.tracks.firstOrNull { it.id == trackId }
        val updated = persist(current.removeTrack(trackId))
        ownedTrack?.let(::deleteOwnedFiles)
        return updated
    }

    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: "Imported audio"
    }

    private fun normalize(snapshot: LibrarySnapshot): LibrarySnapshot {
        val tracks = snapshot.tracks.mapNotNull(::validateTrack).distinctBy(Track::id)
        val knownTrackIds = tracks.mapTo(hashSetOf(), Track::id)
        val validPlaylists = snapshot.playlists
            .filter { it.id.matches(VALID_ID) && it.name.isNotBlank() }
            .distinctBy(Playlist::id)
            .distinctBy { it.name.trim().lowercase(Locale.ROOT) }
            .map { playlist ->
                playlist.copy(
                    name = playlist.name.trim().take(MAX_PLAYLIST_NAME_LENGTH),
                    trackIds = playlist.trackIds.filter { it in knownTrackIds }.distinct(),
                )
            }
        val favorites = snapshot.favoriteTrackIds.filterTo(hashSetOf()) { it in knownTrackIds }
        return LibrarySnapshot(tracks, validPlaylists, favorites)
    }

    private fun validateTrack(track: Track): Track? {
        if (!track.id.matches(VALID_ID)) return null
        val audioFile = File(track.filePath)
        if (!TrackFileName.isGeneratedFileName(track.id, audioFile.name)) return null
        if (!PrivateAudioPath.isDirectChild(audioDirectory, audioFile) || !audioFile.isFile) return null

        val artworkPath = track.artworkPath?.let { rawPath ->
            val artworkFile = File(rawPath)
            if (artworkFile.name == "${track.id}.img" &&
                PrivateAudioPath.isDirectChild(audioDirectory, artworkFile) &&
                artworkFile.isFile
            ) artworkFile.canonicalPath else null
        }
        return track.copy(filePath = audioFile.canonicalPath, artworkPath = artworkPath)
    }

    private fun persist(snapshot: LibrarySnapshot): LibrarySnapshot {
        val normalized = normalize(snapshot)
        val saved = preferences.edit().putString(STATE_KEY, LibraryStateCodec.encode(normalized)).commit()
        if (!saved) throw IOException("Could not save the local music library.")
        return normalized
    }

    private fun deleteOwnedFiles(track: Track) {
        val audioFile = File(track.filePath)
        if (TrackFileName.isGeneratedFileName(track.id, audioFile.name) &&
            PrivateAudioPath.isDirectChild(audioDirectory, audioFile) && audioFile.isFile
        ) audioFile.delete()

        val artworkFile = track.artworkPath?.let(::File) ?: return
        if (artworkFile.name == "${track.id}.img" &&
            PrivateAudioPath.isDirectChild(audioDirectory, artworkFile) && artworkFile.isFile
        ) artworkFile.delete()
    }

    private companion object {
        const val PREFERENCES_NAME = "aftertone_library_v1"
        const val STATE_KEY = "library_state"
        const val MAX_PLAYLIST_NAME_LENGTH = 48
        val VALID_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
