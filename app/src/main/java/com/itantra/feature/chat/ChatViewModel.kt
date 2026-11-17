package com.itantra.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.ITantraApplication
import com.itantra.core.crypto.AesGcmCryptoEngine
import com.itantra.core.model.DeliveryStatus
import com.itantra.core.model.Language
import com.itantra.core.model.Message
import com.itantra.core.protocol.PacketProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel : ViewModel() {
    private val app = ITantraApplication.instance
    private val messageRepo = app.messageRepository
    private val transport get() = app.activeTransport.value
    private val crypto = app.cryptoEngine
    private val tts = app.textToSpeech
    private val prefs = app.appPreferencesRepository

    val messages: StateFlow<List<Message>> = messageRepo.getMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences = prefs.preferences

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    val currentSpokenText: StateFlow<String?> = tts.currentSpokenText

    init {
        // Listen for incoming packets over the active transport
        viewModelScope.launch {
            app.activeTransport.collect { activeTrans ->
                activeTrans.incomingPackets.collect { packetBytes ->
                    try {
                        val packet = PacketProtocol.decode(packetBytes)
                        if (packet.header.messageType == PacketProtocol.MessageType.TEXT) {
                            val userPrefs = prefs.preferences.value
                            val sessionKeys = crypto.deriveSessionKeys(
                                masterSecret = "iTantra-Fixed-Pairing-Secret-32B".toByteArray(Charsets.UTF_8),
                                sessionId = packet.header.sessionId
                            )
                            val aad = PacketProtocol.serializeHeaderToAad(packet.header)
                            val ciphertextWithTag = packet.ciphertext + packet.tag
                            val plaintextBytes = crypto.decrypt(
                                ciphertextWithTag = ciphertextWithTag,
                                aad = aad,
                                nonce = packet.header.nonce,
                                key = sessionKeys.keyBtoA
                            )
                            val text = String(plaintextBytes, Charsets.UTF_8)
                            val inMsg = Message(
                                id = UUID.randomUUID().toString(),
                                senderId = packet.header.senderId,
                                recipientId = userPrefs.localNodeId,
                                sessionId = packet.header.sessionId,
                                sequenceNumber = packet.header.sequenceNumber,
                                timestamp = System.currentTimeMillis(),
                                text = text,
                                sourceLanguage = userPrefs.inputLanguage,
                                targetLanguage = userPrefs.outputLanguage,
                                deliveryStatus = DeliveryStatus.DELIVERED,
                                isIncoming = true
                            )
                            messageRepo.insertMessage(inMsg)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _isSending.value) return

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

        _inputText.value = ""
        _isSending.value = true

        viewModelScope.launch {
            messageRepo.insertMessage(message)

            // Step 1: Encrypt message with AES-256-GCM
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

            val plaintextBytes = text.toByteArray(Charsets.UTF_8)
            val header = PacketProtocol.Header(
                messageType = PacketProtocol.MessageType.TEXT,
                flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
                senderId = userPrefs.localNodeId,
                sessionId = userPrefs.sessionId,
                sequenceNumber = seqNum,
                fragmentIndex = 0,
                fragmentCount = 1,
                nonce = nonce,
                ciphertextLength = plaintextBytes.size
            )

            val encryptionResult = crypto.encrypt(
                plaintext = plaintextBytes,
                header = header,
                key = sessionKeys.keyAtoB
            )

            val packet = PacketProtocol.Packet(
                header = header,
                ciphertext = encryptionResult.ciphertext,
                tag = encryptionResult.tag
            )

            val packetBytes = PacketProtocol.encode(packet)

            // Step 2: Stream packet through transport and observe delivery transitions
            transport.sendPacket(packetBytes).collect { status ->
                messageRepo.updateDeliveryStatus(messageId, status)
            }

            _isSending.value = false
        }
    }

    fun playAudio(message: Message) {
        viewModelScope.launch {
            tts.synthesizeAndPlay(message.text, message.targetLanguage)
        }
    }

    fun retryMessage(message: Message) {
        viewModelScope.launch {
            messageRepo.updateDeliveryStatus(message.id, DeliveryStatus.QUEUED)
            val userPrefs = prefs.preferences.value
            val seqNum = System.currentTimeMillis() % 1000000L
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
            val plaintextBytes = message.text.toByteArray(Charsets.UTF_8)
            val header = PacketProtocol.Header(
                messageType = PacketProtocol.MessageType.TEXT,
                flags = PacketProtocol.Flags.FLAG_NEEDS_ACK,
                senderId = userPrefs.localNodeId,
                sessionId = userPrefs.sessionId,
                sequenceNumber = seqNum,
                fragmentIndex = 0,
                fragmentCount = 1,
                nonce = nonce,
                ciphertextLength = plaintextBytes.size
            )
            val enc = crypto.encrypt(plaintextBytes, header, sessionKeys.keyAtoB)
            val pkt = PacketProtocol.Packet(header, enc.ciphertext, enc.tag)
            val encoded = PacketProtocol.encode(pkt)

            transport.sendPacket(encoded).collect { status ->
                messageRepo.updateDeliveryStatus(message.id, status)
            }
        }
    }
}
