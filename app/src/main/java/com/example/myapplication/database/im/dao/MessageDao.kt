package com.example.myapplication.database.im.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.example.myapplication.database.im.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 消息DAO
 * 支持Paging3分页加载
 */
@Dao
interface MessageDao {

    /**
     * 插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    /**
     * 批量插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    /**
     * 更新消息状态
     */
    @Query("UPDATE messages SET status = :status WHERE id = :messageId AND status != :status")
    suspend fun updateMessageStatus(messageId: String, status: String)

    /**
     * 将所有SENDING状态的消息标记为FAILED（断网时调用）
     */
    @Query("UPDATE messages SET status = 'FAILED' WHERE status = 'SENDING' AND ownerUserId = :ownerUserId")
    suspend fun markSendingAsFailed(ownerUserId: String)

    /**
     * 获取会话消息列表（Paging3分页）
     * 按本地入库时间倒序排列，最新消息在前
     * 使用createdAt而非timestamp，因为createdAt统一使用本设备时钟，不存在跨设备时钟差异
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun getMessagesByConversationId(conversationId: String): PagingSource<Int, MessageEntity>

    /**
     * 获取会话消息列表（Flow，用于实时更新）
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    fun getMessagesFlow(conversationId: String, limit: Int = 50): Flow<List<MessageEntity>>

    /**
     * 获取最新消息
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestMessage(conversationId: String): MessageEntity?

    /**
     * 根据ID获取消息
     */
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    /**
     * 删除会话的所有消息
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversationId(conversationId: String)

    /**
     * 删除单条消息
     */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    /**
     * 获取会话消息总数
     */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int

    /**
     * 获取所有未发送成功的消息
     */
    @Query("SELECT * FROM messages WHERE ownerUserId = :ownerUserId AND status IN ('SENDING', 'FAILED') ORDER BY timestamp ASC")
    suspend fun getPendingMessages(ownerUserId: String): List<MessageEntity>

    /**
     * 搜索消息（跨会话，FTS4 前缀匹配）
     * keyword || '*' 实现前缀搜索：输入 "ye" 匹配 "yes"、"year" 等
     */
    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts fts ON m.rowid = fts.rowid
        WHERE fts.content MATCH :keyword || '*' AND m.ownerUserId = :ownerUserId
        ORDER BY m.timestamp DESC
    """)
    fun searchMessagesFts(keyword: String, ownerUserId: String): PagingSource<Int, MessageEntity>

    /**
     * 搜索消息（指定会话内，FTS4 前缀匹配）
     */
    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts fts ON m.rowid = fts.rowid
        WHERE fts.content MATCH :keyword || '*' AND m.conversationId = :conversationId AND m.ownerUserId = :ownerUserId
        ORDER BY m.timestamp DESC
    """)
    fun searchMessagesInConversationFts(keyword: String, conversationId: String, ownerUserId: String): PagingSource<Int, MessageEntity>

}
