package com.example.myapplication.database.im.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.example.myapplication.database.im.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话DAO
 */
@Dao
interface ConversationDao {

    /**
     * 插入或更新会话
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    /**
     * 批量插入会话
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    /**
     * 获取所有会话列表（Flow，实时更新）
     * 按置顶和最新消息时间排序
     */
    @Query("""
        SELECT * FROM conversations
        ORDER BY isTop DESC, lastMessageTime DESC
    """)
    fun getAllConversations(): Flow<List<ConversationEntity>>

    /**
     * 获取所有会话列表（Paging3分页）
     */
    @Query("""
        SELECT * FROM conversations
        ORDER BY isTop DESC, lastMessageTime DESC
    """)
    fun getConversationsPaging(): PagingSource<Int, ConversationEntity>

    /**
     * 根据ID获取会话
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    /**
     * 根据ID获取会话（Flow）
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationByIdFlow(conversationId: String): Flow<ConversationEntity?>

    /**
     * 更新会话的最新消息
     */
    @Query("""
        UPDATE conversations
        SET lastMessage = :lastMessage,
            lastMessageTime = :lastMessageTime,
            updatedAt = :updatedAt
        WHERE id = :conversationId
    """)
    suspend fun updateLastMessage(
        conversationId: String,
        lastMessage: String,
        lastMessageTime: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * 更新未读消息数
     */
    @Query("UPDATE conversations SET unreadCount = :count WHERE id = :conversationId")
    suspend fun updateUnreadCount(conversationId: String, count: Int)

    /**
     * 增加未读消息数
     */
    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE id = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)

    /**
     * 清除未读消息数
     */
    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnreadCount(conversationId: String)

    /**
     * 设置/取消置顶
     */
    @Query("UPDATE conversations SET isTop = :isTop WHERE id = :conversationId")
    suspend fun setTop(conversationId: String, isTop: Boolean)

    /**
     * 设置/取消免打扰
     */
    @Query("UPDATE conversations SET isMuted = :isMuted WHERE id = :conversationId")
    suspend fun setMuted(conversationId: String, isMuted: Boolean)

    /**
     * 删除会话
     */
    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    /**
     * 获取会话总数
     */
    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    /**
     * 获取未读消息总数
     */
    @Query("SELECT SUM(unreadCount) FROM conversations")
    fun getTotalUnreadCount(): Flow<Int?>

    /**
     * 搜索会话
     */
    @Query("""
        SELECT * FROM conversations
        WHERE contactName LIKE '%' || :keyword || '%'
        OR lastMessage LIKE '%' || :keyword || '%'
        ORDER BY lastMessageTime DESC
    """)
    fun searchConversations(keyword: String): Flow<List<ConversationEntity>>
}
