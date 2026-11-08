package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackFileName
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID

private const val ORPHAN_CLEANUP_PENDING_ID = "orphan-cleanup-pending"

internal interface LibraryStateStore {
    fun read(): String?
    fun write(encoded: String?): Boolean
}

internal data class PendingLibraryRestore(
    val transactionId: String,
    val encodedState: String,
    internal val previousEncodedState: String?,
    internal val previousSnapshot: LibrarySnapshot,
    internal val snapshot: LibrarySnapshot,
    internal val stagingDirectory: File,
    internal val generationDirectory: File,
    internal val integrity: List<RestoredAssetIntegrity>,
)

internal data class LibraryRestoreResult(
    val transactionId: String,
    val snapshot: LibrarySnapshot,
    val previousSnapshot: LibrarySnapshot,
)

internal data class RestoredAssetIntegrity(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal enum class RestoreFinalizationResult {
    COMMITTED_AND_CLEANED,
    ROLLED_BACK_AND_CLEANED,
    COMMITTED_CLEANUP_PENDING,
    ROLLED_BACK_CLEANUP_PENDING,
    CLEANUP_PENDING;

    val cleanupComplete: Boolean
        get() = this == COMMITTED_AND_CLEANED || this == ROLLED_BACK_AND_CLEANED

    val rolledBack: Boolean
        get() = this == ROLLED_BACK_AND_CLEANED || this == ROLLED_BACK_CLEANUP_PENDING
}

internal class LibraryRestoreTransaction(
    private val filesDirectory: File,
    private val audioDirectory: File,
    private val stateStore: LibraryStateStore,
    private val durability: RestoreFileDurability = SystemRestoreFileDurability,
    private val decodeSnapshot: (String?) -> LibrarySnapshot,
) {
    fun createStagingDirectory(): File {
        requireSafeAudioDirectory()
        if (!audioDirectory.exists() && !audioDirectory.mkdirs()) {
            throw IOException("Could not create private restore storage.")
        }
        if (!audioDirectory.isDirectory) throw IOException("The private audio directory is unavailable.")
        val id = UUID.randomUUID().toString()
        val directory = File(audioDirectory, "$STAGING_PREFIX$id")
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory) || !directory.mkdir()) {
            throw IOException("Could not create private restore staging storage.")
        }
        try {
            durability.syncDirectory(audioDirectory)
            writeOwnerMarker(directory, id)
            durability.syncDirectory(directory)
            if (!isOwnedStagingDirectory(directory, id)) throw SecurityException("The restore staging directory is not owned by this operation.")
            return directory
        } catch (error: Exception) {
            cleanupStagingDirectory(directory, id)
            runCatching { durability.syncDirectory(audioDirectory) }
            throw error
        }
    }

    fun discardStagingDirectory(directory: File): Boolean {
        if (!PrivateAudioPath.isDirectChild(filesDirectory, audioDirectory)) return false
        val id = PrivateAudioPath.restoreStageId(directory.name) ?: return false
        return cleanupStagingDirectory(directory, id)
    }

    fun prepareRestore(
        stagingDirectory: File,
        prepared: PreparedLibraryBackup,
        checkCancelled: () -> Unit = {},
    ): PendingLibraryRestore {
        requireSafeAudioDirectory()
        val transactionId = PrivateAudioPath.restoreStageId(stagingDirectory.name)
            ?: throw SecurityException("The restore staging path is not owned by Aftertone.")
        if (!isOwnedStagingDirectory(stagingDirectory, transactionId)) {
            throw SecurityException("The restore staging directory could not be verified.")
        }
        val previousEncoded = stateStore.read()
        val previousSnapshot = decodeSnapshot(previousEncoded)
        val assets = validateStagedAssets(stagingDirectory, prepared)
        assets.forEach { asset ->
            checkCancelled()
            val file = File(stagingDirectory, asset.entryName.substringAfter('/'))
            FileOutputStream(file, true).use(durability::syncFile)
        }
        durability.syncDirectory(stagingDirectory)
        val expectedNames = assets.map { it.entryName }.toSet()
        require(prepared.assetSha256ByEntry.isEmpty() || prepared.assetSha256ByEntry.keys == expectedNames) {
            "The restored file integrity records do not match the archive."
        }
        val integrity = assets.map { asset ->
            checkCancelled()
            val fileName = asset.entryName.substringAfter('/')
            val digest = prepared.assetSha256ByEntry[asset.entryName]
                ?: sha256(File(stagingDirectory, fileName), checkCancelled)
            require(SHA256_PATTERN.matches(digest)) { "The restored file integrity record is invalid." }
            RestoredAssetIntegrity(fileName, asset.sizeBytes, digest)
        }
        val portableState = LibraryStateCodec.encode(prepared.snapshot)
        LibraryBackupManifestCodec.encode(portableState, assets)

        val generationDirectory = File(audioDirectory, "$GENERATION_PREFIX$transactionId")
        if (!PrivateAudioPath.isDirectChild(audioDirectory, generationDirectory) || generationDirectory.exists()) {
            throw SecurityException("The restore destination is not a new private directory.")
        }
        val restoredSnapshot = remapToGeneration(prepared.snapshot, generationDirectory)
        val encodedState = LibraryStateCodec.encode(restoredSnapshot)
        val pending = PendingLibraryRestore(
            transactionId = transactionId,
            encodedState = encodedState,
            previousEncodedState = previousEncoded,
            previousSnapshot = previousSnapshot,
            snapshot = restoredSnapshot,
            stagingDirectory = stagingDirectory,
            generationDirectory = generationDirectory,
            integrity = integrity,
        )

        var journalWritten = false
        var generationMoved = false
        try {
            writeJournal(pending)
            journalWritten = true
            if (!stagingDirectory.renameTo(generationDirectory)) {
                throw IOException("Could not move the verified backup into private storage.")
            }
            generationMoved = true
            durability.syncDirectory(audioDirectory)
            decodeSnapshot(encodedState)
            return pending
        } catch (error: Exception) {
            if (journalWritten && stateStore.read() == previousEncoded) {
                val filesRemoved = if (generationMoved) {
                    cleanupGenerationDirectory(generationDirectory, transactionId)
                } else {
                    cleanupStagingDirectory(stagingDirectory, transactionId)
                }
                if (filesRemoved) deleteJournal(transactionId)
            }
            throw error
        }
    }

    fun commitRestore(
        pending: PendingLibraryRestore,
        beforeCommit: () -> Unit = {},
    ): LibraryRestoreResult {
        requireSafeAudioDirectory()
        val journal = readJournal()
        if (journal?.transactionId != pending.transactionId ||
            journal.previousEncodedState != pending.previousEncodedState ||
            journal.encodedState != pending.encodedState ||
            stateStore.read() != pending.previousEncodedState
        ) {
            throw IOException("The restore transaction changed before it could be saved.")
        }
        try {
            beforeCommit()
            if (!stateStore.write(pending.encodedState) || stateStore.read() != pending.encodedState) {
                throw IOException("Could not save the restored library state.")
            }
            return LibraryRestoreResult(pending.transactionId, pending.snapshot, pending.previousSnapshot)
        } catch (error: Exception) {
            val rolledBack = runCatching {
                stateStore.write(pending.previousEncodedState) && stateStore.read() == pending.previousEncodedState
            }.getOrDefault(false)
            if (rolledBack) {
                if (cleanupGenerationDirectory(pending.generationDirectory, pending.transactionId)) {
                    deleteJournal(pending.transactionId)
                }
            }
            if (rolledBack && error is CancellationException) throw error
            throw IOException(
                if (rolledBack) "The restore could not be saved; the previous library was kept."
                else "The restore could not be saved safely; both library copies were kept for recovery.",
                error,
            )
        }
    }

    fun restore(stagingDirectory: File, prepared: PreparedLibraryBackup): LibraryRestoreResult =
        commitRestore(prepareRestore(stagingDirectory, prepared))

    fun finishRestore(transactionId: String): Boolean =
        finishRestoreOutcome(transactionId).cleanupComplete

    fun finishRestoreOutcome(transactionId: String): RestoreFinalizationResult {
        requireSafeAudioDirectory()
        val journal = readJournal() ?: return if (retryAbandonedRecoveryCleanup(forceSyncDirectories = true)) {
            RestoreFinalizationResult.COMMITTED_AND_CLEANED
        } else {
            RestoreFinalizationResult.CLEANUP_PENDING
        }
        if (journal.transactionId != transactionId) return RestoreFinalizationResult.CLEANUP_PENDING

        return when (stateStore.read()) {
            journal.encodedState -> {
                if (finishCommittedJournal(journal)) {
                    if (deleteJournal(transactionId)) RestoreFinalizationResult.COMMITTED_AND_CLEANED
                    else RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING
                } else if (restorePreviousStateIfNewGenerationIsInvalid(journal)) {
                    cleanupRolledBackJournal(journal)
                } else {
                    val committedGenerationIsValid = runCatching {
                        isCommittedGenerationValid(journal, decodeSnapshot(journal.encodedState))
                    }.getOrDefault(false)
                    if (committedGenerationIsValid) RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING
                    else RestoreFinalizationResult.CLEANUP_PENDING
                }
            }
            journal.previousEncodedState -> cleanupRolledBackJournal(journal)
            else -> RestoreFinalizationResult.CLEANUP_PENDING
        }
    }

    private fun cleanupRolledBackJournal(journal: RestoreJournal): RestoreFinalizationResult {
        val generationRemoved = cleanupGenerationDirectory(
            File(audioDirectory, journal.generationDirectoryName),
            journal.transactionId,
        )
        val stagingRemoved = cleanupStagingDirectory(
            File(audioDirectory, journal.stagingDirectoryName),
            journal.transactionId,
        )
        return if (generationRemoved && stagingRemoved && deleteJournal(journal.transactionId)) {
            RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED
        } else {
            RestoreFinalizationResult.ROLLED_BACK_CLEANUP_PENDING
        }
    }

    fun recoverInterruptedRestore(): String? {
        requireSafeAudioDirectory()
        val journal = readJournal()
        if (journal == null) {
            return if (retryAbandonedRecoveryCleanup(forceSyncDirectories = false)) {
                null
            } else {
                ORPHAN_CLEANUP_PENDING_ID
            }
        }
        val cleanupComplete = when (stateStore.read()) {
            journal.encodedState -> {
                if (finishCommittedJournal(journal)) {
                    deleteJournal(journal.transactionId)
                } else if (restorePreviousStateIfNewGenerationIsInvalid(journal)) {
                    val generationRemoved = cleanupGenerationDirectory(
                        File(audioDirectory, journal.generationDirectoryName),
                        journal.transactionId,
                    )
                    val stagingRemoved = cleanupStagingDirectory(
                        File(audioDirectory, journal.stagingDirectoryName),
                        journal.transactionId,
                    )
                    generationRemoved && stagingRemoved && deleteJournal(journal.transactionId)
                } else {
                    false
                }
            }
            journal.previousEncodedState -> {
                val generationRemoved = cleanupGenerationDirectory(
                    File(audioDirectory, journal.generationDirectoryName),
                    journal.transactionId,
                )
                val stagingRemoved = cleanupStagingDirectory(
                    File(audioDirectory, journal.stagingDirectoryName),
                    journal.transactionId,
                )
                generationRemoved && stagingRemoved && deleteJournal(journal.transactionId)
            }
            else -> false
        }
        return journal.transactionId.takeUnless { cleanupComplete }
    }

    private fun finishCommittedJournal(journal: RestoreJournal): Boolean = runCatching {
        val previous = journal.previousEncodedState?.let { LibraryStateCodec.decode(it) } ?: LibrarySnapshot()
        val current = decodeSnapshot(journal.encodedState)
        if (!isCommittedGenerationValid(journal, current)) return@runCatching false
        if (!cleanupReplacedFiles(previous, current)) return@runCatching false
        cleanupEmptyPreviousGenerations(previous)
    }.getOrDefault(false)

    private fun restorePreviousStateIfNewGenerationIsInvalid(journal: RestoreJournal): Boolean {
        val activeGenerationIsValid = runCatching {
            isCommittedGenerationValid(journal, decodeSnapshot(journal.encodedState))
        }.getOrDefault(false)
        if (activeGenerationIsValid) return false
        if (runCatching { decodeSnapshot(journal.previousEncodedState) }.isFailure) return false
        return runCatching {
            stateStore.write(journal.previousEncodedState) && stateStore.read() == journal.previousEncodedState
        }.getOrDefault(false)
    }

    private fun isCommittedGenerationValid(journal: RestoreJournal, snapshot: LibrarySnapshot): Boolean {
        val generation = File(audioDirectory, journal.generationDirectoryName)
        if (!PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, generation, journal.transactionId)) return false

        val expectedNames = linkedSetOf<String>()
        snapshot.tracks.forEach { track ->
            val audio = File(track.filePath)
            if (!PrivateAudioPath.isDirectChild(generation, audio) ||
                !TrackFileName.isGeneratedFileName(track.id, audio.name)
            ) return false
            expectedNames += audio.name
            track.artworkPath?.let { path ->
                val artwork = File(path)
                if (!PrivateAudioPath.isDirectChild(generation, artwork) || artwork.name != "${track.id}.img") {
                    return false
                }
                expectedNames += artwork.name
            }
        }
        val records = journal.integrity.associateBy(RestoredAssetIntegrity::fileName)
        if (records.size != journal.integrity.size || records.keys != expectedNames) return false
        return journal.integrity.all { record ->
            val file = File(generation, record.fileName)
            PrivateAudioPath.isDirectChild(generation, file) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.length() == record.sizeBytes &&
                sha256(file) == record.sha256
        }
    }

    private fun sha256(file: File, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validateStagedAssets(
        stagingDirectory: File,
        prepared: PreparedLibraryBackup,
    ): List<LibraryBackupAsset> {
        val assets = mutableListOf<LibraryBackupAsset>()
        prepared.snapshot.tracks.forEach { track ->
            require(TrackFileName.isGeneratedFileName(track.id, portableFileName(track.filePath, "audio/"))) {
                "The restored library contains an invalid audio reference."
            }
            val audio = File(stagingDirectory, portableFileName(track.filePath, "audio/"))
            require(PrivateAudioPath.isDirectChild(stagingDirectory, audio) && audio.isFile) {
                "The restored audio file is missing from staging."
            }
            assets += LibraryBackupAsset(track.filePath, audio.length())
            track.artworkPath?.let { artworkPath ->
                require(artworkPath == "artwork/${track.id}.img") { "The restored library contains an invalid artwork reference." }
                val artwork = File(stagingDirectory, portableFileName(artworkPath, "artwork/"))
                require(PrivateAudioPath.isDirectChild(stagingDirectory, artwork) && artwork.isFile) {
                    "The restored artwork is missing from staging."
                }
                assets += LibraryBackupAsset(artworkPath, artwork.length())
            }
        }
        val expectedNames = assets.map { it.entryName.substringAfter('/') }.toSet() + OWNER_MARKER
        val children = stagingDirectory.listFiles()?.toList() ?: throw IOException("Could not inspect restore staging files.")
        require(children.size == expectedNames.size && children.all { it.name in expectedNames }) {
            "Restore staging contains an unreferenced or unexpected file."
        }
        require(children.all { child ->
            if (child.name == OWNER_MARKER) {
                isOwnedMarker(stagingDirectory, child, PrivateAudioPath.restoreStageId(stagingDirectory.name)!!)
            } else {
                child.isFile && PrivateAudioPath.isDirectChild(stagingDirectory, child)
            }
        }) { "Restore staging contains an unsafe file." }
        return assets
    }

    private fun remapToGeneration(snapshot: LibrarySnapshot, generationDirectory: File): LibrarySnapshot =
        snapshot.copy(tracks = snapshot.tracks.map { track ->
            val audioName = portableFileName(track.filePath, "audio/")
            require(TrackFileName.isGeneratedFileName(track.id, audioName)) { "The restored library contains an invalid audio reference." }
            val audio = File(generationDirectory, audioName)
            require(PrivateAudioPath.isDirectChild(generationDirectory, audio)) { "The restore path is outside private storage." }
            val artwork = track.artworkPath?.let { path ->
                require(path == "artwork/${track.id}.img") { "The restored library contains an invalid artwork reference." }
                File(generationDirectory, portableFileName(path, "artwork/")).also {
                    require(PrivateAudioPath.isDirectChild(generationDirectory, it)) { "The restore path is outside private storage." }
                }.canonicalPath
            }
            track.copy(filePath = audio.canonicalPath, artworkPath = artwork)
        })

    private fun cleanupReplacedFiles(previous: LibrarySnapshot, current: LibrarySnapshot): Boolean {
        val currentPaths = current.tracks.flatMap { track -> listOfNotNull(track.filePath, track.artworkPath) }.toSet()
        val changedDirectories = linkedSetOf<File>()
        previous.tracks.forEach { track ->
            val audio = File(track.filePath)
            if (track.filePath !in currentPaths) {
                if (!isOwnedPreviousTrackAudio(track, audio)) return false
                val parent = audio.parentFile ?: return false
                changedDirectories += if (isMissingPreviousGenerationDirectory(parent)) audioDirectory else parent
                if (audio.exists() && !audio.delete()) return false
            }
            val artwork = track.artworkPath?.let(::File)
            if (artwork != null && artwork.path !in currentPaths) {
                if (!isOwnedPreviousTrackArtwork(track, artwork)) return false
                val parent = artwork.parentFile ?: return false
                changedDirectories += if (isMissingPreviousGenerationDirectory(parent)) audioDirectory else parent
                if (artwork.exists() && !artwork.delete()) return false
            }
        }
        changedDirectories.forEach { directory ->
            if (!syncDirectoryIfPresent(directory)) return false
        }
        return true
    }

    private fun cleanupEmptyPreviousGenerations(previous: LibrarySnapshot): Boolean {
        var changed = false
        val directories = previous.tracks.flatMap { track ->
            listOfNotNull(File(track.filePath).parentFile, track.artworkPath?.let { File(it).parentFile })
        }.distinct()
        directories.forEach { directory ->
            val id = directory.name.removePrefix(GENERATION_PREFIX)
                .takeIf { directory.name.startsWith(GENERATION_PREFIX) } ?: return@forEach
            if (!directory.exists()) {
                if (Files.isSymbolicLink(directory.toPath()) ||
                    !PrivateAudioPath.isDirectChild(audioDirectory, directory) ||
                    !syncDirectoryIfPresent(audioDirectory)
                ) return false
                return@forEach
            }
            if (!PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, directory, id)) return false
            val children = directory.listFiles()?.toList() ?: return false
            if (children.size == 1 && children.single().name == OWNER_MARKER) {
                val marker = children.single()
                if (!isOwnedMarker(directory, marker, id) || !marker.delete() || !PrivateAudioPath.isDirectChild(audioDirectory, directory) || !directory.delete()) {
                    return false
                }
                changed = true
            }
        }
        if (changed && runCatching { durability.syncDirectory(audioDirectory) }.isFailure) return false
        return true
    }

    private fun isOwnedPreviousTrackAudio(track: Track, file: File): Boolean =
        TrackFileName.isGeneratedFileName(track.id, file.name) &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) ||
                PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file) ||
                isMissingPreviousGenerationDirectory(file.parentFile))

    private fun isOwnedPreviousTrackArtwork(track: Track, file: File): Boolean =
        file.name == "${track.id}.img" &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) ||
                PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file) ||
                isMissingPreviousGenerationDirectory(file.parentFile))

    private fun isMissingPreviousGenerationDirectory(directory: File?): Boolean {
        if (directory == null || PrivateAudioPath.restoreGenerationId(directory.name) == null) return false
        return !directory.exists() && !Files.isSymbolicLink(directory.toPath()) &&
            PrivateAudioPath.isDirectChild(audioDirectory, directory)
    }

    private fun isOwnedTrackAudio(track: Track, file: File): Boolean =
        TrackFileName.isGeneratedFileName(track.id, file.name) &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file))

    private fun isOwnedTrackArtwork(track: Track, file: File): Boolean =
        file.name == "${track.id}.img" &&
            (PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file))

    private fun writeOwnerMarker(directory: File, id: String) {
        val marker = File(directory, OWNER_MARKER)
        if (!PrivateAudioPath.isDirectChild(directory, marker) || !marker.createNewFile()) {
            throw IOException("Could not create the restore ownership marker.")
        }
        FileOutputStream(marker).use { output ->
            output.write(id.toByteArray(StandardCharsets.UTF_8))
            durability.syncFile(output)
        }
    }

    private fun writeJournal(pending: PendingLibraryRestore) {
        if (!filesDirectory.isDirectory || !PrivateAudioPath.isDirectChild(filesDirectory, journalFile) || journalFile.exists()) {
            throw IOException("A previous restore transaction needs recovery.")
        }
        val temporary = File(filesDirectory, "$JOURNAL_TEMP_PREFIX${pending.transactionId}")
        if (!PrivateAudioPath.isDirectChild(filesDirectory, temporary) || !temporary.createNewFile()) {
            throw IOException("Could not create the restore recovery journal.")
        }
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(fileOutput)
                output.writeInt(JOURNAL_MAGIC)
                output.writeInt(JOURNAL_VERSION)
                output.writeUTF(pending.transactionId)
                output.writeUTF(pending.stagingDirectory.name)
                output.writeUTF(pending.generationDirectory.name)
                writeNullableState(output, pending.previousEncodedState)
                writeRequiredState(output, pending.encodedState)
                output.writeInt(pending.integrity.size)
                pending.integrity.forEach { asset ->
                    output.writeUTF(asset.fileName)
                    output.writeLong(asset.sizeBytes)
                    output.writeUTF(asset.sha256)
                }
                output.flush()
                durability.syncFile(fileOutput)
            }
            if (!temporary.renameTo(journalFile)) throw IOException("Could not publish the restore recovery journal.")
            durability.syncDirectory(filesDirectory)
        } catch (error: Exception) {
            if (temporary.isFile && PrivateAudioPath.isDirectChild(filesDirectory, temporary)) temporary.delete()
            throw error
        }
    }

    private fun readJournal(): RestoreJournal? {
        if (!journalFile.exists()) return null
        if (!PrivateAudioPath.isDirectChild(filesDirectory, journalFile) || !journalFile.isFile || journalFile.length() !in 1..MAX_JOURNAL_BYTES) {
            throw IOException("The restore recovery journal could not be verified.")
        }
        return try {
            DataInputStream(FileInputStream(journalFile)).use { input ->
                if (input.readInt() != JOURNAL_MAGIC || input.readInt() != JOURNAL_VERSION) {
                    throw IOException("The restore recovery journal version is unsupported.")
                }
                val transactionId = input.readUTF()
                val stagingName = input.readUTF()
                val generationName = input.readUTF()
                if (!isUuid(transactionId) || stagingName != "$STAGING_PREFIX$transactionId" || generationName != "$GENERATION_PREFIX$transactionId") {
                    throw IOException("The restore recovery journal contains an unsafe path.")
                }
                val previous = readNullableState(input)
                val encoded = readRequiredState(input)
                val integrityCount = input.readInt()
                if (integrityCount !in 0..MAX_RESTORE_ASSETS) {
                    throw IOException("The restore journal contains an invalid asset count.")
                }
                val integrity = ArrayList<RestoredAssetIntegrity>(integrityCount)
                val integrityNames = hashSetOf<String>()
                repeat(integrityCount) {
                    val fileName = input.readUTF()
                    val sizeBytes = input.readLong()
                    val sha256 = input.readUTF()
                    val maximumSize = if (fileName.endsWith(".img")) {
                        LibraryBackupLimits.DEFAULT.maxArtworkBytes
                    } else {
                        LibraryBackupLimits.DEFAULT.maxAudioFileBytes
                    }
                    if (!isSafeStagedAssetName(fileName) || !integrityNames.add(fileName) ||
                        sizeBytes !in 1..maximumSize || !SHA256_PATTERN.matches(sha256)
                    ) {
                        throw IOException("The restore journal contains invalid asset integrity data.")
                    }
                    integrity += RestoredAssetIntegrity(fileName, sizeBytes, sha256)
                }
                if (input.available() != 0) throw IOException("The restore recovery journal contains unexpected data.")
                RestoreJournal(transactionId, stagingName, generationName, previous, encoded, integrity)
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("The restore recovery journal is incomplete.", error)
        }
    }

    private fun deleteJournal(transactionId: String): Boolean {
        val journal = runCatching { readJournal() }.getOrNull()
        if (journal == null) {
            if (journalFile.exists() || Files.isSymbolicLink(journalFile.toPath())) return false
            return syncDirectoryIfPresent(filesDirectory)
        }
        if (journal.transactionId != transactionId || !PrivateAudioPath.isDirectChild(filesDirectory, journalFile)) return false
        if (journalFile.exists() && !journalFile.delete()) return false
        return syncDirectoryIfPresent(filesDirectory)
    }

    private fun requireSafeAudioDirectory() {
        if (!PrivateAudioPath.isDirectChild(filesDirectory, audioDirectory)) {
            throw SecurityException("The app-private audio directory is redirected or unavailable.")
        }
    }

    private fun cleanupStagingDirectory(directory: File, id: String): Boolean {
        if (!directory.exists()) {
            return !Files.isSymbolicLink(directory.toPath()) && syncDirectoryIfPresent(audioDirectory)
        }
        if (!isOwnedStagingDirectory(directory, id)) return false
        return cleanupMarkerOwnedDirectory(directory, id)
    }

    private fun cleanupGenerationDirectory(directory: File, id: String): Boolean {
        if (!directory.exists()) {
            return !Files.isSymbolicLink(directory.toPath()) && syncDirectoryIfPresent(audioDirectory)
        }
        if (!PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, directory, id)) return false
        return cleanupMarkerOwnedDirectory(directory, id)
    }

    private fun cleanupMarkerOwnedDirectory(directory: File, id: String): Boolean {
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory)) return false
        val children = directory.listFiles()?.toList() ?: return false
        val markers = children.filter { it.name == OWNER_MARKER }
        if (markers.size != 1) return false
        val marker = markers.single()
        if (!isOwnedMarker(directory, marker, id)) return false
        val assets = children.filterNot { it.name == OWNER_MARKER }
        if (assets.any { !isSafeStagedAssetName(it.name) || !it.isFile || !PrivateAudioPath.isDirectChild(directory, it) }) {
            return false
        }
        assets.forEach { if (!it.delete()) return false }
        if (assets.isNotEmpty() && !syncDirectoryIfPresent(directory)) return false

        val remaining = directory.listFiles()?.toList() ?: return false
        if (remaining.size != 1 || remaining.single().name != OWNER_MARKER || !isOwnedMarker(directory, marker, id)) {
            return false
        }
        if (!marker.delete()) return false
        if (!PrivateAudioPath.isDirectChild(audioDirectory, directory) || !directory.delete()) return false
        return syncDirectoryIfPresent(audioDirectory)
    }

    private fun retryAbandonedRecoveryCleanup(forceSyncDirectories: Boolean): Boolean {
        val journalTemporariesCleaned = cleanupAbandonedJournalTemporaries(forceSyncDirectories)
        val stagingDirectoriesCleaned = cleanupAbandonedStagingDirectories(forceSyncDirectories)
        return journalTemporariesCleaned && stagingDirectoriesCleaned
    }

    private fun cleanupAbandonedStagingDirectories(forceSyncDirectory: Boolean): Boolean {
        if (!audioDirectory.exists()) {
            return !forceSyncDirectory || syncDirectoryIfPresent(audioDirectory)
        }
        if (!audioDirectory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(audioDirectory)) return false
        val children = audioDirectory.listFiles() ?: return false
        var cleanupComplete = true
        var candidateFound = false
        children.forEach { child ->
            val id = PrivateAudioPath.restoreStageId(child.name) ?: return@forEach
            candidateFound = true
            if (!cleanupStagingDirectory(child, id)) cleanupComplete = false
        }
        val mustSyncParent = forceSyncDirectory || candidateFound
        val parentSynced = !mustSyncParent || syncDirectoryIfPresent(audioDirectory)
        return cleanupComplete && parentSynced
    }

    private fun cleanupAbandonedJournalTemporaries(forceSyncDirectory: Boolean): Boolean {
        if (!filesDirectory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(filesDirectory)) return false
        val children = filesDirectory.listFiles() ?: return false
        var cleanupComplete = true
        var candidateFound = false
        children.forEach { child ->
            val id = child.name.removePrefix(JOURNAL_TEMP_PREFIX)
                .takeIf { child.name.startsWith(JOURNAL_TEMP_PREFIX) && isUuid(it) } ?: return@forEach
            if (!PrivateAudioPath.isDirectChild(filesDirectory, child)) return@forEach
            if (!Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
            if (!isUuid(id)) return@forEach
            candidateFound = true
            if (!child.delete() && child.exists()) cleanupComplete = false
        }
        val mustSyncParent = forceSyncDirectory || candidateFound
        val parentSynced = !mustSyncParent || syncDirectoryIfPresent(filesDirectory)
        return cleanupComplete && parentSynced
    }

    private fun syncDirectoryIfPresent(directory: File): Boolean {
        if (!directory.exists()) return true
        if (!directory.isDirectory || !PrivateAudioPath.isCanonicalDirectory(directory)) return false
        return runCatching { durability.syncDirectory(directory) }.isSuccess
    }

    private fun isOwnedStagingDirectory(directory: File, id: String): Boolean =
        PrivateAudioPath.restoreStageId(directory.name) == id &&
            PrivateAudioPath.isDirectChild(audioDirectory, directory) && directory.isDirectory &&
            isOwnedMarker(directory, File(directory, OWNER_MARKER), id)

    private fun isOwnedMarker(directory: File, marker: File, id: String): Boolean = try {
        marker.length() in 1..64 && marker.isFile && PrivateAudioPath.isDirectChild(directory, marker) &&
            marker.readText(Charsets.UTF_8) == id
    } catch (_: Exception) {
        false
    }

    private fun isSafeStagedAssetName(name: String): Boolean {
        if (name == OWNER_MARKER || '/' in name || '\\' in name) return false
        val id = name.substringBeforeLast('.', missingDelimiterValue = "")
        if (id.isEmpty() || !id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return false
        return TrackFileName.isGeneratedFileName(id, name) || name == "$id.img"
    }

    private fun cleanupEmptyStageFromFailedPreparation(stagingDirectory: File, id: String) {
        cleanupStagingDirectory(stagingDirectory, id)
    }

    private fun writeNullableState(output: DataOutputStream, state: String?) {
        output.writeBoolean(state != null)
        state?.let { writeStateBytes(output, it) }
    }

    private fun writeRequiredState(output: DataOutputStream, state: String) = writeStateBytes(output, state)

    private fun writeStateBytes(output: DataOutputStream, state: String) {
        val bytes = state.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STATE_BYTES) { "The restore journal state is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readNullableState(input: DataInputStream): String? =
        if (input.readBoolean()) readStateBytes(input) else null

    private fun readRequiredState(input: DataInputStream): String = readStateBytes(input)

    private fun readStateBytes(input: DataInputStream): String {
        val length = input.readInt()
        if (length !in 1..MAX_STATE_BYTES || length > input.available()) throw IOException("The restore journal state is incomplete.")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw IOException("The restore journal state is unreadable.", error)
        }
    }

    private fun portableFileName(path: String, prefix: String): String {
        require(path.startsWith(prefix)) { "The restored library contains a non-portable file path." }
        return path.removePrefix(prefix).also { name ->
            require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") {
                "The restored library contains an unsafe file path."
            }
        }
    }

    private val journalFile: File get() = File(filesDirectory, JOURNAL_NAME)

    private data class RestoreJournal(
        val transactionId: String,
        val stagingDirectoryName: String,
        val generationDirectoryName: String,
        val previousEncodedState: String?,
        val encodedState: String,
        val integrity: List<RestoredAssetIntegrity>,
    )

    private companion object {
        const val STAGING_PREFIX = ".aftertone-restore-stage-"
        const val GENERATION_PREFIX = "restore-"
        const val OWNER_MARKER = ".aftertone-restore-owner"
        const val JOURNAL_NAME = ".aftertone-restore-journal"
        const val JOURNAL_TEMP_PREFIX = ".aftertone-restore-journal-"
        const val JOURNAL_MAGIC = 0x4154524a
        const val JOURNAL_VERSION = 2
        const val MAX_STATE_BYTES = LibraryStateCodec.MAX_STATE_UTF8_BYTES
        const val MAX_JOURNAL_BYTES = 64 * 1024 * 1024L
        const val MAX_RESTORE_ASSETS = 40_000
        val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun isUuid(value: String): Boolean = uuidPattern.matches(value)
    }
}
