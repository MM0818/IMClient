package com.example.myapplication.page.imchat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.example.myapplication.database.im.entity.MessageEntity
import com.example.myapplication.network.websocket.MessageStatus
import com.example.myapplication.network.websocket.MessageType
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IMChatPage(
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    viewModel: IMChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // 观察被踢下线事件
    LaunchedEffect(Unit) {
        viewModel.kickedEvent.collect { reason ->
            android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_LONG).show()
            onNavigateToLogin()
        }
    }

    val messages = viewModel.messages.collectAsLazyPagingItems()
    val inputText by viewModel.inputText.collectAsState()
    val contactOnline by viewModel.contactOnline.collectAsState()
    val ocrState by viewModel.ocrState.collectAsState()
    val listState = rememberLazyListState()

    // OCR 相关状态
    var showOcrResult by remember { mutableStateOf(false) }
    var showOcrOptions by remember { mutableStateOf(false) }
    var ocrResultText by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    // 拍照 launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            viewModel.recognizeText(context, photoUri!!, compress = false)
        }
    }

    // 相册选择 launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.recognizeText(context, it, compress = false) }
    }

    // 相机权限 launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = File(context.cacheDir, "ocr_photo_${System.currentTimeMillis()}.jpg")
            photoUri = FileProvider.getUriForFile(context, "${context.packageName}.image.provider", file)
            cameraLauncher.launch(photoUri!!)
        } else {
            Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // 观察 OCR 状态
    LaunchedEffect(ocrState) {
        when (val state = ocrState) {
            is OcrState.Success -> {
                ocrResultText = state.text
                showOcrResult = true
                viewModel.resetOcrState()
            }
            is OcrState.Error -> {
                Toast.makeText(context, "OCR识别失败: ${state.message}", Toast.LENGTH_SHORT).show()
                viewModel.resetOcrState()
            }
            else -> {}
        }
    }

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
                            text = if (contactOnline) "在线" else "离线",
                            fontSize = 12.sp,
                            color = if (contactOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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

                            // OCR 识字按钮
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = {
                                        showOcrOptions = true
                                        showMoreOptions = false
                                    }
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "OCR识字", tint = MaterialTheme.colorScheme.primary)
                                }
                                Text("OCR识字", fontSize = 12.sp)
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
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (messages.itemCount == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Email,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无消息", color = MaterialTheme.colorScheme.outline)
                        Text("发送第一条消息开始聊天吧", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, reverseLayout = true) {
                    items(count = messages.itemCount, key = { index -> messages.peek(index)?.id ?: index }) { index ->
                        messages[index]?.let { entity ->
                            MessageItem(
                                message = entity,
                                onRetryClick = { viewModel.resendMessage(entity.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // OCR 选项弹窗（选择拍照或相册）
    if (showOcrOptions) {
        AlertDialog(
            onDismissRequest = { showOcrOptions = false },
            title = { Text("选择图片来源") },
            text = { Text("请选择获取图片的方式") },
            confirmButton = {
                TextButton(onClick = {
                    showOcrOptions = false
                    // 检查相机权限
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        val file = File(context.cacheDir, "ocr_photo_${System.currentTimeMillis()}.jpg")
                        photoUri = FileProvider.getUriForFile(context, "${context.packageName}.image.provider", file)
                        cameraLauncher.launch(photoUri!!)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Text("拍照")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOcrOptions = false
                    galleryLauncher.launch("image/*")
                }) {
                    Text("相册")
                }
            }
        )
    }

    // OCR 结果弹窗
    if (showOcrResult) {
        ModalBottomSheet(
            onDismissRequest = { showOcrResult = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "OCR 识别结果",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 识别结果文本
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = ocrResultText.ifEmpty { "未识别到文字" },
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 复制到输入框
                    OutlinedButton(onClick = {
                        viewModel.updateInputText(ocrResultText)
                        showOcrResult = false
                        Toast.makeText(context, "已复制到输入框", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制到输入框")
                    }

                    // 直接发送
                    Button(onClick = {
                        if (ocrResultText.isNotBlank()) {
                            viewModel.updateInputText(ocrResultText)
                            viewModel.sendMessage()
                            showOcrResult = false
                            Toast.makeText(context, "已发送", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("直接发送")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // OCR 处理中状态
    if (ocrState is OcrState.Processing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun MessageItem(message: MessageEntity, onRetryClick: () -> Unit = {}) {
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
                    MessageStatusIcon(status = message.status, onRetryClick = onRetryClick)
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
private fun MessageStatusIcon(status: String, onRetryClick: (() -> Unit)? = null) {
    val messageStatus = try {
        MessageStatus.valueOf(status)
    } catch (e: Exception) {
        MessageStatus.SENDING
    }

    when (messageStatus) {
        MessageStatus.SENDING -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.outline
        )
        MessageStatus.SENT -> Icon(
            Icons.Default.Done,
            contentDescription = "已发送",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        MessageStatus.DELIVERED -> DoubleCheckIcon(
            tint = MaterialTheme.colorScheme.outline
        )
        MessageStatus.READ -> DoubleCheckIcon(
            tint = Color(0xFF2196F3)
        )
        MessageStatus.FAILED -> Icon(
            Icons.Default.Warning,
            contentDescription = "发送失败，点击重试",
            modifier = Modifier
                .size(14.dp)
                .clickable { onRetryClick?.invoke() },
            tint = MaterialTheme.colorScheme.error
        )
    }
}

/**
 * 双对勾图标（用Canvas绘制，避免引入material-icons-extended）
 */
@Composable
private fun DoubleCheckIcon(tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val color = tint

        // 第一个对勾（靠左）
        drawLine(
            color = color,
            start = Offset(size.width * 0.05f, size.height * 0.50f),
            end = Offset(size.width * 0.28f, size.height * 0.72f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.28f, size.height * 0.72f),
            end = Offset(size.width * 0.45f, size.height * 0.30f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // 第二个对勾（靠右，稍有重叠）
        drawLine(
            color = color,
            start = Offset(size.width * 0.30f, size.height * 0.50f),
            end = Offset(size.width * 0.53f, size.height * 0.72f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.53f, size.height * 0.72f),
            end = Offset(size.width * 0.90f, size.height * 0.25f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun LaunchedMessages(itemCount: Int, block: suspend () -> Unit) {
    LaunchedEffect(itemCount) { block() }
}
