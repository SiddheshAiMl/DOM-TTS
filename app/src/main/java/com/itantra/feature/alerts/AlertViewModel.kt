package com.itantra.feature.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.ITantraApplication
import com.itantra.core.crypto.AesGcmCryptoEngine
import com.itantra.core.model.AlertType
import com.itantra.core.model.DeliveryStatus
import com.itantra.core.model.Message
import com.itantra.core.protocol.PacketProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class AlertDispatchState(
    val lastAlertSent: AlertType? = null,
    val deliveryStatus: DeliveryStatus? = null,
    val isTransmitting: Boolean = false,
    val acknowledgedByPeer: Boolean = false
)

class AlertViewModel : ViewModel() {
    private val app = ITantraApplication.instance
    private val transport get() = app.activeTransport.value
    private val crypto = app.cryptoEngine
    private val messageRepo = app.messageRepository
    private val prefs = app.appPreferencesRepository

    private val _dispatchState = MutableStateFlow(AlertDispatchState())
    val dispatchState: StateFlow<AlertDispatchState> = _dispatchState.asStateFlow()

    private val _customAlertText = MutableStateFlow("")
    val customAlertText: StateFlow<String> = _customAlertText.asStateFlow()

    fun updateCustomText(text: String) {
        _customAlertText.value = text
    }

    fun sendEmergencyAlert(type: AlertType) {
        val text = if (type == AlertType.CUSTOM) {
            _customAlertText.value.trim().ifEmpty { AlertType.SOS.defaultText }
        } else {
            type.defaultText
        }

        _dispatchState.value = AlertDispatchState(
            lastAlertSent = type,
            deliveryStatus = DeliveryStatus.QUEUED,
            isTransmitting = true,
            acknowledgedByPeer = false
        )

        viewModelScope.launch {
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
                text = "[EMERGENCY: ${type.title}] $text",
                sourceLanguage = userPrefs.inputLanguage,
                targetLanguage = userPrefs.outputLanguage,
                deliveryStatus = DeliveryStatus.QUEUED,
                isUrgent = true,
                isIncoming = false
            )
            messageRepo.insertMessage(message)

            // Priority packet construction with FLAG_URGENT
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
                messageType = PacketProtocol.MessageType.ALERT,
                flags = (PacketProtocol.Flags.FLAG_URGENT.toInt() or PacketProtocol.Flags.FLAG_NEEDS_ACK.toInt()).toByte(),
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
                messageRepo.updateDeliveryStatus(messageId, status)
                _dispatchState.value = _dispatchState.value.copy(
                    deliveryStatus = status,
                    acknowledgedByPeer = status == DeliveryStatus.DELIVERED || status == DeliveryStatus.RADIO_ACKNOWLEDGED,
                    isTransmitting = status != DeliveryStatus.DELIVERED && status != DeliveryStatus.FAILED
                )
            }
        }
    }
}
