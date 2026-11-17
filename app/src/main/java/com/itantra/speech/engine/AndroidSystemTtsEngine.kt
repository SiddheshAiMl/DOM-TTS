package com.itantra.speech.engine

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.itantra.core.model.Language
import com.itantra.speech.api.TextToSpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Production on-device Text-To-Speech engine using Android's native TextToSpeech API.
 * Maps all 10 target Indic languages to standard BCP-47 locales.
 */
class AndroidSystemTtsEngine(
    private val context: Context
) : TextToSpeechEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpokenText = MutableStateFlow<String?>(null)
    override val currentSpokenText: StateFlow<String?> = _currentSpokenText.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    _currentSpokenText.value = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    _currentSpokenText.value = null
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    _currentSpokenText.value = null
                }
            })
        }
    }

    override suspend fun synthesizeAndPlay(text: String, language: Language) {
        if (!isInitialized || tts == null || text.isBlank()) return

        val locale = getLocaleForLanguage(language)
        tts?.language = locale

        _currentSpokenText.value = text
        val utteranceId = "iTantra_${System.currentTimeMillis()}"
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    override fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        _currentSpokenText.value = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun getLocaleForLanguage(language: Language): Locale = when (language) {
        Language.HINDI -> Locale("hi", "IN")
        Language.GUJARATI -> Locale("gu", "IN")
        Language.MARATHI -> Locale("mr", "IN")
        Language.KANNADA -> Locale("kn", "IN")
        Language.MALAYALAM -> Locale("ml", "IN")
        Language.TAMIL -> Locale("ta", "IN")
        Language.TELUGU -> Locale("te", "IN")
        Language.ODIA -> Locale("or", "IN")
        Language.BENGALI -> Locale("bn", "IN")
        Language.ENGLISH -> Locale("en", "IN")
    }
}
