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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.neiro.aftertone.model.filterLocalTracks
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
    val tracks = filterLocalTracks(library.tracks, query)

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ТВОЯ МУЗЫКА · ОФЛАЙН", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                    Text("Библиотека", color = AftertoneText, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                    Text(formatTrackCount(library.tracks.size), color = AftertoneMuted, fontSize = 13.sp)
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
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Очистить поиск", tint = AftertoneMuted) } }
                } else null,
                placeholder = { Text("Название, артист или альбом", color = AftertoneMuted) },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = AftertoneOutline,
                    focusedContainerColor = AftertoneSurface,
                    unfocusedContainerColor = AftertoneSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        when {
            tracks.isEmpty() && query.isNotBlank() -> item {
                EmptyLibraryState(
                    icon = Icons.Filled.Search,
                    title = "Совпадений нет",
                    message = "Попробуй другое название, имя артиста или альбом.",
                )
            }
            library.tracks.isEmpty() -> item {
                EmptyLibraryState(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Здесь пока тихо",
                    message = "Добавь аудиофайлы, чтобы собрать свою офлайн-медиатеку.",
                    action = {
                        ActionPill(label = if (isImporting) "Импорт…" else "Выбрать аудио", icon = Icons.Filled.Add, onClick = onImport)
                    },
                    note = "При удалении Aftertone приватная копия музыки удалится; оригинал останется.",
                )
            }
            else -> {
                item {
                    SectionHeading(title = if (query.isBlank()) "Все треки" else "Результаты · ${tracks.size}")
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
                        HorizontalDivider(color = AftertoneOutline.copy(alpha = 0.72f), thickness = 0.6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    action: @Composable (() -> Unit)? = null,
    note: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AftertoneMuted, modifier = Modifier.size(34.dp))
        Text(title, color = AftertoneText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
        action?.invoke()
        note?.let { Text(it, color = AftertoneMuted, fontSize = 12.sp, lineHeight = 17.sp) }
        Spacer(Modifier.height(1.dp))
    }
}
