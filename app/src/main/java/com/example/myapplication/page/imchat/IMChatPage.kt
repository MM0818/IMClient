package com.example.myapplication.page.imchat

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.websocket.MessageStatus
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
            Surface(tonalElevation = 2.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { }) { Icon(Icons.Default.Add, contentDescription = "更多") }
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
    val isFromMe = message.isFromMe
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
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isFromMe) 16.dp else 4.dp, bottomEnd = if (isFromMe) 4.dp else 16.dp))
                    .background(if (isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(text = message.content, color = if (isFromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                if (isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageStatusIcon(status = message.status)
                }
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(status: String) {
    when (MessageStatus.valueOf(status)) {
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
