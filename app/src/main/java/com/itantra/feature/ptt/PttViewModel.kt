package com.itantra.feature.ptt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.ITantraApplication
import com.itantra.core.crypto.AesGcmCryptoEngine
import com.itantra.core.model.DeliveryStatus
import com.itantra.core.model.Language
import com.itantra.core.model.Message
import com.itantra.core.protocol.PacketProtocol
import com.itantra.speech.api.TranscriptState
import com.itantra.speech.compression.SemanticCompressionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class PttState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    PREVIEW_READY,
    SENDING
}

class PttViewModel : ViewModel() {
    private val app = ITantraApplication.instance
    private val recognizer = app.speechRecognizer
    private val messageRepo = app.messageRepository
    private val transport get() = app.activeTransport.value
    private val crypto = app.cryptoEngine
    private val prefs = app.appPreferencesRepository

    val transcriptState: StateFlow<TranscriptState> = recognizer.transcriptState
    val preferences = prefs.preferences

    private val _pttState = MutableStateFlow(PttState.IDLE)
    val pttState: StateFlow<PttState> = _pttState.asStateFlow()

    private val _editableTranscript = MutableStateFlow("")
    val editableTranscript: StateFlow<String> = _editableTranscript.asStateFlow()

    private val _lastSentStatus = MutableStateFlow<DeliveryStatus?>(null)
    val lastSentStatus: StateFlow<DeliveryStatus?> = _lastSentStatus.asStateFlow()

    fun startRecording() {
        if (_pttState.value != PttState.IDLE) return
        _pttState.value = PttState.RECORDING
        _lastSentStatus.value = null
        val currentLang = prefs.preferences.value.inputLanguage

        viewModelScope.launch {
            recognizer.startListening(currentLang)
        }
    }

    fun stopRecording() {
        if (_pttState.value != PttState.RECORDING) return
        _pttState.value = PttState.TRANSCRIBING

        viewModelScope.launch {
            val finalizedText = recognizer.stopListening()
            _editableTranscript.value = finalizedText

            if (prefs.preferences.value.autoSendAfterTranscription) {
                sendPttMessage()
            } else {
                _pttState.value = PttState.PREVIEW_READY
            }
        }
    }

    fun cancelRecording() {
        recognizer.cancel()
        _editableTranscript.value = ""
        _pttState.value = PttState.IDLE
    }

    fun updateEditableTranscript(text: String) {
        _editableTranscript.value = text
    }

    fun sendPttMessage() {
        val text = _editableTranscript.value.trim()
        if (text.isEmpty() || _pttState.value == PttState.SENDING) return

        _pttState.value = PttState.SENDING
        val userPrefs = prefs.preferences.value
        val messageId = UUID.randomUUID().toString()
        val seqNum = System.currentTimeMillis() % 1000000L

        val message = Message(
            id = messageId,
            senderId = userPrefs.localNodeId,
            recipientId = userPrefs.peerNodeId,
            sessionId = userPrefs.sessionId,
            sequenceNumber = seqNum,
            timestamp = System.currentTimeMillis(),
            text = text,
            sourceLanguage = userPrefs.inputLanguage,
            targetLanguage = userPrefs.outputLanguage,
            deliveryStatus = DeliveryStatus.QUEUED,
            isIncoming = false
        )

        viewModelScope.launch {
            messageRepo.insertMessage(message)

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
            val compressedBytes = SemanticCompressionEngine.compress(text, userPrefs.inputLanguage)
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
                _lastSentStatus.value = status
            }

            _editableTranscript.value = ""
            _pttState.value = PttState.IDLE
        }
    }
}
