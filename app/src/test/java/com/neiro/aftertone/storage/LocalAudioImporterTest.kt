package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioImporterTest {
    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var wasClosed = false

        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    @Test
    fun copiesAudioToGeneratedPrivatePathAndKeepsMetadata() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            val content = byteArrayOf(1, 4, 9, 16, 25)
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader {
                    AudioMetadata(title = "Evening Air", artist = "North Window", album = "Blue Hour", durationMs = 4_200)
                },
                availableBytes = { Long.MAX_VALUE },
            )

            val track = importer.import(
                input = ByteArrayInputStream(content),
                displayName = "../../outside/song.mp3",
                mimeType = "audio/mpeg",
            )

            val importedFile = File(track.filePath)
            assertTrue(PrivateAudioPath.isDirectChild(directory, importedFile))
            assertEquals(content.toList(), importedFile.readBytes().toList())
            assertEquals("Evening Air", track.title)
            assertEquals("North Window", track.artist)
            assertEquals("Blue Hour", track.album)
            assertEquals(4_200L, track.durationMs)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonAudioMimeWithoutLeavingFilesBehind() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { error("must not parse") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalArgumentException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1)), "notes.txt", "text/plain")
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun closesInputWhenUnsupportedDocumentFailsBeforeCopy() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        val input = CloseTrackingInputStream(byteArrayOf(1))
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { error("must not parse") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalArgumentException::class.java) {
                importer.import(input, "notes.txt", "text/plain")
            }

            assertTrue(input.wasClosed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesPartialFileWhenMetadataValidationFails() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { error("invalid audio data") },
                availableBytes = { Long.MAX_VALUE },
            )

            assertThrows(IllegalStateException::class.java) {
                importer.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "broken.mp3", "audio/mpeg")
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesPartialFileWhenAllocatableSpaceRunsOut() {
        val directory = Files.createTempDirectory("aftertone-import-").toFile()
        try {
            var checks = 0
            val importer = LocalAudioImporter(
                directory,
                AudioMetadataReader { AudioMetadata() },
                availableBytes = { if (++checks == 1) Long.MAX_VALUE else 0L },
            )

            assertThrows(IOException::class.java) {
                importer.import(ByteArrayInputStream(ByteArray(1024 * 1024)), "large.mp3", "audio/mpeg")
            }

            assertTrue(checks >= 2)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
