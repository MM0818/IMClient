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
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    /**
     * 获取会话消息列表（Paging3分页）
     * 按时间倒序排列，最新消息在前
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun getMessagesByConversationId(conversationId: String): PagingSource<Int, MessageEntity>

    /**
     * 获取会话消息列表（Flow，用于实时更新）
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    fun getMessagesFlow(conversationId: String, limit: Int = 50): Flow<List<MessageEntity>>

    /**
     * 获取最新消息
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
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
    @Query("SELECT * FROM messages WHERE status IN ('SENDING', 'FAILED') ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<MessageEntity>

    /**
     * 搜索消息
     */
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :keyword || '%' ORDER BY timestamp DESC")
    fun searchMessages(keyword: String): PagingSource<Int, MessageEntity>
}
