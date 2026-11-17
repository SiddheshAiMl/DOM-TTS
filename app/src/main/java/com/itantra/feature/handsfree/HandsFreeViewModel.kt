package com.itantra.feature.handsfree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.ITantraApplication
import com.itantra.core.crypto.AesGcmCryptoEngine
import com.itantra.core.model.DeliveryStatus
import com.itantra.core.model.Message
import com.itantra.core.protocol.PacketProtocol
import com.itantra.speech.compression.SemanticCompressionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConversationalState(val label: String) {
    IDLE("Session Stopped"),
    LISTENING("Listening for Speech (VAD Active)"),
    SEGMENTING("Pause Detected, Segmenting Phrase"),
    TRANSCRIBING("Converting Speech to Text"),
    TRANSMITTING("Encrypting & Transmitting over LoRa"),
    RECEIVING_PEER("Receiving & Playing Peer Utterance")
}

class HandsFreeViewModel : ViewModel() {
    private val app = ITantraApplication.instance
    private val recognizer = app.speechRecognizer
    private val tts = app.textToSpeech
    private val transport get() = app.activeTransport.value
    private val crypto = app.cryptoEngine
    private val prefs = app.appPreferencesRepository
    private val messageRepo = app.messageRepository

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _state = MutableStateFlow(ConversationalState.IDLE)
    val state: StateFlow<ConversationalState> = _state.asStateFlow()

    private val _lastTranscribedPhrase = MutableStateFlow("")
    val lastTranscribedPhrase: StateFlow<String> = _lastTranscribedPhrase.asStateFlow()

    private var sessionLoopJob: Job? = null

    fun toggleSession() {
        if (_isSessionActive.value) {
            stopSession()
        } else {
            startSession()
        }
    }

    private fun startSession() {
        _isSessionActive.value = true
        _state.value = ConversationalState.LISTENING

        sessionLoopJob = viewModelScope.launch {
            val userPrefs = prefs.preferences.value

            while (_isSessionActive.value) {
                // 1. Listening phase
                _state.value = ConversationalState.LISTENING
                recognizer.startListening(userPrefs.inputLanguage)

                var elapsedMs = 0L
                while (_isSessionActive.value && !recognizer.transcriptState.value.isFinalized && elapsedMs < 10000L) {
                    delay(100)
                    elapsedMs += 100L
                    if (recognizer.transcriptState.value.partialText.isNotEmpty()) {
                        _state.value = ConversationalState.SEGMENTING
                    }
                }

                // 2. Transcribing phase
                _state.value = ConversationalState.TRANSCRIBING
                val phrase = recognizer.stopListening().trim()
                if (phrase.isEmpty()) {
                    delay(500)
                    continue
                }
                _lastTranscribedPhrase.value = phrase

                // 3. Transmitting phase
                _state.value = ConversationalState.TRANSMITTING
                val messageId = UUID.randomUUID().toString()
                val seqNum = System.currentTimeMillis() % 1000000L
                val msg = Message(
                    id = messageId,
                    senderId = userPrefs.localNodeId,
                    recipientId = userPrefs.peerNodeId,
                    sessionId = userPrefs.sessionId,
                    sequenceNumber = seqNum,
                    timestamp = System.currentTimeMillis(),
                    text = phrase,
                    sourceLanguage = userPrefs.inputLanguage,
                    targetLanguage = userPrefs.outputLanguage,
                    deliveryStatus = DeliveryStatus.QUEUED,
                    isIncoming = false
                )
                messageRepo.insertMessage(msg)

                // Encrypt and transmit with MonotonicNonceGenerator
                val sessionKeys = crypto.deriveSessionKeys(
                    masterSecret = "iTantra-Fixed-Pairing-Secret-32B".toByteArray(Charsets.UTF_8),
                    sessionId = userPrefs.sessionId
                )
                val nonceGen = AesGcmCryptoEngine.MonotonicNonceGenerator(
                    senderId = userPrefs.localNodeId,
                    sessionId = userPrefs.sessionId,
                    initialCounter = seqNum
                )
                val nonce = nonceGen.nextNonce()
                val compressedBytes = SemanticCompressionEngine.compress(phrase, userPrefs.inputLanguage)
                val flags = (PacketProtocol.Flags.FLAG_NEEDS_ACK.toInt() or PacketProtocol.Flags.FLAG_COMPRESSED.toInt()).toByte()
                val header = PacketProtocol.Header(
                    messageType = PacketProtocol.MessageType.TEXT,
                    flags = flags,
                    senderId = userPrefs.localNodeId,
                    sessionId = userPrefs.sessionId,
                    sequenceNumber = seqNum,
                    fragmentIndex = 0,
                    fragmentCount = 1,
                    nonce = nonce,
                    ciphertextLength = compressedBytes.size
                )
                val enc = crypto.encrypt(compressedBytes, header, sessionKeys.keyAtoB)
                val pkt = PacketProtocol.Packet(header, enc.ciphertext, enc.tag)
                val encoded = PacketProtocol.encode(pkt)

                transport.sendPacket(encoded).collect { status ->
                    messageRepo.updateDeliveryStatus(messageId, status)
                }

                // 4. Half-duplex turn wait
                delay(1000)
            }
        }
    }

    private fun stopSession() {
        _isSessionActive.value = false
        sessionLoopJob?.cancel()
        recognizer.cancel()
        _state.value = ConversationalState.IDLE
    }
}
