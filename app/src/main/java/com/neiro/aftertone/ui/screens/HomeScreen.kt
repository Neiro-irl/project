package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.TrackArtwork
import com.neiro.aftertone.ui.components.TrackRow
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneSurface
import com.neiro.aftertone.ui.theme.AftertoneSurfaceHigh
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun HomeScreen(
    library: LibrarySnapshot,
    isImporting: Boolean,
    onImport: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenCollections: () -> Unit,
    onPlayTracks: (List<Track>, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToCollection: (String) -> Unit,
    onRemoveFromDevice: (Track) -> Unit,
) {
    val recentTracks = library.tracks.asReversed().take(4)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AftertoneMark()
                    Spacer(Modifier.width(9.dp))
                    Text("AFTERTONE  /  ЛОКАЛЬНО", color = AftertoneMuted, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "Твоя музыка.\nТвоё пространство.",
                    color = AftertoneText,
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                    letterSpacing = (-1.1).sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (library.tracks.isEmpty()) "Личная медиатека, которая остаётся на этом телефоне." else "${formatTrackCount(library.tracks.size)} всегда под рукой.",
                    color = AftertoneMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        }

        if (library.tracks.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = AftertoneSurfaceHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(listOf(Color(0xFF1E3025), AftertoneSurfaceHigh)))
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(shape = CircleShape, color = AftertoneAccent.copy(alpha = 0.13f)) {
                            Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.padding(12.dp).size(25.dp))
                        }
                        Text("Начни с музыки, которая у тебя есть.", color = AftertoneText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Выбери аудиофайлы на телефоне. Aftertone сохранит их в закрытой памяти приложения для прослушивания офлайн.", color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        Text("Важно: при удалении приложения импортированная копия удалится; исходные файлы останутся.", color = AftertoneMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        ActionPill(
                            label = if (isImporting) "Импорт…" else "Добавить музыку",
                            icon = Icons.Filled.Add,
                            onClick = onImport,
                        )
                    }
                }
            }
        } else {
            item {
                val spotlight = recentTracks.firstOrNull()
                if (spotlight != null) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = AftertoneSurface,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPlayTracks(recentTracks, spotlight.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TrackArtwork(spotlight, Modifier.size(82.dp), cornerRadius = 20)
                            Spacer(Modifier.width(15.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("НЕДАВНО ДОБАВЛЕНО", color = AftertoneAccent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                                Text(spotlight.displayTitle, color = AftertoneText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(spotlight.displayArtist, color = AftertoneMuted, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickLink(
                        icon = Icons.Filled.LibraryMusic,
                        title = "Вся музыка",
                        subtitle = formatTrackCount(library.tracks.size),
                        onClick = onOpenLibrary,
                        modifier = Modifier.weight(1f),
                    )
                    QuickLink(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = "Подборки",
                        subtitle = "Избранное и плейлисты",
                        onClick = onOpenCollections,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                SectionHeading(title = "Недавно добавлено", action = "Вся музыка", onAction = onOpenLibrary)
            }
            items(recentTracks.drop(1), key = Track::id) { track ->
                TrackRow(
                    track = track,
                    isFavorite = track.id in library.favoriteTrackIds,
                    onClick = { onPlayTracks(recentTracks, track.id) },
                    onToggleFavorite = { onToggleFavorite(track.id) },
                    onAddToCollection = { onAddToCollection(track.id) },
                    onRemoveFromDevice = { onRemoveFromDevice(track) },
                )
            }
        }
    }
}

@Composable
private fun AftertoneMark() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(9.dp, 16.dp, 12.dp, 20.dp, 10.dp).forEachIndexed { index, height ->
            Surface(
                color = if (index == 2) AftertoneAccent else AftertoneAccent.copy(alpha = 0.68f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.width(3.dp).height(height),
                content = {},
            )
        }
    }
}

@Composable
private fun QuickLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = AftertoneSurface,
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Icon(icon, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.size(21.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = AftertoneText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = AftertoneMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SectionHeading(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AftertoneText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(action, color = AftertoneAccent, fontSize = 12.sp)
            }
        }
    }
}
