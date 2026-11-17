package com.itantra.ble

import com.itantra.core.ble.BleChunker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleChunkerTest {

    @Test
    fun splitFrame_singleChunkWhenFitsMtu() {
        val payload = ByteArray(15) { it.toByte() }
        val effectiveMtu = 20

        val chunks = BleChunker.splitFrame(payload, effectiveMtu)
        assertEquals(1, chunks.size)

        val chunk = chunks[0]
        assertEquals(BleChunker.CHUNK_HEADER_SIZE + payload.size, chunk.size)
        // Check flags: START (0x80) | END (0x40) = 0xC0
        val flags = chunk[0].toInt() and 0xFF
        assertTrue((flags and BleChunker.FLAG_START) != 0)
        assertTrue((flags and BleChunker.FLAG_END) != 0)
        assertEquals(payload.size, chunk[1].toInt() and 0xFF)

        val reassembler = BleChunker.Reassembler()
        val reassembled = reassembler.processChunk(chunk)
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled)
    }

    @Test
    fun splitFrame_multiChunkWhenExceedsMtu() {
        val payload = ByteArray(50) { (it * 3).toByte() }
        val effectiveMtu = 20 // Max payload per chunk = 18 bytes -> 3 chunks (18 + 18 + 14)

        val chunks = BleChunker.splitFrame(payload, effectiveMtu)
        assertEquals(3, chunks.size)

        // Chunk 0: START
        val flags0 = chunks[0][0].toInt() and 0xFF
        assertTrue((flags0 and BleChunker.FLAG_START) != 0)
        assertTrue((flags0 and BleChunker.FLAG_END) == 0)
        assertEquals(0, flags0 and BleChunker.SEQ_MASK)

        // Chunk 1: Middle
        val flags1 = chunks[1][0].toInt() and 0xFF
        assertTrue((flags1 and BleChunker.FLAG_START) == 0)
        assertTrue((flags1 and BleChunker.FLAG_END) == 0)
        assertEquals(1, flags1 and BleChunker.SEQ_MASK)

        // Chunk 2: END
        val flags2 = chunks[2][0].toInt() and 0xFF
        assertTrue((flags2 and BleChunker.FLAG_START) == 0)
        assertTrue((flags2 and BleChunker.FLAG_END) != 0)
        assertEquals(2, flags2 and BleChunker.SEQ_MASK)

        val reassembler = BleChunker.Reassembler()
        assertNull(reassembler.processChunk(chunks[0]))
        assertNull(reassembler.processChunk(chunks[1]))
        val result = reassembler.processChunk(chunks[2])
        assertNotNull(result)
        assertArrayEquals(payload, result)
    }

    @Test
    fun reassembler_dropsOutOfOrderChunk() {
        val payload = ByteArray(50) { (it + 5).toByte() }
        val chunks = BleChunker.splitFrame(payload, effectiveMtu = 20)
        assertEquals(3, chunks.size)

        val reassembler = BleChunker.Reassembler()
        assertNull(reassembler.processChunk(chunks[0]))
        // Skip chunk 1, feed chunk 2 directly (out of order)
        val result = reassembler.processChunk(chunks[2])
        assertNull("Out of order chunk must be rejected", result)
    }
}
