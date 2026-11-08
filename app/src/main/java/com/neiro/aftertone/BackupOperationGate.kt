package com.neiro.aftertone

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

internal class BackupOperationGate {
    private val state = AtomicReference(State.ACTIVE)

    val isCancelable: Boolean get() = state.get() == State.ACTIVE
    val isCancelled: Boolean get() = state.get() == State.CANCELLED
    val isFinalizing: Boolean get() = state.get() == State.FINALIZING

    fun requestCancellation(): Boolean = state.compareAndSet(State.ACTIVE, State.CANCELLED)

    fun beginFinalizing(): Boolean = state.compareAndSet(State.ACTIVE, State.FINALIZING)

    fun checkCancelled() {
        if (isCancelled) throw CancellationException("Backup operation cancelled.")
    }

    private enum class State { ACTIVE, CANCELLED, FINALIZING }
}
