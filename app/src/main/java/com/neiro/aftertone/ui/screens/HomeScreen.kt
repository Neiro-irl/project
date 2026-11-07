package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val spotlight = recentTracks.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AftertoneMark()
                    Spacer(Modifier.width(9.dp))
                    Text("AFTERTONE", color = AftertoneText, fontSize = 12.sp, letterSpacing = 2.1.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = CircleShape, color = AftertoneSurface) {
                        Text("НА ЭТОМ УСТРОЙСТВЕ", color = AftertoneMuted, fontSize = 9.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
                    }
                }
                Text(
                    text = "Твоя музыка.\nТвой ритм.",
                    color = AftertoneText,
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                    letterSpacing = (-1.1).sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (library.tracks.isEmpty()) "Личная коллекция для прослушивания офлайн." else "${formatTrackCount(library.tracks.size)} всегда рядом — даже без сети.",
                    color = AftertoneMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        }

        if (spotlight == null) {
            item {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = AftertoneSurfaceHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Surface(shape = CircleShape, color = AftertoneAccent.copy(alpha = 0.14f)) {
                            Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.padding(12.dp).size(25.dp))
                        }
                        Text("Добавь музыку, которой владеешь.", color = AftertoneText, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text("Выбери аудиофайлы на телефоне. Aftertone сохранит приватную копию, чтобы слушать её офлайн.", color = AftertoneMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        Text("При удалении приложения импортированные копии удалятся, исходные файлы останутся.", color = AftertoneMuted, fontSize = 12.sp, lineHeight = 18.sp)
                        ActionPill(label = if (isImporting) "Импорт…" else "Выбрать аудио", icon = Icons.Filled.Add, onClick = onImport)
                    }
                }
            }
        } else {
            item {
                SpotlightCard(track = spotlight, onClick = { onPlayTracks(recentTracks, spotlight.id) })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickLink(
                        icon = Icons.Filled.LibraryMusic,
                        title = "Библиотека",
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
            if (recentTracks.size > 1) {
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
}

@Composable
private fun SpotlightCard(track: Track, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = AftertoneSurfaceHigh,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(254.dp)) {
            TrackArtwork(track, Modifier.matchParentSize(), cornerRadius = 0)
            Box(
                modifier = Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.92f)),
                    ),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("ИЗ ТВОЕЙ КОЛЛЕКЦИИ", color = AftertoneAccent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(track.displayTitle, color = AftertoneText, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.displayArtist, color = AftertoneText.copy(alpha = 0.76f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Surface(shape = CircleShape, color = AftertoneAccent, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Воспроизвести ${track.displayTitle}", tint = Color.Black, modifier = Modifier.padding(11.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AftertoneMark() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(9.dp, 16.dp, 12.dp, 20.dp, 10.dp).forEachIndexed { index, height ->
            Surface(
                color = if (index == 2) AftertoneAccent else AftertoneAccent.copy(alpha = 0.66f),
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
        shape = RoundedCornerShape(20.dp),
        color = AftertoneSurface,
        modifier = modifier,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = AftertoneAccent.copy(alpha = 0.12f)) {
                Icon(icon, contentDescription = null, tint = AftertoneAccent, modifier = Modifier.padding(10.dp).size(19.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = AftertoneText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = AftertoneMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
