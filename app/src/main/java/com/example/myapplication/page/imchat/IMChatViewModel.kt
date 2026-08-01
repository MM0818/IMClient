package com.example.myapplication.page.imchat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.user.UserService
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
    private val webSocketManager: WebSocketManager,
    private val userService: UserService
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""
    val contactName: String = savedStateHandle.get<String>("contactName") ?: ""

    // 从会话中获取联系人ID（用于消息发送的receiverId）
    private var contactId: String = ""

    val messages: Flow<PagingData<MessageEntity>> =
        messageRepository.getMessagesPaging(conversationId).cachedIn(viewModelScope)

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // 联系人在线状态（从服务端获取）
    private val _contactOnline = MutableStateFlow(false)
    val contactOnline: StateFlow<Boolean> = _contactOnline.asStateFlow()

    // 被踢下线事件
    private val _kickedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val kickedEvent: SharedFlow<String> = _kickedEvent.asSharedFlow()

    init {
        com.example.myapplication.Const.ActiveChat.open(conversationId)
        loadContactId()
        clearUnreadCount()
        observeWebSocketEvents()
        resendPendingMessages()
    }

    override fun onCleared() {
        super.onCleared()
        com.example.myapplication.Const.ActiveChat.close()
    }

    private fun loadContactId() {
        if (conversationId.isNotEmpty()) {
            viewModelScope.launch {
                conversationRepository.getConversationById(conversationId)?.let {
                    contactId = it.contactId
                    android.util.Log.d("IM_DEBUG", "会话加载: conversationId=$conversationId, contactId=$contactId, contactName=${it.contactName}")
                    // 获取联系人在线状态
                    loadContactOnlineStatus()
                } ?: run {
                    android.util.Log.w("IM_DEBUG", "会话未找到: conversationId=$conversationId")
                }
            }
        }
    }

    private suspend fun loadContactOnlineStatus() {
        try {
            val token = "Bearer ${com.example.myapplication.Const.Token.TOKEN}"
            val response = userService.getUsers(token)
            val contact = response.data?.find { it.userId == contactId }
            _contactOnline.value = contact?.online == true
            android.util.Log.d("IM_DEBUG", "联系人在线状态: contactId=$contactId, online=${_contactOnline.value}")
        } catch (e: Exception) {
            android.util.Log.e("IM_DEBUG", "获取联系人在线状态失败: ${e.message}")
        }
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
                        // 跳过自己发送的消息（sendMessage已处理lastMessage更新）
                        if (event.message.senderId == webSocketManager.getCurrentUserId()) {
                            android.util.Log.d("IM_DEBUG", "跳过自己发的消息: id=${event.message.id}")
                            return@collect
                        }
                        // 只处理当前会话的消息，其他会话由ConversationListViewModel处理
                        if (event.message.conversationId == conversationId) {
                            android.util.Log.d("IM_DEBUG", "收到消息: id=${event.message.id}, content=${event.message.content}")
                            messageRepository.receiveMessage(event.message)
                            conversationRepository.handleMessageReceived(
                                conversationId = event.message.conversationId,
                                senderId = event.message.senderId,
                                senderName = event.message.senderName.ifEmpty { contactName },
                                messageContent = event.message.content,
                                timestamp = event.message.timestamp,
                                isFromMe = false
                            )
                            // 聊天页已读，立即清除未读数
                            conversationRepository.clearUnreadCount(conversationId)
                        }
                    }
                    is WebSocketEvent.MessageStatusChanged -> {
                        android.util.Log.d("IM_DEBUG", "[VM-ACK] 收到状态变更事件: messageId=${event.messageId}, status=${event.status}")
                        try {
                            messageRepository.updateMessageStatus(event.messageId, event.status)
                            android.util.Log.d("IM_DEBUG", "[VM-ACK] 数据库已更新: messageId=${event.messageId} → ${event.status}")
                        } catch (e: Exception) {
                            android.util.Log.e("IM_DEBUG", "[VM-ACK] 数据库更新失败: messageId=${event.messageId}, error=${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        // 断网时将SENDING消息标记为FAILED，避免spinner无限转圈
                        viewModelScope.launch { messageRepository.markSendingAsFailed() }
                    }
                    is WebSocketEvent.Connected -> {
                        // 重连成功后，自动重发当前会话中FAILED和SENDING的消息
                        viewModelScope.launch {
                            messageRepository.getPendingMessages().forEach { msg ->
                                if (msg.conversationId == conversationId) {
                                    android.util.Log.d("IM_DEBUG", "[RECONNECT] 自动重发待发消息: id=${msg.id}, status=${msg.status}")
                                    messageRepository.resendMessage(msg.id)
                                }
                            }
                        }
                    }
                    is WebSocketEvent.Kicked -> {
                        android.util.Log.w("IM_DEBUG", "聊天页被踢下线: reason=${event.reason}")
                        webSocketManager.disconnect()
                        com.example.myapplication.Const.Token.TOKEN = ""
                        com.example.myapplication.Const.Token.USER_ID = ""
                        com.example.myapplication.Const.Token.USERNAME = ""
                        com.example.myapplication.Const.Token.clearCache()
                        _kickedEvent.emit(event.reason)
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
            val receiver = contactId.ifEmpty { conversationId }
            android.util.Log.d("IM_DEBUG", "发送消息: conversationId=$conversationId, receiverId=$receiver, contactId=$contactId")
            messageRepository.sendTextMessage(conversationId, receiver, content)
            conversationRepository.updateLastMessage(conversationId, content, System.currentTimeMillis())
            _inputText.value = ""
        }
    }

    /**
     * 发送图片消息
     */
    fun sendImageMessage(context: Context, imageUri: Uri) {
        if (conversationId.isEmpty()) return
        viewModelScope.launch {
            val receiver = contactId.ifEmpty { conversationId }
            val (messageId, uploadTaskId) = messageRepository.sendImageMessage(
                context = context,
                conversationId = conversationId,
                receiverId = receiver,
                imageUri = imageUri
            )
            conversationRepository.updateLastMessage(conversationId, "[图片]", System.currentTimeMillis())
        }
    }

    /**
     * 发送文件消息
     */
    fun sendFileMessage(context: Context, fileUri: Uri) {
        if (conversationId.isEmpty()) return
        viewModelScope.launch {
            val receiver = contactId.ifEmpty { conversationId }
            val fileName = getFileName(context, fileUri) ?: "未知文件"
            val (messageId, uploadTaskId) = messageRepository.sendFileMessage(
                context = context,
                conversationId = conversationId,
                receiverId = receiver,
                fileUri = fileUri,
                fileName = fileName
            )
            conversationRepository.updateLastMessage(conversationId, fileName, System.currentTimeMillis())
        }
    }

    /**
     * 获取文件名
     */
    private fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }
        return fileName
    }

    fun deleteMessage(messageId: String) { viewModelScope.launch { messageRepository.deleteMessage(messageId) } }
    fun clearChat() { viewModelScope.launch { messageRepository.clearConversationMessages(conversationId) } }
    fun reconnect() { webSocketManager.reconnect() }

    /**
     * 重发失败的消息
     */
    fun resendMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.resendMessage(messageId)
        }
    }
}
