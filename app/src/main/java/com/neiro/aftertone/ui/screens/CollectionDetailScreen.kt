package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackRow
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
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

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AftertoneText)
                }
                Text(title, color = AftertoneText, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (favorites) Icon(Icons.Filled.Favorite, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(18.dp))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                Text("${formatTrackCount(tracks.size)} · сохранено на этом телефоне", color = AftertoneMuted, fontSize = 13.sp)
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
                Spacer(Modifier.size(18.dp))
                Text(if (favorites) "Избранные треки появятся здесь." else "В этой подборке пока нет треков.", color = AftertoneText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Добавляй треки через меню в медиатеке.", color = AftertoneMuted, fontSize = 13.sp)
            }
        } else {
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
                    HorizontalDivider(color = AftertoneOutline.copy(alpha = 0.55f), thickness = 0.6.dp)
                }
            }
        }
    }
}
