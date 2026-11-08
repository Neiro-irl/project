package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRestoreTransactionTest {
    @Test
    fun stagingCleanupRefusesSymlinkedChildWithoutDeletingItsTarget() {
        val fixture = fixture()
        try {
            val staging = fixture.transaction().createStagingDirectory()
            val outsideFile = File(fixture.root, "outside.mp3").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            Files.createSymbolicLink(File(staging, "restored-1.mp3").toPath(), outsideFile.toPath())

            assertFalse(fixture.transaction().discardStagingDirectory(staging))

            assertTrue(outsideFile.isFile)
            assertTrue(staging.isDirectory)
            val ownerMarker = File(staging, PrivateAudioPath.restoreOwnerMarkerName())
            assertTrue(ownerMarker.isFile)
            assertEquals(PrivateAudioPath.restoreStageId(staging.name), ownerMarker.readText())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun successfulCommitKeepsOldFilesUntilFinishAndThenRemovesOnlyReplacedFiles() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())

            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertTrue(File(pending.snapshot.tracks.single().filePath).isFile)
            assertTrue(fixture.stateStore.read() == fixture.oldEncodedState)

            val committed = transaction.commitRestore(pending)

            assertEquals(fixture.oldSnapshot, committed.previousSnapshot)
            assertEquals("Новая копия", committed.snapshot.tracks.single().title)
            assertEquals(setOf("restored-1"), committed.snapshot.favoriteTrackIds)
            assertEquals(listOf("restored-1"), committed.snapshot.playlists.single().trackIds)
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertTrue(fixture.newAudio.isFile)
            assertTrue(transaction.finishRestore(committed.transactionId))
            assertFalse(fixture.oldAudio.exists())
            assertFalse(fixture.oldArtwork.exists())
            assertTrue(fixture.newAudio.isFile)
            assertTrue(fixture.newArtwork.isFile)
            assertEquals(committed.snapshot, fixture.stateStore.read()?.let(LibraryStateCodec::decode))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun playerHandoffRunsBeforeTheNewLibraryStateIsCommitted() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())

            val committed = transaction.commitRestore(pending) {
                assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
                assertTrue(fixture.oldAudio.isFile)
                assertTrue(fixture.oldArtwork.isFile)
                assertTrue(fixture.newAudio.isFile)
            }

            assertEquals("Новая копия", committed.snapshot.tracks.single().title)
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.newAudio.isFile)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun failedPlayerHandoffRollsBackNewGenerationAndKeepsOldLibrary() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())

            assertThrows(IOException::class.java) {
                transaction.commitRestore(pending) { throw IllegalStateException("Player handoff failed.") }
            }

            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertFalse(fixture.newGeneration.exists())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun cancellationAtPlayerHandoffRollsBackAndPropagatesCancellation() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())

            val cancellation = assertThrows(CancellationException::class.java) {
                transaction.commitRestore(pending) { throw CancellationException("cancelled before commit") }
            }

            assertEquals("cancelled before commit", cancellation.message)
            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertFalse(fixture.newGeneration.exists())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun aRestoredGenerationCanBeExportedAgainAsAPortableArchive() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val restored = transaction.restore(staging, fixture.preparedBackup())
            val archive = File(fixture.root, "portable-again.zip")

            archive.outputStream().use { LibraryBackupArchive.export(restored.snapshot, fixture.audioDirectory, it) }

            assertEquals(BackupSummary(1, 1, 1, 7), LibraryBackupArchive.inspect(archive))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun failedPreferenceCommitRollsBackNewGenerationAndLeavesOldSnapshotAndAudioUntouched() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            fixture.stateStore.failedWritesRemaining = 1

            assertThrows(IOException::class.java) { transaction.commitRestore(pending) }

            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertFalse(fixture.newGeneration.exists())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun interruptedRestoreBeforePreferenceCommitDeletesOnlyNewGeneration() {
        val fixture = fixture()
        try {
            val firstProcess = fixture.transaction()
            val staging = firstProcess.createStagingDirectory()
            fixture.populateStaging(staging)
            firstProcess.prepareRestore(staging, fixture.preparedBackup())

            fixture.transaction().recoverInterruptedRestore()

            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertFalse(fixture.newGeneration.exists())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryRemovesOnlyValidDirectChildOrphanJournalTemporaries() {
        val fixture = fixture()
        try {
            val orphan = File(fixture.filesDirectory, ".aftertone-restore-journal-00000000-0000-0000-0000-000000000001")
                .apply { writeText("incomplete journal") }
            val malformed = File(fixture.filesDirectory, ".aftertone-restore-journal-not-a-uuid")
                .apply { writeText("keep") }
            val unrelated = File(fixture.filesDirectory, "unrelated.tmp").apply { writeText("keep") }
            val outsideTarget = File(fixture.root, "outside-journal-target").apply { writeText("keep") }
            val symlink = File(fixture.filesDirectory, ".aftertone-restore-journal-00000000-0000-0000-0000-000000000002")
            Files.createSymbolicLink(symlink.toPath(), outsideTarget.toPath())

            fixture.transaction().recoverInterruptedRestore()

            assertFalse(orphan.exists())
            assertTrue(malformed.isFile)
            assertTrue(unrelated.isFile)
            assertTrue(Files.isSymbolicLink(symlink.toPath()))
            assertEquals("keep", outsideTarget.readText())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun interruptedRestoreAfterPreferenceCommitFinishesCleanupAndKeepsNewGeneration() {
        val fixture = fixture()
        try {
            val firstProcess = fixture.transaction()
            val staging = firstProcess.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = firstProcess.prepareRestore(staging, fixture.preparedBackup())
            assertTrue(fixture.stateStore.write(pending.encodedState))

            fixture.transaction().recoverInterruptedRestore()

            assertFalse(fixture.oldAudio.exists())
            assertFalse(fixture.oldArtwork.exists())
            assertTrue(fixture.newAudio.isFile)
            assertTrue(fixture.newArtwork.isFile)
            assertEquals(pending.snapshot, fixture.stateStore.read()?.let(LibraryStateCodec::decode))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryReportsPendingWhenJournalDirectorySyncFails() {
        val fixture = fixture()
        try {
            var failFilesDirectorySync = false
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: java.io.FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    if (failFilesDirectorySync && directory == fixture.filesDirectory) {
                        throw IOException("Injected journal directory sync failure.")
                    }
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }
            val transaction = fixture.transaction(durability)
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            transaction.commitRestore(pending)
            failFilesDirectorySync = true

            val recoveredId = fixture.transaction(durability).recoverInterruptedRestore()

            assertEquals(pending.transactionId, recoveredId)
            assertFalse(fixture.oldAudio.exists())
            assertTrue(fixture.newAudio.isFile)
            val gate = RestoreActionGate()
            val pendingId = requireNotNull(recoveredId)
            gate.adoptPendingCleanup(pendingId)
            gate.unblock()
            assertTrue(gate.isBlocked)

            assertEquals(
                RestoreFinalizationResult.CLEANUP_PENDING,
                fixture.transaction(durability).finishRestoreOutcome(pendingId),
            )
            failFilesDirectorySync = false
            val cleanup = gate.beginCleanupRetry() ?: error("Journal directory sync must be retryable.")
            assertEquals(
                RestoreFinalizationResult.COMMITTED_AND_CLEANED,
                fixture.transaction(durability).finishRestoreOutcome(pendingId),
            )
            cleanup.markCleanupComplete()
            cleanup.finish()
            assertFalse(gate.isBlocked)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryReportsUnremovableOrphanStagingAndRetriesCleanup() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            val invalidAssetDirectory = File(staging, "restored-1.mp3").apply { assertTrue(mkdir()) }

            val pendingId = requireNotNull(fixture.transaction().recoverInterruptedRestore())
            val gate = RestoreActionGate()
            gate.adoptPendingCleanup(pendingId)
            gate.unblock()
            assertTrue(gate.isBlocked)
            assertTrue(staging.isDirectory)
            assertEquals(
                RestoreFinalizationResult.CLEANUP_PENDING,
                fixture.transaction().finishRestoreOutcome(pendingId),
            )

            assertTrue(invalidAssetDirectory.deleteRecursively())
            val cleanup = gate.beginCleanupRetry() ?: error("Orphan staging cleanup must be retryable.")
            assertEquals(
                RestoreFinalizationResult.COMMITTED_AND_CLEANED,
                fixture.transaction().finishRestoreOutcome(pendingId),
            )
            cleanup.markCleanupComplete()
            cleanup.finish()

            assertFalse(gate.isBlocked)
            assertFalse(staging.exists())
            assertTrue(fixture.oldAudio.isFile)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryReportsOrphanJournalTempWhenFilesDirectorySyncFails() {
        val fixture = fixture()
        try {
            var failFilesDirectorySync = false
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: java.io.FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    if (failFilesDirectorySync && directory == fixture.filesDirectory) {
                        throw IOException("Injected orphan journal temp directory sync failure.")
                    }
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }
            val temporary = File(
                fixture.filesDirectory,
                ".aftertone-restore-journal-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ).apply { writeText("incomplete") }
            failFilesDirectorySync = true

            val pendingId = requireNotNull(fixture.transaction(durability).recoverInterruptedRestore())
            val gate = RestoreActionGate()
            gate.adoptPendingCleanup(pendingId)
            gate.unblock()
            assertTrue(gate.isBlocked)
            assertFalse(temporary.exists())
            assertEquals(
                RestoreFinalizationResult.CLEANUP_PENDING,
                fixture.transaction(durability).finishRestoreOutcome(pendingId),
            )

            failFilesDirectorySync = false
            val cleanup = gate.beginCleanupRetry() ?: error("Orphan temp directory sync must be retryable.")
            assertEquals(
                RestoreFinalizationResult.COMMITTED_AND_CLEANED,
                fixture.transaction(durability).finishRestoreOutcome(pendingId),
            )
            cleanup.markCleanupComplete()
            cleanup.finish()

            assertFalse(gate.isBlocked)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryRetriesAudioDirectorySyncEvenWhenGenerationDirectoryIsAlreadyGone() {
        val fixture = fixture()
        try {
            var failAudioDirectorySync = false
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: java.io.FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    if (failAudioDirectorySync && directory == fixture.audioDirectory) {
                        throw IOException("Injected audio directory sync failure.")
                    }
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }
            val transaction = fixture.transaction(durability)
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            failAudioDirectorySync = true

            val firstRecoveryId = fixture.transaction(durability).recoverInterruptedRestore()
            val repeatedRecoveryId = fixture.transaction(durability).recoverInterruptedRestore()

            assertEquals(pending.transactionId, firstRecoveryId)
            assertEquals(pending.transactionId, repeatedRecoveryId)
            assertFalse(fixture.newGeneration.exists())
            val gate = RestoreActionGate()
            gate.adoptPendingCleanup(requireNotNull(repeatedRecoveryId))
            gate.unblock()
            assertTrue(gate.isBlocked)

            failAudioDirectorySync = false
            val cleanup = gate.beginCleanupRetry() ?: error("Audio directory sync must be retryable.")
            assertEquals(
                RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED,
                fixture.transaction(durability).finishRestoreOutcome(pending.transactionId),
            )
            cleanup.markCleanupComplete()
            cleanup.finish()

            assertFalse(gate.isBlocked)
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun committedCleanupRetriesAudioSyncAfterLegacyFilesWereDeleted() {
        val fixture = fixture()
        try {
            var failAudioDirectorySync = false
            var audioDirectorySyncAttempts = 0
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: java.io.FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    if (directory == fixture.audioDirectory) {
                        audioDirectorySyncAttempts++
                        if (failAudioDirectorySync) throw IOException("Injected audio directory sync failure.")
                    }
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }
            val transaction = fixture.transaction(durability)
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            transaction.commitRestore(pending)
            audioDirectorySyncAttempts = 0
            failAudioDirectorySync = true

            assertEquals(
                RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING,
                transaction.finishRestoreOutcome(pending.transactionId),
            )
            assertFalse(fixture.oldAudio.exists())
            assertFalse(fixture.oldArtwork.exists())
            val attemptsAfterFirstCleanup = audioDirectorySyncAttempts

            assertEquals(
                RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING,
                transaction.finishRestoreOutcome(pending.transactionId),
            )
            assertTrue("Retry must sync the legacy audio parent again.", audioDirectorySyncAttempts > attemptsAfterFirstCleanup)

            failAudioDirectorySync = false
            assertEquals(
                RestoreFinalizationResult.COMMITTED_AND_CLEANED,
                transaction.finishRestoreOutcome(pending.transactionId),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun committedCleanupRetriesAudioSyncAfterPreviousGenerationDirectoryWasDeleted() {
        val fixture = fixture()
        try {
            val previousGenerationId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val previousGeneration = File(fixture.audioDirectory, "restore-$previousGenerationId").apply { mkdir() }
            File(previousGeneration, PrivateAudioPath.restoreOwnerMarkerName()).writeText(previousGenerationId)
            val previousAudio = File(previousGeneration, "old-1.mp3").apply { writeBytes(byteArrayOf(10, 20, 30)) }
            val previousArtwork = File(previousGeneration, "old-1.img").apply { writeBytes(byteArrayOf(40, 50)) }
            val previousSnapshot = fixture.oldSnapshot.copy(
                tracks = listOf(fixture.oldSnapshot.tracks.single().copy(
                    filePath = previousAudio.canonicalPath,
                    artworkPath = previousArtwork.canonicalPath,
                )),
            )
            assertTrue(fixture.stateStore.write(LibraryStateCodec.encode(previousSnapshot)))

            var failAudioDirectorySync = false
            var audioDirectorySyncAttempts = 0
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: java.io.FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    if (directory == fixture.audioDirectory) {
                        audioDirectorySyncAttempts++
                        if (failAudioDirectorySync) throw IOException("Injected audio directory sync failure.")
                    }
                    SystemRestoreFileDurability.syncDirectory(directory)
                }
            }
            val transaction = fixture.transaction(durability)
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            transaction.commitRestore(pending)
            audioDirectorySyncAttempts = 0
            failAudioDirectorySync = true

            assertEquals(
                RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING,
                transaction.finishRestoreOutcome(pending.transactionId),
            )
            assertFalse(previousGeneration.exists())
            val attemptsAfterFirstCleanup = audioDirectorySyncAttempts

            assertEquals(
                RestoreFinalizationResult.COMMITTED_CLEANUP_PENDING,
                transaction.finishRestoreOutcome(pending.transactionId),
            )
            assertTrue("Retry must sync after the previous generation disappeared.", audioDirectorySyncAttempts > attemptsAfterFirstCleanup)

            failAudioDirectorySync = false
            assertEquals(
                RestoreFinalizationResult.COMMITTED_AND_CLEANED,
                transaction.finishRestoreOutcome(pending.transactionId),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryReportsPendingUntilRollbackGenerationCleanupCanBeRetried() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            transaction.commitRestore(pending)
            assertTrue(fixture.newAudio.delete())
            assertTrue(fixture.newAudio.mkdir())

            val firstRecoveryId = fixture.transaction().recoverInterruptedRestore()
            val repeatedRecoveryId = fixture.transaction().recoverInterruptedRestore()

            assertEquals(pending.transactionId, firstRecoveryId)
            assertEquals(pending.transactionId, repeatedRecoveryId)
            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertTrue(fixture.newAudio.isDirectory)

            val gate = RestoreActionGate()
            gate.adoptPendingCleanup(requireNotNull(repeatedRecoveryId))
            gate.unblock()
            assertTrue(gate.isBlocked)

            assertTrue(fixture.newAudio.deleteRecursively())
            val cleanup = gate.beginCleanupRetry() ?: error("Failed recovery cleanup must be retryable.")
            val finalization = fixture.transaction().finishRestoreOutcome(pending.transactionId)
            assertEquals(RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED, finalization)
            cleanup.markCleanupComplete()
            cleanup.finish()

            assertFalse(gate.isBlocked)
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryRollsBackWhenCommittedGenerationAssetIsTruncated() {
        val fixture = fixture()
        try {
            val firstProcess = fixture.transaction()
            val staging = firstProcess.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = firstProcess.prepareRestore(staging, fixture.preparedBackup())
            firstProcess.commitRestore(pending)
            fixture.newAudio.writeBytes(fixture.newAudioBytes.copyOf(2))

            fixture.transaction().recoverInterruptedRestore()

            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertFalse(fixture.newGeneration.exists())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun finishRestoreRollsBackCorruptCommittedGenerationBeforeOldFileCleanupRetry() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            transaction.commitRestore(pending)
            fixture.newAudio.writeBytes(byteArrayOf(0x00, 0x00))

            assertEquals(
                RestoreFinalizationResult.ROLLED_BACK_AND_CLEANED,
                transaction.finishRestoreOutcome(pending.transactionId),
            )

            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertFalse(fixture.newGeneration.exists())
            assertTrue(transaction.finishRestore(pending.transactionId))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun recoveryRollsBackWhenCommittedGenerationContentIsCorrupt() {
        val fixture = fixture()
        try {
            val firstProcess = fixture.transaction()
            val staging = firstProcess.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = firstProcess.prepareRestore(staging, fixture.preparedBackup())
            firstProcess.commitRestore(pending)
            fixture.newAudio.writeBytes(ByteArray(fixture.newAudioBytes.size) { 0x7f })

            fixture.transaction().recoverInterruptedRestore()

            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.oldArtwork.isFile)
            assertFalse(fixture.newGeneration.exists())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun restoreJournalAcceptsBothUnicodeStatesByTheSharedUtf8ByteBudget() {
        val fixture = fixture()
        try {
            val title = "界".repeat(1_800_000)
            val previousSnapshot = fixture.oldSnapshot.copy(
                tracks = fixture.oldSnapshot.tracks.map { it.copy(title = title) },
            )
            fixture.stateStore.write(LibraryStateCodec.encode(previousSnapshot))
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val base = fixture.preparedBackup()
            val prepared = base.copy(
                snapshot = base.snapshot.copy(
                    tracks = base.snapshot.tracks.map { it.copy(title = title) },
                ),
            )

            val pending = transaction.prepareRestore(staging, prepared)

            assertTrue(pending.encodedState.toByteArray(Charsets.UTF_8).size > 5 * 1024 * 1024)
            assertTrue(
                fixture.filesDirectory.listFiles().orEmpty().any { it.isFile && it.length() > 10L * 1024 * 1024 },
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun failedRollbackRetainsBothGenerationsUntilRecoveryCanVerifyTheActiveState() {
        val fixture = fixture()
        try {
            val transaction = fixture.transaction()
            val staging = transaction.createStagingDirectory()
            fixture.populateStaging(staging)
            val pending = transaction.prepareRestore(staging, fixture.preparedBackup())
            fixture.stateStore.failedWritesRemaining = 2

            assertThrows(IOException::class.java) { transaction.commitRestore(pending) }
            assertTrue(fixture.oldAudio.isFile)
            assertTrue(fixture.newAudio.isFile)

            fixture.transaction().recoverInterruptedRestore()

            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
            assertFalse(fixture.newGeneration.exists())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun refusesStagingDirectoryOutsideTheAppPrivateAudioRoot() {
        val fixture = fixture()
        try {
            val outside = File(fixture.root, "outside-stage").apply { mkdir() }
            File(outside, ".aftertone-restore-owner").writeText("00000000-0000-0000-0000-000000000000")
            val transaction = fixture.transaction()

            assertThrows(SecurityException::class.java) {
                transaction.prepareRestore(outside, fixture.preparedBackup())
            }
            assertEquals(fixture.oldEncodedState, fixture.stateStore.read())
            assertTrue(fixture.oldAudio.isFile)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("aftertone-restore-transaction-").toFile()
        val filesDirectory = File(root, "files").apply { mkdir() }
        val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
        val oldAudio = File(audioDirectory, "old-1.mp3").apply { writeBytes(byteArrayOf(10, 20, 30)) }
        val oldArtwork = File(audioDirectory, "old-1.img").apply { writeBytes(byteArrayOf(40, 50)) }
        val oldSnapshot = LibrarySnapshot(
            tracks = listOf(Track("old-1", "Старая копия", "Исполнитель", "", 1_000, oldAudio.canonicalPath, oldArtwork.canonicalPath)),
            playlists = listOf(Playlist("old-mix", "Старый список", listOf("old-1"))),
            favoriteTrackIds = setOf("old-1"),
        )
        val newAudio = byteArrayOf(1, 2, 3, 4)
        val newArtwork = byteArrayOf(5, 6, 7)
        val portableSnapshot = LibrarySnapshot(
            tracks = listOf(Track("restored-1", "Новая копия", "Новый исполнитель", "Альбом", 2_000, "audio/restored-1.mp3", "artwork/restored-1.img")),
            playlists = listOf(Playlist("new-mix", "Новый список", listOf("restored-1"))),
            favoriteTrackIds = setOf("restored-1"),
        )
        return Fixture(
            root = root,
            filesDirectory = filesDirectory,
            audioDirectory = audioDirectory,
            oldAudio = oldAudio,
            oldArtwork = oldArtwork,
            oldSnapshot = oldSnapshot,
            oldEncodedState = LibraryStateCodec.encode(oldSnapshot),
            newAudioBytes = newAudio,
            newArtworkBytes = newArtwork,
            portableSnapshot = portableSnapshot,
            stateStore = MemoryLibraryStateStore(LibraryStateCodec.encode(oldSnapshot)),
        )
    }

    private class Fixture(
        val root: File,
        val filesDirectory: File,
        val audioDirectory: File,
        val oldAudio: File,
        val oldArtwork: File,
        val oldSnapshot: LibrarySnapshot,
        val oldEncodedState: String,
        val newAudioBytes: ByteArray,
        val newArtworkBytes: ByteArray,
        val portableSnapshot: LibrarySnapshot,
        val stateStore: MemoryLibraryStateStore,
    ) {
        val newGeneration: File get() = audioDirectory.listFiles().orEmpty().singleOrNull { it.name.startsWith("restore-") } ?: File(audioDirectory, "restore-missing")
        val newAudio: File get() = newGeneration.takeIf(File::exists)?.let { File(it, "restored-1.mp3") } ?: File(audioDirectory, "restore-missing/restored-1.mp3")
        val newArtwork: File get() = File(newAudio.parentFile, "restored-1.img")

        fun transaction(durability: RestoreFileDurability = SystemRestoreFileDurability) = LibraryRestoreTransaction(
            filesDirectory = filesDirectory,
            audioDirectory = audioDirectory,
            stateStore = stateStore,
            durability = durability,
            decodeSnapshot = ::decodeAndValidate,
        )

        fun populateStaging(staging: File) {
            File(staging, "restored-1.mp3").writeBytes(newAudioBytes)
            File(staging, "restored-1.img").writeBytes(newArtworkBytes)
        }

        fun preparedBackup() = PreparedLibraryBackup(
            snapshot = portableSnapshot,
            summary = BackupSummary(1, 1, 1, (newAudioBytes.size + newArtworkBytes.size).toLong()),
        )

        private fun decodeAndValidate(encoded: String?): LibrarySnapshot {
            if (encoded == null) return LibrarySnapshot()
            val snapshot = LibraryStateCodec.decode(encoded)
            snapshot.tracks.forEach { track ->
                val audio = File(track.filePath)
                require(audio.isFile && (PrivateAudioPath.isDirectChild(audioDirectory, audio) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, audio)))
                track.artworkPath?.let { path ->
                    val artwork = File(path)
                    require(artwork.isFile && (PrivateAudioPath.isDirectChild(audioDirectory, artwork) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, artwork)))
                }
            }
            return snapshot
        }
    }

    private class MemoryLibraryStateStore(initial: String?) : LibraryStateStore {
        private var encoded: String? = initial
        var failedWritesRemaining: Int = 0

        override fun read(): String? = encoded

        override fun write(encoded: String?): Boolean {
            if (failedWritesRemaining > 0) {
                failedWritesRemaining--
                return false
            }
            this.encoded = encoded
            return true
        }
    }
}
