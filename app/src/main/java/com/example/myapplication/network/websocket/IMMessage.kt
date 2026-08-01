package com.example.myapplication.network.websocket

import kotlinx.serialization.Serializable

/**
 * IM消息协议定义
 * 使用Kotlin Serialization进行序列化/反序列化
 */

/**
 * 消息类型枚举
 */
@Serializable
enum class MessageType {
    TEXT,       // 文本消息
    IMAGE,     // 图片消息
    FILE,      // 文件消息
    SYSTEM     // 系统消息
}

/**
 * 消息状态枚举
 * SENDING -> SENT -> DELIVERED -> READ
 */
@Serializable
enum class MessageStatus {
    SENDING,    // 发送中
    SENT,       // 已发送到服务器
    DELIVERED,  // 已送达对方
    READ,       // 已读
    FAILED      // 发送失败
}

/**
 * WebSocket传输消息格式
 */
@Serializable
data class IMMessage(
    val id: String,                    // 消息唯一ID（客户端生成UUID）
    val conversationId: String,        // 会话ID
    val senderId: String,             // 发送者ID
    val receiverId: String,           // 接收者ID
    val content: String,              // 消息内容
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENDING
)

/**
 * 服务端发送的消息确认（ACK）
 */
@Serializable
data class MessageAck(
    val messageId: String,            // 原消息ID
    val status: MessageStatus,        // 确认的状态
    val serverTimestamp: Long = 0     // 服务端时间戳
)

/**
 * 接收到的消息
 */
@Serializable
data class IncomingMessage(
    val id: String,                    // 服务端消息ID
    val conversationId: String,
    val senderId: String,
    val senderName: String = "",       // 发送者用户名（服务端附带）
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long,
    val serverTimestamp: Long = 0      // 服务端时间戳（用于排序，避免设备时钟不同步导致乱序）
)

/**
 * 发送消息请求
 */
@Serializable
data class SendMessageRequest(
    val id: String,                    // 客户端生成的消息ID（幂等键）
    val conversationId: String,
    val receiverId: String,
    val content: String,
    val type: String = MessageType.TEXT.name,  // 服务端用 type 字段路由消息
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 心跳消息
 */
@Serializable
data class Heartbeat(
    val type: String = "ping",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 认证消息
 */
@Serializable
data class AuthMessage(
    val type: String = "auth",
    val token: String,
    val userId: String
)

/**
 * WebSocket事件类型（用于UI层监听）
 */
sealed class WebSocketEvent {
    data class Connected(val timestamp: Long) : WebSocketEvent()
    data class Disconnected(val reason: String) : WebSocketEvent()
    data class MessageReceived(val message: IncomingMessage) : WebSocketEvent()
    data class MessageStatusChanged(val messageId: String, val status: MessageStatus) : WebSocketEvent()
    data class Error(val error: Throwable) : WebSocketEvent()
    data class Kicked(val reason: String) : WebSocketEvent()
    object Reconnecting : WebSocketEvent()
}
