package com.neiro.aftertone.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
internal fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая подборка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Дай название своей подборке.", color = AftertoneMuted, fontSize = 13.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Название подборки") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    trackTitle: String,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onSelect: (Playlist) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в подборку") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(trackTitle, color = AftertoneMuted, fontSize = 12.sp)
                if (playlists.isEmpty()) {
                    Text("У тебя пока нет подборок.", color = AftertoneMuted, fontSize = 13.sp)
                } else {
                    playlists.forEach { playlist ->
                        TextButton(onClick = { onSelect(playlist) }, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(playlist.name, color = AftertoneText, modifier = Modifier.weight(1f))
                                Text(formatTrackCount(playlist.trackIds.size), color = AftertoneMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                TextButton(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
                    Text("Создать подборку", color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
internal fun ConfirmRemoveDialog(track: Track, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить этот трек?", fontWeight = FontWeight.SemiBold) },
        text = { Text("Aftertone удалит свою копию «${track.displayTitle}» из памяти телефона. Исходный файл не изменится.", color = AftertoneMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun ConfirmDeletePlaylistDialog(playlist: Playlist, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить подборку?", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "Подборка «${playlist.name}» будет удалена. Музыка на телефоне и другие подборки останутся без изменений.",
                color = AftertoneMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Удалить подборку", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
