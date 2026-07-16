package com.example.myapplication.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.myapplication.database.im.dao.ConversationDao
import com.example.myapplication.database.im.dao.MessageDao
import com.example.myapplication.database.im.entity.ConversationEntity
import com.example.myapplication.database.im.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话仓库
 * 负责会话的创建、更新、查询和管理
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {

    companion object {
        private const val PAGE_SIZE = 20
    }

    /**
     * 获取所有会话列表（Flow，实时更新）
     */
    fun getAllConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.getAllConversations()
    }

    /**
     * 获取会话列表（Paging3分页）
     */
    fun getConversationsPaging(): Flow<PagingData<ConversationEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = 5
            ),
            pagingSourceFactory = { conversationDao.getConversationsPaging() }
        ).flow
    }

    /**
     * 创建或获取会话
     */
    suspend fun getOrCreateConversation(
        contactId: String,
        contactName: String,
        contactAvatar: String = ""
    ): ConversationEntity {
        // 尝试获取已有会话（根据contactId查找，而不是conversationId）
        val existingConversation = conversationDao.getConversationByContactId(contactId)
        if (existingConversation != null) {
            return existingConversation
        }

        // 创建新会话
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            contactId = contactId,
            contactName = contactName,
            contactAvatar = contactAvatar,
            lastMessage = "",
            lastMessageTime = System.currentTimeMillis()
        )
        conversationDao.insertConversation(conversation)
        return conversation
    }

    /**
     * 根据ID获取会话
     */
    suspend fun getConversationById(conversationId: String): ConversationEntity? {
        return conversationDao.getConversationById(conversationId)
    }

    /**
     * 根据ID获取会话（Flow）
     */
    fun getConversationByIdFlow(conversationId: String): Flow<ConversationEntity?> {
        return conversationDao.getConversationByIdFlow(conversationId)
    }

    /**
     * 更新会话的最新消息
     */
    suspend fun updateLastMessage(
        conversationId: String,
        lastMessage: String,
        lastMessageTime: Long
    ) {
        conversationDao.updateLastMessage(
            conversationId = conversationId,
            lastMessage = lastMessage,
            lastMessageTime = lastMessageTime
        )
    }

    /**
     * 增加未读消息数
     */
    suspend fun incrementUnreadCount(conversationId: String) {
        conversationDao.incrementUnreadCount(conversationId)
    }

    /**
     * 清除未读消息数
     */
    suspend fun clearUnreadCount(conversationId: String) {
        conversationDao.clearUnreadCount(conversationId)
    }

    /**
     * 设置/取消置顶
     */
    suspend fun setTop(conversationId: String, isTop: Boolean) {
        conversationDao.setTop(conversationId, isTop)
    }

    /**
     * 设置/取消免打扰
     */
    suspend fun setMuted(conversationId: String, isMuted: Boolean) {
        conversationDao.setMuted(conversationId, isMuted)
    }

    /**
     * 删除会话
     */
    suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteConversation(conversationId)
        messageDao.deleteMessagesByConversationId(conversationId)
    }

    /**
     * 获取未读消息总数
     */
    fun getTotalUnreadCount(): Flow<Int?> {
        return conversationDao.getTotalUnreadCount()
    }

    /**
     * 搜索会话
     */
    fun searchConversations(keyword: String): Flow<List<ConversationEntity>> {
        return conversationDao.searchConversations(keyword)
    }

    /**
     * 处理收到的消息，更新会话
     */
    suspend fun handleMessageReceived(
        conversationId: String,
        senderId: String,
        senderName: String,
        messageContent: String,
        timestamp: Long,
        isFromMe: Boolean
    ) {
        // 更新会话的最新消息
        updateLastMessage(conversationId, messageContent, timestamp)

        // 如果不是自己发送的消息，增加未读数
        if (!isFromMe) {
            incrementUnreadCount(conversationId)
        }
    }

    /**
     * 打开会话时清除未读数
     */
    suspend fun onConversationOpened(conversationId: String) {
        clearUnreadCount(conversationId)
    }
}
