package com.example.myapplication.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.myapplication.database.im.dao.MessageDao
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.websocket.IMMessage
import com.example.myapplication.network.websocket.MessageStatus
import com.example.myapplication.network.websocket.MessageType
import com.example.myapplication.network.websocket.WebSocketManager
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息仓库
 * 负责消息的发送、接收、存储和查询
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val webSocketManager: WebSocketManager
) {

    companion object {
        private const val PAGE_SIZE = 20
    }

    /**
     * 获取会话消息列表（Paging3分页）
     */
    fun getMessagesPaging(conversationId: String): Flow<PagingData<MessageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = 5
            ),
            pagingSourceFactory = { messageDao.getMessagesByConversationId(conversationId) }
        ).flow
    }

    /**
     * 获取会话最新消息（Flow）
     */
    fun getLatestMessages(conversationId: String, limit: Int = 50): Flow<List<MessageEntity>> {
        return messageDao.getMessagesFlow(conversationId, limit)
    }

    /**
     * 发送文本消息
     * @return 消息ID
     */
    suspend fun sendTextMessage(
        conversationId: String,
        receiverId: String,
        content: String
    ): String {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // 1. 保存到本地数据库（状态：SENDING）
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = webSocketManager.getCurrentUserId(),
            receiverId = receiverId,
            content = content,
            type = MessageType.TEXT.name,
            status = MessageStatus.SENDING.name,
            timestamp = timestamp,
            isFromMe = true
        )
        messageDao.insertMessage(messageEntity)

        // 2. 通过WebSocket发送
        webSocketManager.sendMessage(
            conversationId = conversationId,
            receiverId = receiverId,
            content = content,
            type = MessageType.TEXT
        )

        return messageId
    }

    /**
     * 接收消息
     */
    suspend fun receiveMessage(message: IMMessage) {
        val messageEntity = MessageEntity(
            id = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            content = message.content,
            type = message.type.name,
            status = MessageStatus.DELIVERED.name,
            timestamp = message.timestamp,
            isFromMe = false
        )
        messageDao.insertMessage(messageEntity)
    }

    /**
     * 更新消息状态
     */
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status.name)
    }

    /**
     * 获取待发送的消息
     */
    suspend fun getPendingMessages(): List<MessageEntity> {
        return messageDao.getPendingMessages()
    }

    /**
     * 重新发送失败的消息
     */
    suspend fun resendMessage(messageId: String) {
        val message = messageDao.getMessageById(messageId) ?: return

        // 更新状态为发送中
        messageDao.updateMessageStatus(messageId, MessageStatus.SENDING.name)

        // 重新发送
        webSocketManager.sendMessage(
            conversationId = message.conversationId,
            receiverId = message.receiverId,
            content = message.content,
            type = MessageType.valueOf(message.type)
        )
    }

    /**
     * 删除消息
     */
    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    /**
     * 清空会话消息
     */
    suspend fun clearConversationMessages(conversationId: String) {
        messageDao.deleteMessagesByConversationId(conversationId)
    }

    /**
     * 搜索消息
     */
    fun searchMessages(keyword: String): Flow<PagingData<MessageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { messageDao.searchMessages(keyword) }
        ).flow
    }

    /**
     * 获取消息数量
     */
    suspend fun getMessageCount(conversationId: String): Int {
        return messageDao.getMessageCount(conversationId)
    }
}
