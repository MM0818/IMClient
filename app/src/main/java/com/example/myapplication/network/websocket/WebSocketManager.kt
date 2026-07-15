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
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            Log.w(TAG, "WebSocket已连接或正在连接中")
            return
        }

        currentUrl = url
        currentToken = token
        currentUserId = userId
        reconnectAttempts = 0

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
                Log.d(TAG, "WebSocket连接成功")
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
                Log.d(TAG, "收到文本消息: $text")
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "收到二进制消息: ${bytes.hex()}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket正在关闭: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket已关闭: $code / $reason")
                handleDisconnection("连接关闭: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败: ${t.message}")
                handleDisconnection("连接失败: ${t.message}")
            }
        }
    }

    /**
     * 处理断开连接
     */
    private fun handleDisconnection(reason: String) {
        stopHeartbeat()
        stopAckTimeoutCheck()
        stopMessageRetry()
        _connectionState.value = ConnectionState.DISCONNECTED

        scope.launch {
            _events.emit(WebSocketEvent.Disconnected(reason))
        }

        // 自动重连
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
        webSocket?.send(jsonMessage)
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
                    Log.d(TAG, "心跳发送成功")
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
        scope.launch {
            try {
                // 尝试解析为ACK消息
                val ack = json.decodeFromString<MessageAck>(text)
                if (ack.messageId.isNotEmpty()) {
                    handleMessageAck(ack)
                    return@launch
                }
            } catch (e: Exception) {
                // 不是ACK消息，继续尝试解析为普通消息
            }

            try {
                // 解析为普通消息
                val message = json.decodeFromString<IncomingMessage>(text)
                _events.emit(WebSocketEvent.MessageReceived(message))

                // 发送确认给服务端
                sendAck(message.id, MessageStatus.DELIVERED)
            } catch (e: Exception) {
                Log.e(TAG, "消息解析失败: ${e.message}")
            }
        }
    }

    /**
     * 处理消息确认
     */
    private fun handleMessageAck(ack: MessageAck) {
        // 从未确认队列中移除，清除发送时间记录
        unacknowledgedMessages.removeAll { it.id == ack.messageId }
        messageSendTimes.remove(ack.messageId)

        // 通知UI层状态变更
        scope.launch {
            _events.emit(WebSocketEvent.MessageStatusChanged(ack.messageId, ack.status))
        }

        Log.d(TAG, "消息确认: ${ack.messageId} -> ${ack.status}")
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
        webSocket?.send(jsonMessage)
    }

    /**
     * 发送消息
     * @return 生成的消息ID
     */
    fun sendMessage(
        conversationId: String,
        receiverId: String,
        content: String,
        type: MessageType = MessageType.TEXT
    ): String {
        val messageId = UUID.randomUUID().toString()

        val message = SendMessageRequest(
            id = messageId,
            conversationId = conversationId,
            receiverId = receiverId,
            content = content,
            type = type,
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
     * 通过WebSocket发送消息
     */
    private fun sendToWebSocket(message: SendMessageRequest) {
        scope.launch {
            try {
                val jsonMessage = json.encodeToString(message)
                val success = webSocket?.send(jsonMessage) ?: false

                if (success) {
                    // 加入未确认队列，记录发送时间
                    unacknowledgedMessages.add(message)
                    messageSendTimes[message.id] = System.currentTimeMillis()
                    Log.d(TAG, "消息发送成功: ${message.id}")
                } else {
                    // 发送失败，加入待发送队列
                    pendingMessages.add(message)
                    Log.w(TAG, "消息发送失败，加入待发送队列: ${message.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "消息发送异常: ${e.message}")
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

        while (iterator.hasNext()) {
            val message = iterator.next()
            val sendTime = messageSendTimes[message.id] ?: continue

            if (now - sendTime > ACK_TIMEOUT) {
                // ACK超时，标记为失败
                Log.w(TAG, "消息ACK超时: ${message.id}")
                iterator.remove()
                messageSendTimes.remove(message.id)

                // 通知UI层
                scope.launch {
                    _events.emit(WebSocketEvent.MessageStatusChanged(message.id, MessageStatus.FAILED))
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
     * 断开连接
     */
    fun disconnect() {
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
