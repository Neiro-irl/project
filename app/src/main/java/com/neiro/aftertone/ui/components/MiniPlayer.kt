package com.neiro.aftertone.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.theme.AftertoneAccent
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneOutline
import com.neiro.aftertone.ui.theme.AftertoneSurfaceHigh

internal fun miniPlayerTitle(track: Track?): String = track?.displayTitle ?: "Пока ничего не играет"

@Composable
fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = track,
        transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically { it / 10 }) togetherWith
                (fadeOut(tween(120)) + slideOutVertically { -it / 10 })
        },
        modifier = modifier.fillMaxWidth(),
        label = "mini-player-content",
    ) { visibleTrack ->
        if (visibleTrack == null) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = AftertoneSurfaceHigh) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = AftertoneMuted,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(miniPlayerTitle(null), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Добавь аудио, чтобы начать", color = AftertoneMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            val effectiveDuration = durationMs.takeIf { it > 0 } ?: visibleTrack.durationMs
            val progress = if (effectiveDuration > 0) {
                (currentPositionMs.toFloat() / effectiveDuration).coerceIn(0f, 1f)
            } else {
                0f
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackArtwork(visibleTrack, Modifier.size(42.dp), cornerRadius = 12)
                    Spacer(Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(miniPlayerTitle(visibleTrack), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(visibleTrack.displayArtist, color = AftertoneMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.semantics { contentDescription = if (isPlaying) "Пауза" else "Продолжить воспроизведение" },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    color = AftertoneAccent,
                    trackColor = AftertoneOutline,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
    }
}
