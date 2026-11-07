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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackArtwork
import com.neiro.aftertone.ui.components.TrackRow
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneSurface
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun CollectionDetailScreen(
    library: LibrarySnapshot,
    playlist: Playlist?,
    favorites: Boolean,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToCollection: (String) -> Unit,
    onRemoveFromDevice: (Track) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
) {
    val tracks = if (favorites) {
        library.tracks.filter { it.id in library.favoriteTrackIds }
    } else {
        val trackIds = playlist?.trackIds.orEmpty().toSet()
        library.tracks.filter { it.id in trackIds }
    }
    val title = if (favorites) "Избранное" else playlist?.name ?: "Подборка"
    val heroTrack = tracks.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AftertoneText)
                }
                Text(if (favorites) "ИЗБРАННОЕ" else "ПОДБОРКА", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (heroTrack != null) {
                    TrackArtwork(heroTrack, Modifier.fillMaxWidth().height(230.dp), cornerRadius = 22)
                }
                Text(title, color = AftertoneText, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatTrackCount(tracks.size)} · хранится на этом устройстве", color = AftertoneMuted, fontSize = 13.sp)
                if (tracks.isNotEmpty()) {
                    ActionPill(
                        label = "Слушать подборку",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { onPlayTracks(tracks, tracks.first().id) },
                    )
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                Spacer(Modifier.height(34.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(if (favorites) Icons.Filled.Favorite else Icons.Filled.MusicNote, contentDescription = null, tint = AftertoneMuted, modifier = Modifier.size(35.dp))
                    Text(if (favorites) "Сохранённые треки появятся здесь." else "В этой подборке пока пусто.", color = AftertoneText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Добавляй музыку через меню в библиотеке.", color = AftertoneMuted, fontSize = 13.sp)
                }
            }
        } else {
            item {
                Text("ТРЕКИ В ПОДБОРКЕ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
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
                        onRemoveFromCollection = playlist?.id?.let { playlistId ->
                            { onRemoveFromPlaylist(playlistId, track.id) }
                        },
                    )
                    HorizontalDivider(color = AftertoneOutline.copy(alpha = 0.72f), thickness = 0.6.dp)
                }
            }
        }
    }
}
