package com.neiro.aftertone

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun <T> captureRestoreCommit(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    restore: suspend () -> T,
    onCommitted: (T) -> Unit,
): T = withContext(NonCancellable + dispatcher) {
    val result = restore()
    onCommitted(result)
    result
}
