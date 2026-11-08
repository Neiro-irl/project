package com.neiro.aftertone.ui.screens

import java.util.concurrent.atomic.AtomicLong

internal class BackupProgressThrottle(
    private val minimumIntervalNanos: Long = 200_000_000L,
) {
    private val lastPublishedNanos = AtomicLong(Long.MIN_VALUE)

    init {
        require(minimumIntervalNanos >= 0)
    }

    fun shouldPublishAt(nowNanos: Long, force: Boolean = false): Boolean {
        if (force) {
            lastPublishedNanos.set(nowNanos)
            return true
        }
        while (true) {
            val previous = lastPublishedNanos.get()
            if (previous != Long.MIN_VALUE && nowNanos - previous < minimumIntervalNanos) return false
            if (lastPublishedNanos.compareAndSet(previous, nowNanos)) return true
        }
    }
}
