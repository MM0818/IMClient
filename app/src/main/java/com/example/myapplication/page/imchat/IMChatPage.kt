package com.example.myapplication.page.imchat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.websocket.MessageStatus
import com.example.myapplication.network.websocket.MessageType
import com.example.myapplication.network.websocket.WebSocketManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IMChatPage(
    onNavigateBack: () -> Unit,
    viewModel: IMChatViewModel = hiltViewModel()
) {
    val messages = viewModel.messages.collectAsLazyPagingItems()
    val inputText by viewModel.inputText.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedMessages(messages.itemCount) {
        if (messages.itemCount > 0) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(viewModel.contactName, fontWeight = FontWeight.Bold)
                        Text(
                            text = when (connectionState) {
                                WebSocketManager.ConnectionState.CONNECTED -> "在线"
                                WebSocketManager.ConnectionState.RECONNECTING -> "重连中..."
                                else -> "离线"
                            },
                            fontSize = 12.sp,
                            color = when (connectionState) {
                                WebSocketManager.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                WebSocketManager.ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                }
            )
        },
        bottomBar = {
            val context = LocalContext.current

            // 图片选择器
            val imagePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let { viewModel.sendImageMessage(context, it) }
            }

            // 文件选择器
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let { viewModel.sendFileMessage(context, it) }
            }

            var showMoreOptions by remember { mutableStateOf(false) }

            Surface(tonalElevation = 2.dp) {
                Column {
                    // 更多选项面板
                    if (showMoreOptions) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // 图片按钮
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = {
                                        imagePickerLauncher.launch("image/*")
                                        showMoreOptions = false
                                    }
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = "图片", tint = MaterialTheme.colorScheme.primary)
                                }
                                Text("图片", fontSize = 12.sp)
                            }

                            // 文件按钮
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = {
                                        filePickerLauncher.launch("*/*")
                                        showMoreOptions = false
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "文件", tint = MaterialTheme.colorScheme.primary)
                                }
                                Text("文件", fontSize = 12.sp)
                            }
                        }
                    }

                    // 输入栏
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { showMoreOptions = !showMoreOptions }) {
                            Icon(
                                if (showMoreOptions) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = "更多"
                            )
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { viewModel.updateInputText(it) },
                            modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 120.dp),
                            placeholder = { Text("输入消息") },
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { viewModel.sendMessage() }, enabled = inputText.isNotBlank()) {
                            Icon(Icons.Default.Send, contentDescription = "发送", tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 8.dp), state = listState, reverseLayout = true) {
            items(count = messages.itemCount, key = { index -> messages.peek(index)?.id ?: index }) { index ->
                messages[index]?.let { MessageItem(message = it) }
            }
        }
    }
}

@Composable
private fun MessageItem(message: MessageEntity) {
    // 使用remember缓存计算结果，避免不必要的重组
    val isFromMe = remember(message) { message.isFromMe }
    val formattedTime = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    val bubbleShape = remember(isFromMe) {
        RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isFromMe) 16.dp else 4.dp,
            bottomEnd = if (isFromMe) 4.dp else 16.dp
        )
    }
    val messageType = remember(message.type) { message.type }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start) {
        if (!isFromMe) {
            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier.widthIn(max = 280.dp)
                    .clip(bubbleShape)
                    .background(if (isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                when (messageType) {
                    "IMAGE" -> {
                        // 图片消息
                        if (message.fileUrl.isNotEmpty()) {
                            AsyncImage(
                                model = message.fileUrl,
                                contentDescription = "图片",
                                modifier = Modifier.widthIn(max = 200.dp).heightIn(max = 200.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else if (message.thumbnailUrl.isNotEmpty()) {
                            AsyncImage(
                                model = message.thumbnailUrl,
                                contentDescription = "图片",
                                modifier = Modifier.widthIn(max = 200.dp).heightIn(max = 200.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            // 上传中显示进度
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp), tint = if (isFromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = message.uploadProgress,
                                    modifier = Modifier.width(100.dp)
                                )
                                Text("上传中 ${(message.uploadProgress * 100).toInt()}%", fontSize = 12.sp)
                            }
                        }
                    }
                    "FILE" -> {
                        // 文件消息
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(32.dp), tint = if (isFromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = message.fileName.ifEmpty { message.content },
                                    color = if (isFromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    maxLines = 2
                                )
                                if (message.fileSize > 0) {
                                    Text(
                                        text = formatFileSize(message.fileSize),
                                        fontSize = 12.sp,
                                        color = if (isFromMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        // 文本消息
                        Text(text = message.content, color = if (isFromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = formattedTime, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                if (isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageStatusIcon(status = message.status)
                }
            }
        }
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

@Composable
private fun MessageStatusIcon(status: String) {
    // 使用remember缓存状态枚举值
    val messageStatus = remember(status) { MessageStatus.valueOf(status) }

    when (messageStatus) {
        MessageStatus.SENDING -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.outline)
        MessageStatus.SENT -> Icon(Icons.Default.Done, contentDescription = "已发送", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        MessageStatus.DELIVERED -> Icon(Icons.Default.CheckCircle, contentDescription = "已送达", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        MessageStatus.READ -> Icon(Icons.Default.CheckCircle, contentDescription = "已读", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        MessageStatus.FAILED -> Icon(Icons.Default.Warning, contentDescription = "发送失败", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LaunchedMessages(itemCount: Int, block: suspend () -> Unit) {
    LaunchedEffect(itemCount) { block() }
}
