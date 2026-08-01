package com.example.myapplication.database.im.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话实体
 * 用于会话列表展示，聚合最新消息信息
 */
@Entity(
    tableName = "conversations",
    indices = [Index(value = ["ownerUserId"])]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,                    // 会话ID
    val contactId: String,            // 对方用户ID
    val contactName: String,          // 对方用户名
    val contactAvatar: String = "",   // 对方头像URL
    val lastMessage: String = "",     // 最新消息摘要
    val lastMessageTime: Long = 0,    // 最新消息时间
    val unreadCount: Int = 0,         // 未读消息数
    val isTop: Boolean = false,       // 是否置顶
    val isMuted: Boolean = false,     // 是否免打扰
    val ownerUserId: String = "",     // 归属用户ID（数据隔离）
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
