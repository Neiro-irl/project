package com.neiro.aftertone.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PreparedBackupArchiveFilesTest {
    @Test
    fun queuedCleanupRetryDoesNotDeleteTheCurrentRestoreSelection() = runBlocking {
        val cache = Files.createTempDirectory("aftertone-live-archive-selection-").toFile()
        try {
            val currentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val queuedId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            val currentArchive = PreparedBackupArchiveFiles.create(cache, currentId).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            PreparedBackupArchiveFiles.create(cache, queuedId).writeBytes(byteArrayOf(4, 5, 6))
            val owner = PreparedBackupArchiveOwner()
            val current = PreparedBackupArchive(currentId, BackupSummary(1, 0, 0, 3))
            val queued = PreparedBackupArchive(queuedId, BackupSummary(1, 0, 0, 3))
            assertTrue(owner.retain(current))
            assertTrue(owner.retain(queued, cleanupPending = true))

            assertTrue(owner.discard(queued) { PreparedBackupArchiveFiles.discard(cache, queued.id) })
            val cleanup = PreparedBackupArchiveFiles.cleanupOrphansWithPending(
                cache,
                owner.protectedArchiveIdsForCleanup(),
            )

            assertTrue(cleanup.pendingIds.isEmpty())
            assertTrue(currentArchive.isFile)
            assertTrue(PreparedBackupArchiveFiles.isOwned(cache, currentId))
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun ownerMarkerDirectoryEntryIsSyncedBeforeArchiveCanBeWritten() {
        val cache = Files.createTempDirectory("aftertone-owner-marker-durability-").toFile()
        try {
            val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val marker = File(cache, "aftertone-restore-source-$id.owner")
            val archive = File(cache, "aftertone-restore-source-$id.zip")
            var directorySyncs = 0
            val durability = object : RestoreFileDurability {
                override fun syncFile(output: FileOutputStream) = output.fd.sync()

                override fun syncDirectory(directory: File) {
                    assertEquals(cache, directory)
                    assertTrue(marker.isFile)
                    assertEquals(id, marker.readText(Charsets.UTF_8))
                    assertFalse(archive.exists())
                    directorySyncs++
                }
            }

            assertEquals(archive, PreparedBackupArchiveFiles.create(cache, id, durability))
            assertEquals(1, directorySyncs)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepFailsClosedWhenDirectoryListingIsUnavailable() {
        val cache = Files.createTempDirectory("aftertone-owner-scan-unavailable-").toFile()
        try {
            assertThrows(IOException::class.java) {
                PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache) { null }
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepDeletesOnlyArchivesWithMatchingOwnerMarkers() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-store-").toFile()
        try {
            val ownedId = "11111111-1111-1111-1111-111111111111"
            val ownedArchive = PreparedBackupArchiveFiles.create(cache, ownedId).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val ownedMarker = File(cache, "aftertone-restore-source-$ownedId.owner")

            val unownedId = "22222222-2222-2222-2222-222222222222"
            val unownedArchive = File(cache, "aftertone-restore-source-$unownedId.zip").apply {
                writeBytes(byteArrayOf(4, 5, 6))
            }
            val mismatchedMarker = File(cache, "aftertone-restore-source-$unownedId.owner").apply {
                writeText("not-the-archive-id")
            }

            assertEquals(1, PreparedBackupArchiveFiles.cleanupOrphans(cache))

            assertFalse(ownedArchive.exists())
            assertFalse(ownedMarker.exists())
            assertTrue(unownedArchive.isFile)
            assertTrue(mismatchedMarker.isFile)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun orphanSweepReportsFailedOwnedArchiveForLaterCleanupRetry() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-retry-").toFile()
        try {
            val id = "33333333-3333-3333-3333-333333333333"
            val archive = PreparedBackupArchiveFiles.create(cache, id)
            assertTrue(archive.mkdir())

            val failed = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)
            assertEquals(listOf(id), failed.pendingIds)
            assertTrue(archive.isDirectory)

            assertTrue(archive.delete())
            val retried = PreparedBackupArchiveFiles.cleanupOrphansWithPending(cache)
            assertTrue(retried.pendingIds.isEmpty())
            assertFalse(File(cache, "aftertone-restore-source-$id.owner").exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun markerlessArchiveIsNotReportedAsFullyDiscarded() {
        val cache = Files.createTempDirectory("aftertone-prepared-archive-markerless-").toFile()
        try {
            val id = "44444444-4444-4444-4444-444444444444"
            val archive = PreparedBackupArchiveFiles.create(cache, id).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val marker = File(cache, "aftertone-restore-source-$id.owner")
            assertTrue(marker.delete())

            assertFalse(PreparedBackupArchiveFiles.isFullyDiscarded(cache, id))
            assertFalse(PreparedBackupArchiveFiles.discard(cache, id))
        } finally {
            cache.deleteRecursively()
        }
    }
}
