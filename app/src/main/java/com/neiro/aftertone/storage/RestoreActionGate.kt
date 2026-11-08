package com.neiro.aftertone.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RestoreActionGate {
    companion object {
        const val RECOVERY_PENDING_ID = "restore-recovery-pending"
    }

    private val stateLock = Any()
    private var blocked = true
    private var activeRestore: RestoreOperation? = null
    private var pendingCleanupId: String? = null
    private val mutationMutex = Mutex()

    val isBlocked: Boolean
        get() = synchronized(stateLock) { blocked }

    val pendingCleanupTransactionId: String?
        get() = synchronized(stateLock) { pendingCleanupId }

    fun block() {
        synchronized(stateLock) { blocked = true }
    }

    fun unblock() {
        synchronized(stateLock) {
            if (activeRestore == null && pendingCleanupId == null) blocked = false
        }
    }

    fun beginRestoreOperation(): RestoreOperation = synchronized(stateLock) {
        check(!blocked && activeRestore == null) { "A restore or its cleanup is already pending." }
        blocked = true
        RestoreOperation(this).also { activeRestore = it }
    }

    fun beginCleanupRetry(): RestoreOperation? = synchronized(stateLock) {
        val transactionId = pendingCleanupId ?: return@synchronized null
        if (activeRestore != null) return@synchronized null
        blocked = true
        RestoreOperation(this, transactionId, committed = true).also { activeRestore = it }
    }

    fun adoptPendingCleanup(transactionId: String) {
        require(transactionId.isNotBlank())
        synchronized(stateLock) {
            val active = activeRestore
            if (active == null) {
                if (pendingCleanupId == null || pendingCleanupId == RECOVERY_PENDING_ID || pendingCleanupId == transactionId) {
                    pendingCleanupId = transactionId
                }
                blocked = true
            } else if (active.transactionId == null || active.transactionId == RECOVERY_PENDING_ID || active.transactionId == transactionId) {
                active.transactionId = transactionId
                active.committed = true
                active.cleanupComplete = false
                pendingCleanupId = transactionId
                blocked = true
            }
        }
    }

    fun completeRecoveryWithoutPendingCleanup() {
        synchronized(stateLock) {
            val active = activeRestore
            when {
                active == null -> {
                    pendingCleanupId = null
                    blocked = false
                }
                active.transactionId == null -> {
                    if (pendingCleanupId == RECOVERY_PENDING_ID) pendingCleanupId = null
                }
                active.transactionId == pendingCleanupId -> {
                    active.cleanupComplete = true
                    pendingCleanupId = null
                }
            }
        }
    }

    fun runIfAllowed(action: () -> Unit): Boolean = synchronized(stateLock) {
        if (blocked) return@synchronized false
        action()
        true
    }

    fun allowsMediaSessionCommand(isTrusted: Boolean, isAppController: Boolean): Boolean =
        isTrusted && (isAppController || !isBlocked)

    suspend fun <T> withLibraryAction(action: suspend () -> T): T? = mutationMutex.withLock {
        if (isBlocked) null else action()
    }

    suspend fun <T> withRestoreLock(action: suspend () -> T): T = mutationMutex.withLock {
        action()
    }

    private fun markCommitted(operation: RestoreOperation, transactionId: String) {
        synchronized(stateLock) {
            check(activeRestore === operation) { "The restore operation is no longer active." }
            require(transactionId.isNotBlank())
            operation.transactionId = transactionId
            operation.committed = true
            operation.cleanupComplete = false
            pendingCleanupId = transactionId
            blocked = true
        }
    }

    private fun markCleanupComplete(operation: RestoreOperation) {
        synchronized(stateLock) {
            check(activeRestore === operation) { "The restore operation is no longer active." }
            val transactionId = operation.transactionId
                ?: throw IllegalStateException("No committed restore is awaiting cleanup.")
            check(pendingCleanupId == transactionId) { "The pending restore cleanup changed." }
            operation.cleanupComplete = true
            pendingCleanupId = null
        }
    }

    private fun finish(operation: RestoreOperation) {
        synchronized(stateLock) {
            if (activeRestore !== operation) return
            activeRestore = null
            if (operation.committed && !operation.cleanupComplete) {
                pendingCleanupId = operation.transactionId
                blocked = true
            } else {
                pendingCleanupId = null
                blocked = false
            }
        }
    }

    internal class RestoreOperation internal constructor(
        private val gate: RestoreActionGate,
        internal var transactionId: String? = null,
        internal var committed: Boolean = false,
    ) {
        internal var cleanupComplete: Boolean = false

        fun markCommitted(transactionId: String) = gate.markCommitted(this, transactionId)
        fun markCleanupComplete() = gate.markCleanupComplete(this)
        fun finish() = gate.finish(this)
    }
}

internal fun CoroutineScope.launchRestoreOperation(
    operation: RestoreActionGate.RestoreOperation,
    block: suspend (RestoreActionGate.RestoreOperation) -> Unit,
): Job {
    val job = launch {
        try {
            block(operation)
        } finally {
            operation.finish()
        }
    }
    job.invokeOnCompletion { operation.finish() }
    return job
}
