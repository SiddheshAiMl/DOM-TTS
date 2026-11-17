package com.itantra.core.pairing

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class PairingPayload(
    val nodeId: Long,
    val sessionId: Long,
    val masterSecret: ByteArray,
    val verificationCode: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PairingPayload
        return nodeId == other.nodeId &&
                sessionId == other.sessionId &&
                masterSecret.contentEquals(other.masterSecret) &&
                verificationCode == other.verificationCode
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + masterSecret.contentHashCode()
        result = 31 * result + verificationCode.hashCode()
        return result
    }
}

object PairingManager {
    private const val PREFIX = "ITANTRA:v1"
    private val random = SecureRandom()

    /**
     * Generates a fresh 256-bit pairing secret and session configuration.
     */
    fun createPairingPayload(localNodeId: Long): PairingPayload {
        val masterSecret = ByteArray(32)
        random.nextBytes(masterSecret)
        val sessionId = random.nextLong() and 0x7FFFFFFFL
        val code = computeVerificationCode(masterSecret, sessionId)

        return PairingPayload(
            nodeId = localNodeId,
            sessionId = sessionId,
            masterSecret = masterSecret,
            verificationCode = code
        )
    }

    /**
     * Computes a 6-digit human-verifiable confirmation code using HMAC-SHA-256.
     */
    fun computeVerificationCode(masterSecret: ByteArray, sessionId: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterSecret, "HmacSHA256"))
        val sessionBytes = ByteBuffer.allocate(8).putLong(sessionId).array()
        val hash = mac.doFinal("iTantra-pairing-code-".toByteArray(Charsets.UTF_8) + sessionBytes)

        val value = ((hash[0].toInt() and 0x7F) shl 24) or
                ((hash[1].toInt() and 0xFF) shl 16) or
                ((hash[2].toInt() and 0xFF) shl 8) or
                (hash[3].toInt() and 0xFF)

        val codeNum = value % 1_000_000
        return "%06d".format(codeNum)
    }

    /**
     * Serializes pairing payload to a QR code string.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun serializeToQrString(payload: PairingPayload): String {
        val secretB64 = Base64.Default.encode(payload.masterSecret)
        return "$PREFIX:${payload.nodeId}:${payload.sessionId}:$secretB64:${payload.verificationCode}"
    }

    /**
     * Parses and validates a QR code string.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun parseFromQrString(qrString: String): PairingPayload? {
        val parts = qrString.split(":")
        if (parts.size != 6 || parts[0] != "ITANTRA" || parts[1] != "v1") {
            return null
        }

        return try {
            val nodeId = parts[2].toLong()
            val sessionId = parts[3].toLong()
            val masterSecret = Base64.Default.decode(parts[4])
            val expectedCode = parts[5]

            val computedCode = computeVerificationCode(masterSecret, sessionId)
            if (computedCode != expectedCode) {
                return null // Verification code mismatch
            }

            PairingPayload(
                nodeId = nodeId,
                sessionId = sessionId,
                masterSecret = masterSecret,
                verificationCode = computedCode
            )
        } catch (_: Exception) {
            null
        }
    }
}
