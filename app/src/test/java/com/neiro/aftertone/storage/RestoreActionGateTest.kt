package com.neiro.aftertone.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreActionGateTest {
    @Test
    fun blocksPlayerActionsUntilRestoreAndOldFileCleanupFinish() {
        val gate = RestoreActionGate()
        gate.unblock()
        var playCount = 0

        assertTrue(gate.runIfAllowed { playCount++ })
        gate.block()
        assertFalse(gate.runIfAllowed { playCount++ })
        assertEquals(1, playCount)

        gate.unblock()
        assertTrue(gate.runIfAllowed { playCount++ })
        assertEquals(2, playCount)
    }

    @Test
    fun cancelledRestoreWaitingForRepositoryLockReleasesTheGate() = runBlocking {
        val gate = RestoreActionGate()
        gate.unblock()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = async {
            gate.withLibraryAction {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()
        val operation = gate.beginRestoreOperation()
        val restoreStarted = CompletableDeferred<Unit>()
        val restore = launchRestoreOperation(operation) {
            restoreStarted.complete(Unit)
            gate.withRestoreLock { error("A cancelled restore must not enter the repository lock.") }
        }
        restoreStarted.await()
        yield()
        assertTrue(restore.isActive)
        assertFalse(restore.isCompleted)

        restore.cancelAndJoin()

        assertFalse(gate.isBlocked)
        releaseMutation.complete(Unit)
        mutation.await()
        Unit
    }

    @Test
    fun cancelledCoroutineThatNeverStartsStillReleasesTheRestoreGate() = runBlocking {
        val gate = RestoreActionGate()
        gate.unblock()
        val operation = gate.beginRestoreOperation()
        val parent = Job().apply { cancel() }
        var actionStarted = false

        val restore = CoroutineScope(parent + Dispatchers.Default).launchRestoreOperation(operation) {
            actionStarted = true
        }
        restore.join()

        assertFalse(actionStarted)
        assertFalse(gate.isBlocked)
    }

    @Test
    fun committedRestoreKeepsGateClosedUntilCleanupRetrySucceeds() = runBlocking {
        val gate = RestoreActionGate()
        gate.unblock()
        val operation = gate.beginRestoreOperation()
        val restore = launchRestoreOperation(operation) {
            operation.markCommitted("transaction-1")
        }
        restore.join()

        assertTrue(gate.isBlocked)
        val cleanupRetry = gate.beginCleanupRetry() ?: error("The committed cleanup should be retryable.")
        val cleanup = launchRestoreOperation(cleanupRetry) { it.markCleanupComplete() }
        cleanup.join()
        assertFalse(gate.isBlocked)
    }

    @Test
    fun recoveredCommitPromotesAnActiveRestoreLeaseBeforeItFinishes() {
        val gate = RestoreActionGate()
        gate.unblock()
        val restore = gate.beginRestoreOperation()

        gate.adoptPendingCleanup("recovered-transaction")
        restore.finish()

        assertTrue(gate.isBlocked)
        assertEquals("recovered-transaction", gate.pendingCleanupTransactionId)
        val cleanupRetry = gate.beginCleanupRetry() ?: error("Recovered cleanup must remain retryable.")
        cleanupRetry.markCleanupComplete()
        cleanupRetry.finish()
        assertFalse(gate.isBlocked)
    }

    @Test
    fun discoveredJournalIdReplacesGenericRecoveryPendingId() {
        val gate = RestoreActionGate()
        gate.unblock()
        val restore = gate.beginRestoreOperation()
        restore.markCommitted("restore-recovery-pending")

        gate.adoptPendingCleanup("actual-journal-transaction")
        restore.finish()

        assertEquals("actual-journal-transaction", gate.pendingCleanupTransactionId)
        assertTrue(gate.isBlocked)
    }

    @Test
    fun cancelledRestoreKeepsGateClosedUntilNonCancellableJournalRecoveryFinishes() = runBlocking {
        val gate = RestoreActionGate()
        gate.unblock()
        val operation = gate.beginRestoreOperation()
        val restoreStarted = CompletableDeferred<Unit>()
        val recoveryStarted = CompletableDeferred<Unit>()
        val finishRecovery = CompletableDeferred<Unit>()
        val restore = launchRestoreOperation(operation) {
            try {
                restoreStarted.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    gate.adoptPendingCleanup("cancelled-transaction")
                    recoveryStarted.complete(Unit)
                    finishRecovery.await()
                }
            }
        }

        restoreStarted.await()
        restore.cancel()
        recoveryStarted.await()
        assertTrue(gate.isBlocked)
        assertEquals("cancelled-transaction", gate.pendingCleanupTransactionId)

        finishRecovery.complete(Unit)
        restore.join()
        assertTrue(gate.isBlocked)
    }

    @Test
    fun successfulGenericRecoveryRetryClearsThePendingGate() = runBlocking {
        val gate = RestoreActionGate()
        gate.adoptPendingCleanup(RestoreActionGate.RECOVERY_PENDING_ID)
        val retry = gate.beginCleanupRetry() ?: error("A generic recovery failure must be retryable.")

        gate.completeRecoveryWithoutPendingCleanup()
        retry.finish()

        assertFalse(gate.isBlocked)
        assertNull(gate.pendingCleanupTransactionId)
    }

    @Test
    fun successfulRecoveryCompletesAnActiveCommittedLease() {
        val gate = RestoreActionGate()
        gate.unblock()
        val operation = gate.beginRestoreOperation()
        operation.markCommitted("recovered-transaction")

        gate.completeRecoveryWithoutPendingCleanup()
        operation.finish()

        assertFalse(gate.isBlocked)
        assertNull(gate.pendingCleanupTransactionId)
    }

    @Test
    fun blockedRecoveryGateRejectsLibraryStateWriteSideEffects() {
        val gate = RestoreActionGate()
        gate.adoptPendingCleanup("pending-transaction")
        var storedState = "old snapshot"

        val persisted = gate.runIfAllowed { storedState = "divergent snapshot" }

        assertFalse(persisted)
        assertEquals("old snapshot", storedState)
    }

    @Test
    fun restoreGateRejectsExternalMediaSessionCommandsButAllowsInternalHandoff() {
        val gate = RestoreActionGate()
        gate.block()

        assertFalse(gate.allowsMediaSessionCommand(isTrusted = true, isAppController = false))
        assertTrue(gate.allowsMediaSessionCommand(isTrusted = true, isAppController = true))
        assertFalse(gate.allowsMediaSessionCommand(isTrusted = false, isAppController = true))
    }

    @Test
    fun externalCommandsStayBlockedUntilStartupRestoreRecoveryCompletes() {
        val gate = RestoreActionGate()

        assertTrue(gate.isBlocked)
        assertFalse(gate.allowsMediaSessionCommand(isTrusted = true, isAppController = false))

        gate.unblock()
        assertFalse(gate.isBlocked)
        assertTrue(gate.allowsMediaSessionCommand(isTrusted = true, isAppController = false))

        gate.adoptPendingCleanup("recovered-transaction")
        gate.unblock()
        assertTrue(gate.isBlocked)
        assertEquals("recovered-transaction", gate.pendingCleanupTransactionId)
        assertFalse(gate.allowsMediaSessionCommand(isTrusted = true, isAppController = false))
    }

    @Test
    fun restoreWaitsForExistingMutationAndRejectsQueuedStaleMutations() = runBlocking {
        val gate = RestoreActionGate()
        gate.unblock()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()

        val existingMutation = async {
            gate.withLibraryAction {
                mutationEntered.complete(Unit)
                releaseMutation.await()
                "old snapshot mutation"
            }
        }
        mutationEntered.await()
        gate.block()

        var restoreStarted = false
        val restore = async {
            gate.withRestoreLock {
                restoreStarted = true
                "restored snapshot"
            }
        }
        val queuedMutation = async {
            gate.withLibraryAction { "stale mutation" }
        }

        releaseMutation.complete(Unit)
        assertEquals("old snapshot mutation", existingMutation.await())
        assertEquals("restored snapshot", restore.await())
        assertTrue(restoreStarted)
        assertNull(queuedMutation.await())

        gate.unblock()
        assertEquals("new mutation", gate.withLibraryAction { "new mutation" })
    }
}
