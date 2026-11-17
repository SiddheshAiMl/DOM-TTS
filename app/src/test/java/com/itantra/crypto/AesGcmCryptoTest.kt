package com.itantra.crypto

import com.itantra.core.crypto.AesGcmCryptoEngine
import com.itantra.core.protocol.PacketProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

class AesGcmCryptoTest {

    private val crypto = AesGcmCryptoEngine()
    private val masterSecret = "iTantra-Master-Pairing-Secret-32".toByteArray(Charsets.UTF_8)
    private val sessionId = 0x55AA1122L

    @Test
    fun encryptAndDecrypt_roundTrip_matchesPlaintext() {
        val keys = crypto.deriveSessionKeys(masterSecret, sessionId)
        val plaintext = "Secure tactical LoRa payload at 433 MHz".toByteArray(Charsets.UTF_8)

        val nonce = ByteArray(12) { 0x07 }
        val header = PacketProtocol.Header(
            messageType = PacketProtocol.MessageType.TEXT,
            flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
            senderId = 1L,
            sessionId = sessionId,
            sequenceNumber = 100L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = nonce,
            ciphertextLength = plaintext.size
        )

        val encResult = crypto.encrypt(plaintext, header, keys.keyAtoB)
        assertEquals(plaintext.size, encResult.ciphertext.size)
        assertEquals(AesGcmCryptoEngine.GCM_TAG_LENGTH_BYTES, encResult.tag.size)

        val aad = PacketProtocol.serializeHeaderToAad(header)
        val decrypted = crypto.decrypt(encResult.ciphertextWithTag, aad, nonce, keys.keyAtoB)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_withTamperedAad_failsAuthentication() {
        val keys = crypto.deriveSessionKeys(masterSecret, sessionId)
        val plaintext = "Tamper detection test".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12) { 0x05 }
        val header = PacketProtocol.Header(
            messageType = PacketProtocol.MessageType.TEXT,
            flags = 0,
            senderId = 1L,
            sessionId = sessionId,
            sequenceNumber = 1L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = nonce,
            ciphertextLength = plaintext.size
        )

        val enc = crypto.encrypt(plaintext, header, keys.keyAtoB)
        val tamperedAad = PacketProtocol.serializeHeaderToAad(header)
        tamperedAad[1] = PacketProtocol.MessageType.ALERT.code // Mutate message type

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decrypt(enc.ciphertextWithTag, tamperedAad, nonce, keys.keyAtoB)
        }
    }

    @Test
    fun decrypt_withTamperedCiphertext_failsAuthentication() {
        val keys = crypto.deriveSessionKeys(masterSecret, sessionId)
        val plaintext = "Integrity test".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12) { 0x01 }
        val header = PacketProtocol.Header(
            messageType = PacketProtocol.MessageType.TEXT,
            flags = 0,
            senderId = 1L,
            sessionId = sessionId,
            sequenceNumber = 2L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = nonce,
            ciphertextLength = plaintext.size
        )

        val enc = crypto.encrypt(plaintext, header, keys.keyAtoB)
        val tamperedCiphertextWithTag = enc.ciphertextWithTag.copyOf()
        tamperedCiphertextWithTag[0] = (tamperedCiphertextWithTag[0].toInt() xor 0xFF).toByte()

        val aad = PacketProtocol.serializeHeaderToAad(header)
        assertThrows(GeneralSecurityException::class.java) {
            crypto.decrypt(tamperedCiphertextWithTag, aad, nonce, keys.keyAtoB)
        }
    }

    @Test
    fun directionalKeys_areDistinct() {
        val keys = crypto.deriveSessionKeys(masterSecret, sessionId)
        assertFalse(keys.keyAtoB.contentEquals(keys.keyBtoA))

        val plaintext = "Directional separation test".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12) { 0x02 }
        val header = PacketProtocol.Header(
            messageType = PacketProtocol.MessageType.TEXT,
            flags = 0,
            senderId = 1L,
            sessionId = sessionId,
            sequenceNumber = 3L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = nonce,
            ciphertextLength = plaintext.size
        )

        val enc = crypto.encrypt(plaintext, header, keys.keyAtoB)
        val aad = PacketProtocol.serializeHeaderToAad(header)

        // Attempt decrypting with opposite directional key B->A must fail
        assertThrows(GeneralSecurityException::class.java) {
            crypto.decrypt(enc.ciphertextWithTag, aad, nonce, keys.keyBtoA)
        }
    }

    @Test
    fun monotonicNonceGenerator_guaranteesZeroCollision() {
        val generator = AesGcmCryptoEngine.MonotonicNonceGenerator(
            senderId = 0x0001L,
            sessionId = 0x1234L,
            initialCounter = 1L
        )

        val seenNonces = HashSet<String>()
        val count = 10_000

        for (i in 1..count) {
            val nonce = generator.nextNonce()
            val hex = nonce.joinToString("") { "%02x".format(it) }
            val added = seenNonces.add(hex)
            assertTrue("Nonce collision detected at iteration $i", added)
        }

        assertEquals(count, seenNonces.size)
    }

    @Test
    fun replayWindow_detectsDuplicatesAndStalePackets() {
        val window = AesGcmCryptoEngine.ReplayWindow(windowSize = 64)

        // In-order packets
        assertTrue(window.checkAndMark(1L))
        assertTrue(window.checkAndMark(2L))
        assertTrue(window.checkAndMark(3L))

        // Duplicate rejection
        assertFalse("Duplicate sequence 2 must be rejected", window.checkAndMark(2L))
        assertFalse("Duplicate sequence 3 must be rejected", window.checkAndMark(3L))

        // Out-of-order within window
        assertTrue(window.checkAndMark(10L))
        assertTrue(window.checkAndMark(5L))
        assertFalse(window.checkAndMark(5L)) // Second time rejected

        // Stale packet older than window (current highest: 10, window 64 -> diff <= 64 accepted)
        assertTrue(window.checkAndMark(80L)) // Shifts window, highest = 80
        assertFalse("Packet 1 is now outside the 64-window horizon (80-64 = 16)", window.checkAndMark(1L))
    }
}
