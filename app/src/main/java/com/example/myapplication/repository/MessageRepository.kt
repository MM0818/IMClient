package com.example.myapplication.repository

import android.content.Context
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.myapplication.database.im.dao.MessageDao
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.upload.FileUploadManager
import com.example.myapplication.network.upload.ImageCompressor
import com.example.myapplication.network.upload.UploadState
import com.example.myapplication.network.websocket.IMMessage
import com.example.myapplication.network.websocket.IncomingMessage
import com.example.myapplication.network.websocket.MessageStatus
import com.example.myapplication.network.websocket.MessageType
import com.example.myapplication.network.websocket.WebSocketManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
    private val webSocketManager: WebSocketManager,
    private val fileUploadManager: FileUploadManager,
    private val imageCompressor: ImageCompressor
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

        android.util.Log.d("IM_DEBUG", "[SORT-DEBUG] sendTextMessage: id=$messageId, timestamp=$timestamp, content=$content")
        android.util.Log.d("IM_DEBUG", "[SEND] 开始发送文本消息: messageId=$messageId, conversationId=$conversationId, receiverId=$receiverId")

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
            isFromMe = true,
            ownerUserId = com.example.myapplication.Const.Token.USER_ID
        )
        messageDao.insertMessage(messageEntity)
        val verifyEntity = messageDao.getMessageById(messageId)
        android.util.Log.d("IM_DEBUG", "[SEND] 已入库: messageId=$messageId, status=SENDING, DB验证=${verifyEntity?.status}, wsConnected=${webSocketManager.isConnected()}")

        // 2. 通过WebSocket发送（使用同一个messageId，保证ACK能匹配）
        val wsMessageId = webSocketManager.sendMessage(
            conversationId = conversationId,
            receiverId = receiverId,
            content = content,
            type = MessageType.TEXT,
            existingMessageId = messageId
        )
        android.util.Log.d("IM_DEBUG", "[SEND] WebSocket已调用: wsMessageId=$wsMessageId, 原messageId=$messageId, 匹配=${wsMessageId == messageId}")

        return messageId
    }

    /**
     * 接收消息
     * 如果本地已存在该消息（自己发的），不更新状态（由ACK流程管理：SENDING→SENT→DELIVERED）
     */
    suspend fun receiveMessage(message: IncomingMessage) {
        val existing = messageDao.getMessageById(message.id)
        if (existing != null) {
            // 消息已存在（自己发送时已入库），状态由ACK管理，不在此处更新
            android.util.Log.d("IM_DEBUG", "[RECV] 消息已存在，跳过状态更新: id=${message.id}, 当前状态=${existing.status}")
            return
        }

        // timestamp保留原始发送时间（用于显示），排序使用createdAt（本地入库时间，统一设备时钟）
        val messageEntity = MessageEntity(
            id = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            receiverId = webSocketManager.getCurrentUserId(),
            content = message.content,
            type = message.type.name,
            status = MessageStatus.DELIVERED.name,
            timestamp = message.timestamp,
            isFromMe = false,
            ownerUserId = com.example.myapplication.Const.Token.USER_ID
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
     * 断网时将所有SENDING状态的消息标记为FAILED
     */
    suspend fun markSendingAsFailed() {
        messageDao.markSendingAsFailed(com.example.myapplication.Const.Token.USER_ID)
    }

    /**
     * 获取待发送的消息
     */
    suspend fun getPendingMessages(): List<MessageEntity> {
        return messageDao.getPendingMessages(com.example.myapplication.Const.Token.USER_ID)
    }

    /**
     * 重新发送失败的消息
     * 使用相同messageId保证幂等去重，服务端不会生成重复消息
     */
    suspend fun resendMessage(messageId: String) {
        val message = messageDao.getMessageById(messageId) ?: return

        // 更新状态为发送中
        messageDao.updateMessageStatus(messageId, MessageStatus.SENDING.name)

        // 使用相同messageId重新发送
        webSocketManager.retryPendingMessage(
            messageId = messageId,
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
        val ownerUserId = com.example.myapplication.Const.Token.USER_ID
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { messageDao.searchMessages(keyword, ownerUserId) }
        ).flow
    }

    /**
     * 获取消息数量
     */
    suspend fun getMessageCount(conversationId: String): Int {
        return messageDao.getMessageCount(conversationId)
    }

    /**
     * 发送图片消息
     * @return 消息ID和上传任务ID
     */
    suspend fun sendImageMessage(
        context: Context,
        conversationId: String,
        receiverId: String,
        imageUri: Uri
    ): Pair<String, String> {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // 1. 压缩图片
        val compressResult = imageCompressor.compressImage(context, imageUri)

        // 2. 创建上传任务
        val uploadTask = fileUploadManager.createUploadTask(
            context = context,
            fileUri = Uri.fromFile(compressResult.compressedFile),
            fileName = "image_${messageId}.jpg"
        )

        // 3. 保存到本地数据库
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = webSocketManager.getCurrentUserId(),
            receiverId = receiverId,
            content = "[图片]",
            type = MessageType.IMAGE.name,
            status = MessageStatus.SENDING.name,
            timestamp = timestamp,
            isFromMe = true,
            fileName = "image_${messageId}.jpg",
            fileSize = compressResult.compressedSize,
            thumbnailUrl = compressResult.thumbnailFile?.absolutePath ?: "",
            ownerUserId = com.example.myapplication.Const.Token.USER_ID
        )
        messageDao.insertMessage(messageEntity)

        // 4. 开始上传
        fileUploadManager.startUpload(uploadTask.id, context)

        return messageId to uploadTask.id
    }

    /**
     * 发送文件消息
     * @return 消息ID和上传任务ID
     */
    suspend fun sendFileMessage(
        context: Context,
        conversationId: String,
        receiverId: String,
        fileUri: Uri,
        fileName: String
    ): Pair<String, String> {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // 1. 创建上传任务
        val uploadTask = fileUploadManager.createUploadTask(
            context = context,
            fileUri = fileUri,
            fileName = fileName
        )

        // 2. 保存到本地数据库
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = webSocketManager.getCurrentUserId(),
            receiverId = receiverId,
            content = fileName,
            type = MessageType.FILE.name,
            status = MessageStatus.SENDING.name,
            timestamp = timestamp,
            isFromMe = true,
            fileName = fileName,
            fileSize = uploadTask.fileSize,
            ownerUserId = com.example.myapplication.Const.Token.USER_ID
        )
        messageDao.insertMessage(messageEntity)

        // 3. 开始上传
        fileUploadManager.startUpload(uploadTask.id, context)

        return messageId to uploadTask.id
    }

    /**
     * 获取上传进度
     */
    fun getUploadProgress(uploadTaskId: String): StateFlow<Float> {
        return fileUploadManager.getUploadProgress(uploadTaskId)
    }

    /**
     * 获取上传状态
     */
    fun getUploadState(uploadTaskId: String): StateFlow<UploadState> {
        return fileUploadManager.getUploadState(uploadTaskId)
    }

    /**
     * 暂停上传
     */
    fun pauseUpload(uploadTaskId: String) {
        fileUploadManager.pauseUpload(uploadTaskId)
    }

    /**
     * 恢复上传
     */
    fun resumeUpload(uploadTaskId: String, context: Context) {
        fileUploadManager.resumeUpload(uploadTaskId, context)
    }

    /**
     * 取消上传
     */
    fun cancelUpload(uploadTaskId: String) {
        fileUploadManager.cancelUpload(uploadTaskId)
    }
}
