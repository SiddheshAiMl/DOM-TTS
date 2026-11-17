package com.itantra.speech.compression

import com.itantra.core.model.Language
import com.itantra.core.protocol.PacketProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SemanticCompressionEngineTest {

    @Test
    fun compressAndDecompress_emptyString_returnsEmpty() {
        val compressed = SemanticCompressionEngine.compress("", "hi")
        assertEquals(0, compressed.size)

        val (decompressedText, lang) = SemanticCompressionEngine.decompress(compressed)
        assertEquals("", decompressedText)
        assertEquals("en", lang)
    }

    @Test
    fun compress_hindiUtterance_payloadUnder60Bytes() {
        val hindiUtterance = "नमस्ते तुरंत बेस कैंप पर रिपोर्ट करो"
        val rawBytes = hindiUtterance.toByteArray(Charsets.UTF_8)
        val compressed = SemanticCompressionEngine.compress(hindiUtterance, "hi")

        // Must achieve < 60 bytes payload
        assertTrue("Compressed payload size (${compressed.size}) must be < 60 bytes", compressed.size < 60)
        // With 7 dictionary tokens + 1 lang byte, should be 8 bytes
        assertEquals(8, compressed.size)
        assertTrue("Compression ratio must be > 3x (raw: ${rawBytes.size}, compressed: ${compressed.size})",
            rawBytes.size.toDouble() / compressed.size.toDouble() > 3.0)

        val (decompressedText, lang) = SemanticCompressionEngine.decompress(compressed)
        assertEquals(hindiUtterance, decompressedText)
        assertEquals("hi", lang)
    }

    @Test
    fun compress_bengaliUtterance_compressionRatioExceedsTwoPointFive() {
        val bengaliUtterance = "নমস্কার আমরা নিরাপদ স্থানে আছি"
        val rawBytes = bengaliUtterance.toByteArray(Charsets.UTF_8)
        val compressed = SemanticCompressionEngine.compress(bengaliUtterance, "bn")

        val ratio = rawBytes.size.toDouble() / compressed.size.toDouble()
        assertTrue("Bengali compression ratio ($ratio) must exceed 2.5", ratio > 2.5)

        val (decompressedText, lang) = SemanticCompressionEngine.decompress(compressed)
        assertEquals(bengaliUtterance, decompressedText)
        assertEquals("bn", lang)
    }

    @Test
    fun roundtrip_all10IndicLanguages_lossless() {
        val testPhrases = mapOf(
            "hi" to "नमस्ते तुरंत बेस कैंप पर रिपोर्ट करो",
            "bn" to "নমস্কার আমরা নিরাপদ স্থানে আছি",
            "mr" to "नमस्कार आम्ही सुरक्षित आहोत तात्काळ मदत",
            "te" to "నమస్కారం రవాణా సిద్ధంగా ఉంది సహాయం",
            "ta" to "வணக்கம் உதவி தேவைப்படுகிறது பாதுகாப்பான",
            "gu" to "નમસ્તે અમને મદદની જરૂર છે",
            "kn" to "ನಮಸ್ಕಾರ ತುರ್ತು ಸಹಾಯ ಬೇಕಾಗಿದೆ ಸುರಕ್ಷಿತ",
            "ml" to "നമസ്കാരം സഹായം ആവശ്യമാണ് സുരക്ഷിതമാണ്",
            "or" to "ନମସ୍କାର ଆମେ ସୁରକ୍ଷିତ ଅଛୁ ସାହାଯ୍ୟ",
            "en" to "hello immediate base camp report help needed safe"
        )

        for ((langCode, phrase) in testPhrases) {
            val compressed = SemanticCompressionEngine.compress(phrase, langCode)
            assertTrue("Compressed size for $langCode should be < 60 bytes", compressed.size < 60)
            val (decompressedText, detectedLang) = SemanticCompressionEngine.decompress(compressed)
            assertEquals("Roundtrip mismatch for language $langCode", phrase, decompressedText)
            assertEquals("Detected language code mismatch", langCode, detectedLang)
        }
    }

    @Test
    fun compress_outOfVocabularyIndicWord_fallsBackLosslessly() {
        // Single Indic character not in preset vocabulary
        val oovText = "क"
        val compressed = SemanticCompressionEngine.compress(oovText, "hi")

        // Byte 0: lang (0), Byte 1: token len (3 bytes in UTF-8), Bytes 2..4: UTF-8 bytes for 'क'
        assertEquals(5, compressed.size)
        assertEquals(0.toByte(), compressed[0])
        assertEquals(3.toByte(), compressed[1])

        val (decompressedText, lang) = SemanticCompressionEngine.decompress(compressed)
        assertEquals(oovText, decompressedText)
        assertEquals("hi", lang)
    }

    @Test
    fun compress_mixedAlphanumericAndEmoji_losslessRoundtrip() {
        val mixedText = "Alert 123 🚨 नमस्ते safe"
        val compressed = SemanticCompressionEngine.compress(mixedText, "hi")

        val (decompressedText, lang) = SemanticCompressionEngine.decompress(compressed)
        assertEquals(mixedText, decompressedText)
        assertEquals("hi", lang)
    }

    @Test
    fun decompress_truncatedStream_throwsIllegalArgumentException() {
        // Token claims length 50 but payload has only 2 bytes remaining
        val truncatedPayload = byteArrayOf(0x00, 0x32, 0x01, 0x02)
        try {
            SemanticCompressionEngine.decompress(truncatedPayload)
            fail("Expected IllegalArgumentException on truncated stream")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Truncated") == true)
        }
    }

    @Test
    fun packetProtocol_withFlagCompressed_encodesAndDecodesCorrectly() {
        val sampleText = "नमस्ते सुरक्षित"
        val compressed = SemanticCompressionEngine.compress(sampleText, "hi")

        val header = PacketProtocol.Header(
            version = PacketProtocol.CURRENT_PROTOCOL_VERSION,
            messageType = PacketProtocol.MessageType.TEXT,
            flags = (PacketProtocol.Flags.FLAG_NEEDS_ACK.toInt() or PacketProtocol.Flags.FLAG_COMPRESSED.toInt()).toByte(),
            senderId = 0x0001L,
            sessionId = 0x1234L,
            sequenceNumber = 42L,
            fragmentIndex = 0,
            fragmentCount = 1,
            nonce = ByteArray(12) { it.toByte() },
            ciphertextLength = compressed.size
        )

        val packet = PacketProtocol.Packet(
            header = header,
            ciphertext = compressed,
            tag = ByteArray(16) { 0x55.toByte() }
        )

        val encoded = PacketProtocol.encode(packet)
        val decoded = PacketProtocol.decode(encoded)

        assertEquals(header.flags, decoded.header.flags)
        assertTrue((decoded.header.flags.toInt() and PacketProtocol.Flags.FLAG_COMPRESSED.toInt()) != 0)

        val (decompressedText, lang) = SemanticCompressionEngine.decompress(decoded.ciphertext)
        assertEquals(sampleText, decompressedText)
        assertEquals("hi", lang)
    }
}
