package com.itantra

import android.app.Application
import android.bluetooth.BluetoothDevice
import com.itantra.core.ble.AndroidBleTransport
import com.itantra.core.ble.BleScanner
import com.itantra.core.crypto.AesGcmCryptoEngine
import com.itantra.core.database.InMemoryMessageRepository
import com.itantra.core.database.MessageRepository
import com.itantra.core.preferences.AppPreferencesRepository
import com.itantra.core.transport.DemoTransport
import com.itantra.core.transport.RadioTransport
import com.itantra.speech.api.SpeechRecognizerEngine
import com.itantra.speech.api.TextToSpeechEngine
import com.itantra.speech.audio.AudioCaptureManager
import com.itantra.speech.engine.AndroidSpeechRecognizerEngine
import com.itantra.speech.engine.AndroidSystemTtsEngine
import com.itantra.speech.manager.LanguageModelManager
import com.itantra.speech.vad.EnergyAndSileroVad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ITantraApplication : Application() {

    lateinit var messageRepository: MessageRepository
        private set

    lateinit var appPreferencesRepository: AppPreferencesRepository
        private set

    private val _activeTransport = MutableStateFlow<RadioTransport>(AndroidBleTransport(this))
    val activeTransport: StateFlow<RadioTransport> = _activeTransport.asStateFlow()

    lateinit var bleScanner: BleScanner
        private set

    lateinit var cryptoEngine: AesGcmCryptoEngine
        private set

    lateinit var audioCaptureManager: AudioCaptureManager
        private set

    lateinit var speechRecognizer: SpeechRecognizerEngine
        private set

    lateinit var textToSpeech: TextToSpeechEngine
        private set

    lateinit var voiceActivityDetector: EnergyAndSileroVad
        private set

    lateinit var languageModelManager: LanguageModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        messageRepository = InMemoryMessageRepository()
        appPreferencesRepository = AppPreferencesRepository()
        bleScanner = BleScanner(this)
        cryptoEngine = AesGcmCryptoEngine()
        audioCaptureManager = AudioCaptureManager(this)

        speechRecognizer = AndroidSpeechRecognizerEngine(this)
        textToSpeech = AndroidSystemTtsEngine(this)
        voiceActivityDetector = EnergyAndSileroVad()
        languageModelManager = LanguageModelManager(getExternalFilesDir(null))

        val realTransport = AndroidBleTransport(this)
        _activeTransport.value = realTransport
    }

    suspend fun switchToRealBle(device: BluetoothDevice) {
        val realTransport = AndroidBleTransport(this)
        realTransport.setTargetDevice(device)
        realTransport.connect()
        _activeTransport.value = realTransport
    }

    suspend fun switchToDemoTransport() {
        val demo = DemoTransport()
        demo.connect()
        _activeTransport.value = demo
    }

    companion object {
        lateinit var instance: ITantraApplication
            private set
    }
}
