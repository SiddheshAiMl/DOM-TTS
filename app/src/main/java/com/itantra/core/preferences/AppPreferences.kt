package com.itantra.core.preferences

import com.itantra.core.model.Language
import com.itantra.core.model.OperationalMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserPreferences(
    val localNodeId: Long = 0x0001L,
    val peerNodeId: Long = 0x0002L,
    val sessionId: Long = 0x12345678L,
    val inputLanguage: Language = Language.HINDI,
    val outputLanguage: Language = Language.HINDI,
    val operationalMode: OperationalMode = OperationalMode.TYPED_CHAT,
    val pttEndpointingSilenceMs: Long = 600L,
    val autoSendAfterTranscription: Boolean = false,
    val radioFrequencyHz: Long = 433175000L, // 433.175 MHz (WPC G.S.R. 347(E) compliant)
    val maxTxPowerDbm: Int = 10, // 10 mW ERP ceiling
    val isDemoTransportEnabled: Boolean = false
)

class AppPreferencesRepository {
    private val _preferences = MutableStateFlow(UserPreferences())
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    fun updateInputLanguage(language: Language) {
        _preferences.value = _preferences.value.copy(inputLanguage = language)
    }

    fun updateOutputLanguage(language: Language) {
        _preferences.value = _preferences.value.copy(outputLanguage = language)
    }

    fun updateOperationalMode(mode: OperationalMode) {
        _preferences.value = _preferences.value.copy(operationalMode = mode)
    }

    fun updateAutoSend(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(autoSendAfterTranscription = enabled)
    }

    fun updateEndpointingSilenceMs(silenceMs: Long) {
        _preferences.value = _preferences.value.copy(pttEndpointingSilenceMs = silenceMs)
    }
}
