package com.example.myapplication.page.imchat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.user.UserService
import com.example.myapplication.network.websocket.MessageStatus
import com.example.myapplication.network.websocket.WebSocketEvent
import com.example.myapplication.network.websocket.WebSocketManager
import com.example.myapplication.repository.ConversationRepository
import com.example.myapplication.repository.MessageRepository
import com.example.myapplication.utils.OcrPerfTracker
import com.example.myapplication.utils.OcrPerfResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@HiltViewModel
class IMChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val webSocketManager: WebSocketManager,
    private val userService: UserService
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""
    val contactName: String = savedStateHandle.get<String>("contactName") ?: ""

    // 从会话中获取联系人ID（用于消息发送的receiverId）
    private var contactId: String = ""

    val messages: Flow<PagingData<MessageEntity>> =
        messageRepository.getMessagesPaging(conversationId).cachedIn(viewModelScope)

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // 联系人在线状态（从服务端获取）
    private val _contactOnline = MutableStateFlow(false)
    val contactOnline: StateFlow<Boolean> = _contactOnline.asStateFlow()

    // 被踢下线事件
    private val _kickedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val kickedEvent: SharedFlow<String> = _kickedEvent.asSharedFlow()

    // OCR 相关状态
    private val _ocrState = MutableStateFlow<OcrState>(OcrState.Idle)
    val ocrState: StateFlow<OcrState> = _ocrState.asStateFlow()

    private val textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    init {
        com.example.myapplication.Const.ActiveChat.open(conversationId)
        loadContactId()
        clearUnreadCount()
        observeWebSocketEvents()
        resendPendingMessages()
    }

    override fun onCleared() {
        super.onCleared()
        com.example.myapplication.Const.ActiveChat.close()
        textRecognizer.close()
    }

    private fun loadContactId() {
        if (conversationId.isNotEmpty()) {
            viewModelScope.launch {
                conversationRepository.getConversationById(conversationId)?.let {
                    contactId = it.contactId
                    android.util.Log.d("IM_DEBUG", "会话加载: conversationId=$conversationId, contactId=$contactId, contactName=${it.contactName}")
                    // 获取联系人在线状态
                    loadContactOnlineStatus()
                } ?: run {
                    android.util.Log.w("IM_DEBUG", "会话未找到: conversationId=$conversationId")
                }
            }
        }
    }

    private suspend fun loadContactOnlineStatus() {
        try {
            val token = "Bearer ${com.example.myapplication.Const.Token.TOKEN}"
            val response = userService.getUsers(token)
            val contact = response.data?.find { it.userId == contactId }
            _contactOnline.value = contact?.online == true
            android.util.Log.d("IM_DEBUG", "联系人在线状态: contactId=$contactId, online=${_contactOnline.value}")
        } catch (e: Exception) {
            android.util.Log.e("IM_DEBUG", "获取联系人在线状态失败: ${e.message}")
        }
    }

    private fun clearUnreadCount() {
        if (conversationId.isNotEmpty()) {
            viewModelScope.launch { conversationRepository.onConversationOpened(conversationId) }
        }
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is WebSocketEvent.MessageReceived -> {
                        // 跳过自己发送的消息（sendMessage已处理lastMessage更新）
                        if (event.message.senderId == webSocketManager.getCurrentUserId()) {
                            android.util.Log.d("IM_DEBUG", "跳过自己发的消息: id=${event.message.id}")
                            return@collect
                        }
                        // 只处理当前会话的消息，其他会话由ConversationListViewModel处理
                        if (event.message.conversationId == conversationId) {
                            android.util.Log.d("IM_DEBUG", "收到消息: id=${event.message.id}, content=${event.message.content}")
                            messageRepository.receiveMessage(event.message)
                            conversationRepository.handleMessageReceived(
                                conversationId = event.message.conversationId,
                                senderId = event.message.senderId,
                                senderName = event.message.senderName.ifEmpty { contactName },
                                messageContent = event.message.content,
                                timestamp = event.message.timestamp,
                                isFromMe = false
                            )
                            // 聊天页已读，立即清除未读数
                            conversationRepository.clearUnreadCount(conversationId)
                        }
                    }
                    is WebSocketEvent.MessageStatusChanged -> {
                        android.util.Log.d("IM_DEBUG", "[VM-ACK] 收到状态变更事件: messageId=${event.messageId}, status=${event.status}")
                        try {
                            messageRepository.updateMessageStatus(event.messageId, event.status)
                            android.util.Log.d("IM_DEBUG", "[VM-ACK] 数据库已更新: messageId=${event.messageId} → ${event.status}")
                        } catch (e: Exception) {
                            android.util.Log.e("IM_DEBUG", "[VM-ACK] 数据库更新失败: messageId=${event.messageId}, error=${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        // 断网时将SENDING消息标记为FAILED，避免spinner无限转圈
                        viewModelScope.launch { messageRepository.markSendingAsFailed() }
                    }
                    is WebSocketEvent.Connected -> {
                        // 重连成功后，自动重发当前会话中FAILED和SENDING的消息
                        viewModelScope.launch {
                            messageRepository.getPendingMessages().forEach { msg ->
                                if (msg.conversationId == conversationId) {
                                    android.util.Log.d("IM_DEBUG", "[RECONNECT] 自动重发待发消息: id=${msg.id}, status=${msg.status}")
                                    messageRepository.resendMessage(msg.id)
                                }
                            }
                        }
                    }
                    is WebSocketEvent.Kicked -> {
                        android.util.Log.w("IM_DEBUG", "聊天页被踢下线: reason=${event.reason}")
                        webSocketManager.disconnect()
                        com.example.myapplication.Const.Token.TOKEN = ""
                        com.example.myapplication.Const.Token.USER_ID = ""
                        com.example.myapplication.Const.Token.USERNAME = ""
                        com.example.myapplication.Const.Token.clearCache()
                        _kickedEvent.emit(event.reason)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun resendPendingMessages() {
        viewModelScope.launch {
            messageRepository.getPendingMessages().forEach { msg ->
                if (msg.conversationId == conversationId) messageRepository.resendMessage(msg.id)
            }
        }
    }

    fun updateInputText(text: String) { _inputText.value = text }

    fun sendMessage() {
        val content = _inputText.value.trim()
        if (content.isEmpty() || conversationId.isEmpty()) return
        viewModelScope.launch {
            val receiver = contactId.ifEmpty { conversationId }
            android.util.Log.d("IM_DEBUG", "发送消息: conversationId=$conversationId, receiverId=$receiver, contactId=$contactId")
            messageRepository.sendTextMessage(conversationId, receiver, content)
            conversationRepository.updateLastMessage(conversationId, content, System.currentTimeMillis())
            _inputText.value = ""
        }
    }

    /**
     * 发送图片消息
     */
    fun sendImageMessage(context: Context, imageUri: Uri) {
        if (conversationId.isEmpty()) return
        viewModelScope.launch {
            val receiver = contactId.ifEmpty { conversationId }
            val (messageId, uploadTaskId) = messageRepository.sendImageMessage(
                context = context,
                conversationId = conversationId,
                receiverId = receiver,
                imageUri = imageUri
            )
            conversationRepository.updateLastMessage(conversationId, "[图片]", System.currentTimeMillis())
        }
    }

    /**
     * 发送文件消息
     */
    fun sendFileMessage(context: Context, fileUri: Uri) {
        if (conversationId.isEmpty()) return
        viewModelScope.launch {
            val receiver = contactId.ifEmpty { conversationId }
            val fileName = getFileName(context, fileUri) ?: "未知文件"
            val (messageId, uploadTaskId) = messageRepository.sendFileMessage(
                context = context,
                conversationId = conversationId,
                receiverId = receiver,
                fileUri = fileUri,
                fileName = fileName
            )
            conversationRepository.updateLastMessage(conversationId, fileName, System.currentTimeMillis())
        }
    }

    /**
     * 获取文件名
     */
    private fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }
        return fileName
    }

    fun deleteMessage(messageId: String) { viewModelScope.launch { messageRepository.deleteMessage(messageId) } }
    fun clearChat() { viewModelScope.launch { messageRepository.clearConversationMessages(conversationId) } }
    fun reconnect() { webSocketManager.reconnect() }

    /**
     * 重发失败的消息
     */
    fun resendMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.resendMessage(messageId)
        }
    }

    /**
     * 识别图片中的文字 (OCR)
     * @param context 上下文
     * @param imageUri 图片URI
     * @param compress 是否压缩图片（用于性能对比）
     */
    fun recognizeText(context: Context, imageUri: Uri, compress: Boolean = true) {
        viewModelScope.launch {
            _ocrState.value = OcrState.Processing
            try {
                val startTime = System.currentTimeMillis()
                val runtime = Runtime.getRuntime()
                val memoryBefore = runtime.totalMemory() - runtime.freeMemory()

                // 读取图片尺寸
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(imageUri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                val originalWidth = options.outWidth
                val originalHeight = options.outHeight

                // 创建 InputImage
                val inputImage = InputImage.fromFilePath(context, imageUri)

                // 执行 OCR 识别
                val result = withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine { continuation ->
                        textRecognizer.process(inputImage)
                            .addOnSuccessListener { text ->
                                continuation.resume(text)
                            }
                            .addOnFailureListener { exception ->
                                continuation.resumeWithException(exception)
                            }
                    }
                }

                val endTime = System.currentTimeMillis()
                val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
                val memoryDeltaMB = (memoryAfter - memoryBefore) / (1024.0 * 1024.0)

                // 确定分辨率标签
                val resolutionLabel = when {
                    originalHeight <= 480 -> "480p"
                    originalHeight <= 720 -> "720p"
                    originalHeight <= 1080 -> "1080p"
                    else -> "${originalHeight}p"
                }

                // 记录性能数据
                val perfResult = OcrPerfResult(
                    imageWidth = originalWidth,
                    imageHeight = originalHeight,
                    resolutionLabel = resolutionLabel,
                    inferenceTimeMs = endTime - startTime,
                    memoryDeltaMB = memoryDeltaMB,
                    recognizedTextLength = result.text.length,
                    isCompressed = compress
                )
                OcrPerfTracker.record(perfResult)

                _ocrState.value = OcrState.Success(
                    text = result.text,
                    perfResult = perfResult
                )

                android.util.Log.d("IM_DEBUG", "OCR识别成功: ${result.text.length} chars, 耗时${endTime - startTime}ms")
            } catch (e: Exception) {
                android.util.Log.e("IM_DEBUG", "OCR识别失败: ${e.message}")
                _ocrState.value = OcrState.Error(e.message ?: "识别失败")
            }
        }
    }

    /**
     * 重置 OCR 状态
     */
    fun resetOcrState() {
        _ocrState.value = OcrState.Idle
    }

    /**
     * 打印 OCR 性能汇总
     */
    fun printOcrPerfSummary() {
        OcrPerfTracker.printSummary()
    }
}

/**
 * OCR 状态密封类
 */
sealed class OcrState {
    data object Idle : OcrState()
    data object Processing : OcrState()
    data class Success(val text: String, val perfResult: OcrPerfResult) : OcrState()
    data class Error(val message: String) : OcrState()
}
