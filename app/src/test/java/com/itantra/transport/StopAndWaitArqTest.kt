package com.itantra.transport

import com.itantra.core.model.DeliveryStatus
import com.itantra.core.protocol.PacketProtocol
import com.itantra.core.transport.ArqState
import com.itantra.core.transport.DemoTransport
import com.itantra.core.transport.StopAndWaitArqEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopAndWaitArqTest {

    @Test
    fun sendPacket_traversesAllDeliveryStatesSequentially_demoTransport() = runBlocking {
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

    @Test
    fun testArqEngine_packetWithoutNeedsAck_completesImmediately() = runBlocking {
        val sentFrames = mutableListOf<ByteArray>()
        val engine = StopAndWaitArqEngine(
            rawFrameSender = { frame ->
                sentFrames.add(frame)
                true
            },
            ackTimeoutMs = 100L
        )

        // Packet without FLAG_NEEDS_ACK
        val header = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = 0x00,
            senderId = 10L,
            sessionId = 20L,
            sequenceNumber = 1L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = ByteArray(12),
            ciphertextLength = 0
        )
        val packet = PacketProtocol.Packet(header, ByteArray(0), ByteArray(16))
        val packetBytes = PacketProtocol.encode(packet)

        val states = engine.sendPacket(packetBytes).toList()

        val expectedStates = listOf(
            DeliveryStatus.QUEUED,
            DeliveryStatus.SENDING,
            DeliveryStatus.SENT_TO_NODE,
            DeliveryStatus.RADIO_ACKNOWLEDGED,
            DeliveryStatus.DELIVERED
        )
        assertEquals(expectedStates, states)
        assertEquals(1, sentFrames.size)
        assertEquals(ArqState.DELIVERED, engine.currentState.value)
    }

    @Test
    fun testArqEngine_successfulAckDelivery() = runBlocking {
        val sentFrames = mutableListOf<ByteArray>()
        lateinit var engine: StopAndWaitArqEngine
        engine = StopAndWaitArqEngine(
            rawFrameSender = { frame ->
                sentFrames.add(frame)
                // When data packet is sent, simulate remote peer answering with ACK
                val decoded = PacketProtocol.decode(frame)
                if ((decoded.header.flags.toInt() and PacketProtocol.Flags.FLAG_NEEDS_ACK.toInt()) != 0) {
                    val ackPacket = PacketProtocol.buildAckPacket(
                        senderId = 99L,
                        sessionId = decoded.header.sessionId,
                        ackSequenceNumber = decoded.header.sequenceNumber,
                        nonce = ByteArray(12)
                    )
                    val ackBytes = PacketProtocol.encode(ackPacket)
                    // Trigger peer ACK
                    engine.onIncomingFrame(ackBytes)
                }
                true
            },
            ackTimeoutMs = 200L,
            maxRetries = 3
        )

        val header = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
            senderId = 100L,
            sessionId = 200L,
            sequenceNumber = 55L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = ByteArray(12),
            ciphertextLength = 0
        )
        val packet = PacketProtocol.Packet(header, ByteArray(0), ByteArray(16))
        val packetBytes = PacketProtocol.encode(packet)

        val states = engine.sendPacket(packetBytes).toList()

        val expectedStates = listOf(
            DeliveryStatus.QUEUED,
            DeliveryStatus.SENDING,
            DeliveryStatus.SENT_TO_NODE,
            DeliveryStatus.RADIO_ACKNOWLEDGED,
            DeliveryStatus.DELIVERED
        )
        assertEquals(expectedStates, states)
        assertEquals(ArqState.DELIVERED, engine.currentState.value)
    }

    @Test
    fun testArqEngine_timeoutAndRetry() = runBlocking {
        var sendCount = 0
        var retryCallbackCount = 0
        val engine = StopAndWaitArqEngine(
            rawFrameSender = {
                sendCount++
                // Never send ACK to force timeout and retries
                true
            },
            ackTimeoutMs = 50L,
            maxRetries = 3
        )

        val header = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
            senderId = 100L,
            sessionId = 200L,
            sequenceNumber = 77L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = ByteArray(12),
            ciphertextLength = 0
        )
        val packet = PacketProtocol.Packet(header, ByteArray(0), ByteArray(16))
        val packetBytes = PacketProtocol.encode(packet)

        val states = engine.sendPacket(packetBytes) {
            retryCallbackCount++
        }.toList()

        // 1 initial send + 3 retries = 4 transmissions total
        assertEquals(4, sendCount)
        assertEquals(3, retryCallbackCount)
        assertEquals(DeliveryStatus.FAILED, states.last())
        assertEquals(ArqState.FAILED, engine.currentState.value)
    }

    @Test
    fun testArqEngine_unmatchedAckIgnored() = runBlocking {
        var sendCount = 0
        lateinit var engine: StopAndWaitArqEngine
        engine = StopAndWaitArqEngine(
            rawFrameSender = { frame ->
                sendCount++
                // Send an ACK with WRONG sequence number (e.g. 999 instead of 10)
                val ackPacket = PacketProtocol.buildAckPacket(
                    senderId = 99L,
                    sessionId = 200L,
                    ackSequenceNumber = 999L,
                    nonce = ByteArray(12)
                )
                engine.onIncomingFrame(PacketProtocol.encode(ackPacket))
                true
            },
            ackTimeoutMs = 50L,
            maxRetries = 1
        )

        val header = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
            senderId = 100L,
            sessionId = 200L,
            sequenceNumber = 10L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = ByteArray(12),
            ciphertextLength = 0
        )
        val packet = PacketProtocol.Packet(header, ByteArray(0), ByteArray(16))
        val states = engine.sendPacket(PacketProtocol.encode(packet)).toList()

        // Unmatched ACK should be ignored, resulting in timeout and eventual FAILED
        assertEquals(DeliveryStatus.FAILED, states.last())
        assertEquals(ArqState.FAILED, engine.currentState.value)
    }

    @Test
    fun testArqEngine_autoAckOnReceivedData() = runBlocking {
        val sentFrames = mutableListOf<ByteArray>()
        val engine = StopAndWaitArqEngine(
            rawFrameSender = { frame ->
                sentFrames.add(frame)
                true
            }
        )

        // Remote peer sends an incoming packet that has FLAG_NEEDS_ACK
        val remoteHeader = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
            senderId = 444L,
            sessionId = 888L,
            sequenceNumber = 123L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = ByteArray(12),
            ciphertextLength = 0
        )
        val incomingFrame = PacketProtocol.encode(PacketProtocol.Packet(remoteHeader, ByteArray(0), ByteArray(16)))

        engine.onIncomingFrame(incomingFrame)

        // Autonomous Peer ACK should have been sent
        assertEquals(1, sentFrames.size)
        val ack = PacketProtocol.decode(sentFrames[0])
        assertEquals(PacketProtocol.MessageType.ACK, ack.header.messageType)
        assertEquals(888L, ack.header.sessionId)
        assertEquals(123L, ack.header.sequenceNumber)
    }
}
