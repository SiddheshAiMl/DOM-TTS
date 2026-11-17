package com.itantra.speech.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.itantra.core.model.Language
import com.itantra.speech.api.SpeechRecognizerEngine
import com.itantra.speech.api.TranscriptState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production on-device Speech-To-Text engine using Android's SpeechRecognizer API.
 * Configured with EXTRA_PREFER_OFFLINE and streaming partial transcript support.
 */
class AndroidSpeechRecognizerEngine(
    private val context: Context
) : SpeechRecognizerEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _transcriptState = MutableStateFlow(TranscriptState())
    override val transcriptState: StateFlow<TranscriptState> = _transcriptState.asStateFlow()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _transcriptState.value = _transcriptState.value.copy(
                isListening = true,
                isFinalized = false
            )
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _transcriptState.value = _transcriptState.value.copy(audioEnergyLevel = normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _transcriptState.value = _transcriptState.value.copy(isListening = false)
        }

        override fun onError(error: Int) {
            _transcriptState.value = _transcriptState.value.copy(
                isListening = false,
                isFinalized = true
            )
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull() ?: _transcriptState.value.partialText
            _transcriptState.value = _transcriptState.value.copy(
                finalText = recognizedText,
                partialText = recognizedText,
                isListening = false,
                isFinalized = true,
                audioEnergyLevel = 0f
            )
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: return
            _transcriptState.value = _transcriptState.value.copy(
                partialText = partial,
                isListening = true
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override suspend fun startListening(language: Language) {
        mainHandler.post {
            cancel()
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(listener)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLanguageTag(language))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                _transcriptState.value = TranscriptState(
                    partialText = "",
                    finalText = "",
                    isFinalized = false,
                    isListening = true,
                    audioEnergyLevel = 0f
                )

                recognizer?.startListening(intent)
            }
        }
    }

    override suspend fun stopListening(): String {
        mainHandler.post {
            recognizer?.stopListening()
        }
        return _transcriptState.value.finalText.ifBlank { _transcriptState.value.partialText }
    }

    override fun cancel() {
        mainHandler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            _transcriptState.value = _transcriptState.value.copy(
                isListening = false,
                audioEnergyLevel = 0f
            )
        }
    }

    private fun getLanguageTag(language: Language): String = when (language) {
        Language.HINDI -> "hi-IN"
        Language.GUJARATI -> "gu-IN"
        Language.MARATHI -> "mr-IN"
        Language.KANNADA -> "kn-IN"
        Language.MALAYALAM -> "ml-IN"
        Language.TAMIL -> "ta-IN"
        Language.TELUGU -> "te-IN"
        Language.ODIA -> "or-IN"
        Language.BENGALI -> "bn-IN"
        Language.ENGLISH -> "en-IN"
    }
}
