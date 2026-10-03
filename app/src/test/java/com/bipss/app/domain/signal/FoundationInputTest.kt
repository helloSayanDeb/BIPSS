package com.bipss.app.domain.signal

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream

class FoundationInputTest {
    private fun fixture(name: String, n: Int): FloatArray = DataInputStream(
        javaClass.getResourceAsStream("/foundation/$name")!!).use { stream -> FloatArray(n) { stream.readFloat() } }
    @Test fun preparationMatchesScipyFilterAndFourierResampling() {
        val output = FoundationInput.prepare(fixture("input.bin", 1024))!!
        assertArrayEquals(fixture("output.bin", 1250), output, 0.0002f)
    }
    @Test fun flatAndInvalidWaveformsHaveNoEmbeddingInput() {
        assertNull(FoundationInput.prepare(FloatArray(1024) { 50000f }))
        assertNull(FoundationInput.prepare(FloatArray(1000)))
        assertNull(FoundationInput.prepare(FloatArray(1024) { Float.NaN }))
    }
}
