package com.example.myapplication.page.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.myapplication.database.im.entity.ConversationEntity
import com.example.myapplication.network.user.UserItem
import com.example.myapplication.network.websocket.WebSocketManager
import com.example.myapplication.page.login.LoginViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListPage(
    onNavigateToChat: (conversationId: String, contactName: String) -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel(),
    loginViewModel: LoginViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val users by loginViewModel.users.collectAsState()
    val isLoadingUsers by loginViewModel.isLoadingUsers.collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    var showNewConversationDialog by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 新建会话对话框（显示用户列表）
    if (showNewConversationDialog) {
        NewConversationDialog(
            users = users,
            isLoading = isLoadingUsers,
            searchKeyword = searchKeyword,
            onSearchChange = { searchKeyword = it; loginViewModel.searchUsers(it) },
            onDismiss = { showNewConversationDialog = false; searchKeyword = "" },
            onSelectUser = { user ->
                showNewConversationDialog = false
                searchKeyword = ""
                scope.launch {
                    val (conversationId, name) = viewModel.createConversation(user.userId, user.username)
                    onNavigateToChat(conversationId, name)
                }
            }
        )
    }

    LaunchedEffect(showNewConversationDialog) {
        if (showNewConversationDialog) {
            loginViewModel.fetchUsers()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("消息", fontWeight = FontWeight.Bold)
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
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { showNewConversationDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建会话")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("搜索会话") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearSearch() }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true
                )
            }

            val displayList = if (searchQuery.isBlank()) conversations else searchResults

            if (displayList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无会话", color = MaterialTheme.colorScheme.outline)
                        Text("点击右上角 + 开始聊天", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = displayList, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            onClick = { onNavigateToChat(conversation.id, conversation.contactName) },
                            onDelete = { viewModel.deleteConversation(conversation.id) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationItem(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // 使用remember缓存计算结果，避免不必要的重组
    val hasUnread = remember(conversation.unreadCount) { conversation.unreadCount > 0 }
    val formattedTime = remember(conversation.lastMessageTime) { formatTime(conversation.lastMessageTime) }
    val lastMessage = remember(conversation.lastMessage) { conversation.lastMessage.ifEmpty { "暂无消息" } }
    val unreadText = remember(conversation.unreadCount) {
        if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()
    }
    val initial = remember(conversation.contactName) { conversation.contactName.take(1) }

    val dismissState = rememberDismissState(confirmValueChange = {
        if (it == DismissValue.DismissedToStart) { onDelete(); true } else false
    })

    SwipeToDismiss(
        state = dismissState,
        background = {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.White)
            }
        },
        dismissContent = {
            Surface(modifier = Modifier.clickable(onClick = onClick)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        if (conversation.contactAvatar.isNotEmpty()) {
                            AsyncImage(model = conversation.contactAvatar, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Text(text = initial, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = conversation.contactName, fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(text = formattedTime, fontSize = 12.sp, color = if (hasUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = lastMessage, fontSize = 14.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (hasUnread) {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text(text = unreadText, color = Color.White, fontSize = 12.sp)
                                }
                            }
                            if (conversation.isTop) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "置顶", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 172_800_000 -> "昨天"
        else -> "${Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.MONTH) + 1}/${Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.DAY_OF_MONTH)}"
    }
}

@Composable
private fun NewConversationDialog(
    users: List<UserItem>,
    isLoading: Boolean,
    searchKeyword: String,
    onSearchChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectUser: (UserItem) -> Unit
) {
    val initial = remember { { user: UserItem -> user.username.take(1) } }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择联系人") },
        text = {
            Column(modifier = Modifier.width(300.dp)) {
                OutlinedTextField(
                    value = searchKeyword,
                    onValueChange = onSearchChange,
                    label = { Text("搜索用户") },
                    placeholder = { Text("输入用户名") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (users.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("暂无用户", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = users, key = { it.userId }) { user ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectUser(user) },
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                        if (!user.avatar.isNullOrEmpty()) {
                                            AsyncImage(model = user.avatar, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Text(text = initial(user), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = user.username, fontSize = 16.sp)
                                        if (user.online) {
                                            Text(text = "在线", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Text(text = "离线", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                    if (user.online) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
