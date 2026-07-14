package com.example.myapplication.page.imchat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.websocket.MessageStatus
import com.example.myapplication.network.websocket.WebSocketEvent
import com.example.myapplication.network.websocket.WebSocketManager
import com.example.myapplication.repository.ConversationRepository
import com.example.myapplication.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IMChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""
    val contactName: String = savedStateHandle.get<String>("contactName") ?: ""

    val messages: Flow<PagingData<MessageEntity>> =
        messageRepository.getMessagesPaging(conversationId).cachedIn(viewModelScope)

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    val connectionState = webSocketManager.connectionState

    init {
        clearUnreadCount()
        observeWebSocketEvents()
        resendPendingMessages()
    }

    private fun clearUnreadCount() {
        if (conversationId.isNotEmpty()) {
            viewModelScope.launch { conversationRepository.onConversationOpened(conversationId) }
        }
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is WebSocketEvent.MessageReceived -> {
                        if (event.message.conversationId == conversationId) {
                            val entity = MessageEntity(
                                id = event.message.id,
                                conversationId = event.message.conversationId,
                                senderId = event.message.senderId,
                                receiverId = "",
                                content = event.message.content,
                                type = event.message.type.name,
                                status = MessageStatus.DELIVERED.name,
                                timestamp = event.message.timestamp,
                                isFromMe = false
                            )
                            messageRepository.updateMessageStatus(event.message.id, MessageStatus.DELIVERED)
                            conversationRepository.handleMessageReceived(
                                conversationId = event.message.conversationId,
                                senderId = event.message.senderId,
                                senderName = contactName,
                                messageContent = event.message.content,
                                timestamp = event.message.timestamp,
                                isFromMe = false
                            )
                        }
                    }
                    is WebSocketEvent.MessageStatusChanged -> {
                        messageRepository.updateMessageStatus(event.messageId, event.status)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun resendPendingMessages() {
        viewModelScope.launch {
            messageRepository.getPendingMessages().forEach { msg ->
                if (msg.conversationId == conversationId) messageRepository.resendMessage(msg.id)
            }
        }
    }

    fun updateInputText(text: String) { _inputText.value = text }

    fun sendMessage() {
        val content = _inputText.value.trim()
        if (content.isEmpty() || conversationId.isEmpty()) return
        viewModelScope.launch {
            messageRepository.sendTextMessage(conversationId, conversationId, content)
            conversationRepository.updateLastMessage(conversationId, content, System.currentTimeMillis())
            _inputText.value = ""
        }
    }

    fun deleteMessage(messageId: String) { viewModelScope.launch { messageRepository.deleteMessage(messageId) } }
    fun clearChat() { viewModelScope.launch { messageRepository.clearConversationMessages(conversationId) } }
    fun reconnect() { webSocketManager.reconnect() }
}
