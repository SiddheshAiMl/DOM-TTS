package com.itantra.core.crypto

import com.itantra.core.protocol.PacketProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface CryptoEngine {
    fun encrypt(
        plaintext: ByteArray,
        header: PacketProtocol.Header,
        key: ByteArray
    ): EncryptionResult

    fun decrypt(
        ciphertextWithTag: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray

    fun deriveSessionKeys(
        masterSecret: ByteArray,
        sessionId: Long
    ): SessionKeys
}

data class EncryptionResult(
    val ciphertext: ByteArray,
    val tag: ByteArray,
    val ciphertextWithTag: ByteArray
)

data class SessionKeys(
    val keyAtoB: ByteArray,
    val keyBtoA: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SessionKeys
        return keyAtoB.contentEquals(other.keyAtoB) && keyBtoA.contentEquals(other.keyBtoA)
    }

    override fun hashCode(): Int {
        var result = keyAtoB.contentHashCode()
        result = 31 * result + keyBtoA.contentHashCode()
        return result
    }
}

/**
 * Standard-compliant AES-256-GCM and HKDF-SHA-256 implementation.
 */
class AesGcmCryptoEngine(
    private val random: SecureRandom = SecureRandom()
) : CryptoEngine {

    companion object {
        const val KEY_SIZE_BYTES = 32 // AES-256
        const val GCM_TAG_LENGTH_BITS = 128 // 16 bytes
        const val GCM_TAG_LENGTH_BYTES = 16
        const val NONCE_SIZE_BYTES = 12 // 96 bits
    }

    /**
     * Generates a 96-bit monotonic nonce ensuring zero collision under the same key.
     * Nonce layout: [SenderId (4B)] [SessionId (4B)] [MonotonicCounter (4B)]
     */
    class MonotonicNonceGenerator(
        private val senderId: Long,
        private val sessionId: Long,
        initialCounter: Long = 1L
    ) {
        private val counter = AtomicLong(initialCounter)

        fun nextNonce(): ByteArray {
            val seq = counter.getAndIncrement()
            val buffer = ByteBuffer.allocate(NONCE_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt((senderId and 0xFFFFFFFFL).toInt())
            buffer.putInt((sessionId and 0xFFFFFFFFL).toInt())
            buffer.putInt((seq and 0xFFFFFFFFL).toInt())
            return buffer.array()
        }

        fun currentSequence(): Long = counter.get()
    }

    override fun encrypt(
        plaintext: ByteArray,
        header: PacketProtocol.Header,
        key: ByteArray
    ): EncryptionResult {
        require(key.size == KEY_SIZE_BYTES) { "AES-256 key must be exactly $KEY_SIZE_BYTES bytes" }
        require(header.nonce.size == NONCE_SIZE_BYTES) { "Nonce must be exactly $NONCE_SIZE_BYTES bytes" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, header.nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        // Bind the header as Additional Authenticated Data (AAD)
        val aad = PacketProtocol.serializeHeaderToAad(header)
        cipher.updateAAD(aad)

        val ciphertextWithTag = cipher.doFinal(plaintext)
        val ciphertextLen = ciphertextWithTag.size - GCM_TAG_LENGTH_BYTES
        val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextLen)
        val tag = ciphertextWithTag.copyOfRange(ciphertextLen, ciphertextWithTag.size)

        return EncryptionResult(ciphertext, tag, ciphertextWithTag)
    }

    override fun decrypt(
        ciphertextWithTag: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "AES-256 key must be exactly $KEY_SIZE_BYTES bytes" }
        require(nonce.size == NONCE_SIZE_BYTES) { "Nonce must be exactly $NONCE_SIZE_BYTES bytes" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        // Validate AAD against tag
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * HKDF-SHA-256 Key Derivation (RFC 5869)
     */
    override fun deriveSessionKeys(
        masterSecret: ByteArray,
        sessionId: Long
    ): SessionKeys {
        val salt = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(sessionId).array()
        val prk = hkdfExtract(salt, masterSecret)

        val keyAtoB = hkdfExpand(prk, "iTantra-v1-A2B".toByteArray(Charsets.UTF_8), KEY_SIZE_BYTES)
        val keyBtoA = hkdfExpand(prk, "iTantra-v1-B2A".toByteArray(Charsets.UTF_8), KEY_SIZE_BYTES)

        return SessionKeys(keyAtoB, keyBtoA)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = SecretKeySpec(if (salt.isNotEmpty()) salt else ByteArray(32), "HmacSHA256")
        mac.init(saltKey)
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1

        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()

            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            i++
        }

        return result
    }

    /**
     * Sliding Replay Window of width 64 packets.
     */
    class ReplayWindow(val windowSize: Int = 64) {
        private var highestSeq: Long = 0L
        private var bitmap: Long = 0L

        @Synchronized
        fun checkAndMark(sequenceNumber: Long): Boolean {
            if (sequenceNumber <= 0L) return false

            if (sequenceNumber > highestSeq) {
                val diff = sequenceNumber - highestSeq
                bitmap = if (diff >= windowSize) {
                    1L
                } else {
                    (bitmap shl diff.toInt()) or 1L
                }
                highestSeq = sequenceNumber
                return true
            }

            val diff = highestSeq - sequenceNumber
            if (diff >= windowSize) {
                // Too old, rejected
                return false
            }

            val mask = 1L shl diff.toInt()
            if ((bitmap and mask) != 0L) {
                // Already seen, duplicate rejected
                return false
            }

            // Mark sequence as seen
            bitmap = bitmap or mask
            return true
        }

        @Synchronized
        fun reset() {
            highestSeq = 0L
            bitmap = 0L
        }
    }
}
