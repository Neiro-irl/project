package com.neiro.aftertone.ui.screens

import com.neiro.aftertone.storage.BackupSummary
import com.neiro.aftertone.ui.components.formatTrackCount
import java.util.Locale

internal data class BackupRestoreConfirmationCopy(
    val itemCounts: String,
    val payloadDescription: String,
    val replacementWarning: String,
)

internal fun backupRestoreConfirmationCopy(summary: BackupSummary): BackupRestoreConfirmationCopy =
    BackupRestoreConfirmationCopy(
        itemCounts = "${formatTrackCount(summary.trackCount)} · ${formatPlaylistCount(summary.playlistCount)} · ${formatTrackCount(summary.favoriteCount)} в избранном",
        payloadDescription = "Аудиофайлы и обложки: ${formatBackupBytes(summary.payloadBytes)}",
        replacementWarning = "Медиатека на устройстве будет заменена данными из архива. Текущий трек остановится перед заменой. Старые файлы удалятся только после сохранения восстановленной библиотеки.",
    )

internal fun formatPlaylistCount(count: Int): String {
    require(count >= 0) { "Playlist count cannot be negative." }
    val lastTwoDigits = count % 100
    val noun = if (lastTwoDigits in 11..14) {
        "подборок"
    } else {
        when (count % 10) {
            1 -> "подборка"
            2, 3, 4 -> "подборки"
            else -> "подборок"
        }
    }
    return "$count $noun"
}

internal fun formatBackupBytes(bytes: Long): String {
    require(bytes >= 0) { "Byte count cannot be negative." }
    if (bytes < 1024) return "$bytes Б"
    if (bytes < 1024 * 1024) return "${bytes / 1024} КБ"
    return "${String.format(Locale.forLanguageTag("ru-RU"), "%.1f", bytes / (1024.0 * 1024.0))} МБ"
}
