package com.neiro.aftertone

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.storage.LibraryRestoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreCancellationCopyTest {
    @Test
    fun committedCancellationReportsRecoveredRestoreInsteadOfUnchangedLibrary() {
        val restored = LibrarySnapshot(playlists = listOf(Playlist("restored", "Restored playlist")))
        val previous = LibrarySnapshot(playlists = listOf(Playlist("previous", "Previous playlist")))
        val committedResult = LibraryRestoreResult("transaction-1", restored, previous)

        val copy = restoreCancellationCopy(
            committedResult = committedResult,
            recoveredLibrary = restored,
            cleanupPending = false,
        )

        assertTrue(copy.statusMessage.contains("восстановлена"))
        assertFalse(copy.statusMessage.contains("не изменена"))
        assertEquals("Резервная копия восстановлена.", copy.notification)
    }

    @Test
    fun committedCancellationReportsPreviousLibraryWhenRecoveryRolledBack() {
        val restored = LibrarySnapshot(playlists = listOf(Playlist("restored", "Restored playlist")))
        val previous = LibrarySnapshot(playlists = listOf(Playlist("previous", "Previous playlist")))
        val committedResult = LibraryRestoreResult("transaction-2", restored, previous)

        val copy = restoreCancellationCopy(
            committedResult = committedResult,
            recoveredLibrary = previous,
            cleanupPending = false,
        )

        assertTrue(copy.statusMessage.contains("предыдущая медиатека восстановлена"))
        assertEquals("Предыдущая медиатека восстановлена.", copy.notification)
    }

    @Test
    fun pendingCleanupDoesNotClaimTheLibraryWasUnchanged() {
        val copy = restoreCancellationCopy(
            committedResult = null,
            recoveredLibrary = null,
            cleanupPending = true,
        )

        assertTrue(copy.statusMessage.contains("очистка ещё не подтверждена"))
        assertFalse(copy.statusMessage.contains("не изменена"))
        assertEquals("Очистка восстановления отложена.", copy.notification)
    }

    @Test
    fun unconfirmedCommittedRecoveryDoesNotClaimSuccessOrNoChange() {
        val restored = LibrarySnapshot(playlists = listOf(Playlist("restored", "Restored playlist")))
        val previous = LibrarySnapshot(playlists = listOf(Playlist("previous", "Previous playlist")))
        val committedResult = LibraryRestoreResult("transaction-3", restored, previous)

        val copy = restoreCancellationCopy(
            committedResult = committedResult,
            recoveredLibrary = null,
            cleanupPending = false,
        )

        assertTrue(copy.statusMessage.contains("результат не подтверждён"))
        assertFalse(copy.statusMessage.contains("не изменена"))
        assertEquals("Восстановление требует проверки.", copy.notification)
    }

    @Test
    fun unrelatedRecoveredSnapshotGetsNeutralRecheckedStatus() {
        val restored = LibrarySnapshot(playlists = listOf(Playlist("restored", "Restored playlist")))
        val previous = LibrarySnapshot(playlists = listOf(Playlist("previous", "Previous playlist")))
        val unexpected = LibrarySnapshot(playlists = listOf(Playlist("unexpected", "Unexpected playlist")))
        val committedResult = LibraryRestoreResult("transaction-4", restored, previous)

        val copy = restoreCancellationCopy(
            committedResult = committedResult,
            recoveredLibrary = unexpected,
            cleanupPending = false,
        )

        assertTrue(copy.statusMessage.contains("повторно загружено и проверено"))
        assertFalse(copy.statusMessage.contains("Резервная копия восстановлена"))
        assertFalse(copy.statusMessage.contains("предыдущая медиатека восстановлена"))
        assertEquals("Медиатека перепроверена.", copy.notification)
    }

    @Test
    fun cancellationBeforeCommitStillReportsUnchangedLibrary() {
        val previous = LibrarySnapshot(playlists = listOf(Playlist("previous", "Previous playlist")))

        val copy = restoreCancellationCopy(
            committedResult = null,
            recoveredLibrary = previous,
            cleanupPending = false,
        )

        assertEquals("Операция отменена. Текущая медиатека не изменена.", copy.statusMessage)
        assertEquals("Восстановление отменено.", copy.notification)
    }
}
