package com.neiro.aftertone.ui.components

internal object ArtworkDecodePolicy {
    private const val MAX_DECODED_DIMENSION = 1024

    fun sampleSize(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null

        val longestDimension = maxOf(width, height).toLong()
        var sampleSize = 1
        while (longestDimension > MAX_DECODED_DIMENSION.toLong() * sampleSize) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
