package com.example.myapplication.page.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.Const.Token
import com.example.myapplication.Const.WebSocketUrl
import com.example.myapplication.database.im.entity.ConversationEntity
import com.example.myapplication.network.mock.MockWebSocketServer
import com.example.myapplication.network.websocket.WebSocketEvent
import com.example.myapplication.network.websocket.WebSocketManager
import com.example.myapplication.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: com.example.myapplication.repository.MessageRepository,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> =
        conversationRepository.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalUnreadCount: StateFlow<Int> =
        conversationRepository.getTotalUnreadCount()
            .map { it ?: 0 }
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val connectionState = webSocketManager.connectionState

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // 被踢下线事件（UI观察后跳转登录页+Toast）
    private val _kickedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val kickedEvent: SharedFlow<String> = _kickedEvent.asSharedFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<ConversationEntity>> =
        _searchQuery
            .debounce(300)
            .flatMapLatest { query ->
                if (query.isBlank()) flowOf(emptyList())
                else conversationRepository.searchConversations(query)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var mockServer: MockWebSocketServer? = null

    init {
        connectWebSocket()
        observeWebSocketEvents()
    }

    private fun connectWebSocket() {
        if (Token.TOKEN.isNotEmpty() && Token.USER_ID.isNotEmpty()) {
            if (WebSocketUrl.USE_MOCK) {
                // 启动本地Mock服务器（需要在IO线程）
                viewModelScope.launch {
                    val url = withContext(Dispatchers.IO) {
                        mockServer = MockWebSocketServer()
                        mockServer!!.start()
                    }
                    webSocketManager.connect(url, Token.TOKEN, Token.USER_ID)
                }
            } else {
                webSocketManager.connect(WebSocketUrl.IM_URL, Token.TOKEN, Token.USER_ID)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mockServer?.stop()
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is WebSocketEvent.MessageReceived -> {
                        // 跳过自己发送的消息（服务端会广播回发送者，由IMChatViewModel处理）
                        if (event.message.senderId == Token.USER_ID) {
                            android.util.Log.d("IM_DEBUG", "会话列表跳过自己发的消息: id=${event.message.id}")
                            return@collect
                        }
                        // 跳过当前聊天页已处理的会话消息（避免重复处理导致未读数翻倍）
                        if (event.message.conversationId == com.example.myapplication.Const.ActiveChat.activeConversationId.value) {
                            android.util.Log.d("IM_DEBUG", "会话列表跳过当前聊天页会话消息: id=${event.message.id}")
                            return@collect
                        }
                        android.util.Log.d("IM_DEBUG", "会话列表收到消息: id=${event.message.id}, content=${event.message.content}, senderName=${event.message.senderName}")
                        messageRepository.receiveMessage(event.message)
                        conversationRepository.handleMessageReceived(
                            conversationId = event.message.conversationId,
                            senderId = event.message.senderId,
                            senderName = event.message.senderName,
                            messageContent = event.message.content,
                            timestamp = event.message.timestamp,
                            isFromMe = false
                        )
                    }
                    is WebSocketEvent.Disconnected -> {
                        // 断网时将SENDING消息标记为FAILED，避免spinner无限转圈
                        viewModelScope.launch { messageRepository.markSendingAsFailed() }
                    }
                    is WebSocketEvent.Kicked -> {
                        android.util.Log.w("IM_DEBUG", "被踢下线: reason=${event.reason}")
                        // 立即断开WebSocket，防止旧token重连
                        webSocketManager.disconnect()
                        // 清除本地Token
                        Token.TOKEN = ""
                        Token.USER_ID = ""
                        Token.USERNAME = ""
                        Token.clearCache()
                        _kickedEvent.emit(event.reason)
                    }
                    else -> {}
                }
            }
        }
    }

    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    fun clearSearch() { _searchQuery.value = "" }
    fun deleteConversation(id: String) { viewModelScope.launch { conversationRepository.deleteConversation(id) } }
    fun setTop(id: String, isTop: Boolean) { viewModelScope.launch { conversationRepository.setTop(id, isTop) } }
    fun setMuted(id: String, isMuted: Boolean) { viewModelScope.launch { conversationRepository.setMuted(id, isMuted) } }
    fun reconnect() { webSocketManager.reconnect() }

    /**
     * 创建新会话
     * @return 会话ID和联系人名称
     */
    suspend fun createConversation(contactId: String, contactName: String): Pair<String, String> {
        val conversation = conversationRepository.getOrCreateConversation(contactId, contactName)
        return conversation.id to conversation.contactName
    }
}
