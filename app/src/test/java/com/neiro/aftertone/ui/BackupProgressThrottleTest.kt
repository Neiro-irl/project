package com.neiro.aftertone.ui

import com.neiro.aftertone.ui.screens.BackupProgressThrottle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProgressThrottleTest {
    @Test
    fun progressIsCoalescedButFinalUpdateCanBeForced() {
        val throttle = BackupProgressThrottle(minimumIntervalNanos = 100)

        assertTrue(throttle.shouldPublishAt(1_000))
        assertFalse(throttle.shouldPublishAt(1_099))
        assertTrue(throttle.shouldPublishAt(1_100))
        assertTrue(throttle.shouldPublishAt(1_101, force = true))
        assertFalse(throttle.shouldPublishAt(1_150))
        assertTrue(throttle.shouldPublishAt(1_201))
    }
}
