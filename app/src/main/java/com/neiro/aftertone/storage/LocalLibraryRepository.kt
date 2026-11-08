package com.neiro.aftertone.storage

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackFileName
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

internal data class PreparedBackupArchive(
    val id: String,
    val summary: BackupSummary,
)

class LocalLibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    internal val restoreActionGate = RestoreActionGate()
    internal val preparedBackupArchiveOwner = PreparedBackupArchiveOwner()
    private val audioDirectory = File(appContext.filesDir, "audio")
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val stateStore = object : LibraryStateStore {
        override fun read(): String? = preferences.getString(STATE_KEY, null)

        @SuppressLint("UseKtx") // The transaction must observe commit()'s Boolean result.
        override fun write(encoded: String?): Boolean = preferences.edit().apply {
            if (encoded == null) remove(STATE_KEY) else putString(STATE_KEY, encoded)
        }.commit()
    }
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val restoreTransaction = LibraryRestoreTransaction(
        filesDirectory = appContext.filesDir,
        audioDirectory = audioDirectory,
        stateStore = stateStore,
        decodeSnapshot = ::decodeStoredSnapshot,
    )
    private val importer = LocalAudioImporter(
        audioDirectory,
        AndroidAudioMetadataReader(),
        availableBytes = ::availableBytes,
    )
    private var preparedArchiveSweepComplete = false

    @Synchronized
    fun load(): LibrarySnapshot {
        if (!preparedArchiveSweepComplete) {
            runCatching {
                ensureCacheDirectory()
                preparedArchiveSweepComplete = cleanupPreparedArchiveOrphans()
            }.onFailure { preparedBackupArchiveOwner.markSweepPending() }
        }
        var recoveryCompleted = false
        try {
            val pendingTransactionId = restoreTransaction.recoverInterruptedRestore()
            if (pendingTransactionId == null) {
                recoveryCompleted = true
            } else {
                restoreActionGate.adoptPendingCleanup(pendingTransactionId)
            }
        } catch (_: Exception) {
            if (restoreActionGate.pendingCleanupTransactionId == null) {
                restoreActionGate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
            }
        }
        val snapshot = try {
            decodeStoredSnapshot(stateStore.read())
        } catch (error: Exception) {
            if (restoreActionGate.pendingCleanupTransactionId == null) {
                restoreActionGate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
            }
            throw error
        }
        if (recoveryCompleted) restoreActionGate.completeRecoveryWithoutPendingCleanup()
        restoreActionGate.unblock()
        return snapshot
    }

    @Synchronized
    internal fun exportBackup(
        output: OutputStream,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): BackupSummary {
        val snapshot = load()
        ensureAudioDirectory()
        return LibraryBackupArchive.export(snapshot, audioDirectory, output, onProgress, checkCancelled)
    }

    @Synchronized
    internal fun prepareBackupArchive(
        input: InputStream,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): PreparedBackupArchive {
        val id = UUID.randomUUID().toString()
        var archiveFile: File? = null
        try {
            return input.use { source ->
                checkCancelled()
                load()
                if (preparedBackupArchiveOwner.state.value.cleanupPending) {
                    throw IOException("A temporary restore archive still needs cleanup.")
                }
                ensureAudioDirectory()
                ensureCacheDirectory()
                val stagedArchive = PreparedBackupArchiveFiles.create(appContext.cacheDir, id)
                archiveFile = stagedArchive
                val summary = LibraryBackupArchive.stageAndInspectArchive(
                    input = source,
                    destination = stagedArchive,
                    availableBytes = ::availableBytes,
                    checkCancelled = checkCancelled,
                    onProgress = onProgress,
                )
                if (availableBytes() < addReservedSpace(summary.payloadBytes)) {
                    throw IOException("There is not enough free space to restore this archive safely.")
                }
                checkCancelled()
                PreparedBackupArchive(id, summary)
            }
        } catch (error: Throwable) {
            if (archiveFile == null) runCatching { input.close() }
            if (archiveFile != null) {
                val removed = runCatching { PreparedBackupArchiveFiles.discard(appContext.cacheDir, id) }
                    .getOrDefault(false)
                if (!removed) {
                    preparedBackupArchiveOwner.retain(
                        PreparedBackupArchive(id, BackupSummary(0, 0, 0, 0)),
                        cleanupPending = true,
                    )
                }
            }
            throw error
        }
    }

    @Synchronized
    internal fun discardPreparedBackup(prepared: PreparedBackupArchive): Boolean {
        if (!VALID_UUID.matches(prepared.id)) return false
        return PreparedBackupArchiveFiles.discard(appContext.cacheDir, prepared.id)
    }

    @Synchronized
    internal fun retryPreparedBackupArchiveCleanupSweep(): Boolean {
        return try {
            ensureCacheDirectory()
            cleanupPreparedArchiveOrphans().also { preparedArchiveSweepComplete = it }
        } catch (error: Exception) {
            preparedBackupArchiveOwner.markSweepPending()
            throw error
        }
    }

    private fun cleanupPreparedArchiveOrphans(): Boolean {
        val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(
            appContext.cacheDir,
            preparedBackupArchiveOwner.protectedArchiveIdsForCleanup(),
        )
        preparedBackupArchiveOwner.markSweepComplete()
        preparedBackupArchiveOwner.cleanupCandidates().forEach { candidate ->
            if (candidate.id !in cleanup.pendingIds &&
                PreparedBackupArchiveFiles.isFullyDiscarded(appContext.cacheDir, candidate.id)
            ) {
                preparedBackupArchiveOwner.completeCleanup(candidate)
            }
        }
        cleanup.pendingIds.forEach { id ->
            preparedBackupArchiveOwner.retain(
                PreparedBackupArchive(id, BackupSummary(0, 0, 0, 0)),
                cleanupPending = true,
            )
        }
        return cleanup.pendingIds.isEmpty()
    }

    @Synchronized
    internal fun restoreBackup(
        prepared: PreparedBackupArchive,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
        onCommitStarted: () -> Unit = {},
    ): LibraryRestoreResult {
        val archiveFile = preparedArchiveFileOrNull(prepared)
            ?: throw SecurityException("The selected archive is not owned by this restore operation.")
        var stagingDirectory: File? = null
        try {
            load()
            ensureAudioDirectory()
            if (!archiveFile.isFile || LibraryBackupArchive.inspect(archiveFile, checkCancelled = checkCancelled) != prepared.summary) {
                throw IllegalArgumentException("The selected archive changed after it was checked.")
            }
            checkCancelled()
            stagingDirectory = restoreTransaction.createStagingDirectory()
            val decoded = LibraryBackupArchive.extract(
                archiveFile = archiveFile,
                stagingDirectory = stagingDirectory,
                availableBytes = ::availableBytes,
                onProgress = onProgress,
                checkCancelled = checkCancelled,
            )
            checkCancelled()
            val pending = restoreTransaction.prepareRestore(stagingDirectory, decoded, checkCancelled)
            checkCancelled()
            return restoreTransaction.commitRestore(pending, beforeCommit = onCommitStarted)
        } finally {
            stagingDirectory?.let { directory ->
                if (directory.exists()) restoreTransaction.discardStagingDirectory(directory)
            }
            discardPreparedBackup(prepared)
        }
    }

    @Synchronized
    internal fun finishRestore(transactionId: String): Boolean = restoreTransaction.finishRestore(transactionId)

    @Synchronized
    internal fun finishRestoreOutcome(transactionId: String): RestoreFinalizationResult =
        restoreTransaction.finishRestoreOutcome(transactionId)

    @Synchronized
    fun import(uri: Uri): Track {
        ensureAudioDirectory()
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

    private fun decodeStoredSnapshot(encoded: String?): LibrarySnapshot {
        if (encoded == null) return LibrarySnapshot()
        val stored = LibraryStateCodec.decode(encoded)
        val normalized = normalize(stored)
        return LibraryStateCodec.requireNoDataLoss(stored, normalized)
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
        if (!isOwnedAudioFile(audioFile) || !audioFile.isFile) return null

        val artworkPath = track.artworkPath?.let { rawPath ->
            val artworkFile = File(rawPath)
            if (artworkFile.name == "${track.id}.img" && isOwnedAudioFile(artworkFile) && artworkFile.isFile) {
                artworkFile.canonicalPath
            } else {
                null
            }
        }
        return track.copy(filePath = audioFile.canonicalPath, artworkPath = artworkPath)
    }

    private fun persist(snapshot: LibrarySnapshot): LibrarySnapshot {
        val normalized = normalize(snapshot)
        val encoded = LibraryStateCodec.encode(normalized)
        val persisted = restoreActionGate.runIfAllowed {
            if (!stateStore.write(encoded)) throw IOException("Could not save the local music library.")
        }
        if (!persisted) throw IOException("Restore recovery is pending; the local music library was not changed.")
        return normalized
    }

    private fun deleteOwnedFiles(track: Track) {
        val audioFile = File(track.filePath)
        if (TrackFileName.isGeneratedFileName(track.id, audioFile.name) && isOwnedAudioFile(audioFile) && audioFile.isFile) {
            audioFile.delete()
            cleanupEmptyRestoreGeneration(audioFile.parentFile)
        }

        val artworkFile = track.artworkPath?.let(::File) ?: return
        if (artworkFile.name == "${track.id}.img" && isOwnedAudioFile(artworkFile) && artworkFile.isFile) {
            artworkFile.delete()
            cleanupEmptyRestoreGeneration(artworkFile.parentFile)
        }
    }

    private fun cleanupEmptyRestoreGeneration(directory: File?) {
        if (directory == null) return
        val id = PrivateAudioPath.restoreGenerationId(directory.name) ?: return
        if (!PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, directory, id)) return
        val children = directory.listFiles()?.toList() ?: return
        if (children.size != 1 || children.single().name != PrivateAudioPath.restoreOwnerMarkerName()) return
        val marker = children.single()
        if (marker.isFile && PrivateAudioPath.isDirectChild(directory, marker) && marker.readText(Charsets.UTF_8) == id) {
            marker.delete()
            if (PrivateAudioPath.isDirectChild(audioDirectory, directory)) directory.delete()
        }
    }

    private fun isOwnedAudioFile(file: File): Boolean =
        PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file)

    private fun ensureAudioDirectory() {
        if (!PrivateAudioPath.isDirectChild(appContext.filesDir, audioDirectory)) {
            throw SecurityException("The app-private audio directory is redirected or unsafe.")
        }
        if (!audioDirectory.exists() && !audioDirectory.mkdirs()) throw IOException("Could not create the private audio directory.")
        if (!audioDirectory.isDirectory || !PrivateAudioPath.isDirectChild(appContext.filesDir, audioDirectory)) {
            throw IOException("The private audio directory is unavailable or redirected.")
        }
    }

    private fun ensureCacheDirectory() {
        val directory = appContext.cacheDir
        if (!PrivateAudioPath.isDirectChild(appContext.dataDir, directory)) {
            throw SecurityException("The app-private cache directory is redirected or unsafe.")
        }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create private archive staging storage.")
        if (!directory.isDirectory || !PrivateAudioPath.isDirectChild(appContext.dataDir, directory)) {
            throw SecurityException("The app-private cache directory is unavailable or redirected.")
        }
    }

    private fun availableBytes(): Long =
        storageManager.getAllocatableBytes(storageManager.getUuidForPath(audioDirectory))

    private fun addReservedSpace(bytes: Long): Long = try {
        Math.addExact(bytes, LibraryBackupLimits.DEFAULT.reservedSpaceBytes)
    } catch (error: ArithmeticException) {
        throw IOException("The selected backup is too large.", error)
    }

    private fun preparedArchiveFileOrNull(prepared: PreparedBackupArchive): File? {
        if (!VALID_UUID.matches(prepared.id)) return null
        if (!PreparedBackupArchiveFiles.isOwned(appContext.cacheDir, prepared.id)) return null
        return runCatching { PreparedBackupArchiveFiles.archiveFile(appContext.cacheDir, prepared.id) }.getOrNull()
    }

    companion object {
        @Volatile
        private var processRepository: LocalLibraryRepository? = null

        internal fun shared(context: Context): LocalLibraryRepository = processRepository ?: synchronized(this) {
            processRepository ?: LocalLibraryRepository(context.applicationContext).also { processRepository = it }
        }

        const val PREFERENCES_NAME = "aftertone_library_v1"
        const val STATE_KEY = "library_state"
        const val MAX_PLAYLIST_NAME_LENGTH = 48
        val VALID_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val VALID_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
