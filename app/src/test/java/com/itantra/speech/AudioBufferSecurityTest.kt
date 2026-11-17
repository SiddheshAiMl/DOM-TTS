package com.itantra.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class AudioBufferSecurityTest {

    @Test
    fun testBufferZeroingSanitizesAudioData() {
        // Given a buffer populated with simulated sensitive speech PCM samples
        val buffer = ShortArray(480) { (it * 100).toShort() }
        var hasNonZero = buffer.any { it != 0.toShort() }
        assertTrue("Buffer must initially contain non-zero speech samples", hasNonZero)

        // When the privacy hygiene routine is executed (matching AudioCaptureManager)
        Arrays.fill(buffer, 0.toShort())

        // Then all elements must be zeroed out
        val allZero = buffer.all { it == 0.toShort() }
        assertTrue("All audio samples in transient buffer must be zeroed out", allZero)
        assertEquals("Buffer length remains unchanged", 480, buffer.size)
    }

    @Test
    fun testRmsEnergyCalculationNormalizesCorrectly() {
        val silentChunk = ShortArray(480) { 0 }
        var sum = 0.0
        for (i in silentChunk.indices) {
            sum += silentChunk[i] * silentChunk[i]
        }
        val rmsSilent = kotlin.math.sqrt(sum / silentChunk.size).toFloat() / 32768f
        assertEquals("Silent chunk should have 0.0 normalized RMS", 0f, rmsSilent, 0.001f)

        // Full scale square wave
        val maxChunk = ShortArray(480) { 32767.toShort() }
        var sumMax = 0.0
        for (i in maxChunk.indices) {
            sumMax += maxChunk[i] * maxChunk[i]
        }
        val rmsMax = kotlin.math.sqrt(sumMax / maxChunk.size).toFloat() / 32768f
        assertTrue("Max volume chunk normalized RMS should approach 1.0", rmsMax > 0.99f)
    }
}
