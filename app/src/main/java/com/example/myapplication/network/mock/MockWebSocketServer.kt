package com.example.myapplication.network.mock

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.UUID

/**
 * 本地Mock WebSocket服务器
 * 用于测试完整消息流程，无需真实服务端
 */
class MockWebSocketServer {

    companion object {
        private const val TAG = "MockWebSocketServer"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var mockWebServer: MockWebServer? = null
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 消息回调
    var onMessageReceived: ((String) -> Unit)? = null

    /**
     * 启动Mock服务器
     * @return 服务器地址（ws://localhost:port）
     */
    fun start(): String {
        mockWebServer = MockWebServer().apply {
            // 设置WebSocket响应
            enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Mock服务器：客户端已连接")
                    this@MockWebSocketServer.webSocket = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Mock服务器收到消息: $text")
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Mock服务器：连接已关闭")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Mock服务器错误: ${t.message}")
                }
            }))
        }

        val url = "ws://${mockWebServer?.hostName}:${mockWebServer?.port}/ws/im"
        Log.d(TAG, "Mock服务器已启动: $url")
        return url
    }

    /**
     * 处理收到的消息
     */
    private fun handleMessage(text: String) {
        scope.launch {
            try {
                // 尝试解析为认证消息
                if (text.contains("\"type\":\"auth\"")) {
                    Log.d(TAG, "收到认证消息")
                    // 模拟认证成功，返回连接确认
                    delay(100)
                    return@launch
                }

                // 尝试解析为心跳
                if (text.contains("\"type\":\"ping\"")) {
                    Log.d(TAG, "收到心跳")
                    // 返回心跳响应
                    val pong = """{"type":"pong","timestamp":${System.currentTimeMillis()}}"""
                    webSocket?.send(pong)
                    return@launch
                }

                // 尝试解析为发送消息
                if (text.contains("\"conversationId\"")) {
                    Log.d(TAG, "收到发送消息请求")
                    handleMessageSend(text)
                    return@launch
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理消息失败: ${e.message}")
            }
        }
    }

    /**
     * 处理消息发送
     */
    private suspend fun handleMessageSend(text: String) {
        try {
            // 解析消息
            val messageJson = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(text)
            val messageId = messageJson["id"]?.toString()?.removeSurrounding("\"") ?: return
            val conversationId = messageJson["conversationId"]?.toString()?.removeSurrounding("\"") ?: return
            val content = messageJson["content"]?.toString()?.removeSurrounding("\"") ?: return

            // 1. 先返回SENT确认
            delay(50)
            val sentAck = """{"messageId":"$messageId","status":"SENT","serverTimestamp":${System.currentTimeMillis()}}"""
            webSocket?.send(sentAck)
            Log.d(TAG, "返回SENT确认: $messageId")

            // 2. 模拟对方已送达
            delay(500)
            val deliveredAck = """{"messageId":"$messageId","status":"DELIVERED","serverTimestamp":${System.currentTimeMillis()}}"""
            webSocket?.send(deliveredAck)
            Log.d(TAG, "返回DELIVERED确认: $messageId")

            // 3. 模拟对方已读
            delay(1000)
            val readAck = """{"messageId":"$messageId","status":"READ","serverTimestamp":${System.currentTimeMillis()}}"""
            webSocket?.send(readAck)
            Log.d(TAG, "返回READ确认: $messageId")

            // 4. 模拟对方回复消息（50%概率）
            if (Math.random() > 0.5) {
                delay(2000)
                val replyMessageId = UUID.randomUUID().toString()
                val replyContent = getAutoReply(content)
                val reply = """{"id":"$replyMessageId","conversationId":"$conversationId","senderId":"mock_user","content":"$replyContent","type":"TEXT","timestamp":${System.currentTimeMillis()}}"""
                webSocket?.send(reply)
                Log.d(TAG, "发送自动回复: $replyContent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理消息发送失败: ${e.message}")
        }
    }

    /**
     * 获取自动回复内容
     */
    private fun getAutoReply(originalMessage: String): String {
        val replies = listOf(
            "收到你的消息了！",
            "好的，我知道了。",
            "嗯嗯，明白了。",
            "哈哈，有意思！",
            "好的，稍后回复你。",
            "收到！",
            "了解！",
            "没问题！"
        )
        return replies.random()
    }

    /**
     * 发送消息（模拟服务端推送）
     */
    fun sendMessage(message: String) {
        webSocket?.send(message)
    }

    /**
     * 停止服务器
     */
    fun stop() {
        scope.cancel()
        webSocket?.close(1000, "服务器关闭")
        mockWebServer?.shutdown()
        mockWebServer = null
        Log.d(TAG, "Mock服务器已停止")
    }
}
