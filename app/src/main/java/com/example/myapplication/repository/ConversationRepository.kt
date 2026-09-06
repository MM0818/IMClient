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
        return conversationDao.getAllConversations(com.example.myapplication.Const.Token.USER_ID)
    }

    /**
     * 获取会话列表（Paging3分页）
     */
    fun getConversationsPaging(): Flow<PagingData<ConversationEntity>> {
        val ownerUserId = com.example.myapplication.Const.Token.USER_ID
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                prefetchDistance = 5
            ),
            pagingSourceFactory = { conversationDao.getConversationsPaging(ownerUserId) }
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
        val ownerUserId = com.example.myapplication.Const.Token.USER_ID
        // 尝试获取已有会话（根据contactId查找，而不是conversationId）
        val existingConversation = conversationDao.getConversationByContactId(contactId, ownerUserId)
        if (existingConversation != null) {
            return existingConversation
        }

        // 使用确定性的会话ID生成算法：基于双方用户ID生成一致的会话ID
        // 这样A和B双方无论谁创建会话，都会得到相同的会话ID
        val conversationId = generateConversationId(contactId)

        // 创建新会话
        val conversation = ConversationEntity(
            id = conversationId,
            contactId = contactId,
            contactName = contactName,
            contactAvatar = contactAvatar,
            lastMessage = "",
            lastMessageTime = System.currentTimeMillis(),
            ownerUserId = ownerUserId
        )
        conversationDao.insertConversation(conversation)
        return conversation
    }

    /**
     * 生成确定性的会话ID
     * 基于当前用户ID和对方用户ID，确保双方生成相同的会话ID
     * 格式：按字典顺序排序的两个ID，用下划线连接
     */
    private fun generateConversationId(contactId: String): String {
        val currentUserId = com.example.myapplication.Const.Token.USER_ID
        val ids = listOf(currentUserId, contactId).sorted()
        return "${ids[0]}_${ids[1]}"
    }

    /**
     * 根据ID获取会话
     */
    suspend fun getConversationById(conversationId: String): ConversationEntity? {
        return conversationDao.getConversationById(conversationId, com.example.myapplication.Const.Token.USER_ID)
    }

    /**
     * 根据ID获取会话（Flow）
     */
    fun getConversationByIdFlow(conversationId: String): Flow<ConversationEntity?> {
        return conversationDao.getConversationByIdFlow(conversationId, com.example.myapplication.Const.Token.USER_ID)
    }

    /**
     * 更新会话的最新消息
     */
    suspend fun updateLastMessage(
        conversationId: String,
        lastMessage: String,
        lastMessageTime: Long
    ) {
        android.util.Log.d("IM_DEBUG", "[ConvRepo] updateLastMessage: conversationId=$conversationId, lastMessage=$lastMessage")
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
        messageDao.deleteMessagesByConversationId(conversationId, com.example.myapplication.Const.Token.USER_ID)
    }

    /**
     * 获取未读消息总数
     */
    fun getTotalUnreadCount(): Flow<Int?> {
        return conversationDao.getTotalUnreadCount(com.example.myapplication.Const.Token.USER_ID)
    }

    /**
     * 搜索会话
     */
    fun searchConversations(keyword: String): Flow<List<ConversationEntity>> {
        return conversationDao.searchConversations(keyword, com.example.myapplication.Const.Token.USER_ID)
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
        val ownerUserId = com.example.myapplication.Const.Token.USER_ID
        // 使用本地时间作为lastMessageTime，避免跨设备时钟差异导致会话排序异常
        val localTimestamp = System.currentTimeMillis()
        // 检查会话是否存在，如果不存在则自动创建
        val existingConversation = conversationDao.getConversationById(conversationId, ownerUserId)
        if (existingConversation == null) {
            // 自动创建会话
            // 解析联系人名称：优先用传入的senderName → 缓存的userName → senderId
            val resolvedName = senderName.ifEmpty {
                com.example.myapplication.Const.Token.getUserName(senderId) ?: senderId
            }
            android.util.Log.d("IM_DEBUG", "[ConvRepo] handleMessageReceived: 会话不存在，新建 conversationId=$conversationId, ownerUserId=$ownerUserId, contactName=$resolvedName, lastMessage=$messageContent")
            val conversation = ConversationEntity(
                id = conversationId,
                contactId = senderId,
                contactName = resolvedName,
                lastMessage = messageContent,
                lastMessageTime = localTimestamp,
                ownerUserId = ownerUserId
            )
            conversationDao.insertConversation(conversation)
        } else {
            // 更新会话的最新消息
            android.util.Log.d("IM_DEBUG", "[ConvRepo] handleMessageReceived: 会话已存在 conversationId=$conversationId, 旧lastMessage=${existingConversation.lastMessage}, 新lastMessage=$messageContent")
            updateLastMessage(conversationId, messageContent, localTimestamp)
        }

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
