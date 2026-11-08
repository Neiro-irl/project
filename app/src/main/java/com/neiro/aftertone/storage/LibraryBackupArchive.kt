package com.neiro.aftertone.storage

import com.neiro.aftertone.model.LibrarySnapshot
import com.neiro.aftertone.model.LibraryStateCodec
import com.neiro.aftertone.model.PrivateAudioPath
import com.neiro.aftertone.model.TrackFileName
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Enumeration
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class LibraryBackupLimits(
    val maxCompressedBytes: Long = 16L * 1024 * 1024 * 1024,
    val maxTotalPayloadBytes: Long = 16L * 1024 * 1024 * 1024,
    val maxAudioFileBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxArtworkBytes: Long = 2L * 1024 * 1024,
    val maxManifestBytes: Int = 20 * 1024 * 1024,
    val maxAssetCompressionRatio: Long = 200,
    val maxEntries: Int = 40_001,
    val reservedSpaceBytes: Long = 32L * 1024 * 1024,
    val spaceCheckIntervalBytes: Long = 512 * 1024,
) {
    init {
        require(maxCompressedBytes > 0 && maxTotalPayloadBytes > 0)
        require(maxAudioFileBytes > 0 && maxArtworkBytes > 0 && maxManifestBytes > 0 && maxAssetCompressionRatio > 0)
        require(maxEntries >= 1 && reservedSpaceBytes >= 0 && spaceCheckIntervalBytes > 0)
    }

    companion object {
        val DEFAULT = LibraryBackupLimits()
    }
}

internal data class BackupSummary(
    val trackCount: Int,
    val playlistCount: Int,
    val favoriteCount: Int,
    val payloadBytes: Long,
)

internal data class BackupProgress(val completedBytes: Long, val totalBytes: Long?)

internal data class PreparedLibraryBackup(
    val snapshot: LibrarySnapshot,
    val summary: BackupSummary,
    val assetSha256ByEntry: Map<String, String> = emptyMap(),
)

internal object LibraryBackupArchive {
    const val MANIFEST_ENTRY = "manifest.bin"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_ENTRY_NAME_CHARS = 128
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 12L * 1024 * 1024
    private const val MAX_EOCD_SEARCH_BYTES = 22 + 0xffff
    private const val MAX_ZIP64_EOCD_BYTES = 4 * 1024
    private const val MAX_CENTRAL_EXTRA_BYTES = 4 * 1024
    private const val MAX_CENTRAL_COMMENT_BYTES = 1024
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L

    fun export(
        snapshot: LibrarySnapshot,
        audioDirectory: File,
        output: OutputStream,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): BackupSummary {
        require(audioDirectory.isDirectory) { "The local audio directory is not available." }
        val assets = mutableListOf<LibraryBackupAsset>()
        val assetSources = linkedMapOf<String, File>()
        val portableTracks = snapshot.tracks.map { track ->
            checkCancelled()
            require(TrackFileName.isGeneratedFileName(track.id, File(track.filePath).name)) {
                "The local library contains an invalid audio file name."
            }
            val audioFile = File(track.filePath)
            require(isOwnedAudioFile(audioDirectory, audioFile) && audioFile.isFile) {
                "An imported audio copy is missing or outside the app's private storage."
            }
            require(audioFile.length() in 1..limits.maxAudioFileBytes) {
                "An audio file exceeds the backup size limit."
            }
            val artworkFile = track.artworkPath?.let { path ->
                File(path).also { file ->
                    require(file.name == "${track.id}.img" && isOwnedAudioFile(audioDirectory, file) && file.isFile) {
                        "A track's artwork is missing or outside the app's private storage."
                    }
                    require(file.length() in 1..limits.maxArtworkBytes) { "An artwork file exceeds the backup size limit." }
                }
            }
            val audioEntry = "audio/${audioFile.name}"
            assets += LibraryBackupAsset(audioEntry, audioFile.length())
            assetSources[audioEntry] = audioFile
            artworkFile?.let { file ->
                val artworkEntry = "artwork/${file.name}"
                assets += LibraryBackupAsset(artworkEntry, file.length())
                assetSources[artworkEntry] = file
            }
            track.copy(
                filePath = "audio/${audioFile.name}",
                artworkPath = artworkFile?.let { "artwork/${it.name}" },
            )
        }
        val portableSnapshot = snapshot.copy(tracks = portableTracks)
        val encodedState = LibraryStateCodec.encode(portableSnapshot)
        val manifest = LibraryBackupManifestCodec.encode(encodedState, assets, limits)
        require(assets.size + 1 <= limits.maxEntries) { "The backup contains too many files." }
        var payloadBytes = manifest.size.toLong()
        assets.forEach { asset -> payloadBytes = safeAdd(payloadBytes, asset.sizeBytes) }
        require(payloadBytes <= limits.maxTotalPayloadBytes) { "The backup payload exceeds the supported size." }
        val totalAssetBytes = assets.sumOf(LibraryBackupAsset::sizeBytes)
        val compressedOutput = BoundedNonClosingOutputStream(output, limits.maxCompressedBytes)
        var copiedBytes = 0L
        ZipOutputStream(compressedOutput).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = 0L })
            zip.write(manifest)
            zip.closeEntry()
            assets.forEach { asset ->
                checkCancelled()
                val file = assetSources[asset.entryName]
                    ?: throw IllegalArgumentException("The backup asset is not referenced by the library.")
                require(file.length() == asset.sizeBytes) { "A local backup file changed while it was being read." }
                zip.putNextEntry(ZipEntry(asset.entryName).apply { time = 0L })
                FileInputStream(file).use { input ->
                    val copied = copyExact(input, zip, asset.sizeBytes, limits.maxTotalPayloadBytes, checkCancelled) { count ->
                        copiedBytes += count
                        onProgress(BackupProgress(copiedBytes, totalAssetBytes))
                    }
                    require(copied == asset.sizeBytes && file.length() == asset.sizeBytes) {
                        "A local backup file changed while it was being read."
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
        }
        return summary(portableSnapshot, totalAssetBytes)
    }

    fun stageArchive(
        input: InputStream,
        destination: File,
        availableBytes: () -> Long,
        checkCancelled: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): File {
        val parent = destination.parentFile ?: throw IllegalArgumentException("The archive staging path is invalid.")
        if (!parent.isDirectory || !PrivateAudioPath.isDirectChild(parent, destination) || destination.exists()) {
            throw SecurityException("The archive staging path is not a new private file.")
        }
        require(destination.createNewFile()) { "Could not create a private temporary archive." }
        var copied = 0L
        var nextSpaceCheck = 0L
        var complete = false
        try {
            FileOutputStream(destination).use { output ->
                input.use { source ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        checkCancelled()
                        val count = source.read(buffer)
                        if (count < 0) break
                        require(count > 0 && copied <= limits.maxCompressedBytes - count) {
                            "The selected archive exceeds the compressed-size limit."
                        }
                        if (copied >= nextSpaceCheck) {
                            if (availableBytes() < safeAdd(count.toLong(), limits.reservedSpaceBytes)) {
                                throw IllegalStateException("There is not enough free space to stage this archive safely.")
                            }
                            nextSpaceCheck = copied + limits.spaceCheckIntervalBytes
                        }
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(BackupProgress(copied, null))
                    }
                }
                output.fd.sync()
            }
            require(copied > 0L) { "The selected archive is empty." }
            complete = true
            return destination
        } finally {
            if (!complete && destination.isFile && PrivateAudioPath.isDirectChild(parent, destination)) {
                destination.delete()
            }
        }
    }

    fun stageAndInspectArchive(
        input: InputStream,
        destination: File,
        availableBytes: () -> Long,
        checkCancelled: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
    ): BackupSummary {
        var staged = false
        var complete = false
        try {
            stageArchive(input, destination, availableBytes, checkCancelled, onProgress, limits)
            staged = true
            checkCancelled()
            val inspected = inspect(destination, limits, checkCancelled)
            checkCancelled()
            complete = true
            return inspected
        } finally {
            if (staged && !complete) {
                val parent = destination.parentFile
                if (parent != null && PrivateAudioPath.isDirectChild(parent, destination)) destination.delete()
            }
        }
    }

    fun inspect(
        archiveFile: File,
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
        checkCancelled: () -> Unit = {},
        zipFileFactory: (File) -> ZipFile = { ZipFile(it) },
    ): BackupSummary = openValidatedArchive(archiveFile, limits, checkCancelled, zipFileFactory) { _, manifest ->
        summary(manifest.snapshot, manifest.payloadBytes)
    }

    fun extract(
        archiveFile: File,
        stagingDirectory: File,
        availableBytes: () -> Long,
        onProgress: (BackupProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
        limits: LibraryBackupLimits = LibraryBackupLimits.DEFAULT,
        durability: RestoreFileDurability = SystemRestoreFileDurability,
    ): PreparedLibraryBackup {
        require(stagingDirectory.isDirectory && stagingDirectory.canonicalFile == stagingDirectory.absoluteFile.toPath().normalize().toFile()) {
            "The private restore staging directory is not safe."
        }
        require(stagingDirectory.listFiles().orEmpty().all { it.name == ".aftertone-restore-owner" }) {
            "The private restore staging directory is not empty."
        }
        val createdFiles = mutableListOf<File>()
        val assetSha256ByEntry = linkedMapOf<String, String>()
        try {
            return openValidatedArchive(archiveFile, limits, checkCancelled, { ZipFile(it) }) { zip, manifest ->
                val reserve = limits.reservedSpaceBytes
                if (availableBytes() < safeAdd(manifest.payloadBytes, reserve)) {
                    throw IllegalStateException("There is not enough free space to restore this archive safely.")
                }
                var remainingBytes = manifest.payloadBytes
                var completedBytes = 0L
                manifest.assets.forEach { asset ->
                    checkCancelled()
                    val baseName = asset.entryName.substringAfter('/')
                    val destination = File(stagingDirectory, baseName)
                    require(PrivateAudioPath.isDirectChild(stagingDirectory, destination)) {
                        "The restore target is outside its private staging directory."
                    }
                    if (!destination.createNewFile()) throw IllegalArgumentException("The backup contains a duplicate file.")
                    createdFiles += destination
                    val entry = zip.getEntry(asset.entryName)
                        ?: throw IllegalArgumentException("The backup is missing a referenced file.")
                    val crc = CRC32()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var fileBytes = 0L
                    var nextSpaceCheck = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(count > 0 && fileBytes <= asset.sizeBytes - count) {
                                    "A file in the backup is larger than its manifest entry."
                                }
                                if (fileBytes >= nextSpaceCheck) {
                                    if (availableBytes() < safeAdd(remainingBytes, reserve)) {
                                        throw IllegalStateException("Free space ran low; the current library has not been changed.")
                                    }
                                    nextSpaceCheck = fileBytes + limits.spaceCheckIntervalBytes
                                }
                                output.write(buffer, 0, count)
                                crc.update(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                fileBytes += count
                                remainingBytes -= count
                                completedBytes += count
                                onProgress(BackupProgress(completedBytes, manifest.payloadBytes))
                            }
                            durability.syncFile(output)
                        }
                    }
                    require(fileBytes == asset.sizeBytes && crc.value == entry.crc) {
                        "A file in the backup is incomplete or damaged."
                    }
                    assetSha256ByEntry[asset.entryName] = digest.digest().toHex()
                }
                require(remainingBytes == 0L) { "The backup file sizes do not match its manifest." }
                durability.syncDirectory(stagingDirectory)
                PreparedLibraryBackup(
                    manifest.snapshot,
                    summary(manifest.snapshot, manifest.payloadBytes),
                    assetSha256ByEntry,
                )
            }
        } catch (error: Throwable) {
            createdFiles.asReversed().forEach { file ->
                if (PrivateAudioPath.isDirectChild(stagingDirectory, file) && file.isFile) file.delete()
            }
            runCatching { durability.syncDirectory(stagingDirectory) }
            throw error
        }
    }

    internal fun collectZipEntries(
        source: Enumeration<out ZipEntry>,
        limits: LibraryBackupLimits,
        checkCancelled: () -> Unit = {},
    ): List<ZipEntry> {
        val entries = ArrayList<ZipEntry>(minOf(limits.maxEntries, 1_024))
        val names = HashSet<String>()
        while (source.hasMoreElements()) {
            checkCancelled()
            require(entries.size < limits.maxEntries) { "The backup contains too many files." }
            val entry = source.nextElement()
            require(entry.name.length in 1..MAX_ENTRY_NAME_CHARS) { "The backup contains an invalid ZIP entry name." }
            require(names.add(entry.name)) { "The backup contains duplicate ZIP entries." }
            entries += entry
        }
        return entries
    }

    private data class CentralDirectoryInfo(
        val entriesOnDisk: Long,
        val totalEntries: Long,
        val directorySize: Long,
        val directoryOffset: Long,
        val endRecordOffset: Long,
    )

    private fun preflightCentralDirectory(
        archiveFile: File,
        limits: LibraryBackupLimits,
        checkCancelled: () -> Unit,
    ) {
        checkCancelled()
        RandomAccessFile(archiveFile, "r").use { archive ->
            val fileLength = archive.length()
            val eocdOffset = findEndOfCentralDirectory(archive)
            archive.seek(eocdOffset + 4)
            val diskNumber = archive.readUnsignedShortLe()
            val directoryDisk = archive.readUnsignedShortLe()
            val classicEntriesOnDisk = archive.readUnsignedShortLe().toLong()
            val classicTotalEntries = archive.readUnsignedShortLe().toLong()
            val classicDirectorySize = archive.readUnsignedIntLe()
            val classicDirectoryOffset = archive.readUnsignedIntLe()
            val commentLength = archive.readUnsignedShortLe()
            require(eocdOffset + 22 + commentLength == fileLength) { "The ZIP end record is invalid." }
            require(diskNumber == 0 && directoryDisk == 0) { "Multi-disk ZIP archives are not supported." }

            val needsZip64 = classicEntriesOnDisk == 0xffffL ||
                classicTotalEntries == 0xffffL ||
                classicDirectorySize == 0xffff_ffffL ||
                classicDirectoryOffset == 0xffff_ffffL
            val directory = if (needsZip64) {
                readZip64Directory(archive, eocdOffset).also { zip64 ->
                    if (classicEntriesOnDisk != 0xffffL) require(classicEntriesOnDisk == zip64.entriesOnDisk) {
                        "The ZIP64 entry counts do not match the end record."
                    }
                    if (classicTotalEntries != 0xffffL) require(classicTotalEntries == zip64.totalEntries) {
                        "The ZIP64 entry counts do not match the end record."
                    }
                    if (classicDirectorySize != 0xffff_ffffL) require(classicDirectorySize == zip64.directorySize) {
                        "The ZIP64 directory size does not match the end record."
                    }
                    if (classicDirectoryOffset != 0xffff_ffffL) require(classicDirectoryOffset == zip64.directoryOffset) {
                        "The ZIP64 directory offset does not match the end record."
                    }
                }
            } else {
                CentralDirectoryInfo(
                    entriesOnDisk = classicEntriesOnDisk,
                    totalEntries = classicTotalEntries,
                    directorySize = classicDirectorySize,
                    directoryOffset = classicDirectoryOffset,
                    endRecordOffset = eocdOffset,
                )
            }

            require(directory.entriesOnDisk == directory.totalEntries) { "Multi-disk ZIP archives are not supported." }
            require(directory.totalEntries <= limits.maxEntries.toLong()) { "The backup contains too many files." }
            require(directory.directorySize <= MAX_CENTRAL_DIRECTORY_BYTES) { "The backup central directory is too large." }
            require(directory.directoryOffset <= Long.MAX_VALUE - directory.directorySize) {
                "The backup central directory has invalid bounds."
            }
            val directoryEnd = directory.directoryOffset + directory.directorySize
            require(directory.directoryOffset >= 0 && directoryEnd <= directory.endRecordOffset) {
                "The backup central directory has invalid bounds."
            }

            var cursor = directory.directoryOffset
            repeat(directory.totalEntries.toInt()) {
                checkCancelled()
                require(cursor <= directoryEnd - 46) { "The backup central directory is incomplete." }
                archive.seek(cursor)
                require(archive.readUnsignedIntLe() == CENTRAL_HEADER_SIGNATURE) {
                    "The backup central directory is damaged."
                }
                archive.seek(cursor + 28)
                val nameLength = archive.readUnsignedShortLe()
                val extraLength = archive.readUnsignedShortLe()
                val entryCommentLength = archive.readUnsignedShortLe()
                archive.seek(cursor + 34)
                val startDisk = archive.readUnsignedShortLe()
                require(startDisk == 0) { "Multi-disk ZIP archives are not supported." }
                require(nameLength in 1..MAX_ENTRY_NAME_CHARS) { "The backup contains an invalid ZIP entry name." }
                require(extraLength <= MAX_CENTRAL_EXTRA_BYTES) { "A ZIP entry contains excessive metadata." }
                require(entryCommentLength <= MAX_CENTRAL_COMMENT_BYTES) { "A ZIP entry comment is too large." }
                val recordLength = 46L + nameLength + extraLength + entryCommentLength
                require(cursor <= directoryEnd - recordLength) { "The backup central directory is incomplete." }
                cursor += recordLength
            }
            require(cursor == directoryEnd) { "The backup central directory has unexpected trailing data." }
        }
    }

    private fun findEndOfCentralDirectory(archive: RandomAccessFile): Long {
        val fileLength = archive.length()
        require(fileLength >= 22) { "The ZIP end record is missing." }
        val tailLength = minOf(fileLength, MAX_EOCD_SEARCH_BYTES.toLong()).toInt()
        val tail = ByteArray(tailLength)
        archive.seek(fileLength - tailLength)
        archive.readFully(tail)
        val tailOffset = fileLength - tailLength
        for (index in tail.size - 22 downTo 0) {
            if (tail.readUnsignedIntLe(index) != EOCD_SIGNATURE) continue
            val commentLength = tail.readUnsignedShortLe(index + 20)
            if (index + 22 + commentLength == tail.size) return tailOffset + index
        }
        throw IllegalArgumentException("The ZIP end record is missing or invalid.")
    }

    private fun readZip64Directory(archive: RandomAccessFile, eocdOffset: Long): CentralDirectoryInfo {
        require(eocdOffset >= 20) { "The ZIP64 locator is missing." }
        val locatorOffset = eocdOffset - 20
        archive.seek(locatorOffset)
        require(archive.readUnsignedIntLe() == ZIP64_LOCATOR_SIGNATURE) { "The ZIP64 locator is missing." }
        val recordDisk = archive.readUnsignedIntLe()
        val zip64Offset = archive.readUnsignedLongLe()
        val diskCount = archive.readUnsignedIntLe()
        require(recordDisk == 0L && diskCount == 1L) { "Multi-disk ZIP archives are not supported." }
        require(zip64Offset >= 0 && zip64Offset <= locatorOffset - 12 - 44) { "The ZIP64 end record has invalid bounds." }

        archive.seek(zip64Offset)
        require(archive.readUnsignedIntLe() == ZIP64_EOCD_SIGNATURE) { "The ZIP64 end record is invalid." }
        val recordSize = archive.readUnsignedLongLe()
        require(recordSize in 44..MAX_ZIP64_EOCD_BYTES.toLong()) { "The ZIP64 end record is too large." }
        require(zip64Offset + 12 + recordSize == locatorOffset) { "The ZIP64 end record has invalid bounds." }
        archive.readUnsignedShortLe() // version made by
        archive.readUnsignedShortLe() // version needed
        val diskNumber = archive.readUnsignedIntLe()
        val directoryDisk = archive.readUnsignedIntLe()
        val entriesOnDisk = archive.readUnsignedLongLe()
        val totalEntries = archive.readUnsignedLongLe()
        val directorySize = archive.readUnsignedLongLe()
        val directoryOffset = archive.readUnsignedLongLe()
        require(diskNumber == 0L && directoryDisk == 0L) { "Multi-disk ZIP archives are not supported." }
        return CentralDirectoryInfo(entriesOnDisk, totalEntries, directorySize, directoryOffset, zip64Offset)
    }

    private fun <T> openValidatedArchive(
        archiveFile: File,
        limits: LibraryBackupLimits,
        checkCancelled: () -> Unit,
        zipFileFactory: (File) -> ZipFile,
        action: (ZipFile, PortableLibraryBackupManifest) -> T,
    ): T {
        checkCancelled()
        require(archiveFile.isFile && archiveFile.length() in 1..limits.maxCompressedBytes) {
            "The selected archive is empty or exceeds the compressed-size limit."
        }
        require(archiveFile.canonicalFile == archiveFile.absoluteFile.toPath().normalize().toFile()) {
            "The selected private archive path is not safe."
        }
        try {
            preflightCentralDirectory(archiveFile, limits, checkCancelled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("The selected ZIP archive is incomplete or unreadable.", error)
        }
        val zip = try {
            zipFileFactory(archiveFile)
        } catch (error: Exception) {
            throw IllegalArgumentException("The selected ZIP archive is incomplete or unreadable.", error)
        }
        zip.use { opened ->
            val entries = try {
                collectZipEntries(opened.entries(), limits, checkCancelled)
            } catch (error: InterruptedException) {
                throw error
            } catch (error: RuntimeException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("The selected ZIP directory is damaged.", error)
            }
            require(entries.isNotEmpty() && entries.size <= limits.maxEntries) { "The backup contains too many files." }
            require(entries.first().name == MANIFEST_ENTRY) { "The backup manifest is missing or out of order." }
            entries.forEach { entry ->
                checkCancelled()
                require(!entry.isDirectory && entry.name.isNotEmpty() && '\\' !in entry.name && !entry.name.startsWith('/')) {
                    "The backup contains an unsafe ZIP path."
                }
                require(entry.size >= 0L) { "The backup ZIP directory contains an unknown file size." }
            }
            var totalUncompressedBytes = 0L
            entries.forEach { entry ->
                checkCancelled()
                totalUncompressedBytes = safeAdd(totalUncompressedBytes, entry.size)
            }
            require(totalUncompressedBytes <= limits.maxTotalPayloadBytes) {
                "The backup payload exceeds the supported size."
            }
            val names = hashSetOf<String>()
            entries.forEach { entry ->
                checkCancelled()
                require(names.add(entry.name)) { "The backup contains duplicate ZIP entries." }
            }
            val manifestEntry = entries.first()
            require(manifestEntry.size in 1..limits.maxManifestBytes.toLong()) {
                "The backup manifest is empty or too large."
            }
            require(manifestEntry.method == ZipEntry.DEFLATED || manifestEntry.method == ZipEntry.STORED) {
                "The backup uses an unsupported compression method."
            }
            val manifestBytes = readVerifiedEntry(opened, manifestEntry, limits.maxManifestBytes.toLong(), checkCancelled)
            checkCancelled()
            val manifest = LibraryBackupManifestCodec.decode(manifestBytes, limits, checkCancelled)
            checkCancelled()
            val expectedNames = hashSetOf(MANIFEST_ENTRY)
            manifest.assets.forEach { asset ->
                checkCancelled()
                expectedNames += asset.entryName
            }
            require(names.toSet() == expectedNames && names.size == expectedNames.size) {
                "The backup is missing a referenced file or contains an unexpected entry."
            }
            val centralAssets = hashMapOf<String, ZipEntry>()
            entries.drop(1).forEach { entry ->
                checkCancelled()
                centralAssets[entry.name] = entry
            }
            manifest.assets.forEach { asset ->
                checkCancelled()
                val entry = centralAssets[asset.entryName]
                    ?: throw IllegalArgumentException("The backup is missing a referenced file.")
                require(isAllowedZipAssetName(entry.name)) { "The backup contains an unsafe ZIP path." }
                require(entry.size == asset.sizeBytes && entry.compressedSize >= 0L) {
                    "A ZIP entry size does not match the backup manifest."
                }
                require(isWithinExpansionRatio(entry.size, entry.compressedSize, limits.maxAssetCompressionRatio)) {
                    "A ZIP asset exceeds the supported expansion ratio."
                }
                require(entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED) {
                    "The backup uses an unsupported compression method."
                }
                require(entry.crc in 0..0xffff_ffffL) { "The ZIP entry checksum is missing." }
            }
            checkCancelled()
            return action(opened, manifest)
        }
    }

    private fun readVerifiedEntry(
        zip: ZipFile,
        entry: ZipEntry,
        maximumBytes: Long,
        checkCancelled: () -> Unit,
    ): ByteArray {
        require(entry.size in 0..maximumBytes && entry.crc in 0..0xffff_ffffL) {
            "The backup manifest entry is invalid."
        }
        val result = ByteArrayOutputStream(entry.size.toInt())
        val crc = CRC32()
        var copied = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0 && copied <= maximumBytes - count) { "The backup manifest is too large." }
                result.write(buffer, 0, count)
                crc.update(buffer, 0, count)
                copied += count
            }
        }
        require(copied == entry.size && crc.value == entry.crc) { "The backup manifest is damaged or incomplete." }
        return result.toByteArray()
    }

    private fun copyExact(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long,
        maximumBytes: Long,
        checkCancelled: () -> Unit,
        onCopied: (Int) -> Unit,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0 && copied <= expectedBytes - count && copied <= maximumBytes - count) {
                "A local audio copy changed size during backup."
            }
            output.write(buffer, 0, count)
            copied += count
            onCopied(count)
        }
        return copied
    }

    private fun isOwnedAudioFile(audioDirectory: File, file: File): Boolean =
        PrivateAudioPath.isDirectChild(audioDirectory, file) || PrivateAudioPath.isOwnedRestoreFile(audioDirectory, file)

    private fun isAllowedZipAssetName(name: String): Boolean =
        name.startsWith("audio/") || name.startsWith("artwork/")

    private fun summary(snapshot: LibrarySnapshot, payloadBytes: Long) = BackupSummary(
        trackCount = snapshot.tracks.size,
        playlistCount = snapshot.playlists.size,
        favoriteCount = snapshot.favoriteTrackIds.size,
        payloadBytes = payloadBytes,
    )

    private fun RandomAccessFile.readUnsignedShortLe(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readUnsignedIntLe(): Long =
        readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 24)

    private fun RandomAccessFile.readUnsignedLongLe(): Long {
        var value = 0L
        repeat(8) { index ->
            val next = readUnsignedByte().toLong()
            require(index != 7 || next <= 0x7fL) { "A ZIP64 value exceeds the supported file-size range." }
            value = value or (next shl (index * 8))
        }
        return value
    }

    private fun ByteArray.readUnsignedShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUnsignedIntLe(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    private fun safeAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("The backup payload is too large.", error)
    }

    private fun isWithinExpansionRatio(uncompressedBytes: Long, compressedBytes: Long, maximumRatio: Long): Boolean {
        if (uncompressedBytes == 0L) return true
        if (compressedBytes <= 0L) return false
        val maximumExpandedBytes = try {
            Math.multiplyExact(compressedBytes, maximumRatio)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        return uncompressedBytes <= maximumExpandedBytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private class BoundedNonClosingOutputStream(
        private val target: OutputStream,
        private val maximumBytes: Long,
    ) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            if (written >= maximumBytes) throw IOException("The backup exceeds the compressed-size limit.")
            target.write(value)
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length < 0 || written > maximumBytes - length) throw IOException("The backup exceeds the compressed-size limit.")
            target.write(buffer, offset, length)
            written += length
        }

        override fun flush() = target.flush()
        override fun close() = flush()
    }
}
