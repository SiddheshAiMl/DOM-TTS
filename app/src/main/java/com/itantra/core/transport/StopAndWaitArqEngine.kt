package com.itantra.core.transport

import com.itantra.core.model.DeliveryStatus
import com.itantra.core.protocol.PacketProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stop-and-Wait ARQ internal state enum.
 */
enum class ArqState {
    IDLE,
    QUEUED,
    SENDING,
    SENT_TO_NODE,
    WAITING_FOR_ACK,
    RETRY,
    RADIO_ACKNOWLEDGED,
    DELIVERED,
    FAILED
}

/**
 * Pure Kotlin Stop-and-Wait ARQ Protocol Engine.
 * Enforces half-duplex single in-flight transmission, awaits remote node Type 0x03 ACK frames
 * matching SessionID and SequenceNumber with timeout and retries, and autonomously generates
 * peer ACK responses for incoming packets requiring acknowledgement.
 */
class StopAndWaitArqEngine(
    private val rawFrameSender: suspend (ByteArray) -> Boolean,
    val ackTimeoutMs: Long = 1200L,
    val maxRetries: Int = 3
) {
    private val arqMutex = Mutex()
    private val ackLock = Any()

    private val _currentState = MutableStateFlow(ArqState.IDLE)
    val currentState: StateFlow<ArqState> = _currentState.asStateFlow()

    private data class PendingAck(
        val sessionId: Long,
        val sequenceNumber: Long,
        val deferred: CompletableDeferred<Unit>
    )

    private var currentPendingAck: PendingAck? = null

    /**
     * Sends a packet frame using Stop-and-Wait ARQ.
     * If the packet has FLAG_NEEDS_ACK, waits for remote ACK with timeout and retries.
     * Otherwise completes upon local node buffering.
     */
    fun sendPacket(
        packetBytes: ByteArray,
        onRetry: ((Int) -> Unit)? = null
    ): Flow<DeliveryStatus> = flow {
        emit(DeliveryStatus.QUEUED)
        _currentState.value = ArqState.QUEUED

        arqMutex.withLock {
            val decodedPacket = try {
                PacketProtocol.decode(packetBytes)
            } catch (_: Exception) {
                null
            }

            val needsAck = decodedPacket != null &&
                    decodedPacket.header.messageType != PacketProtocol.MessageType.ACK &&
                    (decodedPacket.header.flags.toInt() and PacketProtocol.Flags.FLAG_NEEDS_ACK.toInt()) != 0

            if (!needsAck) {
                // Outgoing packet lacking FLAG_NEEDS_ACK (or ACK itself) completes immediately
                emit(DeliveryStatus.SENDING)
                _currentState.value = ArqState.SENDING

                val sent = try {
                    rawFrameSender(packetBytes)
                } catch (_: Exception) {
                    false
                }

                if (!sent) {
                    _currentState.value = ArqState.FAILED
                    emit(DeliveryStatus.FAILED)
                    return@flow
                }

                emit(DeliveryStatus.SENT_TO_NODE)
                _currentState.value = ArqState.SENT_TO_NODE

                emit(DeliveryStatus.RADIO_ACKNOWLEDGED)
                _currentState.value = ArqState.RADIO_ACKNOWLEDGED

                emit(DeliveryStatus.DELIVERED)
                _currentState.value = ArqState.DELIVERED
                return@flow
            }

            // Packet requires ACK from peer node
            val targetSessionId = decodedPacket!!.header.sessionId
            val targetSequenceNumber = decodedPacket.header.sequenceNumber
            var isDelivered = false

            for (attempt in 0..maxRetries) {
                if (attempt > 0) {
                    _currentState.value = ArqState.RETRY
                    onRetry?.invoke(attempt)
                }

                emit(DeliveryStatus.SENDING)
                _currentState.value = ArqState.SENDING

                val ackDeferred = CompletableDeferred<Unit>()
                synchronized(ackLock) {
                    currentPendingAck = PendingAck(targetSessionId, targetSequenceNumber, ackDeferred)
                }

                val sent = try {
                    rawFrameSender(packetBytes)
                } catch (_: Exception) {
                    false
                }

                if (!sent) {
                    synchronized(ackLock) {
                        if (currentPendingAck?.deferred === ackDeferred) {
                            currentPendingAck = null
                        }
                    }
                    _currentState.value = ArqState.FAILED
                    emit(DeliveryStatus.FAILED)
                    return@flow
                }

                emit(DeliveryStatus.SENT_TO_NODE)
                _currentState.value = ArqState.SENT_TO_NODE
                _currentState.value = ArqState.WAITING_FOR_ACK

                val ackReceived = withTimeoutOrNull(ackTimeoutMs) {
                    ackDeferred.await()
                }

                synchronized(ackLock) {
                    if (currentPendingAck?.deferred === ackDeferred) {
                        currentPendingAck = null
                    }
                }

                if (ackReceived != null) {
                    _currentState.value = ArqState.RADIO_ACKNOWLEDGED
                    emit(DeliveryStatus.RADIO_ACKNOWLEDGED)

                    _currentState.value = ArqState.DELIVERED
                    emit(DeliveryStatus.DELIVERED)
                    isDelivered = true
                    break
                }
            }

            if (!isDelivered) {
                _currentState.value = ArqState.FAILED
                emit(DeliveryStatus.FAILED)
            }
        }
    }

    /**
     * Inspects an incoming frame received from the radio link.
     * If it is a matching ACK, satisfies the pending ACK wait.
     * If it is a data packet requiring ACK, autonomously sends an ACK back to the peer.
     * Returns the raw frame.
     */
    suspend fun onIncomingFrame(frame: ByteArray): ByteArray {
        try {
            val packet = PacketProtocol.decode(frame)
            if (packet.header.messageType == PacketProtocol.MessageType.ACK) {
                val pending = synchronized(ackLock) { currentPendingAck }
                if (pending != null &&
                    pending.sessionId == packet.header.sessionId &&
                    pending.sequenceNumber == packet.header.sequenceNumber
                ) {
                    pending.deferred.complete(Unit)
                }
            } else {
                // Autonomous Peer ACK Generation
                val needsAck = (packet.header.flags.toInt() and PacketProtocol.Flags.FLAG_NEEDS_ACK.toInt()) != 0
                if (needsAck) {
                    val ackPacket = PacketProtocol.buildAckPacket(
                        senderId = 0L,
                        sessionId = packet.header.sessionId,
                        ackSequenceNumber = packet.header.sequenceNumber,
                        nonce = ByteArray(PacketProtocol.NONCE_SIZE)
                    )
                    val ackBytes = PacketProtocol.encode(ackPacket)
                    try {
                        rawFrameSender(ackBytes)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // Not a valid packet protocol frame or corrupted
        }
        return frame
    }
}
