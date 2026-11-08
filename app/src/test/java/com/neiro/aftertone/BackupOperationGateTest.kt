package com.neiro.aftertone

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOperationGateTest {
    @Test
    fun cancellationAndCommitHandoffRaceHaveExactlyOneWinner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(40) {
                val gate = BackupOperationGate()
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val cancel = executor.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    gate.requestCancellation()
                }
                val finalize = executor.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    gate.beginFinalizing()
                }

                assertTrue(ready.await(1, TimeUnit.SECONDS))
                start.countDown()
                val cancellationWon = cancel.get(1, TimeUnit.SECONDS)
                val finalizationWon = finalize.get(1, TimeUnit.SECONDS)

                assertTrue(cancellationWon != finalizationWon)
                assertFalse(gate.isCancelable)
                assertTrue(gate.isCancelled != gate.isFinalizing)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationWinnerPreventsCommitHandoff() {
        val gate = BackupOperationGate()

        assertTrue(gate.requestCancellation())
        assertFalse(gate.beginFinalizing())
        assertTrue(gate.isCancelled)
    }
}
