package com.itantra.speech

import com.itantra.speech.vad.EnergyAndSileroVad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EnergyAndSileroVadTest {

    private var vad: EnergyAndSileroVad = EnergyAndSileroVad(silenceEndpointThresholdMs = 600L)

    @Before
    fun setUp() {
        vad = EnergyAndSileroVad(silenceEndpointThresholdMs = 600L)
    }

    @Test
    fun testSilenceChunk_reportsNoSpeech() {
        // 320 samples of 16 kHz audio = 20ms chunk of near silence
        val silentPcm = ShortArray(320) { 5 }
        val state = vad.processPcmChunk(silentPcm)

        assertFalse("Silent frame should not be marked active speech", state.isSpeechActive)
        assertTrue("Speech probability should be low for silence", state.speechProbability < 0.2f)
        assertFalse("Endpoint should not trigger without prior speech", vad.isEndpointReached())
    }

    @Test
    fun testVoiceChunk_reportsSpeechActive() {
        // 400 Hz sine wave sampled at 16 kHz with amplitude 10,000 (typical voiced speech)
        val voicedPcm = ShortArray(320) { i ->
            (10000.0 * sin(2.0 * PI * 400.0 * i / 16000.0)).toInt().toShort()
        }

        val state = vad.processPcmChunk(voicedPcm)

        assertTrue("Voiced frame should be detected as speech", state.isSpeechActive)
        assertTrue("Speech probability should be high", state.speechProbability > 0.8f)
        assertEquals("Silence duration should be reset to 0 during speech", 0L, state.silenceDurationMs)
        assertFalse("Endpoint should not trigger while speaking", vad.isEndpointReached())
    }

    @Test
    fun testEndpointingAfterSustainedSilence() {
        // 1. Speak for 100ms (5 chunks of 20ms)
        val voicedPcm = ShortArray(320) { i ->
            (10000.0 * sin(2.0 * PI * 400.0 * i / 16000.0)).toInt().toShort()
        }
        for (i in 0 until 5) {
            vad.processPcmChunk(voicedPcm)
        }
        assertFalse("Speech active, endpoint not reached", vad.isEndpointReached())

        // 2. Silence for 700ms (35 chunks of 20ms silence, threshold is 600ms)
        val silentPcm = ShortArray(320) { 0 }
        for (i in 0 until 35) {
            vad.processPcmChunk(silentPcm)
        }

        assertTrue("Endpoint should trigger after 700ms silence (>600ms threshold)", vad.isEndpointReached())

        // 3. Reset
        vad.reset()
        assertFalse("After reset, endpoint should be false", vad.isEndpointReached())
    }
}
