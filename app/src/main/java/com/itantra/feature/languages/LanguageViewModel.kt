package com.itantra.feature.languages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.ITantraApplication
import com.itantra.core.model.Language
import com.itantra.speech.manager.LanguagePackInfo
import com.itantra.speech.manager.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LanguageViewModel : ViewModel() {
    private val app = ITantraApplication.instance
    private val modelManager = app.languageModelManager
    private val prefs = app.appPreferencesRepository

    val languagePacks: StateFlow<List<LanguagePackInfo>> = modelManager.languagePacks
    val userPreferences = prefs.preferences

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

    fun setInputLanguage(language: Language) {
        prefs.updateInputLanguage(language)
        _statusNotice.value = "Input language set to ${language.displayName}"
    }

    fun setOutputLanguage(language: Language) {
        prefs.updateOutputLanguage(language)
        _statusNotice.value = "Output voice set to ${language.displayName}"
    }

    fun verifyChecksum(pack: LanguagePackInfo) {
        viewModelScope.launch {
            val valid = modelManager.verifyChecksum(pack.language)
            _statusNotice.value = if (valid) {
                "${pack.language.displayName} SHA-256 verified successfully."
            } else {
                "${pack.language.displayName} checksum verification requires model files."
            }
        }
    }

    fun clearNotice() {
        _statusNotice.value = null
    }
}
