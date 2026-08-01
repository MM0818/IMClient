package com.example.myapplication.Const

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 追踪当前打开的会话ID
 * 用于防止ConversationListViewModel和IMChatViewModel重复处理同一消息
 */
object ActiveChat {
    private val _activeConversationId = MutableStateFlow("")
    val activeConversationId = _activeConversationId.asStateFlow()

    fun open(conversationId: String) {
        _activeConversationId.value = conversationId
    }

    fun close() {
        _activeConversationId.value = ""
    }
}
