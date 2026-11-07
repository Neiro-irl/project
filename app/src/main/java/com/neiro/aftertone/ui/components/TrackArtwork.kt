package com.neiro.aftertone.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val artworkPalettes = listOf(
    listOf(Color(0xFF24382F), Color(0xFF718563)),
    listOf(Color(0xFF243443), Color(0xFF6D8790)),
    listOf(Color(0xFF46352D), Color(0xFFB47C58)),
    listOf(Color(0xFF372B45), Color(0xFF8971A3)),
    listOf(Color(0xFF3A4030), Color(0xFFA6A46A)),
)

@Composable
fun TrackArtwork(
    track: Track?,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 22,
) {
    val artworkPath = track?.artworkPath
    val bitmap = produceState<android.graphics.Bitmap?>(initialValue = null, key1 = artworkPath) {
        value = withContext(Dispatchers.IO) {
            artworkPath?.let { path -> runCatching { decodeArtwork(path) }.getOrNull() }
        }
    }.value
    val shape = RoundedCornerShape(cornerRadius.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(paletteFor(track))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = track?.let { "Обложка альбома: ${it.displayTitle}" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = track?.displayTitle?.firstOrNull()?.uppercaseChar()?.toString() ?: "A",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

private fun decodeArtwork(path: String): android.graphics.Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, boundsOptions)

    val sampleSize = ArtworkDecodePolicy.sampleSize(boundsOptions.outWidth, boundsOptions.outHeight)
        ?: return null
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    }
    return BitmapFactory.decodeFile(path, decodeOptions)
}

private fun paletteFor(track: Track?): List<Color> {
    val index = (track?.id?.hashCode()?.and(Int.MAX_VALUE) ?: 0) % artworkPalettes.size
    return artworkPalettes[index]
}
