package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.neiro.aftertone.storage.BackupSummary
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
internal fun BackupSettingsSection(
    isBusy: Boolean,
    isCancellationAllowed: Boolean,
    progressText: String?,
    statusMessage: String?,
    restoreSummary: BackupSummary?,
    onCreateBackup: () -> Unit,
    onSelectRestore: () -> Unit,
    onCancelOperation: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    isRestoreCleanupPending: Boolean = false,
    onRetryRestoreCleanup: () -> Unit = {},
    isTemporaryArchiveCleanupPending: Boolean = false,
    onRetryTemporaryArchiveCleanup: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "РЕЗЕРВНАЯ КОПИЯ",
            color = AftertoneMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, AftertoneOutline.copy(alpha = 0.7f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Музыка останется на этом устройстве",
                    color = AftertoneText,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "ZIP-копия сохраняет импортированные аудиофайлы и обложки, избранное и подборки. Выбери место хранения в системном окне. Для восстановления интернет не нужен.",
                    color = AftertoneMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onCreateBackup,
                    enabled = !isBusy && !isRestoreCleanupPending && !isTemporaryArchiveCleanupPending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Создать резервную копию")
                }
                OutlinedButton(
                    onClick = onSelectRestore,
                    enabled = !isBusy && !isRestoreCleanupPending && !isTemporaryArchiveCleanupPending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Восстановить из ZIP")
                }
                if (isBusy) {
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics {
                            stateDescription = progressText ?: "Выполняется операция с резервной копией"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            color = AftertoneAccent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = progressText ?: "Подготовка…",
                            modifier = Modifier.weight(1f),
                            color = AftertoneMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (isCancellationAllowed) {
                            TextButton(onClick = onCancelOperation) { Text("Отменить") }
                        } else {
                            Text("Сохраняю медиатеку…", color = AftertoneMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                statusMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = AftertoneMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (isRestoreCleanupPending) {
                    OutlinedButton(
                        onClick = onRetryRestoreCleanup,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Повторить очистку старых копий")
                    }
                }
                if (isTemporaryArchiveCleanupPending) {
                    OutlinedButton(
                        onClick = onRetryTemporaryArchiveCleanup,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Повторить удаление временного ZIP")
                    }
                }
            }
        }
    }

    restoreSummary?.let { summary ->
        val copy = backupRestoreConfirmationCopy(summary)
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text("Заменить медиатеку этой копией?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(copy.itemCounts)
                    Text(copy.payloadDescription)
                    Text(
                        copy.replacementWarning,
                        color = AftertoneMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                Button(onClick = onConfirmRestore) { Text("Восстановить и заменить") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestore) { Text("Отмена") }
            },
        )
    }
}
