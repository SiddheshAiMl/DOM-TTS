package com.itantra.core.transport

import com.itantra.core.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    SCANNING("Scanning for nodes"),
    CONNECTING("Connecting to BLE node"),
    CONNECTED("Connected to local node"),
    RADIO_READY("LoRa radio active and ready")
}

data class LinkStatistics(
    val bleRssi: Int = 0,
    val loraRssi: Int = -120,
    val loraSnr: Int = 0,
    val txCount: Long = 0L,
    val rxCount: Long = 0L,
    val retriesCount: Long = 0L,
    val duplicatesCount: Long = 0L,
    val lastError: String? = null
)

interface RadioTransport {
    val isDemoMode: Boolean
    val connectionState: StateFlow<TransportConnectionState>
    val linkStatistics: StateFlow<LinkStatistics>
    val incomingPackets: SharedFlow<ByteArray>

    suspend fun connect()
    suspend fun disconnect()
    fun sendPacket(packetBytes: ByteArray): Flow<DeliveryStatus>
}
