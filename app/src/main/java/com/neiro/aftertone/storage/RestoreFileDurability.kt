package com.neiro.aftertone.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal interface RestoreFileDurability {
    fun syncFile(output: FileOutputStream)
    fun syncDirectory(directory: File)
}

internal object SystemRestoreFileDurability : RestoreFileDurability {
    override fun syncFile(output: FileOutputStream) {
        output.fd.sync()
    }

    override fun syncDirectory(directory: File) {
        require(directory.isDirectory) { "The directory to sync is unavailable." }
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}
