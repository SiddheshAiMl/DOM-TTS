package com.itantra.core.protocol

import java.io.ByteArrayOutputStream

/**
 * High-level message fragmentation and reassembly.
 * Breaks oversized text messages across multiple FRAGMENT packets.
 */
object PacketFragmentation {
    const val MAX_FRAGMENT_PAYLOAD = 180 // Fits comfortably in single LoRa frame with header & tag
    const val DEFAULT_REASSEMBLY_TIMEOUT_MS = 5000L

    data class FragmentDescriptor(
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FragmentDescriptor
            return fragmentIndex == other.fragmentIndex &&
                    fragmentCount == other.fragmentCount &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = fragmentIndex
            result = 31 * result + fragmentCount
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Splits a large payload into multiple fragments.
     */
    fun fragmentPayload(payload: ByteArray, maxFragmentSize: Int = MAX_FRAGMENT_PAYLOAD): List<FragmentDescriptor> {
        if (payload.isEmpty()) {
            return listOf(FragmentDescriptor(0, 1, ByteArray(0)))
        }

        val count = (payload.size + maxFragmentSize - 1) / maxFragmentSize
        val fragments = mutableListOf<FragmentDescriptor>()

        for (i in 0 until count) {
            val offset = i * maxFragmentSize
            val length = minOf(payload.size - offset, maxFragmentSize)
            val chunk = ByteArray(length)
            System.arraycopy(payload, offset, chunk, 0, length)
            fragments.add(FragmentDescriptor(i, count, chunk))
        }

        return fragments
    }

    /**
     * Session-based reassembler supporting out-of-order arrival and duplicate suppression.
     */
    class Reassembler(private val timeoutMs: Long = DEFAULT_REASSEMBLY_TIMEOUT_MS) {
        private val fragments = mutableMapOf<Int, ByteArray>()
        private var expectedCount = -1
        private var firstFragmentTimestamp = 0L

        @Synchronized
        fun addFragment(fragmentIndex: Int, fragmentCount: Int, payload: ByteArray): ByteArray? {
            val now = System.currentTimeMillis()
            if (firstFragmentTimestamp > 0 && (now - firstFragmentTimestamp > timeoutMs)) {
                reset()
            }

            if (fragments.isEmpty()) {
                firstFragmentTimestamp = now
                expectedCount = fragmentCount
            }

            if (fragmentCount != expectedCount || fragmentIndex >= fragmentCount) {
                return null // Mismatch, discard
            }

            // Duplicate suppression: do not re-add if already stored
            if (!fragments.containsKey(fragmentIndex)) {
                fragments[fragmentIndex] = payload
            }

            if (fragments.size == expectedCount) {
                val output = ByteArrayOutputStream()
                for (i in 0 until expectedCount) {
                    val part = fragments[i] ?: return null // Missing slice
                    output.write(part)
                }
                val result = output.toByteArray()
                reset()
                return result
            }

            return null
        }

        @Synchronized
        fun reset() {
            fragments.clear()
            expectedCount = -1
            firstFragmentTimestamp = 0L
        }
    }
}
