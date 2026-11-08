package com.neiro.aftertone.storage

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PreparedBackupArchiveOwnerState(
    val prepared: PreparedBackupArchive? = null,
    internal val preparedCleanupPending: Boolean = false,
    val queuedCleanup: List<PreparedBackupArchive> = emptyList(),
    val sweepPending: Boolean = false,
) {
    val cleanupPending: Boolean
        get() = preparedCleanupPending || queuedCleanup.isNotEmpty() || sweepPending
}

internal class PreparedBackupArchiveOwner {
    private val stateLock = Any()
    private val cleanupMutex = Mutex()
    private val mutableState = MutableStateFlow(PreparedBackupArchiveOwnerState())
    val state: StateFlow<PreparedBackupArchiveOwnerState> = mutableState.asStateFlow()
    private var inUse = false
    private var deleting = false

    fun current(): PreparedBackupArchive? = synchronized(stateLock) { mutableState.value.prepared }

    fun cleanupCandidate(): PreparedBackupArchive? = synchronized(stateLock) {
        val current = mutableState.value
        if (current.preparedCleanupPending) current.prepared else current.queuedCleanup.firstOrNull()
    }

    fun cleanupCandidates(): List<PreparedBackupArchive> = synchronized(stateLock) {
        val current = mutableState.value
        buildList {
            if (current.preparedCleanupPending) current.prepared?.let(::add)
            addAll(current.queuedCleanup)
        }
    }

    fun protectedArchiveIdsForCleanup(): Set<String> = synchronized(stateLock) {
        val current = mutableState.value
        current.prepared?.takeIf { !current.preparedCleanupPending }?.let { setOf(it.id) }.orEmpty()
    }

    fun retain(candidate: PreparedBackupArchive, cleanupPending: Boolean = false): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        val prepared = current.prepared
        if (prepared?.id == candidate.id) {
            if (inUse || deleting || (current.preparedCleanupPending && !cleanupPending)) return@synchronized false
            mutableState.value = current.copy(
                preparedCleanupPending = current.preparedCleanupPending || cleanupPending,
            )
            return@synchronized true
        }

        if (prepared != null || current.queuedCleanup.isNotEmpty()) {
            if (!cleanupPending) return@synchronized false
            if (current.queuedCleanup.any { it.id == candidate.id }) return@synchronized true
            mutableState.value = current.copy(queuedCleanup = current.queuedCleanup + candidate)
            return@synchronized true
        }

        mutableState.value = current.copy(
            prepared = candidate,
            preparedCleanupPending = cleanupPending,
        )
        true
    }

    fun markCleanupPending(candidate: PreparedBackupArchive): Boolean = retain(candidate, cleanupPending = true)

    fun markSweepPending() = synchronized(stateLock) {
        mutableState.value = mutableState.value.copy(sweepPending = true)
    }

    fun markSweepComplete() = synchronized(stateLock) {
        mutableState.value = mutableState.value.copy(sweepPending = false)
    }

    fun completeCleanup(candidate: PreparedBackupArchive): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        if (current.prepared?.id == candidate.id && current.preparedCleanupPending && !inUse && !deleting) {
            val next = current.queuedCleanup.firstOrNull()
            mutableState.value = current.copy(
                prepared = next,
                preparedCleanupPending = next != null,
                queuedCleanup = current.queuedCleanup.drop(if (next == null) 0 else 1),
            )
            return@synchronized true
        }
        if (current.queuedCleanup.none { it.id == candidate.id } || deleting) return@synchronized false
        mutableState.value = current.copy(queuedCleanup = current.queuedCleanup.filterNot { it.id == candidate.id })
        true
    }

    fun beginUse(candidate: PreparedBackupArchive): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        if (current.prepared?.id != candidate.id || current.cleanupPending || inUse || deleting) {
            return@synchronized false
        }
        inUse = true
        true
    }

    fun endUse(candidate: PreparedBackupArchive): Boolean = synchronized(stateLock) {
        if (mutableState.value.prepared?.id != candidate.id || !inUse) return@synchronized false
        inUse = false
        true
    }

    suspend fun discard(
        candidate: PreparedBackupArchive,
        delete: suspend () -> Boolean,
    ): Boolean = cleanupMutex.withLock {
        val mayDelete = synchronized(stateLock) {
            val current = mutableState.value
            if (deleting || inUse ||
                (current.prepared?.id != candidate.id && current.queuedCleanup.none { it.id == candidate.id })
            ) {
                false
            } else {
                deleting = true
                true
            }
        }
        if (!mayDelete) return@withLock synchronized(stateLock) {
            val current = mutableState.value
            current.prepared == null && current.queuedCleanup.isEmpty() && !current.sweepPending
        }

        var deletionSucceeded = false
        try {
            withContext(NonCancellable) { deletionSucceeded = delete() }
            deletionSucceeded
        } finally {
            synchronized(stateLock) {
                val current = mutableState.value
                if (current.prepared?.id == candidate.id) {
                    if (deletionSucceeded) {
                        val next = current.queuedCleanup.firstOrNull()
                        mutableState.value = current.copy(
                            prepared = next,
                            preparedCleanupPending = next != null,
                            queuedCleanup = current.queuedCleanup.drop(if (next == null) 0 else 1),
                        )
                    } else {
                        mutableState.value = current.copy(preparedCleanupPending = true)
                    }
                } else if (deletionSucceeded) {
                    mutableState.value = current.copy(
                        queuedCleanup = current.queuedCleanup.filterNot { it.id == candidate.id },
                    )
                }
                deleting = false
            }
        }
    }
}
