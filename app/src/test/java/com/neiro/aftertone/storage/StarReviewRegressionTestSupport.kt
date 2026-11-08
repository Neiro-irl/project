package com.neiro.aftertone.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal class RecordingRestoreFileDurability(
    private val events: MutableList<String>,
) : RestoreFileDurability {
    override fun syncFile(output: FileOutputStream) {
        output.fd.sync()
        events += "file"
    }

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        events += "dir:${directory.canonicalPath}"
    }
}
