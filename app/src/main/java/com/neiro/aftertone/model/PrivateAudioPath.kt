package com.neiro.aftertone.model

import java.io.File

object PrivateAudioPath {
    fun isDirectChild(audioDirectory: File, candidate: File): Boolean = try {
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
}
