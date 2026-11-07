package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackArtwork
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneSurface
import com.neiro.aftertone.ui.theme.AftertoneSurfaceHigh
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun CollectionsScreen(
    library: LibrarySnapshot,
    onOpenFavorites: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRequestDeletePlaylist: (Playlist) -> Unit,
) {
    val favoritePreview = library.tracks.firstOrNull { it.id in library.favoriteTrackIds }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ИЗБРАННОЕ И ПЛЕЙЛИСТЫ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Text("Подборки", color = AftertoneText, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                Text("Собери локальные треки под своё настроение.", color = AftertoneMuted, fontSize = 13.sp)
            }
        }
        item {
            CollectionCard(
                title = "Избранное",
                subtitle = formatTrackCount(library.favoriteTrackIds.size),
                preview = favoritePreview,
                icon = Icons.Filled.Favorite,
                onClick = onOpenFavorites,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ТВОИ ПЛЕЙЛИСТЫ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onCreatePlaylist) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(17.dp))
                    Text("Создать", color = AftertoneAccent, fontSize = 12.sp)
                }
            }
        }
        if (library.playlists.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = AftertoneSurface, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(24.dp))
                        Text("Ни одной подборки пока нет", color = AftertoneText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Сохрани треки вместе — для дороги, прогулки или спокойного вечера.", color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        ActionPill(label = "Новая подборка", icon = Icons.Filled.Add, onClick = onCreatePlaylist)
                    }
                }
            }
        } else {
            items(library.playlists, key = Playlist::id) { playlist ->
                val firstTrackId = playlist.trackIds.firstOrNull()
                val preview = firstTrackId?.let { id -> library.tracks.firstOrNull { it.id == id } }
                CollectionCard(
                    title = playlist.name,
                    subtitle = formatTrackCount(playlist.trackIds.size),
                    preview = preview,
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = { onOpenPlaylist(playlist) },
                    onDelete = { onRequestDeletePlaylist(playlist) },
                )
            }
        }
    }
}

@Composable
private fun CollectionCard(
    title: String,
    subtitle: String,
    preview: Track?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember(title) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = AftertoneSurface,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preview != null) {
                TrackArtwork(preview, Modifier.size(64.dp), cornerRadius = 15)
            } else {
                Surface(shape = RoundedCornerShape(15.dp), color = AftertoneSurfaceHigh, modifier = Modifier.size(64.dp)) {
                    Icon(icon, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.padding(19.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = AftertoneText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = AftertoneMuted, fontSize = 12.sp)
            }
            if (onDelete != null) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics { contentDescription = "Действия для подборки $title" },
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AftertoneMuted)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Удалить подборку", color = androidx.compose.material3.MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}
