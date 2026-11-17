package com.itantra.pairing

import com.itantra.core.pairing.PairingManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {

    @Test
    fun createAndSerializePairingPayload_succeeds() {
        val payload = PairingManager.createPairingPayload(localNodeId = 0x0001L)
        assertEquals(0x0001L, payload.nodeId)
        assertEquals(32, payload.masterSecret.size)
        assertEquals(6, payload.verificationCode.length)
        assertTrue(payload.verificationCode.all { it.isDigit() })

        val qrString = PairingManager.serializeToQrString(payload)
        assertTrue(qrString.startsWith("ITANTRA:v1:1:"))

        val parsed = PairingManager.parseFromQrString(qrString)
        assertNotNull(parsed)
        assertEquals(payload.nodeId, parsed!!.nodeId)
        assertEquals(payload.sessionId, parsed.sessionId)
        assertArrayEquals(payload.masterSecret, parsed.masterSecret)
        assertEquals(payload.verificationCode, parsed.verificationCode)
    }

    @Test
    fun parseFromQrString_rejectsTamperedCode() {
        val payload = PairingManager.createPairingPayload(localNodeId = 0x0002L)
        val validQr = PairingManager.serializeToQrString(payload)

        // Mutate the verification code at the end
        val parts = validQr.split(":").toMutableList()
        parts[5] = "000000" // Wrong code
        val tamperedQr = parts.joinToString(":")

        val result = PairingManager.parseFromQrString(tamperedQr)
        assertNull("Tampered verification code must be rejected", result)
    }

    @Test
    fun verificationCode_isDeterministicForSameSecretAndSession() {
        val secret = ByteArray(32) { 0x42 }
        val sessionId = 987654321L

        val code1 = PairingManager.computeVerificationCode(secret, sessionId)
        val code2 = PairingManager.computeVerificationCode(secret, sessionId)
        assertEquals(code1, code2)
        assertEquals(6, code1.length)
    }
}
