package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.Playlist
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.model.TrackFileName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Reader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CancellationException

internal data class LibraryBackupAsset(val entryName: String, val sizeBytes: Long)

internal data class PortableLibraryBackupManifest(
    val snapshot: LibrarySnapshot,
    val assets: List<LibraryBackupAsset>,
    val payloadBytes: Long,
)

internal object LibraryBackupManifestCodec {
    private val magic = byteArrayOf(0x41, 0x46, 0x54, 0x52, 0x42, 0x4b, 0x55, 0x50) // AFTRBKUP
    private const val VERSION = 1
    private const val MAX_TRACKS = 20_000
    private const val MAX_PLAYLISTS = 2_000
    private const val MAX_PLAYLIST_TRACKS = 20_000
    private const val MAX_PLAYLIST_NAME_LENGTH = 48
    private const val MAX_ID_LENGTH = 80
    private val validId = Regex("[A-Za-z0-9_-]{1,$MAX_ID_LENGTH}")

    fun encode(
        encodedState: String,
        assets: List<LibraryBackupAsset>,
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): ByteArray {
        require(encodedState.isNotBlank() && encodedState.length <= LibraryStateCodec.MAX_STATE_CHARS) {
            "The backup library state exceeds the supported size."
        }
        val stateBytes = LibraryStateCodec.encodeUtf8Strict(encodedState)
        require(stateBytes.size <= LibraryStateCodec.MAX_STATE_UTF8_BYTES) { "The backup library state exceeds the supported size." }
        val snapshot = LibraryStateCodec.decode(encodedState)
        validateState(snapshot)
        requireStrictPropertyKeys(encodedState, snapshot)
        validateAssets(snapshot, assets, limits)
        require(assets.size + 1 <= limits.maxEntries) { "The backup contains too many files." }

        val manifest = ByteArrayOutputStream()
        DataOutputStream(manifest).use { output ->
            output.write(magic)
            output.writeInt(VERSION)
            output.writeInt(stateBytes.size)
            output.write(stateBytes)
            output.writeInt(assets.size)
            assets.forEach { asset ->
                output.writeUTF(asset.entryName)
                output.writeLong(asset.sizeBytes)
            }
        }
        return manifest.toByteArray().also { bytes ->
            require(bytes.size <= limits.maxManifestBytes) { "The backup manifest exceeds the supported size." }
        }
    }

    fun decode(
        bytes: ByteArray,
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
        checkCancelled: () -> Unit = {},
    ): PortableLibraryBackupManifest {
        checkCancelled()
        require(bytes.isNotEmpty() && bytes.size <= limits.maxManifestBytes) {
            "The backup manifest is empty or too large."
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val header = ByteArray(magic.size)
        try {
            input.readFully(header)
            require(header.contentEquals(magic)) { "This is not an Aftertone backup archive." }
            require(input.readInt() == VERSION) { "This backup version is not supported." }
            val stateLength = input.readInt()
            require(stateLength in 1..LibraryStateCodec.MAX_STATE_UTF8_BYTES && stateLength <= input.available()) {
                "The backup library state is incomplete or too large."
            }
            checkCancelled()
            val stateBytes = ByteArray(stateLength)
            input.readFully(stateBytes)
            checkCancelled()
            val encodedState = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(stateBytes))
                .toString()
            require(encodedState.length <= LibraryStateCodec.MAX_STATE_CHARS) { "The backup library state exceeds the supported size." }
            checkCancelled()
            val snapshot = LibraryStateCodec.decode(encodedState, checkCancelled)
            validateState(snapshot, checkCancelled)
            requireStrictPropertyKeys(encodedState, snapshot, checkCancelled)

            val assetCount = input.readInt()
            require(assetCount in 0 until limits.maxEntries) { "The backup contains too many files." }
            val assets = ArrayList<LibraryBackupAsset>(assetCount)
            repeat(assetCount) {
                checkCancelled()
                val name = input.readUTF()
                val size = input.readLong()
                assets += LibraryBackupAsset(name, size)
            }
            require(input.available() == 0) { "The backup manifest contains unexpected trailing data." }
            validateAssets(snapshot, assets, limits, checkCancelled)
            var total = 0L
            assets.forEach { asset ->
                checkCancelled()
                try {
                    total = Math.addExact(total, asset.sizeBytes)
                } catch (error: ArithmeticException) {
                    throw IllegalArgumentException("The backup payload is too large.", error)
                }
            }
            checkCancelled()
            require(total <= limits.maxTotalPayloadBytes) { "The backup payload exceeds the supported size." }
            return PortableLibraryBackupManifest(snapshot, assets, total)
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("The backup manifest is damaged or incomplete.", error)
        }
    }

    private fun validateState(snapshot: LibrarySnapshot, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        require(snapshot.tracks.size <= MAX_TRACKS) { "The backup contains too many tracks." }
        require(snapshot.playlists.size <= MAX_PLAYLISTS) { "The backup contains too many playlists." }
        require(snapshot.favoriteTrackIds.size <= MAX_TRACKS) { "The backup contains too many favorites." }
        val trackIds = hashSetOf<String>()
        snapshot.tracks.forEach { track ->
            checkCancelled()
            require(trackIds.add(track.id)) { "The backup contains duplicate track records." }
            require(validId.matches(track.id)) { "The backup contains an invalid track identifier." }
            require(track.durationMs >= 0) { "The backup contains an invalid track duration." }
            val audioName = portableName(track.filePath, "audio/")
            require(TrackFileName.isGeneratedFileName(track.id, audioName)) {
                "The backup contains an invalid audio reference."
            }
            track.artworkPath?.let { artworkPath ->
                require(artworkPath == "artwork/${track.id}.img") {
                    "The backup contains an invalid artwork reference."
                }
            }
        }
        snapshot.favoriteTrackIds.forEach { trackId ->
            checkCancelled()
            require(trackId in trackIds) { "The backup contains a favorite without an audio track." }
        }
        val playlistIds = hashSetOf<String>()
        val playlistNames = hashSetOf<String>()
        snapshot.playlists.forEach { playlist ->
            checkCancelled()
            require(playlistIds.add(playlist.id)) { "The backup contains duplicate playlists." }
            require(playlistNames.add(playlist.name.lowercase(Locale.ROOT))) {
                "The backup contains duplicate playlist names."
            }
            require(validId.matches(playlist.id)) { "The backup contains an invalid playlist identifier." }
            require(playlist.name.isNotBlank() && playlist.name == playlist.name.trim() && playlist.name.length <= MAX_PLAYLIST_NAME_LENGTH) {
                "The backup contains an invalid playlist name."
            }
            require(playlist.trackIds.size <= MAX_PLAYLIST_TRACKS) { "The backup playlist contains too many tracks." }
            val playlistTrackIds = hashSetOf<String>()
            playlist.trackIds.forEach { trackId ->
                checkCancelled()
                require(playlistTrackIds.add(trackId) && trackId in trackIds) {
                    "The backup playlist contains duplicate or missing track references."
                }
            }
        }
    }

    private fun validateAssets(
        snapshot: LibrarySnapshot,
        assets: List<LibraryBackupAsset>,
        limits: LibraryBackupLimits,
        checkCancelled: () -> Unit = {},
    ) {
        val expectedNames = hashSetOf<String>()
        snapshot.tracks.forEach { track ->
            checkCancelled()
            expectedNames += track.filePath
            track.artworkPath?.let(expectedNames::add)
        }
        require(assets.size == expectedNames.size) { "The backup file list does not match its library." }
        val assetNames = hashSetOf<String>()
        var total = 0L
        assets.forEach { asset ->
            checkCancelled()
            require(assetNames.add(asset.entryName)) { "The backup contains duplicate file records." }
            require(asset.entryName in expectedNames) {
                "The backup is missing a referenced file or contains an unexpected file."
            }
            require(isAllowedAssetName(asset.entryName)) { "The backup contains an unsafe file path." }
            val maximum = if (asset.entryName.startsWith("audio/")) limits.maxAudioFileBytes else limits.maxArtworkBytes
            require(asset.sizeBytes in 1..maximum) { "The backup contains a file with an invalid size." }
            total = try {
                Math.addExact(total, asset.sizeBytes)
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("The backup payload is too large.", error)
            }
        }
        require(assetNames == expectedNames) { "The backup is missing a referenced file or contains an unexpected file." }
        require(total <= limits.maxTotalPayloadBytes) { "The backup payload exceeds the supported size." }
    }

    private fun requireStrictPropertyKeys(
        encodedState: String,
        snapshot: LibrarySnapshot,
        checkCancelled: () -> Unit = {},
    ) {
        checkCancelled()
        val actual = propertyKeys(encodedState, checkCancelled)
        val canonical = propertyKeys(LibraryStateCodec.encode(snapshot), checkCancelled)
        checkCancelled()
        require(actual == canonical) { "The backup library state has duplicate, missing, or unknown fields." }
    }

    private fun propertyKeys(encoded: String, checkCancelled: () -> Unit = {}): Set<String> {
        val keys = linkedSetOf<String>()
        encoded.lineSequence().forEach { line ->
            checkCancelled()
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('!')) return@forEach
            val trailingSlashes = line.reversed().takeWhile { it == '\\' }.length
            require(trailingSlashes % 2 == 0) { "The backup library state contains an incomplete property." }
            val property = try {
                Properties().apply { load(CancellationCheckingReader(StringReader(line), checkCancelled)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("The backup library state contains an invalid property.", error)
            }
            require(property.size == 1) { "The backup library state contains an invalid property." }
            val key = property.keys.single().toString()
            require(keys.add(key)) { "The backup library state contains a duplicate field." }
        }
        return keys
    }

    private class CancellationCheckingReader(
        private val delegate: StringReader,
        private val checkCancelled: () -> Unit,
    ) : Reader() {
        private var charsUntilCheck = 0

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (charsUntilCheck <= 0) {
                checkCancelled()
                charsUntilCheck = 8 * 1024
            }
            val count = delegate.read(buffer, offset, minOf(length, charsUntilCheck))
            if (count > 0) charsUntilCheck -= count
            return count
        }

        override fun close() = delegate.close()
    }

    private fun portableName(path: String, prefix: String): String {
        require(path.startsWith(prefix)) { "The backup contains a non-portable file path." }
        val name = path.removePrefix(prefix)
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") {
            "The backup contains an unsafe file path."
        }
        return name
    }

    private fun isAllowedAssetName(name: String): Boolean = when {
        name.startsWith("audio/") -> {
            val base = runCatching { portableName(name, "audio/") }.getOrNull() ?: return false
            val id = base.substringBeforeLast('.', missingDelimiterValue = "")
            TrackFileName.isGeneratedFileName(id, base)
        }
        name.startsWith("artwork/") -> {
            val base = runCatching { portableName(name, "artwork/") }.getOrNull() ?: return false
            val id = base.removeSuffix(".img")
            validId.matches(id) && base == "$id.img"
        }
        else -> false
    }
}
