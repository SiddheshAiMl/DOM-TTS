package com.itantra.core.transport

import com.itantra.core.model.DeliveryStatus
import com.itantra.core.protocol.PacketProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Transport-Demo Mode Implementation.
 * Clearly labeled simulated transport for Milestone 1 UI and integration testing.
 * Never masquerades as real RF delivery.
 */
class DemoTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : RadioTransport {

    override val isDemoMode: Boolean = true

    private val _connectionState = MutableStateFlow(TransportConnectionState.CONNECTED)
    override val connectionState: StateFlow<TransportConnectionState> = _connectionState.asStateFlow()

    private val _linkStatistics = MutableStateFlow(
        LinkStatistics(
            bleRssi = -55,
            loraRssi = -82,
            loraSnr = 7,
            txCount = 0L,
            rxCount = 0L,
            retriesCount = 0L,
            duplicatesCount = 0L
        )
    )
    override val linkStatistics: StateFlow<LinkStatistics> = _linkStatistics.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    override suspend fun connect() {
        _connectionState.value = TransportConnectionState.CONNECTING
        delay(300)
        _connectionState.value = TransportConnectionState.RADIO_READY
    }

    override suspend fun disconnect() {
        _connectionState.value = TransportConnectionState.DISCONNECTED
    }

    override fun sendPacket(packetBytes: ByteArray): Flow<DeliveryStatus> = flow {
        // Step 1: Queued locally
        emit(DeliveryStatus.QUEUED)
        delay(80)

        // Step 2: Submitting over simulated BLE
        emit(DeliveryStatus.SENDING)
        delay(120)

        // Step 3: Buffered on node
        emit(DeliveryStatus.SENT_TO_NODE)
        _linkStatistics.value = _linkStatistics.value.copy(
            txCount = _linkStatistics.value.txCount + 1
        )
        delay(250)

        // Step 4: Radio link ACK (SX1278 hardware ACK)
        emit(DeliveryStatus.RADIO_ACKNOWLEDGED)
        delay(200)

        // Step 5: Peer application delivery confirmed
        emit(DeliveryStatus.DELIVERED)

        // For demo/loopback testing: simulate peer echoing back a response packet
        scope.launch {
            delay(1500)
            simulatePeerResponse(packetBytes)
        }
    }

    private suspend fun simulatePeerResponse(originalPacketBytes: ByteArray) {
        try {
            val original = PacketProtocol.decode(originalPacketBytes)
            if (original.header.messageType == PacketProtocol.MessageType.TEXT) {
                // In demo mode, increment RX counter to simulate incoming traffic
                _linkStatistics.value = _linkStatistics.value.copy(
                    rxCount = _linkStatistics.value.rxCount + 1,
                    loraRssi = -80 + ((-5..5).random()),
                    loraSnr = 6 + ((-2..2).random())
                )
            }
        } catch (_: Exception) {
            // Ignore malformed packets in demo simulation
        }
    }
}
