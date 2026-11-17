package com.itantra.transport

import com.itantra.core.model.DeliveryStatus
import com.itantra.core.protocol.PacketProtocol
import com.itantra.core.transport.DemoTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopAndWaitArqTest {

    @Test
    fun sendPacket_traversesAllDeliveryStatesSequentially() = runBlocking {
        val transport = DemoTransport()
        val dummyPacket = PacketProtocol.encode(
            PacketProtocol.buildAckPacket(
                senderId = 1L,
                sessionId = 1L,
                ackSequenceNumber = 1L,
                nonce = ByteArray(12)
            )
        )

        val states = transport.sendPacket(dummyPacket).toList()

        val expectedStates = listOf(
            DeliveryStatus.QUEUED,
            DeliveryStatus.SENDING,
            DeliveryStatus.SENT_TO_NODE,
            DeliveryStatus.RADIO_ACKNOWLEDGED,
            DeliveryStatus.DELIVERED
        )

        assertEquals(expectedStates, states)
        assertTrue(transport.isDemoMode)
        assertEquals(1L, transport.linkStatistics.value.txCount)
    }
}
