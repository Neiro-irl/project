package com.neiro.aftertone.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkDecodePolicyTest {
    @Test
    fun samplesAn8192SquareBitmapByEight() {
        assertEquals(8, ArtworkDecodePolicy.sampleSize(width = 8192, height = 8192))
    }

    @Test
    fun boundsTheLongestDimensionOfANonSquareBitmap() {
        assertEquals(4, ArtworkDecodePolicy.sampleSize(width = 4096, height = 2048))
    }

    @Test
    fun doesNotDownsampleAnImageAlreadyWithinTheBound() {
        assertEquals(1, ArtworkDecodePolicy.sampleSize(width = 1000, height = 700))
    }

    @Test
    fun rejectsInvalidImageBounds() {
        assertNull(ArtworkDecodePolicy.sampleSize(width = 0, height = 100))
        assertNull(ArtworkDecodePolicy.sampleSize(width = 100, height = 0))
        assertNull(ArtworkDecodePolicy.sampleSize(width = -1, height = 100))
    }
}
