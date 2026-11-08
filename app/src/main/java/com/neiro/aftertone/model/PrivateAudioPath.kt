package com.neiro.aftertone.model

import java.io.File

object PrivateAudioPath {
    private const val RESTORE_OWNER_MARKER = ".aftertone-restore-owner"
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun isCanonicalDirectory(directory: File): Boolean = try {
        val absoluteDirectory = directory.absoluteFile.toPath().normalize().toFile()
        val absoluteParent = absoluteDirectory.parentFile
        absoluteParent != null && directory.canonicalFile ==
            File(absoluteParent.canonicalFile, absoluteDirectory.name).absoluteFile.toPath().normalize().toFile()
    } catch (_: Exception) {
        false
    }

    fun isDirectChild(audioDirectory: File, candidate: File): Boolean = try {
        if (!isCanonicalDirectory(audioDirectory)) return false
        val absoluteRoot = audioDirectory.absoluteFile.toPath().normalize().toFile()
        val absoluteCandidate = candidate.absoluteFile.toPath().normalize().toFile()
        val canonicalRoot = audioDirectory.canonicalFile
        val canonicalCandidate = candidate.canonicalFile

        absoluteCandidate.parentFile == absoluteRoot &&
            canonicalCandidate.parentFile == canonicalRoot &&
            absoluteCandidate.name == canonicalCandidate.name
    } catch (_: Exception) {
        false
    }

    fun isOwnedRestoreFile(audioDirectory: File, candidate: File): Boolean {
        val parent = candidate.absoluteFile.parentFile ?: return false
        val id = restoreGenerationId(parent.name) ?: return false
        return isOwnedRestoreDirectory(audioDirectory, parent, id) && isDirectChild(parent, candidate)
    }

    fun isOwnedRestoreDirectory(audioDirectory: File, candidate: File, expectedId: String): Boolean {
        if (!uuidPattern.matches(expectedId) || candidate.name != "restore-$expectedId") return false
        if (!isDirectChild(audioDirectory, candidate) || !candidate.isDirectory) return false
        val marker = File(candidate, RESTORE_OWNER_MARKER)
        return try {
            marker.length() in 1..64 && marker.isFile && isDirectChild(candidate, marker) &&
                marker.readText(Charsets.UTF_8) == expectedId
        } catch (_: Exception) {
            false
        }
    }

    internal fun restoreGenerationId(directoryName: String): String? =
        directoryName.removePrefix("restore-").takeIf { directoryName.startsWith("restore-") && uuidPattern.matches(it) }

    internal fun restoreStageId(directoryName: String): String? =
        directoryName.removePrefix(".aftertone-restore-stage-")
            .takeIf { directoryName.startsWith(".aftertone-restore-stage-") && uuidPattern.matches(it) }

    internal fun restoreOwnerMarkerName(): String = RESTORE_OWNER_MARKER
}
