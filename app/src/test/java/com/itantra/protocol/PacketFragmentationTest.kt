package com.itantra.protocol

import com.itantra.core.protocol.PacketFragmentation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacketFragmentationTest {

    @Test
    fun fragmentAndReassemble_inOrder_succeeds() {
        val original = "This is a long message that needs to be fragmented across multiple LoRa frames for reliable transmission.".toByteArray(Charsets.UTF_8)
        val fragments = PacketFragmentation.fragmentPayload(original, maxFragmentSize = 30)

        assertEquals(4, fragments.size) // 107 bytes / 30 = 4 fragments
        for (f in fragments) {
            assertEquals(4, f.fragmentCount)
        }

        val reassembler = PacketFragmentation.Reassembler()
        assertNull(reassembler.addFragment(fragments[0].fragmentIndex, fragments[0].fragmentCount, fragments[0].payload))
        assertNull(reassembler.addFragment(fragments[1].fragmentIndex, fragments[1].fragmentCount, fragments[1].payload))
        assertNull(reassembler.addFragment(fragments[2].fragmentIndex, fragments[2].fragmentCount, fragments[2].payload))
        val reassembled = reassembler.addFragment(fragments[3].fragmentIndex, fragments[3].fragmentCount, fragments[3].payload)

        assertNotNull(reassembled)
        assertArrayEquals(original, reassembled)
    }

    @Test
    fun fragmentAndReassemble_outOfOrder_succeeds() {
        val original = ByteArray(100) { (it * 2).toByte() }
        val fragments = PacketFragmentation.fragmentPayload(original, maxFragmentSize = 40)
        assertEquals(3, fragments.size)

        val reassembler = PacketFragmentation.Reassembler()
        // Receive fragment 2, then 0, then 1
        assertNull(reassembler.addFragment(fragments[2].fragmentIndex, fragments[2].fragmentCount, fragments[2].payload))
        assertNull(reassembler.addFragment(fragments[0].fragmentIndex, fragments[0].fragmentCount, fragments[0].payload))
        val reassembled = reassembler.addFragment(fragments[1].fragmentIndex, fragments[1].fragmentCount, fragments[1].payload)

        assertNotNull(reassembled)
        assertArrayEquals(original, reassembled)
    }

    @Test
    fun reassembler_suppressesDuplicates() {
        val original = "Duplicate suppression test".toByteArray(Charsets.UTF_8)
        val fragments = PacketFragmentation.fragmentPayload(original, maxFragmentSize = 10)

        val reassembler = PacketFragmentation.Reassembler()
        // Feed fragment 0 twice
        reassembler.addFragment(fragments[0].fragmentIndex, fragments[0].fragmentCount, fragments[0].payload)
        reassembler.addFragment(fragments[0].fragmentIndex, fragments[0].fragmentCount, fragments[0].payload)

        // Feed rest
        for (i in 1 until fragments.size - 1) {
            reassembler.addFragment(fragments[i].fragmentIndex, fragments[i].fragmentCount, fragments[i].payload)
        }
        val reassembled = reassembler.addFragment(
            fragments.last().fragmentIndex,
            fragments.last().fragmentCount,
            fragments.last().payload
        )

        assertNotNull(reassembled)
        assertArrayEquals(original, reassembled)
    }
}
