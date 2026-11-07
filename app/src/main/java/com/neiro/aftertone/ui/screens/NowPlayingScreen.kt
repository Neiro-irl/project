package com.neiro.aftertone.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.media3.common.Player
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.components.ActionPill
import com.neiro.aftertone.ui.components.SquareTrackArtwork
import com.neiro.aftertone.ui.components.formatDuration
import com.neiro.aftertone.ui.components.formatTrackCount
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
fun NowPlayingScreen(
    track: Track?,
    isFavorite: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    queueSize: Int,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    if (track == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Пока ничего не играет", color = AftertoneText, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text("Выбери трек в медиатеке, чтобы начать.", color = AftertoneMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            ActionPill(label = "В медиатеку", onClick = onBack)
        }
        return
    }

    val effectiveDuration = durationMs.takeIf { it > 0 } ?: track.durationMs
    var scrubValue by remember(track.id) { mutableStateOf<Float?>(null) }
    val progress = if (effectiveDuration > 0) (currentPositionMs.toFloat() / effectiveDuration).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = AftertoneText)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("СЕЙЧАС ИГРАЕТ", color = AftertoneMuted, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Text("ЛОКАЛЬНАЯ ОЧЕРЕДЬ · ${formatTrackCount(queueSize).uppercase()}", color = AftertoneMuted.copy(alpha = 0.72f), fontSize = 9.sp, letterSpacing = 1.sp)
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics { contentDescription = if (isFavorite) "Убрать из избранного" else "Добавить в избранное" },
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) AftertoneAccent else AftertoneMuted,
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val artSize = minOf(maxWidth, 390.dp)
            AnimatedContent(
                targetState = track,
                modifier = Modifier.width(artSize),
                transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(initialScale = 0.97f, animationSpec = tween(240))) togetherWith fadeOut(tween(150))
                },
                label = "now-playing-artwork",
            ) { targetTrack ->
                SquareTrackArtwork(targetTrack)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(track.displayTitle, color = AftertoneText, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = listOf(track.displayArtist, track.album).filter(String::isNotBlank).joinToString(" · "),
                        color = AftertoneMuted,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Slider(
                    value = scrubValue ?: progress,
                    onValueChange = { scrubValue = it },
                    onValueChangeFinished = { scrubValue?.let { onSeek((it * effectiveDuration).toLong()) }; scrubValue = null },
                    enabled = effectiveDuration > 0,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Позиция воспроизведения" },
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(scrubValue?.let { (it * effectiveDuration).toLong() } ?: currentPositionMs), color = AftertoneMuted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(effectiveDuration), color = AftertoneMuted, fontSize = 11.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = if (shuffleModeEnabled) "Выключить перемешивание" else "Включить перемешивание"
                    },
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, tint = if (shuffleModeEnabled) AftertoneAccent else AftertoneMuted, modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp).semantics { contentDescription = "Предыдущий трек" }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = AftertoneText, modifier = Modifier.size(30.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = AftertoneAccent,
                    modifier = Modifier.size(68.dp),
                    onClick = onTogglePlayPause,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(35.dp),
                        )
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp).semantics { contentDescription = "Следующий трек" }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null, tint = AftertoneText, modifier = Modifier.size(30.dp))
                }
                IconButton(
                    onClick = onToggleRepeat,
                    modifier = Modifier.size(44.dp).semantics {
                        contentDescription = when (repeatMode) {
                            Player.REPEAT_MODE_ALL -> "Повторять один трек"
                            Player.REPEAT_MODE_ONE -> "Выключить повтор"
                            else -> "Включить повтор очереди"
                        }
                    },
                ) {
                    val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat
                    Icon(repeatIcon, contentDescription = null, tint = if (repeatMode == Player.REPEAT_MODE_OFF) AftertoneMuted else AftertoneAccent, modifier = Modifier.size(21.dp))
                }
            }
            Text("Сохранено на этом телефоне", color = AftertoneMuted.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
