package com.itantra.transport

import com.itantra.core.transport.DemoTransport
import com.itantra.core.transport.PacketPriority
import com.itantra.core.transport.PriorityTransportQueue
import com.itantra.core.transport.QueuedPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PriorityTransportQueueTest {

    @Test
    fun highPriorityEmergencyPacket_preemptsNormalPackets() {
        val dummyTransport = DemoTransport()
        val priorityQueue = PriorityTransportQueue(dummyTransport)

        val chatPacket1 = QueuedPacket("chat-1", PacketPriority.NORMAL, byteArrayOf(1), timestamp = 1000L)
        val chatPacket2 = QueuedPacket("chat-2", PacketPriority.NORMAL, byteArrayOf(2), timestamp = 2000L)
        val emergencyPacket = QueuedPacket("sos-1", PacketPriority.HIGH, byteArrayOf(9), timestamp = 3000L)

        // Enqueue normal packets first, then emergency packet later
        priorityQueue.enqueue(chatPacket1)
        priorityQueue.enqueue(chatPacket2)
        priorityQueue.enqueue(emergencyPacket)

        assertEquals(3, priorityQueue.size())

        // The first polled packet must be the emergency alert despite arriving last
        val firstOut = priorityQueue.pollNext()
        assertNotNull(firstOut)
        assertEquals("sos-1", firstOut!!.id)
        assertEquals(PacketPriority.HIGH, firstOut.priority)

        // Then in FIFO timestamp order for normal packets
        val secondOut = priorityQueue.pollNext()
        assertEquals("chat-1", secondOut!!.id)

        val thirdOut = priorityQueue.pollNext()
        assertEquals("chat-2", thirdOut!!.id)
    }
}
