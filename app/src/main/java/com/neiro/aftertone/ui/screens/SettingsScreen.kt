package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.neiro.aftertone.storage.BackupSummary
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertonePalette
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
internal fun SettingsScreen(
    selectedPalette: AftertonePalette,
    onPaletteSelected: (AftertonePalette) -> Unit,
    installedVersion: String,
    onCheckForUpdates: () -> Unit,
    isBackupBusy: Boolean,
    isBackupCancelable: Boolean,
    backupProgressText: String?,
    backupStatusMessage: String?,
    restoreSummary: BackupSummary?,
    onCreateBackup: () -> Unit,
    onSelectRestore: () -> Unit,
    onCancelBackup: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    isRestoreCleanupPending: Boolean = false,
    onRetryRestoreCleanup: () -> Unit = {},
    isTemporaryArchiveCleanupPending: Boolean = false,
    onRetryTemporaryArchiveCleanup: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().selectableGroup(),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Настройки",
                    color = AftertoneText,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "Выбери цветовую тему Aftertone. Изменение применяется сразу и сохраняется на этом устройстве.",
                    color = AftertoneMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Text(
                text = "ЦВЕТОВЫЕ ТЕМЫ",
                color = AftertoneMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        items(AftertonePalette.entries, key = AftertonePalette::id) { palette ->
            PaletteOption(
                palette = palette,
                selected = palette == selectedPalette,
                onClick = { onPaletteSelected(palette) },
            )
        }
        item {
            Text(
                text = "Тема хранится только в Aftertone на этом устройстве.",
                color = AftertoneMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        item {
            BackupSettingsSection(
                isBusy = isBackupBusy,
                isCancellationAllowed = isBackupCancelable,
                progressText = backupProgressText,
                statusMessage = backupStatusMessage,
                restoreSummary = restoreSummary,
                onCreateBackup = onCreateBackup,
                onSelectRestore = onSelectRestore,
                onCancelOperation = onCancelBackup,
                onConfirmRestore = onConfirmRestore,
                onDismissRestore = onDismissRestore,
                isRestoreCleanupPending = isRestoreCleanupPending,
                onRetryRestoreCleanup = onRetryRestoreCleanup,
                isTemporaryArchiveCleanupPending = isTemporaryArchiveCleanupPending,
                onRetryTemporaryArchiveCleanup = onRetryTemporaryArchiveCleanup,
            )
        }
        item {
            Text(
                text = "ОБНОВЛЕНИЯ",
                color = AftertoneMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        item {
            UpdateCheckCard(
                installedVersion = installedVersion,
                onCheckForUpdates = onCheckForUpdates,
            )
        }
    }
}

@Composable
private fun PaletteOption(
    palette: AftertonePalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton,
        ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) AftertoneAccent else AftertoneOutline.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = palette.title,
                    color = AftertoneText,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = palette.description,
                    color = AftertoneMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(5.dp))
                PaletteSwatches(palette)
            }
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = AftertoneAccent,
                    unselectedColor = AftertoneMuted,
                ),
            )
        }
    }
}

@Composable
private fun PaletteSwatches(palette: AftertonePalette) {
    val colors = listOf(
        palette.backgroundArgb,
        palette.surfaceArgb,
        palette.surfaceHighArgb,
        palette.secondaryArgb,
        palette.accentArgb,
        palette.outlineArgb,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .semantics { contentDescription = "Образцы цветов темы ${palette.title}" },
    ) {
        colors.forEach { argb ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(argb.toInt())),
            )
        }
    }
}
