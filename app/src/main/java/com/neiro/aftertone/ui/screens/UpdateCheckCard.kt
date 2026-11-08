package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneText
import com.neiro.aftertone.updates.UpdateCheckInfo

@Composable
internal fun UpdateCheckCard(
    installedVersion: String,
    onCheckForUpdates: () -> Unit,
) {
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
            Button(
                onClick = onCheckForUpdates,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Проверить обновления")
            }
            Text(
                text = UpdateCheckInfo.installedVersionLabel(installedVersion),
                color = AftertoneText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Откроется GitHub Releases. Для просмотра понадобится вход в GitHub с доступом к приватному репозиторию.",
                color = AftertoneMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
