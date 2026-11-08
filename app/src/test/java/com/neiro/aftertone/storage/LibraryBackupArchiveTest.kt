package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Enumeration
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupArchiveTest {
    @Test
    fun exportAndExtractPreserveAudioArtworkMetadataFavoritesAndPlaylistOrder() {
        val root = Files.createTempDirectory("aftertone-backup-roundtrip-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "library.zip")

            val exported = archive.outputStream().use { output ->
                LibraryBackupArchive.export(source.snapshot, source.audioDirectory, output)
            }
            val inspected = LibraryBackupArchive.inspect(archive)
            val staging = File(root, "restore-staging").apply { mkdir() }
            val prepared = LibraryBackupArchive.extract(archive, staging, availableBytes = { Long.MAX_VALUE })

            assertEquals(1, exported.trackCount)
            assertEquals(1, inspected.playlistCount)
            assertEquals(1, inspected.favoriteCount)
            assertEquals(exported, inspected)
            assertEquals("Тёплый вечер", prepared.snapshot.tracks.single().title)
            assertEquals(listOf("track-1"), prepared.snapshot.playlists.single().trackIds)
            assertEquals(setOf("track-1"), prepared.snapshot.favoriteTrackIds)
            assertEquals("audio/track-1.mp3", prepared.snapshot.tracks.single().filePath)
            assertEquals("artwork/track-1.img", prepared.snapshot.tracks.single().artworkPath)
            assertTrue(File(staging, "track-1.mp3").readBytes().contentEquals(source.audioBytes))
            assertTrue(File(staging, "track-1.img").readBytes().contentEquals(source.artworkBytes))
            assertFalse(ZipFile(archive).use { zip ->
                zip.getInputStream(zip.getEntry(LibraryBackupArchive.MANIFEST_ENTRY)).bufferedReader().readText()
                    .contains(source.audioDirectory.absolutePath)
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestAcceptsValidUnicodeStateWhoseUtf8BytesExceedFiveMiB() {
        val root = Files.createTempDirectory("aftertone-backup-unicode-state-").toFile()
        try {
            val source = fixture(root)
            val title = "界".repeat(1_800_000)
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(title = title, filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val encodedState = LibraryStateCodec.encode(portableSnapshot)
            val stateBytes = encodedState.toByteArray(Charsets.UTF_8)
            assertTrue(encodedState.length < 5 * 1024 * 1024)
            assertTrue(stateBytes.size > 5 * 1024 * 1024)

            val manifest = LibraryBackupManifestCodec.encode(
                encodedState,
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", source.audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
                ),
            )

            assertEquals(title, LibraryBackupManifestCodec.decode(manifest).snapshot.tracks.single().title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedUnicodeInsteadOfReplacingMetadataDuringBackup() {
        val root = Files.createTempDirectory("aftertone-backup-malformed-unicode-").toFile()
        try {
            val source = fixture(root)
            val invalidTitle = String(charArrayOf('x', '\uD800'))
            val invalidSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map { it.copy(title = invalidTitle) },
            )

            assertThrows(IllegalArgumentException::class.java) { LibraryStateCodec.encode(invalidSnapshot) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveWithExcessiveAssetExpansionRatio() {
        val root = Files.createTempDirectory("aftertone-backup-expansion-ratio-").toFile()
        try {
            val source = fixture(root)
            val audioBytes = ByteArray(512 * 1024) { 'A'.code.toByte() }
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val manifest = LibraryBackupManifestCodec.encode(
                LibraryStateCodec.encode(portableSnapshot),
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
                ),
            )
            val archive = File(root, "high-ratio.zip").apply {
                writeBytes(
                    deflatedZip(
                        listOf(
                            RawEntry(LibraryBackupArchive.MANIFEST_ENTRY, manifest),
                            RawEntry("audio/track-1.mp3", audioBytes),
                            RawEntry("artwork/track-1.img", source.artworkBytes),
                        ),
                    ),
                )
            }

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(archive)
            }

            assertTrue(error.message.orEmpty().contains("ratio", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsClassicEntryCountBeforeOpeningZipFile() {
        val root = Files.createTempDirectory("aftertone-backup-eocd-count-").toFile()
        try {
            val archive = File(root, "over-limit.zip").apply {
                writeBytes(classicEocd(entryCount = 5, centralSize = 0, centralOffset = 0))
            }
            var zipOpened = false

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(
                    archive,
                    limits = LibraryBackupLimits(maxEntries = 4),
                    zipFileFactory = { file -> zipOpened = true; ZipFile(file) },
                )
            }

            assertFalse(zipOpened)
            assertTrue(error.message.orEmpty().contains("too many", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZip64EntryCountBeforeOpeningZipFile() {
        val root = Files.createTempDirectory("aftertone-backup-zip64-count-").toFile()
        try {
            val archive = File(root, "over-limit-zip64.zip").apply {
                writeBytes(zip64Eocd(totalEntries = 100_001))
            }
            var zipOpened = false

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(
                    archive,
                    limits = LibraryBackupLimits(maxEntries = 100_000),
                    zipFileFactory = { file -> zipOpened = true; ZipFile(file) },
                )
            }

            assertFalse(zipOpened)
            assertTrue(error.message.orEmpty().contains("too many", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOversizedCentralDirectoryBeforeOpeningZipFile() {
        val root = Files.createTempDirectory("aftertone-backup-central-size-").toFile()
        try {
            val archive = File(root, "oversized-directory.zip").apply {
                writeBytes(classicEocd(entryCount = 0, centralSize = 64 * 1024 * 1024, centralOffset = 0))
            }
            var zipOpened = false

            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(archive, zipFileFactory = { file -> zipOpened = true; ZipFile(file) })
            }

            assertFalse(zipOpened)
            assertTrue(error.message.orEmpty().contains("central directory", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipDirectoryEnumerationStopsAtConfiguredLimit() {
        val entries = CountingZipEntries(totalEntries = 10_000)

        assertThrows(IllegalArgumentException::class.java) {
            LibraryBackupArchive.collectZipEntries(entries, LibraryBackupLimits(maxEntries = 3))
        }

        assertEquals(3, entries.consumed)
        assertTrue(entries.hasMoreElements())
    }

    @Test
    fun rejectsTraversalConsistentAcrossManifestAndZipMemberBeforeExtraction() {
        val root = Files.createTempDirectory("aftertone-backup-slip-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val outside = File(root.parentFile, "${root.name}-outside.mp3")
            val traversalPath = "audio/../../${outside.name}"
            val manifest = readEntries(valid).single { it.first == LibraryBackupArchive.MANIFEST_ENTRY }.second
            val maliciousManifest = traversalManifest(manifest, traversalPath)
            val entries = readEntries(valid).map { (name, bytes) ->
                when (name) {
                    LibraryBackupArchive.MANIFEST_ENTRY -> RawEntry(name, maliciousManifest)
                    "audio/track-1.mp3" -> RawEntry(traversalPath, bytes)
                    else -> RawEntry(name, bytes)
                }
            }
            val malicious = File(root, "traversal.zip").apply { writeBytes(rawZip(entries)) }
            val staging = File(root, "stage").apply { mkdir() }

            val inspectionError = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(malicious)
            }
            assertTrue(inspectionError.message.orEmpty().contains("unsafe", ignoreCase = true))
            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(malicious, staging, availableBytes = { Long.MAX_VALUE })
            }
            assertTrue(staging.list().isNullOrEmpty())
            assertFalse(outside.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicateAndMissingArchiveEntries() {
        val root = Files.createTempDirectory("aftertone-backup-entries-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val entries = readEntries(valid)
            val audio = entries.single { it.first == "audio/track-1.mp3" }
            val duplicate = File(root, "duplicate.zip").apply {
                writeBytes(rawZip(entries.map { RawEntry(it.first, it.second) } + RawEntry(audio.first, audio.second)))
            }
            val missing = File(root, "missing.zip").apply {
                writeBytes(rawZip(entries.filterNot { it.first == audio.first }.map { RawEntry(it.first, it.second) }))
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(duplicate) }
            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.inspect(missing) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptPayloadAndTruncatedZipDirectory() {
        val root = Files.createTempDirectory("aftertone-backup-corrupt-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val entries = readEntries(valid)
            val corruptedEntries = entries.map { (name, bytes) ->
                if (name == "audio/track-1.mp3") RawEntry(name, bytes, corruptCrc = true) else RawEntry(name, bytes)
            }
            val corrupted = File(root, "corrupted.zip").apply { writeBytes(rawZip(corruptedEntries)) }
            val truncated = File(root, "truncated.zip").apply {
                writeBytes(valid.readBytes().copyOf(valid.length().toInt() - 8))
            }

            assertThrows(IllegalArgumentException::class.java) { LibraryBackupArchive.extract(
                corrupted,
                File(root, "corrupt-stage").apply { mkdir() },
                availableBytes = { Long.MAX_VALUE },
            ) }
            assertThrows(Exception::class.java) { LibraryBackupArchive.inspect(truncated) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsExpandedPayloadOverLimitBeforeWritingAudio() {
        val root = Files.createTempDirectory("aftertone-backup-limit-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staging = File(root, "stage").apply { mkdir() }
            val strictLimits = LibraryBackupLimits(maxTotalPayloadBytes = 4)

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.inspect(archive, strictLimits)
            }
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractRejectsDeflatedBytesAboveManifestAndCentralSizesAndCleansStaging() {
        val root = Files.createTempDirectory("aftertone-backup-expanded-entry-").toFile()
        try {
            val source = fixture(root)
            val valid = File(root, "valid.zip")
            valid.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val reducedSize = source.audioBytes.size - 1L
            val entries = readEntries(valid).map { (name, bytes) ->
                RawEntry(
                    name,
                    if (name == LibraryBackupArchive.MANIFEST_ENTRY) {
                        rewriteManifestAssetSize(bytes, "audio/track-1.mp3", reducedSize)
                    } else {
                        bytes
                    },
                )
            }
            val malformed = File(root, "expanded.zip").apply {
                writeBytes(patchCentralUncompressedSize(deflatedZip(entries), "audio/track-1.mp3", reducedSize.toInt()))
            }
            val staging = File(root, "stage").apply { mkdir() }

            assertEquals(1, LibraryBackupArchive.inspect(malformed).trackCount)
            val error = assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.extract(malformed, staging, availableBytes = { Long.MAX_VALUE })
            }

            assertTrue(error.message.orEmpty().contains("larger than its manifest", ignoreCase = true))
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsInsufficientSpaceBeforeExtractingAndHonorsCancellation() {
        val root = Files.createTempDirectory("aftertone-backup-space-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staging = File(root, "stage").apply { mkdir() }

            assertThrows(IllegalStateException::class.java) {
                LibraryBackupArchive.extract(archive, staging, availableBytes = { 0L })
            }
            assertTrue(staging.list().isNullOrEmpty())
            assertThrows(InterruptedException::class.java) {
                LibraryBackupArchive.extract(
                    archive,
                    staging,
                    availableBytes = { Long.MAX_VALUE },
                    checkCancelled = { throw InterruptedException("cancelled") },
                )
            }
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractRemovesPartialAudioWhenStorageRunsLowMidStream() {
        val root = Files.createTempDirectory("aftertone-backup-extract-low-space-").toFile()
        try {
            val source = fixture(root)
            val incompressibleAudio = ByteArray(2 * 1024 * 1024).also { Random(42).nextBytes(it) }
            File(source.audioDirectory, "track-1.mp3").writeBytes(incompressibleAudio)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staging = File(root, "stage").apply { mkdir() }
            var checks = 0

            val error = assertThrows(IllegalStateException::class.java) {
                LibraryBackupArchive.extract(
                    archive,
                    staging,
                    availableBytes = { checks++; if (checks < 3) Long.MAX_VALUE else 0L },
                )
            }

            assertTrue(error.message.orEmpty().contains("ran low", ignoreCase = true))
            assertTrue(checks >= 3)
            assertTrue(staging.list().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveEnforcesCompressedInputLimitAndRemovesPartialFile() {
        val root = Files.createTempDirectory("aftertone-backup-input-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val destination = File(root, "staged.zip")
            val limits = LibraryBackupLimits(maxCompressedBytes = 32)

            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackupArchive.stageArchive(
                    archive.inputStream(),
                    destination,
                    availableBytes = { Long.MAX_VALUE },
                    limits = limits,
                )
            }
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationDuringInspectionDeletesPreparedArchive() {
        val root = Files.createTempDirectory("aftertone-backup-inspect-cancel-").toFile()
        try {
            val source = fixture(root)
            val archive = File(root, "valid.zip")
            archive.outputStream().use { LibraryBackupArchive.export(source.snapshot, source.audioDirectory, it) }
            val staged = File(root, "prepared.zip")
            var checks = 0

            assertThrows(CancellationException::class.java) {
                LibraryBackupArchive.stageAndInspectArchive(
                    input = archive.inputStream(),
                    destination = staged,
                    availableBytes = { Long.MAX_VALUE },
                    checkCancelled = {
                        checks++
                        if (checks == 4) throw CancellationException("cancelled during inspection")
                    },
                )
            }

            assertEquals(4, checks)
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestDecodePropagatesCancellationDuringPropertiesParsing() {
        val root = Files.createTempDirectory("aftertone-backup-manifest-cancel-").toFile()
        try {
            val source = fixture(root)
            val portableSnapshot = source.snapshot.copy(
                tracks = source.snapshot.tracks.map {
                    it.copy(filePath = "audio/track-1.mp3", artworkPath = "artwork/track-1.img")
                },
            )
            val encodedState = LibraryStateCodec.encode(portableSnapshot) + "\n#" + "x".repeat(256 * 1024) + "\n"
            val manifest = LibraryBackupManifestCodec.encode(
                encodedState,
                listOf(
                    LibraryBackupAsset("audio/track-1.mp3", source.audioBytes.size.toLong()),
                    LibraryBackupAsset("artwork/track-1.img", source.artworkBytes.size.toLong()),
                ),
            )
            var checks = 0

            val cancellation = assertThrows(CancellationException::class.java) {
                LibraryBackupManifestCodec.decode(
                    manifest,
                    checkCancelled = {
                        checks++
                        if (checks == 3) throw CancellationException("cancelled during state parsing")
                    },
                )
            }

            assertEquals("cancelled during state parsing", cancellation.message)
            assertEquals(3, checks)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveRejectsSymlinkedPrivateCacheRoot() {
        val root = Files.createTempDirectory("aftertone-backup-cache-root-").toFile()
        try {
            val redirectedTarget = File(root, "redirected-cache").apply { mkdir() }
            val cacheRootLink = File(root, "app-cache-link")
            Files.createSymbolicLink(cacheRootLink.toPath(), redirectedTarget.toPath())

            assertThrows(SecurityException::class.java) {
                LibraryBackupArchive.stageArchive(
                    ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                    File(cacheRootLink, "staged.zip"),
                    availableBytes = { Long.MAX_VALUE },
                )
            }
            assertFalse(File(redirectedTarget, "staged.zip").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveBatchesFreeSpaceChecksWhileStreamingLargeInput() {
        val root = Files.createTempDirectory("aftertone-backup-space-checks-").toFile()
        try {
            var freeSpaceChecks = 0
            val destination = File(root, "large-input.zip")
            LibraryBackupArchive.stageArchive(
                input = ByteArrayInputStream(ByteArray(2 * 1024 * 1024)),
                destination = destination,
                availableBytes = { freeSpaceChecks++; Long.MAX_VALUE },
            )

            assertTrue(freeSpaceChecks in 1..5)
            assertEquals(2 * 1024 * 1024L, destination.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageArchiveRemovesPartialFileWhenStorageRunsLowMidStream() {
        val root = Files.createTempDirectory("aftertone-backup-stage-low-space-").toFile()
        try {
            var checks = 0
            val destination = File(root, "partial.zip")

            val error = assertThrows(IllegalStateException::class.java) {
                LibraryBackupArchive.stageArchive(
                    input = ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { (it * 17).toByte() }),
                    destination = destination,
                    availableBytes = { checks++; if (checks == 1) Long.MAX_VALUE else 0L },
                )
            }

            assertTrue(error.message.orEmpty().contains("not enough free space", ignoreCase = true))
            assertTrue(checks >= 2)
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun traversalManifest(original: ByteArray, traversalPath: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataInputStream(ByteArrayInputStream(original)).use { input ->
            val magic = ByteArray(8).also(input::readFully)
            val version = input.readInt()
            val stateBytes = ByteArray(input.readInt()).also(input::readFully)
            val state = stateBytes.toString(Charsets.UTF_8)
            require("audio/track-1.mp3" in state)
            val maliciousState = state.replace("audio/track-1.mp3", traversalPath)
            val assets = List(input.readInt()) { input.readUTF() to input.readLong() }
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeInt(version)
                output.writeInt(maliciousState.toByteArray(Charsets.UTF_8).size)
                output.write(maliciousState.toByteArray(Charsets.UTF_8))
                output.writeInt(assets.size)
                assets.forEach { (name, size) ->
                    output.writeUTF(if (name == "audio/track-1.mp3") traversalPath else name)
                    output.writeLong(size)
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun rewriteManifestAssetSize(original: ByteArray, target: String, sizeBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        DataInputStream(ByteArrayInputStream(original)).use { input ->
            val magic = ByteArray(8).also(input::readFully)
            val version = input.readInt()
            val state = ByteArray(input.readInt()).also(input::readFully)
            val assets = List(input.readInt()) { input.readUTF() to input.readLong() }
            require(assets.any { it.first == target })
            DataOutputStream(output).use { manifest ->
                manifest.write(magic)
                manifest.writeInt(version)
                manifest.writeInt(state.size)
                manifest.write(state)
                manifest.writeInt(assets.size)
                assets.forEach { (name, originalSize) ->
                    manifest.writeUTF(name)
                    manifest.writeLong(if (name == target) sizeBytes else originalSize)
                }
            }
        }
        return output.toByteArray()
    }

    private fun deflatedZip(entries: List<RawEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun patchCentralUncompressedSize(bytes: ByteArray, target: String, sizeBytes: Int): ByteArray {
        val patched = bytes.copyOf()
        var found = false
        for (offset in 0..patched.size - 46) {
            if (patched[offset] != 0x50.toByte() || patched[offset + 1] != 0x4b.toByte() ||
                patched[offset + 2] != 0x01.toByte() || patched[offset + 3] != 0x02.toByte()
            ) continue
            val nameLength = readUnsignedShortLe(patched, offset + 28)
            val name = String(patched, offset + 46, nameLength, Charsets.UTF_8)
            if (name != target) continue
            repeat(4) { index -> patched[offset + 24 + index] = (sizeBytes ushr (index * 8)).toByte() }
            found = true
            break
        }
        require(found) { "Test fixture did not contain the requested ZIP member." }
        return patched
    }

    private fun readUnsignedShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun fixture(root: File): Fixture {
        val audioDirectory = File(root, "audio").apply { mkdir() }
        val audioBytes = byteArrayOf(1, 3, 5, 7, 9, 11)
        val artworkBytes = byteArrayOf(2, 4, 6)
        val audio = File(audioDirectory, "track-1.mp3").apply { writeBytes(audioBytes) }
        val artwork = File(audioDirectory, "track-1.img").apply { writeBytes(artworkBytes) }
        return Fixture(
            audioDirectory = audioDirectory,
            audioBytes = audioBytes,
            artworkBytes = artworkBytes,
            snapshot = LibrarySnapshot(
                tracks = listOf(
                    Track("track-1", "Тёплый вечер", "Исполнитель", "Альбом", 61_000, audio.canonicalPath, artwork.canonicalPath),
                ),
                playlists = listOf(Playlist("playlist-1", "Вечер", listOf("track-1"))),
                favoriteTrackIds = setOf("track-1"),
            ),
        )
    }

    private fun readEntries(file: File): List<Pair<String, ByteArray>> = ZipFile(file).use { zip ->
        Collections.list(zip.entries()).map { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
    }

    private fun rawZip(entries: List<RawEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val central = mutableListOf<CentralEntry>()
        entries.forEach { entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(entry.bytes) }.value xor if (entry.corruptCrc) 1L else 0L
            val offset = output.size()
            DataOutputStream(output).apply {
                writeIntLe(0x04034b50)
                writeShortLe(20); writeShortLe(0); writeShortLe(0); writeShortLe(0); writeShortLe(0)
                writeIntLe(crc.toInt()); writeIntLe(entry.bytes.size); writeIntLe(entry.bytes.size)
                writeShortLe(name.size); writeShortLe(0); write(name); write(entry.bytes)
            }
            central += CentralEntry(name, crc, entry.bytes.size, offset)
        }
        val centralOffset = output.size()
        central.forEach { item ->
            DataOutputStream(output).apply {
                writeIntLe(0x02014b50)
                writeShortLe(20); writeShortLe(20); writeShortLe(0); writeShortLe(0); writeShortLe(0); writeShortLe(0)
                writeIntLe(item.crc.toInt()); writeIntLe(item.size); writeIntLe(item.size)
                writeShortLe(item.name.size); writeShortLe(0); writeShortLe(0); writeShortLe(0); writeShortLe(0)
                writeIntLe(0); writeIntLe(item.offset); write(item.name)
            }
        }
        val centralSize = output.size() - centralOffset
        DataOutputStream(output).apply {
            writeIntLe(0x06054b50); writeShortLe(0); writeShortLe(0)
            writeShortLe(central.size); writeShortLe(central.size)
            writeIntLe(centralSize); writeIntLe(centralOffset); writeShortLe(0)
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeShortLe(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
        writeByte((value ushr 16) and 0xff)
        writeByte((value ushr 24) and 0xff)
    }

    private fun DataOutputStream.writeLongLe(value: Long) {
        repeat(8) { index -> writeByte(((value ushr (index * 8)) and 0xff).toInt()) }
    }

    private fun classicEocd(entryCount: Int, centralSize: Int, centralOffset: Int): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeIntLe(0x06054b50)
                writeShortLe(0); writeShortLe(0)
                writeShortLe(entryCount); writeShortLe(entryCount)
                writeIntLe(centralSize); writeIntLe(centralOffset); writeShortLe(0)
            }
        }.toByteArray()

    private fun zip64Eocd(totalEntries: Long): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeIntLe(0x06064b50); writeLongLe(44)
            writeShortLe(45); writeShortLe(45); writeIntLe(0); writeIntLe(0)
            writeLongLe(totalEntries); writeLongLe(totalEntries)
            writeLongLe(0); writeLongLe(0)
            writeIntLe(0x07064b50); writeIntLe(0); writeLongLe(0); writeIntLe(1)
            writeIntLe(0x06054b50); writeShortLe(0); writeShortLe(0)
            writeShortLe(0xffff); writeShortLe(0xffff)
            writeIntLe(-1); writeIntLe(-1); writeShortLe(0)
        }
    }.toByteArray()

    private data class Fixture(
        val audioDirectory: File,
        val audioBytes: ByteArray,
        val artworkBytes: ByteArray,
        val snapshot: LibrarySnapshot,
    )

    private data class RawEntry(val name: String, val bytes: ByteArray, val corruptCrc: Boolean = false)
    private data class CentralEntry(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)

    private class CountingZipEntries(private val totalEntries: Int) : Enumeration<ZipEntry> {
        var consumed = 0
            private set

        override fun hasMoreElements(): Boolean = consumed < totalEntries

        override fun nextElement(): ZipEntry = ZipEntry("entry-${consumed++}")
    }
}
