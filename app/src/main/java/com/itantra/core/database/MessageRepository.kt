package com.itantra.core.database

import com.itantra.core.model.DeliveryStatus
import com.itantra.core.model.Language
import com.itantra.core.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

interface MessageRepository {
    fun getMessages(): Flow<List<Message>>
    suspend fun insertMessage(message: Message)
    suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus)
    suspend fun getMessageById(messageId: String): Message?
    suspend fun clearMessages()
}

/**
 * Thread-safe Reactive Message Repository.
 */
class InMemoryMessageRepository : MessageRepository {
    private val messagesState = MutableStateFlow<List<Message>>(emptyList())

    override fun getMessages(): Flow<List<Message>> = messagesState.asStateFlow()

    override suspend fun insertMessage(message: Message) {
        messagesState.value = messagesState.value + message
    }

    override suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {
        messagesState.value = messagesState.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(deliveryStatus = status)
            } else {
                msg
            }
        }
    }

    override suspend fun getMessageById(messageId: String): Message? {
        return messagesState.value.firstOrNull { it.id == messageId }
    }

    override suspend fun clearMessages() {
        messagesState.value = emptyList()
    }
}
