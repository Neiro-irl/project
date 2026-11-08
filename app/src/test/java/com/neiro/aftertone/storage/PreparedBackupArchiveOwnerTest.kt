package com.neiro.aftertone.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedBackupArchiveOwnerTest {
    @Test
    fun ownershipRemainsUntilDiscardSucceeds() = runBlocking {
        val owner = PreparedBackupArchiveOwner()
        val prepared = preparedArchive()
        assertTrue(owner.retain(prepared))

        assertFalse(owner.discard(prepared) { false })
        assertTrue(owner.current() == prepared)

        assertTrue(owner.discard(prepared) { true })
        assertTrue(owner.discard(prepared) { false })
        assertNull(owner.current())
    }

    @Test
    fun failedCleanupKeepsArchiveOwnedButPreventsItFromBeingUsedForRestore() = runBlocking {
        val owner = PreparedBackupArchiveOwner()
        val prepared = preparedArchive()
        assertTrue(owner.retain(prepared))

        assertFalse(owner.discard(prepared) { false })
        assertTrue(owner.current() == prepared)
        assertTrue(owner.state.value.cleanupPending)
        assertFalse(owner.beginUse(prepared))

        assertTrue(owner.discard(prepared) { true })
        assertNull(owner.current())
        assertFalse(owner.state.value.cleanupPending)
    }

    @Test
    fun secondFailedCleanupStaysQueuedBesideTheCurrentArchive() = runBlocking {
        val owner = PreparedBackupArchiveOwner()
        val current = preparedArchive("11111111-1111-1111-1111-111111111111")
        val cleanupCandidate = preparedArchive("22222222-2222-2222-2222-222222222222")
        assertTrue(owner.retain(current))

        assertTrue(owner.retain(cleanupCandidate, cleanupPending = true))
        assertEquals(current, owner.current())
        assertFalse(owner.discard(cleanupCandidate) { false })
        assertTrue(owner.state.value.cleanupPending)

        assertTrue(owner.discard(cleanupCandidate) { true })
        assertFalse(owner.state.value.cleanupPending)
        assertEquals(current, owner.current())
    }

    @Test
    fun cancellingCompositionCleanupDoesNotReleaseOwnerBeforeDeletionCompletes() = runBlocking {
        val owner = PreparedBackupArchiveOwner()
        val prepared = preparedArchive()
        owner.retain(prepared)
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()

        val cleanup = launch {
            owner.discard(prepared) {
                cleanupStarted.complete(Unit)
                allowDeletion.await()
                true
            }
        }
        cleanupStarted.await()
        cleanup.cancel()
        allowDeletion.complete(Unit)
        cleanup.join()

        assertNull(owner.current())
    }

    @Test
    fun activeRestoreCannotRaceWithDismissOrCompositionCleanup() = runBlocking {
        val owner = PreparedBackupArchiveOwner()
        val prepared = preparedArchive()
        owner.retain(prepared)
        assertTrue(owner.beginUse(prepared))
        var deletionCalled = false

        assertFalse(owner.discard(prepared) { deletionCalled = true; true })
        assertFalse(deletionCalled)
        assertTrue(owner.current() == prepared)

        assertTrue(owner.endUse(prepared))
        assertTrue(owner.discard(prepared) { deletionCalled = true; true })
        assertTrue(deletionCalled)
        assertNull(owner.current())
    }

    private fun preparedArchive(id: String = "11111111-1111-1111-1111-111111111111") = PreparedBackupArchive(
        id = id,
        summary = BackupSummary(1, 0, 0, 3),
    )
}
