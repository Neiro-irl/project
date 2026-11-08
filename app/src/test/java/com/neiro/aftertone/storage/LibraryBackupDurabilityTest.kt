package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Track
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupDurabilityTest {
    @Test
    fun extractionSyncsEveryAssetAndStagingDirectoryBeforeReturning() {
        val root = Files.createTempDirectory("aftertone-extract-durability-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { mkdir() }
            val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
            val audioBytes = ByteArray(64 * 1024) { (it * 13).toByte() }
            val artworkBytes = byteArrayOf(1, 3, 5, 7)
            val audio = File(audioDirectory, "track-1.mp3").apply { writeBytes(audioBytes) }
            val artwork = File(audioDirectory, "track-1.img").apply { writeBytes(artworkBytes) }
            val snapshot = LibrarySnapshot(
                tracks = listOf(Track("track-1", "Local", "Artist", "Album", 1_000, audio.canonicalPath, artwork.canonicalPath)),
            )
            val archive = File(root, "source.zip")
            archive.outputStream().use { LibraryBackupArchive.export(snapshot, audioDirectory, it) }
            val staging = File(audioDirectory, "stage").apply { mkdir() }
            val events = mutableListOf<String>()
            val durability = RecordingRestoreFileDurability(events)

            LibraryBackupArchive.extract(
                archiveFile = archive,
                stagingDirectory = staging,
                availableBytes = { Long.MAX_VALUE },
                durability = durability,
            )

            assertEquals(2, events.count { it == "file" })
            assertTrue("Staging directory was not synced: $events", "dir:${staging.canonicalPath}" in events)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun journalAndGenerationDirectoriesAreSyncedBeforeNewStateCommit() {
        val root = Files.createTempDirectory("aftertone-transaction-durability-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { mkdir() }
            val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
            val oldAudio = File(audioDirectory, "old-1.mp3").apply { writeBytes(byteArrayOf(8, 7, 6)) }
            val oldSnapshot = LibrarySnapshot(
                tracks = listOf(Track("old-1", "Old", "Artist", "Album", 1_000, oldAudio.canonicalPath)),
            )
            var storedState: String? = LibraryStateCodec.encode(oldSnapshot)
            val events = mutableListOf<String>()
            val stateStore = object : LibraryStateStore {
                override fun read(): String? = storedState
                override fun write(encoded: String?): Boolean {
                    events += "state-commit"
                    storedState = encoded
                    return true
                }
            }
            val durability = RecordingRestoreFileDurability(events)
            val transaction = LibraryRestoreTransaction(
                filesDirectory = filesDirectory,
                audioDirectory = audioDirectory,
                stateStore = stateStore,
                decodeSnapshot = { encoded -> if (encoded == null) LibrarySnapshot() else LibraryStateCodec.decode(encoded) },
                durability = durability,
            )
            val staging = transaction.createStagingDirectory()
            File(staging, "new-1.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
            val portableSnapshot = LibrarySnapshot(
                tracks = listOf(Track("new-1", "New", "Artist", "Album", 2_000, "audio/new-1.mp3")),
            )
            val prepared = PreparedLibraryBackup(portableSnapshot, BackupSummary(1, 0, 0, 4))
            events.clear()

            val pending = transaction.prepareRestore(staging, prepared)
            transaction.commitRestore(pending)

            val stagingSync = events.indexOf("dir:${staging.canonicalPath}")
            val journalParentSync = events.indexOf("dir:${filesDirectory.canonicalPath}")
            val generationParentSync = events.indexOf("dir:${audioDirectory.canonicalPath}")
            val commit = events.indexOf("state-commit")
            assertTrue("Staging sync missing or out of order: $events", stagingSync >= 0)
            assertTrue("Journal parent sync missing or out of order: $events", journalParentSync > stagingSync)
            assertTrue("Generation parent sync missing or out of order: $events", generationParentSync > journalParentSync)
            assertTrue("State committed before durable generation: $events", commit > generationParentSync)
        } finally {
            root.deleteRecursively()
        }
    }
}
