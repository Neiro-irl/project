package com.neiro.aftertone.storage

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

class AndroidAudioMetadataReader : AudioMetadataReader {
    override fun read(audioFile: File): AudioMetadata {
        val extractor = MediaExtractor()
        val audioFormat = try {
            extractor.setDataSource(audioFile.absolutePath)
            (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("The selected file contains no readable audio track.")
        } finally {
            extractor.release()
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFile.absolutePath)
            val extractedDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val formatDuration = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                runCatching { audioFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L }.getOrNull()
            } else {
                null
            }
            return AudioMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = extractedDuration ?: formatDuration ?: 0L,
                artwork = validatedArtwork(retriever.embeddedPicture),
            )
        } catch (_: RuntimeException) {
            return AudioMetadata(durationMs = runCatching { audioFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L }.getOrDefault(0L))
        } finally {
            retriever.release()
        }
    }

    private fun validatedArtwork(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return bytes.takeIf { options.outWidth in 1..8_192 && options.outHeight in 1..8_192 }
    }
}
