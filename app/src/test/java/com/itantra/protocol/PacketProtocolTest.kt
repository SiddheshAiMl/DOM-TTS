package com.itantra.protocol

import com.itantra.core.protocol.MalformedPacketException
import com.itantra.core.protocol.PacketProtocol
import com.itantra.core.protocol.UnsupportedVersionException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketProtocolTest {

    private val sampleNonce = ByteArray(12) { (it + 1).toByte() }
    private val sampleTag = ByteArray(16) { 0xAA.toByte() }

    @Test
    fun encodeAndDecode_textPacket_matchesExactly() {
        val payload = "Hello iTantra LoRa".toByteArray(Charsets.UTF_8)
        val header = PacketProtocol.Header(
            version = 0x01,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
            senderId = 0x00000001L,
            sessionId = 0x12345678L,
            sequenceNumber = 42L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = sampleNonce,
            ciphertextLength = payload.size
        )
        val packet = PacketProtocol.Packet(
            header = header,
            ciphertext = payload,
            tag = sampleTag
        )

        val encoded = PacketProtocol.encode(packet)
        assertEquals(PacketProtocol.HEADER_AAD_SIZE + payload.size + PacketProtocol.GCM_TAG_SIZE, encoded.size)

        val decoded = PacketProtocol.decode(encoded)
        assertEquals(packet, decoded)
        assertEquals(PacketProtocol.MessageType.TEXT, decoded.header.messageType)
        assertEquals(42L, decoded.header.sequenceNumber)
        assertArrayEquals(payload, decoded.ciphertext)
    }

    @Test
    fun encodeAndDecode_allMessageTypes_succeeds() {
        for (type in PacketProtocol.MessageType.entries) {
            val header = PacketProtocol.Header(
                messageType = type,
                flags = 0,
                senderId = 100L,
                sessionId = 200L,
                sequenceNumber = 1L,
                fragmentIndex = 0,
                fragmentCount = 1,
                nonce = sampleNonce,
                ciphertextLength = 4
            )
            val packet = PacketProtocol.Packet(
                header = header,
                ciphertext = byteArrayOf(1, 2, 3, 4),
                tag = sampleTag
            )
            val decoded = PacketProtocol.decode(PacketProtocol.encode(packet))
            assertEquals(type, decoded.header.messageType)
        }
    }

    @Test
    fun headerAad_isExactly31Bytes_bigEndian() {
        val header = PacketProtocol.Header(
            version = 0x01,
            messageType = PacketProtocol.MessageType.ALERT,
            flags = 0x03,
            senderId = 0x01020304L,
            sessionId = 0x05060708L,
            sequenceNumber = 0x090A0B0CL,
            fragmentIndex = 2,
            fragmentCount = 5,
            nonce = sampleNonce,
            ciphertextLength = 0x0010
        )

        val aad = PacketProtocol.serializeHeaderToAad(header)
        assertEquals(PacketProtocol.HEADER_AAD_SIZE, aad.size)

        // Verify Big-Endian byte layout
        assertEquals(0x01.toByte(), aad[0]) // Version
        assertEquals(PacketProtocol.MessageType.ALERT.code, aad[1]) // Type
        assertEquals(0x03.toByte(), aad[2]) // Flags
        assertEquals(0x01.toByte(), aad[3]) // Sender ID MSB
        assertEquals(0x04.toByte(), aad[6]) // Sender ID LSB
        assertEquals(0x05.toByte(), aad[7]) // Session ID MSB
        assertEquals(0x09.toByte(), aad[11]) // Sequence MSB
        assertEquals(2.toByte(), aad[15]) // Fragment Index
        assertEquals(5.toByte(), aad[16]) // Fragment Count
    }

    @Test
    fun decode_truncatedPacket_throwsMalformedPacketException() {
        val truncated = ByteArray(30) // Less than minimum 47 bytes
        assertThrows(MalformedPacketException::class.java) {
            PacketProtocol.decode(truncated)
        }
    }

    @Test
    fun decode_unsupportedVersion_throwsUnsupportedVersionException() {
        val payload = ByteArray(47)
        payload[0] = 0x99.toByte() // Invalid version
        assertThrows(UnsupportedVersionException::class.java) {
            PacketProtocol.decode(payload)
        }
    }

    @Test
    fun buildAckPacket_hasZeroLengthCiphertext() {
        val ack = PacketProtocol.buildAckPacket(
            senderId = 1L,
            sessionId = 2L,
            ackSequenceNumber = 99L,
            nonce = sampleNonce
        )

        assertEquals(PacketProtocol.MessageType.ACK, ack.header.messageType)
        assertEquals(99L, ack.header.sequenceNumber)
        assertEquals(0, ack.ciphertext.size)
        assertEquals(PacketProtocol.GCM_TAG_SIZE, ack.tag.size)

        val decoded = PacketProtocol.decode(PacketProtocol.encode(ack))
        assertEquals(ack, decoded)
    }
}
