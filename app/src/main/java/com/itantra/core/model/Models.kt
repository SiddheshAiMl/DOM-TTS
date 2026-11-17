package com.itantra.core.model

import java.util.UUID

enum class DeliveryStatus(val label: String) {
    QUEUED("Queued locally"),
    SENDING("Submitting to BLE node"),
    SENT_TO_NODE("Buffered on node"),
    RADIO_ACKNOWLEDGED("Radio link ACK received"),
    DELIVERED("Peer application delivered"),
    FAILED("Transmission failed")
}

enum class Language(
    val code: String,
    val displayName: String,
    val nativeLabel: String,
    val script: String
) {
    HINDI("hi", "Hindi", "हिन्दी", "Devanagari"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી", "Gujarati"),
    MARATHI("mr", "Marathi", "मराठी", "Devanagari"),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ", "Kannada"),
    MALAYALAM("ml", "Malayalam", "മലയാളം", "Malayalam"),
    TAMIL("ta", "Tamil", "தமிழ்", "Tamil"),
    TELUGU("te", "Telugu", "తెలుగు", "Telugu"),
    ODIA("or", "Odia", "ଓଡ଼ିଆ", "Odia"),
    BENGALI("bn", "Bengali", "বাংলা", "Bengali"),
    ENGLISH("en", "English", "English", "Latin");

    companion object {
        fun fromCode(code: String): Language =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}

enum class OperationalMode(val title: String, val description: String) {
    TYPED_CHAT("Typed Chat", "End-to-end encrypted keyboard messaging"),
    PUSH_TO_TALK("Push-to-Talk (PTT)", "Press and hold to transcribe and transmit speech"),
    HANDS_FREE("Hands-Free Conversational", "Automatic VAD phrase segmentation with half-duplex turn-taking"),
    EMERGENCY("Emergency Alerts", "High-priority urgent distress and hazard alerts")
}

enum class AlertType(val title: String, val defaultText: String) {
    SOS("SOS - Distress", "EMERGENCY: Immediate assistance required at this location."),
    MEDICAL("Medical Emergency", "URGENT: Medical attention needed immediately."),
    EVACUATION("Evacuation Order", "ALERT: Evacuate current area immediately."),
    HAZARD("Environmental Hazard", "WARNING: Hazard or route obstruction detected."),
    SAFE("Status OK / Safe", "REPORT: Team and equipment are safe and secure."),
    CUSTOM("Custom Emergency Message", "")
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderId: Long,
    val recipientId: Long,
    val sessionId: Long,
    val sequenceNumber: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val sourceLanguage: Language = Language.HINDI,
    val targetLanguage: Language = Language.HINDI,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.QUEUED,
    val isUrgent: Boolean = false,
    val isIncoming: Boolean = false,
    val audioDurationMs: Long = 0L,
    val retryCount: Int = 0
)

data class NodeStatus(
    val nodeId: Long = 0x0001L,
    val bleConnected: Boolean = false,
    val peerReachable: Boolean = false,
    val bleRssi: Int = 0,
    val loraRssi: Int = -120,
    val loraSnr: Int = 0,
    val txPackets: Long = 0L,
    val rxPackets: Long = 0L,
    val retries: Int = 0,
    val dropped: Int = 0,
    val queueDepth: Int = 0,
    val lastError: String? = null
)

data class DiagnosticMetrics(
    val bleRssi: Int = 0,
    val loraRssi: Int = -120,
    val loraSnr: Int = 0,
    val txCount: Long = 0L,
    val rxCount: Long = 0L,
    val ackCount: Long = 0L,
    val retryCount: Long = 0L,
    val duplicateCount: Long = 0L,
    val lastError: String? = null,
    val sttEndpointLatencyMs: Long = 0L,
    val ttsSynthesisLatencyMs: Long = 0L,
    val e2eLatencyMs: Long = 0L,
    val lastAirtimeMs: Long = 0L
)
