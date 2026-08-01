package com.example.myapplication.network.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket生命周期管理器
 * 功能：
 * 1. 心跳保活（基于OkHttp Ping机制）
 * 2. 指数退避重连策略
 * 3. 本地Pending消息队列（弱网下消息不丢失）
 * 4. 消息状态追踪
 */
@Singleton
class WebSocketManager @Inject constructor() {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val HEARTBEAT_INTERVAL = 30_000L // 30秒心跳间隔
        private const val INITIAL_RECONNECT_DELAY = 1000L // 初始重连延迟1秒
        private const val MAX_RECONNECT_DELAY = 30_000L // 最大重连延迟30秒
        private const val MAX_RECONNECT_ATTEMPTS = 10 // 最大重连次数
        private const val ACK_TIMEOUT = 10_000L // ACK超时时间10秒
        private const val MESSAGE_RETRY_INTERVAL = 5_000L // 消息重试间隔5秒
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true  // 必须：否则默认值字段（如type）不会序列化
    }

    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // WebSocket事件流（供UI层监听）
    private val _events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    // 待发送消息队列
    private val pendingMessages = ConcurrentLinkedQueue<SendMessageRequest>()

    // 已发送但未确认的消息
    private val unacknowledgedMessages = ConcurrentLinkedQueue<SendMessageRequest>()

    // 重连相关
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    // 心跳相关
    private var heartbeatJob: Job? = null

    // ACK超时检测Job
    private var ackTimeoutJob: Job? = null

    // 消息重试Job
    private var messageRetryJob: Job? = null

    // 消息发送时间记录（用于ACK超时检测）
    private val messageSendTimes = ConcurrentHashMap<String, Long>()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 当前连接URL和Token
    private var currentUrl: String = ""
    private var currentToken: String = ""
    private var currentUserId: String = ""

    // 主动断开标志（被踢、用户登出时为true，不触发自动重连）
    @Volatile
    private var isIntentionalDisconnect = false

    /**
     * 连接状态枚举
     */
    enum class ConnectionState {
        CONNECTED,
        CONNECTING,
        DISCONNECTED,
        RECONNECTING
    }

    /**
     * 初始化WebSocket连接
     * @param url WebSocket服务器地址
     * @param token 用户认证token
     * @param userId 用户ID
     */
    fun connect(url: String, token: String, userId: String) {
        Log.d(TAG, "connect() called: url=$url, userId=$userId, token=${token.take(20)}...")

        // 如果是不同用户尝试连接，先断开旧连接
        if (currentUserId.isNotEmpty() && currentUserId != userId) {
            Log.w(TAG, "检测到用户切换: $currentUserId → $userId, 断开旧连接")
            disconnect()
        }

        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            Log.w(TAG, "WebSocket已连接或正在连接中，state=${_connectionState.value}")
            return
        }

        currentUrl = url
        currentToken = token
        currentUserId = userId
        reconnectAttempts = 0
        isIntentionalDisconnect = false

        _connectionState.value = ConnectionState.CONNECTING

        // 创建OkHttpClient，配置超时和Ping
        okHttpClient = OkHttpClient.Builder()
            .pingInterval(HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS) // OkHttp自动Ping
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket长连接不设置读超时
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient?.newWebSocket(request, createWebSocketListener())
    }

    /**
     * 创建WebSocket监听器
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket连接成功, url=$currentUrl, response=${response.code}")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0

                // 发送认证消息
                sendAuthMessage()

                // 启动心跳
                startHeartbeat()

                // 启动ACK超时检测
                startAckTimeoutCheck()

                // 重试未确认的消息并发送待发送队列
                startMessageRetry()

                // 通知UI层
                scope.launch {
                    _events.emit(WebSocketEvent.Connected(System.currentTimeMillis()))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "<<< onMessage文本: $text")
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "<<< onMessage二进制: ${bytes.hex()}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket正在关闭: code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket已关闭: code=$code, reason=$reason")
                handleDisconnection("连接关闭: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "响应: ${response?.code} ${response?.message}")
                Log.e(TAG, "异常详情", t)
                handleDisconnection("连接失败: ${t.message}")
            }
        }
    }

    /**
     * 处理断开连接
     */
    private fun handleDisconnection(reason: String) {
        Log.w(TAG, "处理断开连接: $reason, isIntentionalDisconnect=$isIntentionalDisconnect")
        stopHeartbeat()
        stopAckTimeoutCheck()
        stopMessageRetry()
        _connectionState.value = ConnectionState.DISCONNECTED

        scope.launch {
            _events.emit(WebSocketEvent.Disconnected(reason))
        }

        // 主动断开（被踢/登出）不触发自动重连
        if (isIntentionalDisconnect) {
            Log.d(TAG, "主动断开，跳过自动重连")
            return
        }

        // 被动断开，自动重连
        attemptReconnect()
    }

    /**
     * 指数退避重连策略
     */
    private fun attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "达到最大重连次数，停止重连")
            scope.launch {
                _events.emit(WebSocketEvent.Error(Exception("重连失败")))
            }
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _connectionState.value = ConnectionState.RECONNECTING

            // 指数退避：1s, 2s, 4s, 8s, 16s, 30s, 30s...
            val delay = minOf(
                INITIAL_RECONNECT_DELAY * (1L shl reconnectAttempts),
                MAX_RECONNECT_DELAY
            )

            Log.d(TAG, "将在 ${delay}ms 后尝试重连 (第${reconnectAttempts + 1}次)")
            _events.emit(WebSocketEvent.Reconnecting)

            delay(delay)
            reconnectAttempts++

            // 重新连接
            connect(currentUrl, currentToken, currentUserId)
        }
    }

    /**
     * 发送认证消息
     */
    private fun sendAuthMessage() {
        val authMessage = AuthMessage(token = currentToken, userId = currentUserId)
        val jsonMessage = json.encodeToString(authMessage)
        Log.d(TAG, "发送认证消息: $jsonMessage")
        val success = webSocket?.send(jsonMessage) ?: false
        Log.d(TAG, "认证消息发送结果: $success")
    }

    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                try {
                    val heartbeat = Heartbeat()
                    val jsonMessage = json.encodeToString(heartbeat)
                    val success = webSocket?.send(jsonMessage) ?: false
                    if (!success) {
                        Log.w(TAG, "心跳发送失败")
                        handleDisconnection("心跳失败")
                        break
                    }
                    Log.d(TAG, "心跳发送成功: $jsonMessage")
                } catch (e: Exception) {
                    Log.e(TAG, "心跳发送异常: ${e.message}")
                    handleDisconnection("心跳异常")
                    break
                }
            }
        }
    }

    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 处理收到的消息
     */
    private fun handleIncomingMessage(text: String) {
        Log.d(TAG, "<<< 收到原始消息: $text")
        scope.launch {
            // 先提取 type 字段做路由
            val typeValue = try {
                json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(text)["type"]?.toString()?.trim('"')
            } catch (e: Exception) {
                null
            }
            Log.d(TAG, "[MSG-ROUTE] type字段=$typeValue")

            // 心跳响应
            if (typeValue == "pong") {
                Log.d(TAG, "[MSG-ROUTE] → 识别为心跳pong, 跳过")
                return@launch
            }

            // 被踢下线
            if (typeValue == "kicked") {
                val reason = try {
                    json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(text)["reason"]?.toString()?.trim('"') ?: "您的账号在其他设备登录"
                } catch (e: Exception) {
                    "您的账号在其他设备登录"
                }
                Log.w(TAG, "[MSG-ROUTE] → 识别为kicked: reason=$reason")
                _events.emit(WebSocketEvent.Kicked(reason))
                disconnect()
                return@launch
            }

            // 有 messageId + status 且没有 type(或type不是pong/auth) 的是 ACK
            val hasMessageId = text.contains("\"messageId\"")
            val hasStatus = text.contains("\"status\"")
            Log.d(TAG, "[MSG-ROUTE] hasMessageId=$hasMessageId, hasStatus=$hasStatus, typeValue==null?${typeValue == null}")

            if (hasMessageId && hasStatus && typeValue == null) {
                try {
                    val ack = json.decodeFromString<MessageAck>(text)
                    Log.d(TAG, "[MSG-ROUTE] → 识别为ACK回执: messageId=${ack.messageId}, status=${ack.status}")
                    handleMessageAck(ack)
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "[MSG-ROUTE] ACK解析失败: ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "[MSG-ROUTE] 原始文本: $text")
                }
            }

            // 普通消息（有 id + conversationId + senderId）
            try {
                val message = json.decodeFromString<IncomingMessage>(text)
                Log.d(TAG, "[SORT-DEBUG] 收到消息: id=${message.id}, senderId=${message.senderId}, clientTimestamp=${message.timestamp}, serverTimestamp=${message.serverTimestamp}, content=${message.content}")
                Log.d(TAG, "[MSG-ROUTE] → 识别为普通消息: id=${message.id}, senderId=${message.senderId}, content=${message.content}")
                _events.emit(WebSocketEvent.MessageReceived(message))

                // 发送确认给服务端
                sendAck(message.id, MessageStatus.DELIVERED)
            } catch (e: Exception) {
                Log.e(TAG, "[MSG-ROUTE] 普通消息解析失败: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "[MSG-ROUTE] 原始文本: $text")
            }
        }
    }

    /**
     * 处理消息确认
     */
    private fun handleMessageAck(ack: MessageAck) {
        Log.d(TAG, "[ACK] 开始处理ACK: messageId=${ack.messageId}, status=${ack.status}")
        Log.d(TAG, "[ACK] 处理前未确认队列大小=${unacknowledgedMessages.size}, 队列IDs=${unacknowledgedMessages.map { it.id }}")

        val removed = unacknowledgedMessages.removeAll { it.id == ack.messageId }
        messageSendTimes.remove(ack.messageId)

        Log.d(TAG, "[ACK] 从队列移除结果: removed=$removed, 处理后队列大小=${unacknowledgedMessages.size}")

        // SENT = 服务端已收到，显示单勾；DELIVERED = 对方已收到，显示双勾
        // 两者都需要通知UI更新
        if (ack.status >= MessageStatus.SENT) {
            scope.launch {
                try {
                    val event = WebSocketEvent.MessageStatusChanged(ack.messageId, ack.status)
                    Log.d(TAG, "[ACK] 准备emit事件: messageId=${event.messageId}, status=${event.status}")
                    val emitted = _events.tryEmit(event)
                    Log.d(TAG, "[ACK] 事件emit结果: $emitted, messageId=${ack.messageId}")
                } catch (e: Exception) {
                    Log.e(TAG, "[ACK] 事件emit异常: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * 发送确认消息
     */
    private fun sendAck(messageId: String, status: MessageStatus) {
        val ack = MessageAck(
            messageId = messageId,
            status = status,
            serverTimestamp = System.currentTimeMillis()
        )
        val jsonMessage = json.encodeToString(ack)
        Log.d(TAG, "发送ACK: $jsonMessage")
        val success = webSocket?.send(jsonMessage) ?: false
        Log.d(TAG, "ACK发送结果: $success")
    }

    /**
     * 发送消息
     * @param existingMessageId 外部传入的消息ID（保证Room和WebSocket使用同一个ID），为空则自动生成
     * @return 使用的消息ID
     */
    fun sendMessage(
        conversationId: String,
        receiverId: String,
        content: String,
        type: MessageType = MessageType.TEXT,
        existingMessageId: String? = null
    ): String {
        val messageId = existingMessageId ?: UUID.randomUUID().toString()

        val message = SendMessageRequest(
            id = messageId,
            conversationId = conversationId,
            receiverId = receiverId,
            content = content,
            type = type.name,
            timestamp = System.currentTimeMillis()
        )

        if (_connectionState.value == ConnectionState.CONNECTED) {
            // 已连接，直接发送
            sendToWebSocket(message)
        } else {
            // 未连接，加入待发送队列
            pendingMessages.add(message)
            Log.d(TAG, "消息加入待发送队列: $messageId")
        }

        return messageId
    }

    /**
     * 重试已存在的消息（使用相同messageId，保证幂等去重）
     * 用于用户点击失败消息重发、重连后自动重发等场景
     */
    fun retryPendingMessage(messageId: String, conversationId: String, receiverId: String, content: String, type: MessageType = MessageType.TEXT) {
        val message = SendMessageRequest(
            id = messageId,
            conversationId = conversationId,
            receiverId = receiverId,
            content = content,
            type = type.name,
            timestamp = System.currentTimeMillis()
        )

        if (_connectionState.value == ConnectionState.CONNECTED) {
            sendToWebSocket(message)
        } else {
            pendingMessages.add(message)
            Log.d(TAG, "重试消息加入待发送队列: $messageId")
        }
    }

    /**
     * 通过WebSocket发送消息
     */
    private fun sendToWebSocket(message: SendMessageRequest) {
        scope.launch {
            try {
                val jsonMessage = json.encodeToString(message)
                Log.d(TAG, ">>> 发送消息JSON: $jsonMessage")
                val success = webSocket?.send(jsonMessage) ?: false

                if (success) {
                    // 加入未确认队列，记录发送时间，等待服务端SENT ACK确认
                    unacknowledgedMessages.add(message)
                    messageSendTimes[message.id] = System.currentTimeMillis()
                    Log.d(TAG, ">>> 消息已写入WebSocket通道: id=${message.id}, conversationId=${message.conversationId}, receiverId=${message.receiverId}")
                    Log.d(TAG, ">>> 加入未确认队列, 当前未确认数=${unacknowledgedMessages.size}, 等待服务端SENT ACK")
                } else {
                    // 发送失败，加入待发送队列
                    pendingMessages.add(message)
                    Log.w(TAG, "消息发送失败(WebSocket.send返回false): ${message.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "消息发送异常: ${e.javaClass.simpleName}: ${e.message}", e)
                pendingMessages.add(message)
            }
        }
    }

    /**
     * 启动ACK超时检测
     */
    private fun startAckTimeoutCheck() {
        stopAckTimeoutCheck()
        ackTimeoutJob = scope.launch {
            while (isActive) {
                delay(MESSAGE_RETRY_INTERVAL)
                checkAckTimeouts()
            }
        }
    }

    /**
     * 停止ACK超时检测
     */
    private fun stopAckTimeoutCheck() {
        ackTimeoutJob?.cancel()
        ackTimeoutJob = null
    }

    /**
     * 检查ACK超时
     */
    private fun checkAckTimeouts() {
        val now = System.currentTimeMillis()
        val iterator = unacknowledgedMessages.iterator()

        Log.d(TAG, "[ACK-TIMEOUT] 开始检查, 未确认队列大小=${unacknowledgedMessages.size}")

        while (iterator.hasNext()) {
            val message = iterator.next()
            val sendTime = messageSendTimes[message.id] ?: continue
            val elapsed = now - sendTime

            Log.d(TAG, "[ACK-TIMEOUT] 检查消息: id=${message.id}, 已等待${elapsed}ms, 超时阈值=${ACK_TIMEOUT}ms")

            if (elapsed > ACK_TIMEOUT) {
                // ACK超时，标记为失败
                Log.w(TAG, "[ACK-TIMEOUT] 消息ACK超时! id=${message.id}, 已等待${elapsed}ms")
                iterator.remove()
                messageSendTimes.remove(message.id)

                // 通知UI层
                scope.launch {
                    val event = WebSocketEvent.MessageStatusChanged(message.id, MessageStatus.FAILED)
                    val emitted = _events.tryEmit(event)
                    Log.w(TAG, "[ACK-TIMEOUT] 发送FAILED事件: id=${message.id}, emitted=$emitted")
                }
            }
        }
    }

    /**
     * 启动消息重试（重连后重新发送未确认的消息）
     */
    private fun startMessageRetry() {
        stopMessageRetry()
        messageRetryJob = scope.launch {
            delay(1000) // 等待连接稳定
            retryUnacknowledgedMessages()
            flushPendingMessages()
        }
    }

    /**
     * 停止消息重试
     */
    private fun stopMessageRetry() {
        messageRetryJob?.cancel()
        messageRetryJob = null
    }

    /**
     * 重试未确认的消息
     */
    private fun retryUnacknowledgedMessages() {
        scope.launch {
            val messagesToRetry = unacknowledgedMessages.toList()
            unacknowledgedMessages.clear()
            messageSendTimes.clear()

            for (message in messagesToRetry) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    sendToWebSocket(message)
                    delay(100) // 避免消息过快发送
                } else {
                    // 连接断开，加入待发送队列
                    pendingMessages.add(message)
                }
            }
        }
    }

    /**
     * 刷新待发送队列
     */
    private fun flushPendingMessages() {
        scope.launch {
            while (pendingMessages.isNotEmpty()) {
                val message = pendingMessages.poll() ?: break
                sendToWebSocket(message)
                delay(100) // 避免消息过快发送
            }
        }
    }

    /**
     * 断开连接（主动断开，不触发自动重连）
     */
    fun disconnect() {
        isIntentionalDisconnect = true
        stopHeartbeat()
        stopAckTimeoutCheck()
        stopMessageRetry()
        reconnectJob?.cancel()
        webSocket?.close(1000, "用户主动断开")
        webSocket = null
        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 重新连接
     */
    fun reconnect() {
        isIntentionalDisconnect = false
        disconnect()
        reconnectAttempts = 0
        connect(currentUrl, currentToken, currentUserId)
    }

    /**
     * 获取当前连接状态
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED
    }

    /**
     * 清理资源
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }

    /**
     * 获取当前用户ID
     */
    fun getCurrentUserId(): String {
        return currentUserId
    }
}
