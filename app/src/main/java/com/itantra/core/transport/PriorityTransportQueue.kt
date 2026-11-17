package com.itantra.core.transport

import com.itantra.core.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.PriorityQueue

enum class PacketPriority(val rank: Int) {
    HIGH(1),   // Emergency Alerts (pre-empts normal)
    NORMAL(2)  // Chat messages, PTT
}

data class QueuedPacket(
    val id: String,
    val priority: PacketPriority,
    val packetBytes: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<QueuedPacket> {
    override fun compareTo(other: QueuedPacket): Int {
        val pComp = priority.rank.compareTo(other.priority.rank)
        return if (pComp != 0) pComp else timestamp.compareTo(other.timestamp)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QueuedPacket
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Priority Queue Scheduler for half-duplex LoRa radio link.
 * Ensures urgent SOS and hazard alerts take precedence over conversational chat.
 */
class PriorityTransportQueue(
    private val underlyingTransport: RadioTransport,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val queue = PriorityQueue<QueuedPacket>()
    private val queueLock = Any()
    private var workerJob: Job? = null

    fun enqueue(packet: QueuedPacket) {
        synchronized(queueLock) {
            queue.add(packet)
        }
    }

    fun size(): Int = synchronized(queueLock) { queue.size }

    fun pollNext(): QueuedPacket? = synchronized(queueLock) { queue.poll() }

    fun clear() = synchronized(queueLock) { queue.clear() }
}
