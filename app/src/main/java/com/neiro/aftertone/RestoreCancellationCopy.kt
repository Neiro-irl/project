package com.neiro.aftertone

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.storage.LibraryRestoreResult

internal data class RestoreCancellationCopy(
    val statusMessage: String,
    val notification: String,
)

internal fun restoreCancellationCopy(
    committedResult: LibraryRestoreResult?,
    recoveredLibrary: LibrarySnapshot?,
    cleanupPending: Boolean,
): RestoreCancellationCopy {
    if (cleanupPending) {
        return RestoreCancellationCopy(
            statusMessage = "Восстановление отменено; очистка ещё не подтверждена. Повтори её, чтобы снова управлять музыкой.",
            notification = "Очистка восстановления отложена.",
        )
    }
    if (committedResult == null) {
        return RestoreCancellationCopy(
            statusMessage = "Операция отменена. Текущая медиатека не изменена.",
            notification = "Восстановление отменено.",
        )
    }
    if (recoveredLibrary == null) {
        return RestoreCancellationCopy(
            statusMessage = "Восстановление было зафиксировано, но его результат не подтверждён. Повтори проверку медиатеки.",
            notification = "Восстановление требует проверки.",
        )
    }
    if (recoveredLibrary == committedResult.snapshot) {
        return RestoreCancellationCopy(
            statusMessage = "Резервная копия восстановлена после отмены. Текущая медиатека проверена.",
            notification = "Резервная копия восстановлена.",
        )
    }
    if (recoveredLibrary == committedResult.previousSnapshot) {
        return RestoreCancellationCopy(
            statusMessage = "Новая копия не принята; предыдущая медиатека восстановлена.",
            notification = "Предыдущая медиатека восстановлена.",
        )
    }
    return RestoreCancellationCopy(
        statusMessage = "Отмена произошла после фиксации; текущее состояние медиатеки повторно загружено и проверено.",
        notification = "Медиатека перепроверена.",
    )
}
