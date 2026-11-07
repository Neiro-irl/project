package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneSurface
import com.neiro.aftertone.ui.theme.AftertoneSurfaceHigh
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun SearchScreen(
    library: LibrarySnapshot,
    isImporting: Boolean,
    onImport: () -> Unit,
    onPlayTracks: (List<Track>, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToCollection: (String) -> Unit,
    onRemoveFromDevice: (Track) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = filterLocalTracks(library.tracks, query)
    val recentTracks = library.tracks.asReversed().take(5)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Только твои файлы", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                Text("Поиск", color = AftertoneText, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                Text("Найди трек в своей медиатеке — офлайн и без каталога.", color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AftertoneText) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Очистить поиск", tint = AftertoneMuted) } }
                } else null,
                placeholder = { Text("Трек, артист или альбом", color = AftertoneMuted) },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = AftertoneOutline,
                    focusedContainerColor = AftertoneSurfaceHigh,
                    unfocusedContainerColor = AftertoneSurfaceHigh,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        when {
            library.tracks.isEmpty() -> item {
                Surface(shape = RoundedCornerShape(24.dp), color = AftertoneSurface, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(21.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(26.dp))
                        Text("Сначала добавь музыку", color = AftertoneText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Поиск работает по аудиофайлам, которые ты импортировал в Aftertone.", color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        ActionPill(label = if (isImporting) "Импорт…" else "Выбрать аудио", icon = Icons.Filled.Add, onClick = onImport)
                    }
                }
            }
            query.isBlank() -> {
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = AftertoneSurface, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("ТВОЯ МУЗЫКА", color = AftertoneAccent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                            Text(formatTrackCount(library.tracks.size), color = AftertoneText, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text("Ищи по названию, исполнителю или альбому. Всё хранится на этом устройстве.", color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
                item { SectionHeading(title = "Недавно добавлено") }
                items(recentTracks, key = Track::id) { track ->
                    Column {
                        TrackRow(
                            track = track,
                            isFavorite = track.id in library.favoriteTrackIds,
                            onClick = { onPlayTracks(recentTracks, track.id) },
                            onToggleFavorite = { onToggleFavorite(track.id) },
                            onAddToCollection = { onAddToCollection(track.id) },
                            onRemoveFromDevice = { onRemoveFromDevice(track) },
                        )
                        HorizontalDivider(color = AftertoneOutline.copy(alpha = 0.72f), thickness = 0.6.dp)
                    }
                }
            }
            results.isEmpty() -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 38.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = AftertoneMuted, modifier = Modifier.size(34.dp))
                    Text("Не нашлось совпадений", color = AftertoneText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Попробуй другое слово или проверь написание.", color = AftertoneMuted, fontSize = 13.sp)
                }
            }
            else -> {
                item { SectionHeading(title = "Найдено · ${results.size}") }
                items(results, key = Track::id) { track ->
                    Column {
                        TrackRow(
                            track = track,
                            isFavorite = track.id in library.favoriteTrackIds,
                            onClick = { onPlayTracks(results, track.id) },
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
