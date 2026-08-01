package com.example.myapplication.database.im.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息实体
 * 联合索引：conversationId + timestamp 加速会话内消息查询
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["conversationId"]),
        Index(value = ["senderId"]),
        Index(value = ["status"]),
        Index(value = ["ownerUserId"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,                    // 消息唯一ID（客户端生成UUID，幂等键）
    val conversationId: String,        // 会话ID
    val senderId: String,             // 发送者ID
    val receiverId: String,           // 接收者ID
    val content: String,              // 消息内容
    val type: String = "TEXT",        // 消息类型：TEXT, IMAGE, FILE, SYSTEM
    val status: String = "SENDING",   // 消息状态：SENDING, SENT, DELIVERED, READ, FAILED
    val timestamp: Long,              // 消息时间戳
    val isFromMe: Boolean = false,    // 是否是自己发送的
    val createdAt: Long = System.currentTimeMillis(), // 创建时间
    // 文件相关字段
    val fileUrl: String = "",         // 文件URL
    val fileName: String = "",        // 文件名
    val fileSize: Long = 0,           // 文件大小
    val thumbnailUrl: String = "",    // 缩略图URL（图片消息）
    val uploadProgress: Float = 0f,   // 上传进度
    val ownerUserId: String = ""      // 归属用户ID（数据隔离）
)
