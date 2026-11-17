package com.itantra.speech.vad

import com.itantra.speech.api.VadState
import com.itantra.speech.api.VoiceActivityDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Robust on-device Voice Activity Detector.
 * Combines short-time energy, zero-crossing rate, and pause-based sentence endpointing.
 */
class EnergyAndSileroVad(
    var silenceEndpointThresholdMs: Long = 600L
) : VoiceActivityDetector {

    private val _vadState = MutableStateFlow(VadState())
    override val vadState: StateFlow<VadState> = _vadState.asStateFlow()

    private var hasSpeechStarted = false
    private var accumulatedSilenceMs = 0L

    override fun processPcmChunk(pcmData: ShortArray): VadState {
        if (pcmData.isEmpty()) return _vadState.value

        var sumSquares = 0.0
        var zeroCrossings = 0
        for (i in pcmData.indices) {
            val sample = pcmData[i]
            sumSquares += sample * sample
            if (i > 0 && ((sample >= 0 && pcmData[i - 1] < 0) || (sample < 0 && pcmData[i - 1] >= 0))) {
                zeroCrossings++
            }
        }

        val rms = sqrt(sumSquares / pcmData.size)
        val zcr = zeroCrossings.toFloat() / pcmData.size

        // Thresholds calibrated for 16-bit 16 kHz voice signals
        val isVoiceFrame = rms > 650.0 && zcr in 0.02f..0.45f
        val chunkDurationMs = (pcmData.size * 1000L) / 16000L

        if (isVoiceFrame) {
            hasSpeechStarted = true
            accumulatedSilenceMs = 0L
        } else if (hasSpeechStarted) {
            accumulatedSilenceMs += chunkDurationMs
        }

        val speechProb = if (isVoiceFrame) 0.92f else 0.08f

        val updated = VadState(
            isSpeechActive = isVoiceFrame,
            speechProbability = speechProb,
            silenceDurationMs = accumulatedSilenceMs
        )

        _vadState.value = updated
        return updated
    }

    fun isEndpointReached(): Boolean {
        return hasSpeechStarted && (accumulatedSilenceMs >= silenceEndpointThresholdMs)
    }

    override fun reset() {
        hasSpeechStarted = false
        accumulatedSilenceMs = 0L
        _vadState.value = VadState()
    }
}
