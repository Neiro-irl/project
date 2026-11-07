package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.ui.components.ActionPill
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
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ТВОИ ПОДБОРКИ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Text("Создано тобой", color = AftertoneText, fontSize = 31.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.7).sp)
                Text("Собери музыку под своё настроение.", color = AftertoneMuted, fontSize = 13.sp)
            }
        }
        item {
            CollectionCard(
                title = "Избранное",
                subtitle = "Треки, которые ты сохранил",
                count = library.favoriteTrackIds.size,
                icon = Icons.Filled.Favorite,
                onClick = onOpenFavorites,
            )
        }
        if (library.playlists.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = AftertoneSurface, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(23.dp))
                        Text("Создай первую подборку", color = AftertoneText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Собирай треки для дороги, прогулки или спокойного вечера.", color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        ActionPill(label = "Новая подборка", icon = Icons.Filled.Add, onClick = onCreatePlaylist)
                    }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ПЛЕЙЛИСТЫ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = onCreatePlaylist) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(17.dp))
                        Text("Создать", color = AftertoneAccent, fontSize = 12.sp)
                    }
                }
            }
            items(library.playlists, key = Playlist::id) { playlist ->
                CollectionCard(
                    title = playlist.name,
                    subtitle = "Твоя подборка",
                    count = playlist.trackIds.size,
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
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember(title) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AftertoneSurfaceHigh,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(17.dp), color = AftertoneSurface, modifier = Modifier.size(54.dp)) {
                Icon(icon, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.padding(15.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = AftertoneText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = AftertoneMuted, fontSize = 12.sp)
            }
            Text(count.toString(), color = AftertoneAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                            text = { Text("Удалить подборку", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}
