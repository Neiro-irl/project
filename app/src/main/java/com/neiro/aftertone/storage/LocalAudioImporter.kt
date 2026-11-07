package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackFileName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private const val MAX_AUDIO_FILE_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_ARTWORK_BYTES = 2 * 1024 * 1024

 data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val artwork: ByteArray? = null,
)

fun interface AudioMetadataReader {
    fun read(audioFile: File): AudioMetadata
}

class LocalAudioImporter(
    private val audioDirectory: File,
    private val metadataReader: AudioMetadataReader,
    private val availableBytes: () -> Long,
) {
    fun import(input: InputStream, displayName: String, mimeType: String?): Track =
        input.use { source -> importOpenedStream(source, displayName, mimeType) }

    private fun importOpenedStream(input: InputStream, displayName: String, mimeType: String?): Track {
        val id = UUID.randomUUID().toString()
        val fileName = TrackFileName.forImport(id, displayName, mimeType)
            ?: throw IllegalArgumentException("Choose a supported audio file.")

        if (!audioDirectory.exists() && !audioDirectory.mkdirs()) {
            throw IOException("Could not create the local audio directory.")
        }

        val temporaryFile = File(audioDirectory, "$id.part")
        val audioFile = File(audioDirectory, fileName)
        val artworkFile = File(audioDirectory, "$id.img")
        if (listOf(temporaryFile, audioFile, artworkFile).any { !PrivateAudioPath.isDirectChild(audioDirectory, it) }) {
            throw SecurityException("The local file path is outside the audio directory.")
        }
        if (temporaryFile.exists() || audioFile.exists() || artworkFile.exists()) {
            throw IOException("A local file with this identifier already exists.")
        }
        if (!temporaryFile.createNewFile()) throw IOException("Could not create an import staging file.")

        var ownsTemporaryFile = true
        var ownsAudioFile = false
        var ownsArtworkFile = false
        var completed = false
        try {
            FileOutputStream(temporaryFile).use { destination -> copyBounded(input, destination) }
            if (temporaryFile.length() == 0L) throw IllegalArgumentException("The selected audio file is empty.")

            val metadata = metadataReader.read(temporaryFile)
            val safeArtwork = metadata.artwork?.takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }
            val artworkPath = if (safeArtwork != null) {
                if (!artworkFile.createNewFile()) throw IOException("Could not create local artwork file.")
                ownsArtworkFile = true
                FileOutputStream(artworkFile).use { it.write(safeArtwork) }
                artworkFile.canonicalPath
            } else {
                null
            }

            if (!temporaryFile.renameTo(audioFile)) throw IOException("Could not finish importing the audio file.")
            ownsTemporaryFile = false
            ownsAudioFile = true

            val fallbackTitle = displayName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
            val track = Track(
                id = id,
                title = cleanMetadata(metadata.title, 180).ifEmpty { cleanMetadata(fallbackTitle, 180).ifEmpty { "Без названия" } },
                artist = cleanMetadata(metadata.artist, 120).ifEmpty { "Неизвестный исполнитель" },
                album = cleanMetadata(metadata.album, 180),
                durationMs = metadata.durationMs.coerceAtLeast(0),
                filePath = audioFile.canonicalPath,
                artworkPath = artworkPath,
            )
            completed = true
            return track
        } finally {
            if (!completed) {
                if (ownsTemporaryFile && PrivateAudioPath.isDirectChild(audioDirectory, temporaryFile)) temporaryFile.delete()
                if (ownsAudioFile && PrivateAudioPath.isDirectChild(audioDirectory, audioFile)) audioFile.delete()
                if (ownsArtworkFile && PrivateAudioPath.isDirectChild(audioDirectory, artworkFile)) artworkFile.delete()
            }
        }
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        var nextSpaceCheckAt = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (copied + count > MAX_AUDIO_FILE_BYTES) throw IOException("Audio file exceeds the 2 GiB import limit.")
            if (copied >= nextSpaceCheckAt) {
                if (availableBytes() < count) throw IOException("There is not enough free space to import this file.")
                nextSpaceCheckAt = copied + 512 * 1024L
            }
            output.write(buffer, 0, count)
            copied += count
        }
    }

    private fun cleanMetadata(value: String?, maximumLength: Int): String = value.orEmpty()
        .map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString("")
        .trim()
        .take(maximumLength)
}
