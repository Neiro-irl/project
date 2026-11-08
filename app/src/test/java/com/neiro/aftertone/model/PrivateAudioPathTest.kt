package com.neiro.aftertone.model

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivateAudioPathTest {
    @Test
    fun rejectsDirectChildWhenPrivateRootIsRedirectedThroughSymlink() {
        val root = Files.createTempDirectory("aftertone-private-root-").toFile()
        try {
            val redirectedTarget = File(root, "redirected-target").apply { mkdir() }
            val privateRootLink = File(root, "app-private-audio-link")
            Files.createSymbolicLink(privateRootLink.toPath(), redirectedTarget.toPath())
            val redirectedAudio = File(privateRootLink, "track-01.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            assertFalse(PrivateAudioPath.isDirectChild(privateRootLink, redirectedAudio))
        } finally {
            root.deleteRecursively()
        }
    }
}
