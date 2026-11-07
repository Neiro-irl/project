package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackRow
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneSurface
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun LibraryScreen(
    library: LibrarySnapshot,
    isImporting: Boolean,
    onImport: () -> Unit,
    onPlayTracks: (List<Track>, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToCollection: (String) -> Unit,
    onRemoveFromDevice: (Track) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val tracks = library.tracks
        .filter { query.isBlank() || "${it.displayTitle} ${it.displayArtist} ${it.album}".contains(query.trim(), ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ТВОЯ МЕДИАТЕКА", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                    Text("Вся музыка", color = AftertoneText, fontSize = 31.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.7).sp)
                    Text("${formatTrackCount(library.tracks.size)} на этом телефоне", color = AftertoneMuted, fontSize = 13.sp)
                }
                ActionPill(label = if (isImporting) "Импорт…" else "Добавить", icon = Icons.Filled.Add, onClick = onImport)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AftertoneMuted) },
                placeholder = { Text("Поиск по названиям и артистам", color = AftertoneMuted) },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = AftertoneOutline,
                    focusedContainerColor = AftertoneSurface,
                    unfocusedContainerColor = AftertoneSurface,
                ),
            )
        }
        if (tracks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 54.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = AftertoneMuted, modifier = Modifier.size(34.dp))
                    Text(if (query.isBlank()) "Медиатека ждёт, когда ты добавишь музыку." else "Ничего не найдено: «$query».", color = AftertoneText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(if (query.isBlank()) "Добавь аудиофайлы, чтобы слушать их без интернета." else "Попробуй другое название или имя артиста.", color = AftertoneMuted, fontSize = 13.sp)
                    if (query.isBlank()) {
                        Text("При удалении Aftertone его копии музыки удалятся; исходные файлы останутся.", color = AftertoneMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(2.dp))
                        ActionPill(label = if (isImporting) "Импорт…" else "Выбрать аудио", icon = Icons.Filled.Add, onClick = onImport)
                    }
                }
            }
        } else {
            item {
                Text("ТРЕКИ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            }
            items(tracks, key = Track::id) { track ->
                Column {
                    TrackRow(
                        track = track,
                        isFavorite = track.id in library.favoriteTrackIds,
                        onClick = { onPlayTracks(tracks, track.id) },
                        onToggleFavorite = { onToggleFavorite(track.id) },
                        onAddToCollection = { onAddToCollection(track.id) },
                        onRemoveFromDevice = { onRemoveFromDevice(track) },
                    )
                    HorizontalDivider(color = AftertoneOutline.copy(alpha = 0.55f), thickness = 0.6.dp)
                }
            }
        }
    }
}
