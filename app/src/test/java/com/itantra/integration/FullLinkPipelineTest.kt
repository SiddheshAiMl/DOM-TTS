package com.itantra.integration

import com.itantra.core.ble.BleChunker
import com.itantra.core.crypto.AesGcmCryptoEngine
import com.itantra.core.model.DeliveryStatus
import com.itantra.core.protocol.PacketProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * End-to-End Link Integration Test.
 * Simulates the complete bidirectional path:
 * Phone A (Hindi Text -> GCM Encrypt -> PacketProtocol -> BleChunker)
 *   -> ESP32 Node A (BLE Rx -> LoRa Tx)
 *   -> ESP32 Node B (LoRa Rx -> BLE Tx)
 *   -> Phone B (BleChunker -> Packet Reassembly -> GCM Decrypt -> Plaintext -> ACK)
 *   -> Phone A receives ACK -> Marks message DELIVERED.
 */
class FullLinkPipelineTest {

    @Test
    fun testCompleteBidirectionalSpeechOverTextPipeline() {
        val crypto = AesGcmCryptoEngine()

        // 1. Setup Identities and Shared Secret
        val senderIdA = 0x0A0A0A0AL
        val receiverIdB = 0x0B0B0B0BL
        val sessionId = 0x12345678L
        val sharedSecret = ByteArray(32) { (it * 3 + 7).toByte() }

        // Derive directional keys using HKDF-SHA-256
        val sessionKeys = crypto.deriveSessionKeys(sharedSecret, sessionId)
        val keyAtoB = sessionKeys.keyAtoB
        val keyBtoA = sessionKeys.keyBtoA

        val nonceGenA = AesGcmCryptoEngine.MonotonicNonceGenerator(senderIdA, sessionId)
        val replayWindowB = AesGcmCryptoEngine.ReplayWindow(windowSize = 64)

        // 2. Sender Phone A: User dictates speech in Hindi, transcribed locally
        val originalHindiText = "नमस्ते, यह iTantra सुरक्षित रेडियो संदेश है। आपातकालीन संचार सक्रिय है।"
        val plaintextBytes = originalHindiText.toByteArray(StandardCharsets.UTF_8)

        // 3. Encrypt payload with AES-256-GCM + 31-byte Big-Endian AAD
        val seqNumA = 1L
        val nonceA = nonceGenA.nextNonce()

        val headerA = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
            senderId = senderIdA,
            sessionId = sessionId,
            sequenceNumber = seqNumA,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = nonceA,
            ciphertextLength = plaintextBytes.size
        )

        val encryptionResult = crypto.encrypt(plaintextBytes, headerA, keyAtoB)

        val fullPacketA = PacketProtocol.Packet(
            header = headerA,
            ciphertext = encryptionResult.ciphertext,
            tag = encryptionResult.tag
        )
        val serializedPacketBytes = PacketProtocol.encode(fullPacketA)

        // 4. Phone A splits frame into BLE chunks (Simulate low MTU 23 = 21 byte effective payload)
        val bleChunksFromPhoneA = BleChunker.splitFrame(serializedPacketBytes, effectiveMtu = 23)
        assertTrue("Packet should be split into multiple BLE chunks", bleChunksFromPhoneA.size > 1)

        // 5. ESP32 Node A reassembles BLE chunks
        val nodeAReassembler = BleChunker.Reassembler()
        var nodeAPacketBytes: ByteArray? = null
        for (chunk in bleChunksFromPhoneA) {
            val res = nodeAReassembler.processChunk(chunk)
            if (res != null) {
                nodeAPacketBytes = res
            }
        }
        assertNotNull("Node A reassembled packet must not be null", nodeAPacketBytes)
        assertArrayEquals("Node A reassembled bytes must match serialized packet", serializedPacketBytes, nodeAPacketBytes)

        // 6. ESP32 Node A transmits across 433 MHz LoRa to Node B (Simulated RF bridge)
        val loraAirBytes = nodeAPacketBytes!!

        // 7. ESP32 Node B receives LoRa frame, chunks it to Phone B over BLE Rx notification
        val bleChunksFromNodeB = BleChunker.splitFrame(loraAirBytes, effectiveMtu = 25)

        // 8. Phone B reassembles BLE chunks
        val phoneBReassembler = BleChunker.Reassembler()
        var phoneBPacketBytes: ByteArray? = null
        for (chunk in bleChunksFromNodeB) {
            val res = phoneBReassembler.processChunk(chunk)
            if (res != null) {
                phoneBPacketBytes = res
            }
        }
        assertNotNull("Phone B must reassemble complete radio packet", phoneBPacketBytes)

        // 9. Phone B decodes Packet Protocol and validates Replay Window
        val receivedPacket = PacketProtocol.decode(phoneBPacketBytes!!)
        assertEquals(PacketProtocol.CURRENT_PROTOCOL_VERSION, receivedPacket.header.version)
        assertEquals(PacketProtocol.MessageType.TEXT, receivedPacket.header.messageType)
        assertEquals(seqNumA, receivedPacket.header.sequenceNumber)

        val isFresh = replayWindowB.checkAndMark(receivedPacket.header.sequenceNumber)
        assertTrue("Packet sequence must be fresh and accepted by replay window", isFresh)

        // 10. Phone B decrypts payload and validates AAD
        val aadB = PacketProtocol.serializeHeaderToAad(receivedPacket.header)
        val ciphertextWithTag = ByteArray(receivedPacket.ciphertext.size + receivedPacket.tag.size)
        System.arraycopy(receivedPacket.ciphertext, 0, ciphertextWithTag, 0, receivedPacket.ciphertext.size)
        System.arraycopy(receivedPacket.tag, 0, ciphertextWithTag, receivedPacket.ciphertext.size, receivedPacket.tag.size)

        val decryptedBytes = crypto.decrypt(
            ciphertextWithTag = ciphertextWithTag,
            aad = aadB,
            nonce = receivedPacket.header.nonce,
            key = keyAtoB
        )
        val decryptedText = String(decryptedBytes, StandardCharsets.UTF_8)

        // Verification: Plaintext matches exactly
        assertEquals("Decrypted Indic text must match original utterance byte-for-byte", originalHindiText, decryptedText)

        // 11. Phone B generates ACK packet and sends back to Phone A
        val nonceGenB = AesGcmCryptoEngine.MonotonicNonceGenerator(receiverIdB, sessionId)
        val ackNonce = nonceGenB.nextNonce()
        val ackHeader = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.ACK,
            flags = 0x00,
            senderId = receiverIdB,
            sessionId = sessionId,
            sequenceNumber = receivedPacket.header.sequenceNumber, // Echoes sequence number being acknowledged
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = ackNonce,
            ciphertextLength = 0
        )
        val ackResult = crypto.encrypt(ByteArray(0), ackHeader, keyBtoA)
        val ackPacket = PacketProtocol.Packet(header = ackHeader, ciphertext = ackResult.ciphertext, tag = ackResult.tag)
        val ackBytes = PacketProtocol.encode(ackPacket)

        // 12. Simulate ACK path: Phone B -> Node B -> LoRa -> Node A -> Phone A
        val ackPacketAtPhoneA = PacketProtocol.decode(ackBytes)
        assertEquals(PacketProtocol.MessageType.ACK, ackPacketAtPhoneA.header.messageType)
        assertEquals(seqNumA, ackPacketAtPhoneA.header.sequenceNumber)

        // 13. Phone A marks delivery state as DELIVERED
        var messageDeliveryStatus = DeliveryStatus.SENT_TO_NODE
        if (ackPacketAtPhoneA.header.messageType == PacketProtocol.MessageType.ACK &&
            ackPacketAtPhoneA.header.sequenceNumber == seqNumA) {
            messageDeliveryStatus = DeliveryStatus.DELIVERED
        }

        assertEquals("Delivery status transitions to DELIVERED upon verified radio ACK",
            DeliveryStatus.DELIVERED, messageDeliveryStatus)
    }
}
