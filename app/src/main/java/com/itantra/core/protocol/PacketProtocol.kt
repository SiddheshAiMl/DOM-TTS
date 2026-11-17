package com.itantra.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary Packet Protocol for iTantra over BLE and LoRa.
 *
 * Frame layout (Big-Endian):
 * [0..0]   Protocol Version (1 Byte): 0x01
 * [1..1]   Message Type (1 Byte): TEXT, ALERT, ACK, PING, PONG, FRAGMENT, STATUS
 * [2..2]   Flags (1 Byte): Bit 0: Urgent, Bit 1: Needs ACK, Bit 2: Compressed
 * [3..6]   Sender ID (4 Bytes): UInt32
 * [7..10]  Session ID (4 Bytes): UInt32
 * [11..14] Sequence Number (4 Bytes): UInt32
 * [15..15] Fragment Index (1 Byte): 0-based
 * [16..16] Fragment Count (1 Byte): >= 1
 * [17..28] Nonce (12 Bytes): Unique AES-GCM nonce
 * [29..30] Ciphertext Length (2 Bytes): UInt16
 * [31..N]  Ciphertext (Variable Length)
 * [N+1..N+16] GCM Authentication Tag (16 Bytes)
 *
 * Bytes [0..30] (31 bytes) are authenticated as AES-GCM AAD.
 */
object PacketProtocol {
    const val CURRENT_PROTOCOL_VERSION: Byte = 0x01
    const val HEADER_AAD_SIZE = 31
    const val NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16
    const val MIN_PACKET_SIZE = HEADER_AAD_SIZE + GCM_TAG_SIZE // 47 bytes (0-length ciphertext)
    const val MAX_PACKET_SIZE = 255 // Maximum single-frame LoRa PHY envelope payload

    enum class MessageType(val code: Byte) {
        TEXT(0x01),
        ALERT(0x02),
        ACK(0x03),
        PING(0x04),
        PONG(0x05),
        FRAGMENT(0x06),
        STATUS(0x07);

        companion object {
            fun fromCode(code: Byte): MessageType =
                entries.firstOrNull { it.code == code }
                    ?: throw MalformedPacketException("Unknown message type: 0x%02X".format(code))
        }
    }

    object Flags {
        const val FLAG_URGENT: Byte = 0x01
        const val FLAG_NEEDS_ACK: Byte = 0x02
        const val FLAG_COMPRESSED: Byte = 0x04
    }

    data class Header(
        val version: Byte = CURRENT_PROTOCOL_VERSION,
        val messageType: MessageType,
        val flags: Byte,
        val senderId: Long,
        val sessionId: Long,
        val sequenceNumber: Long,
        val fragmentIndex: Int = 0,
        val fragmentCount: Int = 1,
        val nonce: ByteArray,
        val ciphertextLength: Int
    ) {
        init {
            require(nonce.size == NONCE_SIZE) { "Nonce must be exactly $NONCE_SIZE bytes" }
            require(fragmentIndex in 0 until fragmentCount) { "Invalid fragment index $fragmentIndex of $fragmentCount" }
            require(ciphertextLength >= 0) { "Ciphertext length cannot be negative" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Header
            return version == other.version &&
                    messageType == other.messageType &&
                    flags == other.flags &&
                    senderId == other.senderId &&
                    sessionId == other.sessionId &&
                    sequenceNumber == other.sequenceNumber &&
                    fragmentIndex == other.fragmentIndex &&
                    fragmentCount == other.fragmentCount &&
                    nonce.contentEquals(other.nonce) &&
                    ciphertextLength == other.ciphertextLength
        }

        override fun hashCode(): Int {
            var result = version.toInt()
            result = 31 * result + messageType.hashCode()
            result = 31 * result + flags.toInt()
            result = 31 * result + senderId.hashCode()
            result = 31 * result + sessionId.hashCode()
            result = 31 * result + sequenceNumber.hashCode()
            result = 31 * result + fragmentIndex
            result = 31 * result + fragmentCount
            result = 31 * result + nonce.contentHashCode()
            result = 31 * result + ciphertextLength
            return result
        }
    }

    data class Packet(
        val header: Header,
        val ciphertext: ByteArray,
        val tag: ByteArray
    ) {
        init {
            require(tag.size == GCM_TAG_SIZE) { "Tag must be exactly $GCM_TAG_SIZE bytes" }
            require(ciphertext.size == header.ciphertextLength) {
                "Ciphertext size (${ciphertext.size}) does not match header length (${header.ciphertextLength})"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Packet
            return header == other.header &&
                    ciphertext.contentEquals(other.ciphertext) &&
                    tag.contentEquals(other.tag)
        }

        override fun hashCode(): Int {
            var result = header.hashCode()
            result = 31 * result + ciphertext.contentHashCode()
            result = 31 * result + tag.contentHashCode()
            return result
        }
    }

    /**
     * Serializes the 31-byte header into Additional Authenticated Data (AAD) for AES-GCM.
     */
    fun serializeHeaderToAad(header: Header): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_AAD_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header.version)
        buffer.put(header.messageType.code)
        buffer.put(header.flags)
        buffer.putInt((header.senderId and 0xFFFFFFFFL).toInt())
        buffer.putInt((header.sessionId and 0xFFFFFFFFL).toInt())
        buffer.putInt((header.sequenceNumber and 0xFFFFFFFFL).toInt())
        buffer.put(header.fragmentIndex.toByte())
        buffer.put(header.fragmentCount.toByte())
        buffer.put(header.nonce)
        buffer.putShort(header.ciphertextLength.toShort())
        return buffer.array()
    }

    /**
     * Encodes a complete Packet into a binary byte array.
     */
    fun encode(packet: Packet): ByteArray {
        val aad = serializeHeaderToAad(packet.header)
        val totalSize = aad.size + packet.ciphertext.size + packet.tag.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(aad)
        buffer.put(packet.ciphertext)
        buffer.put(packet.tag)
        return buffer.array()
    }

    /**
     * Decodes a binary byte array into a verified Packet.
     */
    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < MIN_PACKET_SIZE) {
            throw MalformedPacketException("Packet size (${bytes.size} bytes) is less than minimum $MIN_PACKET_SIZE bytes")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        if (version != CURRENT_PROTOCOL_VERSION) {
            throw UnsupportedVersionException("Unsupported protocol version: $version. Expected: $CURRENT_PROTOCOL_VERSION")
        }

        val messageType = MessageType.fromCode(buffer.get())
        val flags = buffer.get()
        val senderId = buffer.int.toLong() and 0xFFFFFFFFL
        val sessionId = buffer.int.toLong() and 0xFFFFFFFFL
        val sequenceNumber = buffer.int.toLong() and 0xFFFFFFFFL
        val fragmentIndex = buffer.get().toInt() and 0xFF
        val fragmentCount = buffer.get().toInt() and 0xFF
        val nonce = ByteArray(NONCE_SIZE)
        buffer.get(nonce)
        val ciphertextLength = buffer.short.toInt() and 0xFFFF

        val expectedTotalSize = HEADER_AAD_SIZE + ciphertextLength + GCM_TAG_SIZE
        if (bytes.size != expectedTotalSize) {
            throw MalformedPacketException(
                "Packet total size (${bytes.size}) does not match expected length ($expectedTotalSize) with ciphertext length $ciphertextLength"
            )
        }

        val ciphertext = ByteArray(ciphertextLength)
        buffer.get(ciphertext)

        val tag = ByteArray(GCM_TAG_SIZE)
        buffer.get(tag)

        val header = Header(
            version = version,
            messageType = messageType,
            flags = flags,
            senderId = senderId,
            sessionId = sessionId,
            sequenceNumber = sequenceNumber,
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            nonce = nonce,
            ciphertextLength = ciphertextLength
        )

        return Packet(header, ciphertext, tag)
    }

    /**
     * Builds an Acknowledgement (ACK) packet for a received sequence number.
     */
    fun buildAckPacket(
        senderId: Long,
        sessionId: Long,
        ackSequenceNumber: Long,
        nonce: ByteArray
    ): Packet {
        val header = Header(
            messageType = MessageType.ACK,
            flags = 0,
            senderId = senderId,
            sessionId = sessionId,
            sequenceNumber = ackSequenceNumber,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = nonce,
            ciphertextLength = 0
        )
        // ACK packets have empty ciphertext and a zeroed or authenticated dummy tag
        return Packet(header, ByteArray(0), ByteArray(GCM_TAG_SIZE))
    }
}

class MalformedPacketException(message: String) : Exception(message)
class UnsupportedVersionException(message: String) : Exception(message)
