package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.Track
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupTenTrackRoundTripTest {
    @Test
    fun tenDistinctMp3TracksKeepTheirBytesMetadataFavoritesAndPlaylistsAcrossRestore() {
        val root = Files.createTempDirectory("aftertone-ten-mp3-roundtrip-").toFile()
        try {
            val filesDirectory = File(root, "files").apply { mkdir() }
            val audioDirectory = File(filesDirectory, "audio").apply { mkdir() }
            val sourceAudio = mutableMapOf<String, ByteArray>()
            val sourceArtwork = mutableMapOf<String, ByteArray>()
            val tracks = (1..10).map { number ->
                val id = "tone-${number.toString().padStart(2, '0')}"
                val expectedBytes = requireNotNull(javaClass.getResourceAsStream("/mp3-fixtures/$id.mp3")) {
                    "Missing generated MP3 test fixture $id.mp3"
                }.use { it.readBytes() }
                sourceAudio[id] = expectedBytes
                val audioFile = File(audioDirectory, "$id.mp3").apply { writeBytes(expectedBytes) }
                val expectedArtwork = pngArtwork(number)
                assertNotNull("Artwork fixture $number must be a decodable PNG", ImageIO.read(ByteArrayInputStream(expectedArtwork)))
                sourceArtwork[id] = expectedArtwork
                val artworkFile = File(audioDirectory, "$id.img").apply { writeBytes(expectedArtwork) }
                Track(
                    id = id,
                    title = "Мелодия $number",
                    artist = "Aftertone test ensemble",
                    album = "Ten local compositions",
                    durationMs = 4_000,
                    filePath = audioFile.canonicalPath,
                    artworkPath = artworkFile.canonicalPath,
                )
            }
            val playlists = listOf(
                Playlist("first-mix", "Первая подборка", tracks.take(5).map(Track::id)),
                Playlist("second-mix", "Вторая подборка", tracks.drop(5).map(Track::id)),
            )
            val favorites = setOf(tracks[0].id, tracks[4].id, tracks[9].id)
            val sourceSnapshot = LibrarySnapshot(tracks, playlists, favorites)
            val archive = File(root, "ten-tracks.zip")
            val store = MemoryStateStore(null)
            val transaction = LibraryRestoreTransaction(filesDirectory, audioDirectory, store) { encoded ->
                decodeAndValidate(encoded, audioDirectory)
            }

            val exported = archive.outputStream().use { LibraryBackupArchive.export(sourceSnapshot, audioDirectory, it) }
            val staging = transaction.createStagingDirectory()
            val extracted = LibraryBackupArchive.extract(
                archiveFile = archive,
                stagingDirectory = staging,
                availableBytes = { Long.MAX_VALUE },
            )
            val restored = transaction.restore(staging, extracted)
            val restoredHashes = restored.snapshot.tracks.associate { track ->
                track.id to File(track.filePath).readBytes().sha256()
            }
            val restoredTracks = restored.snapshot.tracks.associateBy(Track::id)

            assertEquals(10, exported.trackCount)
            assertEquals(2, exported.playlistCount)
            assertEquals(3, exported.favoriteCount)
            assertEquals(exported, LibraryBackupArchive.inspect(archive))
            assertEquals(sourceSnapshot.tracks.map(Track::title), restored.snapshot.tracks.map(Track::title))
            assertEquals(sourceSnapshot.tracks.map(Track::artist), restored.snapshot.tracks.map(Track::artist))
            assertEquals(sourceSnapshot.tracks.map(Track::album), restored.snapshot.tracks.map(Track::album))
            assertEquals(sourceSnapshot.tracks.map(Track::durationMs), restored.snapshot.tracks.map(Track::durationMs))
            assertEquals(playlists, restored.snapshot.playlists)
            assertEquals(favorites, restored.snapshot.favoriteTrackIds)
            assertEquals(10, restoredHashes.values.toSet().size)
            sourceAudio.forEach { (id, bytes) ->
                assertEquals("MP3 bytes changed for $id", bytes.sha256(), restoredHashes[id])
                assertTrue(File(restored.snapshot.tracks.single { it.id == id }.filePath).isFile)
            }
            sourceArtwork.forEach { (id, bytes) ->
                val artworkPath = requireNotNull(restoredTracks[id]?.artworkPath)
                val restoredArtwork = File(artworkPath)
                assertNotNull("Restored artwork $id must remain a decodable image", ImageIO.read(restoredArtwork))
                assertEquals("Artwork bytes changed for $id", bytes.sha256(), restoredArtwork.readBytes().sha256())
            }
            assertFalse(ZipFile(archive).use { zip ->
                String(zip.getInputStream(zip.getEntry(LibraryBackupArchive.MANIFEST_ENTRY)).use { it.readBytes() }, Charsets.UTF_8)
                    .contains(audioDirectory.absolutePath)
            })
            assertTrue(transaction.finishRestore(restored.transactionId))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun decodeAndValidate(encoded: String?, audioDirectory: File): LibrarySnapshot {
        if (encoded == null) return LibrarySnapshot()
        val snapshot = LibraryStateCodec.decode(encoded)
        snapshot.tracks.forEach { track ->
            val audio = File(track.filePath)
            require(audio.isFile && (PrivateAudioPath.isDirectChild(audioDirectory, audio) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, audio)))
            track.artworkPath?.let { path ->
                val artwork = File(path)
                require(artwork.isFile && (PrivateAudioPath.isDirectChild(audioDirectory, artwork) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, artwork)))
            }
        }
        return snapshot
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun pngArtwork(number: Int): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val color = Color((number * 23) % 256, (number * 47) % 256, (number * 71) % 256).rgb
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, color)
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "The PNG encoder is unavailable." }
            output.toByteArray()
        }
    }

    private class MemoryStateStore(initial: String?) : LibraryStateStore {
        private var encoded = initial
        override fun read(): String? = encoded
        override fun write(encoded: String?): Boolean {
            this.encoded = encoded
            return true
        }
    }
}
