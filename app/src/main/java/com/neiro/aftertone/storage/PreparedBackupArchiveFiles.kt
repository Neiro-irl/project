package com.neiro.aftertone.storage

import com.neiro.aftertone.model.PrivateAudioPath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object PreparedBackupArchiveFiles {
    const val ARCHIVE_PREFIX = "aftertone-restore-source-"
    private const val OWNER_SUFFIX = ".owner"
    private val validId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun create(cacheDirectory: File, id: String): File =
        create(cacheDirectory, id, SystemRestoreFileDurability)

    internal fun create(
        cacheDirectory: File,
        id: String,
        durability: RestoreFileDurability,
    ): File {
        requireValidCacheAndId(cacheDirectory, id)
        val archive = archiveFile(cacheDirectory, id)
        val marker = ownerMarker(cacheDirectory, id)
        if (archive.exists() || marker.exists()) {
            throw IllegalStateException("The temporary backup path already exists.")
        }
        if (!marker.createNewFile()) throw IllegalStateException("Could not create the temporary backup owner marker.")
        try {
            FileOutputStream(marker).use { output ->
                output.write(id.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            durability.syncDirectory(cacheDirectory)
            if (!isOwned(cacheDirectory, id)) throw SecurityException("The temporary backup ownership marker is invalid.")
            return archive
        } catch (error: Throwable) {
            if (isOwnedMarker(cacheDirectory, marker, id)) marker.delete()
            throw error
        }
    }

    fun archiveFile(cacheDirectory: File, id: String): File {
        requireValidCacheAndId(cacheDirectory, id)
        return File(cacheDirectory, "$ARCHIVE_PREFIX$id.zip").also { archive ->
            if (!PrivateAudioPath.isDirectChild(cacheDirectory, archive)) {
                throw SecurityException("The temporary archive path is unsafe.")
            }
        }
    }

    fun ownerMarker(cacheDirectory: File, id: String): File {
        requireValidCacheAndId(cacheDirectory, id)
        return File(cacheDirectory, "$ARCHIVE_PREFIX$id$OWNER_SUFFIX").also { marker ->
            if (!PrivateAudioPath.isDirectChild(cacheDirectory, marker)) {
                throw SecurityException("The temporary archive owner marker path is unsafe.")
            }
        }
    }

    fun isOwned(cacheDirectory: File, id: String): Boolean = runCatching {
        val marker = ownerMarker(cacheDirectory, id)
        isOwnedMarker(cacheDirectory, marker, id)
    }.getOrDefault(false)

    fun isFullyDiscarded(cacheDirectory: File, id: String): Boolean = runCatching {
        if (!isSafeCacheDirectory(cacheDirectory) || !validId.matches(id)) return@runCatching false
        !archiveFile(cacheDirectory, id).exists() && !ownerMarker(cacheDirectory, id).exists()
    }.getOrDefault(false)

    fun discard(cacheDirectory: File, id: String): Boolean {
        if (!isSafeCacheDirectory(cacheDirectory) || !validId.matches(id)) return false
        val archive = archiveFile(cacheDirectory, id)
        val marker = ownerMarker(cacheDirectory, id)
        if (!marker.exists()) return !archive.exists()
        if (!isOwnedMarker(cacheDirectory, marker, id)) return false
        if (archive.exists()) {
            if (!archive.isFile || !PrivateAudioPath.isDirectChild(cacheDirectory, archive)) return false
            if (!archive.delete()) return false
        }
        if (!marker.delete() && marker.exists()) return false
        return runCatching {
            SystemRestoreFileDurability.syncDirectory(cacheDirectory)
            true
        }.getOrDefault(false)
    }

    fun cleanupOrphans(cacheDirectory: File): Int = cleanupOrphansWithPending(cacheDirectory).removedArchives

    fun cleanupOrphansWithPending(cacheDirectory: File): PreparedArchiveCleanupResult =
        cleanupOrphansWithPending(cacheDirectory, emptySet())

    internal fun cleanupOrphansWithPending(
        cacheDirectory: File,
        listFiles: (File) -> Array<File>?,
    ): PreparedArchiveCleanupResult = cleanupOrphansWithPending(cacheDirectory, emptySet(), listFiles)

    internal fun cleanupOrphansWithPending(
        cacheDirectory: File,
        protectedArchiveIds: Set<String>,
        listFiles: (File) -> Array<File>? = { it.listFiles() },
    ): PreparedArchiveCleanupResult {
        if (!isSafeCacheDirectory(cacheDirectory)) {
            throw SecurityException("The app-private cache directory is redirected or unsafe.")
        }
        val markers = listFiles(cacheDirectory)?.toList()
            ?: throw IOException("Could not list temporary restore archives safely.")
        val ids = markers.mapNotNull { marker ->
            val name = marker.name
            if (!name.startsWith(ARCHIVE_PREFIX) || !name.endsWith(OWNER_SUFFIX)) return@mapNotNull null
            name.removePrefix(ARCHIVE_PREFIX).removeSuffix(OWNER_SUFFIX).takeIf(validId::matches)
        }
        var removedArchives = 0
        val pendingIds = mutableListOf<String>()
        ids.forEach { id ->
            val archive = archiveFile(cacheDirectory, id)
            if (id in protectedArchiveIds && archive.isFile && isOwned(cacheDirectory, id)) return@forEach
            val existed = archive.exists()
            if (discard(cacheDirectory, id)) {
                if (existed) removedArchives++
            } else {
                pendingIds += id
            }
        }
        return PreparedArchiveCleanupResult(removedArchives, pendingIds)
    }

    private fun isOwnedMarker(cacheDirectory: File, marker: File, id: String): Boolean =
        isSafeCacheDirectory(cacheDirectory) &&
            marker.isFile &&
            marker.length() == id.length.toLong() &&
            PrivateAudioPath.isDirectChild(cacheDirectory, marker) &&
            runCatching { marker.readText(Charsets.UTF_8) == id }.getOrDefault(false)

    private fun requireValidCacheAndId(cacheDirectory: File, id: String) {
        require(validId.matches(id)) { "The temporary archive identifier is invalid." }
        if (!isSafeCacheDirectory(cacheDirectory)) {
            throw SecurityException("The app-private cache directory is redirected or unsafe.")
        }
    }

    private fun isSafeCacheDirectory(directory: File): Boolean =
        directory.isDirectory &&
            runCatching { directory.canonicalFile == directory.absoluteFile.toPath().normalize().toFile() }.getOrDefault(false)
}

internal data class PreparedArchiveCleanupResult(
    val removedArchives: Int,
    val pendingIds: List<String>,
)
