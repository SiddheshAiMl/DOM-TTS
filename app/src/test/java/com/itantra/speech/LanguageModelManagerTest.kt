package com.itantra.speech

import com.itantra.core.model.Language
import com.itantra.speech.manager.LanguageModelManager
import com.itantra.speech.manager.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LanguageModelManagerTest {

    private var modelManager: LanguageModelManager = LanguageModelManager()

    @Before
    fun setUp() {
        modelManager = LanguageModelManager()
    }

    @Test
    fun testAll10TargetLanguagesConfigured() {
        val expectedLanguages = setOf(
            Language.HINDI,
            Language.GUJARATI,
            Language.MARATHI,
            Language.KANNADA,
            Language.MALAYALAM,
            Language.TAMIL,
            Language.TELUGU,
            Language.ODIA,
            Language.BENGALI,
            Language.ENGLISH
        )

        val configuredLanguages = modelManager.languagePacks.value.map { it.language }.toSet()

        assertEquals("Exactly 10 languages must be configured", 10, configuredLanguages.size)
        assertEquals("Target language set must match SIH requirements", expectedLanguages, configuredLanguages)
    }

    @Test
    fun testLicenseAndProvenanceMetadata() {
        val packs = modelManager.languagePacks.value

        for (pack in packs) {
            assertTrue("License must not be empty for ${pack.language}", pack.license.isNotBlank())
            assertTrue("Provenance must credit source for ${pack.language}", pack.provenance.isNotBlank())
            assertEquals("Expected SHA-256 must be 64 hex characters for ${pack.language}", 64, pack.expectedSha256.length)
            assertTrue("STT model must end with .onnx", pack.sttModelName.endsWith(".onnx"))
            assertTrue("TTS model must end with .onnx", pack.ttsModelName.endsWith(".onnx"))
            assertTrue("Estimated size must be > 100 MB", pack.estimatedSizeBytes > 100_000_000L)
        }
    }

    @Test
    fun testDemoPackReadiness() {
        // Hindi and English are pre-configured as Ready in demo mode
        assertTrue("Hindi should be ready in demo mode", modelManager.isLanguageReady(Language.HINDI))
        assertTrue("English should be ready in demo mode", modelManager.isLanguageReady(Language.ENGLISH))
        assertTrue("Hindi checksum verification in demo mode", modelManager.verifyChecksum(Language.HINDI))

        // Others start as NOT_INSTALLED
        val gujaratiPack = modelManager.languagePacks.value.first { it.language == Language.GUJARATI }
        assertEquals("Gujarati starts as not installed until downloaded", ModelStatus.NOT_INSTALLED, gujaratiPack.status)
    }
}
