package com.example.myapplication.page.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.Const.Token
import com.example.myapplication.Const.WebSocketUrl
import com.example.myapplication.database.im.entity.ConversationEntity
import com.example.myapplication.network.websocket.WebSocketEvent
import com.example.myapplication.network.websocket.WebSocketManager
import com.example.myapplication.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
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

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<ConversationEntity>> =
        _searchQuery
            .debounce(300)
            .flatMapLatest { query ->
                if (query.isBlank()) flowOf(emptyList())
                else conversationRepository.searchConversations(query)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        connectWebSocket()
        observeWebSocketEvents()
    }

    private fun connectWebSocket() {
        if (Token.TOKEN.isNotEmpty() && Token.USER_ID.isNotEmpty()) {
            webSocketManager.connect(WebSocketUrl.IM_URL, Token.TOKEN, Token.USER_ID)
        }
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                if (event is WebSocketEvent.MessageReceived) {
                    conversationRepository.handleMessageReceived(
                        conversationId = event.message.conversationId,
                        senderId = event.message.senderId,
                        senderName = "",
                        messageContent = event.message.content,
                        timestamp = event.message.timestamp,
                        isFromMe = false
                    )
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
