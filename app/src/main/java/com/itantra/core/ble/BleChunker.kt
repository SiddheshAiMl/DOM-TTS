package com.itantra.core.ble

import java.io.ByteArrayOutputStream

/**
 * Handles fragmentation and reassembly of binary frames over BLE ATT MTU.
 * Complies with docs/ESP32_BLE_PROTOCOL.md.
 */
object BleChunker {
    const val CHUNK_HEADER_SIZE = 2
    const val FLAG_START: Int = 0x80
    const val FLAG_END: Int   = 0x40
    const val SEQ_MASK: Int   = 0x3F

    /**
     * Splits a frame into BLE-transmittable chunks based on effective MTU.
     * Max chunk payload = effectiveMtu - CHUNK_HEADER_SIZE.
     */
    fun splitFrame(frame: ByteArray, effectiveMtu: Int): List<ByteArray> {
        require(effectiveMtu > CHUNK_HEADER_SIZE + 1) { "MTU $effectiveMtu too small for chunking" }
        require(frame.size <= 255) { "Frame size ${frame.size} exceeds 255-byte limit" }

        val maxPayloadPerChunk = effectiveMtu - CHUNK_HEADER_SIZE
        if (frame.size <= maxPayloadPerChunk) {
            // Single chunk
            val chunk = ByteArray(CHUNK_HEADER_SIZE + frame.size)
            chunk[0] = (FLAG_START or FLAG_END).toByte()
            chunk[1] = frame.size.toByte()
            System.arraycopy(frame, 0, chunk, CHUNK_HEADER_SIZE, frame.size)
            return listOf(chunk)
        }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var seq = 0

        while (offset < frame.size) {
            val remaining = frame.size - offset
            val chunkSize = minOf(remaining, maxPayloadPerChunk)
            val isStart = (offset == 0)
            val isEnd = (offset + chunkSize >= frame.size)

            var flags = (seq and SEQ_MASK)
            if (isStart) flags = flags or FLAG_START
            if (isEnd) flags = flags or FLAG_END

            val chunk = ByteArray(CHUNK_HEADER_SIZE + chunkSize)
            chunk[0] = flags.toByte()
            chunk[1] = frame.size.toByte()
            System.arraycopy(frame, offset, chunk, CHUNK_HEADER_SIZE, chunkSize)

            chunks.add(chunk)
            offset += chunkSize
            seq = (seq + 1) and SEQ_MASK
        }

        return chunks
    }

    /**
     * Stateful reassembler for incoming BLE chunks.
     */
    class Reassembler(private val timeoutMs: Long = 500L) {
        private val buffer = ByteArrayOutputStream()
        private var expectedTotalLength = -1
        private var lastSeq = -1
        private var lastChunkTime = 0L

        @Synchronized
        fun processChunk(chunk: ByteArray): ByteArray? {
            if (chunk.size < CHUNK_HEADER_SIZE) return null

            val now = System.currentTimeMillis()
            if (expectedTotalLength > 0 && (now - lastChunkTime > timeoutMs)) {
                // Timeout: reset buffer
                reset()
            }
            lastChunkTime = now

            val byte0 = chunk[0].toInt() and 0xFF
            val totalLength = chunk[1].toInt() and 0xFF
            val isStart = (byte0 and FLAG_START) != 0
            val isEnd   = (byte0 and FLAG_END) != 0
            val seq     = byte0 and SEQ_MASK

            val payloadLength = chunk.size - CHUNK_HEADER_SIZE
            val payload = ByteArray(payloadLength)
            System.arraycopy(chunk, CHUNK_HEADER_SIZE, payload, 0, payloadLength)

            if (isStart) {
                buffer.reset()
                expectedTotalLength = totalLength
                lastSeq = seq
                buffer.write(payload)
            } else {
                if (expectedTotalLength <= 0) return null // Missing start chunk
                val expectedSeq = (lastSeq + 1) and SEQ_MASK
                if (seq != expectedSeq) {
                    reset()
                    return null // Out of order chunk, drop
                }
                lastSeq = seq
                buffer.write(payload)
            }

            if (isEnd) {
                if (buffer.size() == expectedTotalLength) {
                    val completed = buffer.toByteArray()
                    reset()
                    return completed
                } else {
                    reset()
                    return null
                }
            }

            return null
        }

        @Synchronized
        fun reset() {
            buffer.reset()
            expectedTotalLength = -1
            lastSeq = -1
            lastChunkTime = 0L
        }
    }
}
