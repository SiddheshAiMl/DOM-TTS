package com.itantra.speech.api

import com.itantra.core.model.Language
import kotlinx.coroutines.flow.StateFlow

data class TranscriptState(
    val partialText: String = "",
    val finalText: String = "",
    val isFinalized: Boolean = false,
    val isListening: Boolean = false,
    val audioEnergyLevel: Float = 0f
)

data class VadState(
    val isSpeechActive: Boolean = false,
    val speechProbability: Float = 0f,
    val silenceDurationMs: Long = 0L
)

interface SpeechRecognizerEngine {
    val transcriptState: StateFlow<TranscriptState>
    suspend fun startListening(language: Language)
    suspend fun stopListening(): String
    fun cancel()
}

interface TextToSpeechEngine {
    val isSpeaking: StateFlow<Boolean>
    val currentSpokenText: StateFlow<String?>
    suspend fun synthesizeAndPlay(text: String, language: Language)
    fun stop()
}

interface VoiceActivityDetector {
    val vadState: StateFlow<VadState>
    fun processPcmChunk(pcmData: ShortArray): VadState
    fun reset()
}
