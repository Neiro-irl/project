package com.neiro.aftertone

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.storage.LibraryRestoreResult
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreCommitCaptureTest {
    @Test
    fun cancellationAtDispatcherHandoffUsesTheAlreadyCapturedCommittedResult() {
        val callerDispatcher = QueuedDispatcher()
        val restoreDispatcher = QueuedDispatcher()
        val parentJob = Job()
        val restored = LibrarySnapshot(playlists = listOf(Playlist("restored", "Restored playlist")))
        val previous = LibrarySnapshot(playlists = listOf(Playlist("previous", "Previous playlist")))
        val committedResult = LibraryRestoreResult("transaction-handoff", restored, previous)
        var capturedResult: LibraryRestoreResult? = null
        var cancellationCopy: RestoreCancellationCopy? = null

        val job = CoroutineScope(parentJob + callerDispatcher).launch {
            try {
                captureRestoreCommit(
                    dispatcher = restoreDispatcher,
                    restore = { committedResult },
                    onCommitted = { capturedResult = it },
                )
            } catch (_: CancellationException) {
                cancellationCopy = restoreCancellationCopy(
                    committedResult = capturedResult,
                    recoveredLibrary = restored,
                    cleanupPending = false,
                )
            }
        }

        callerDispatcher.runNext()
        restoreDispatcher.runNext()
        assertTrue("The coroutine must be suspended at the return dispatcher handoff.", job.isActive)
        assertTrue("The return to the caller must still be queued.", callerDispatcher.hasQueuedTasks())

        parentJob.cancel()
        callerDispatcher.runUntilIdle()

        assertEquals("Резервная копия восстановлена.", cancellationCopy?.notification)
    }
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runNext() {
        check(tasks.isNotEmpty()) { "Expected a queued coroutine continuation." }
        tasks.removeFirst().run()
    }

    fun runUntilIdle() {
        while (tasks.isNotEmpty()) runNext()
    }

    fun hasQueuedTasks(): Boolean = tasks.isNotEmpty()
}
