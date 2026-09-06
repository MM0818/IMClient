package com.example.myapplication.network.upload

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件上传管理器
 * 特性：
 * 1. 大文件分片上传
 * 2. 断点续传
 * 3. 自动重试失败的分片
 * 4. 上传进度追踪
 * 5. 并发分片上传控制
 */
@Singleton
class FileUploadManager @Inject constructor(
    private val uploadApi: FileUploadApi
) {

    companion object {
        private const val TAG = "FileUploadManager"
        private const val DEFAULT_CHUNK_SIZE = 1024 * 1024  // 1MB
        private const val MAX_CHUNK_SIZE = 5 * 1024 * 1024  // 5MB
        private const val MAX_RETRY_COUNT = 3
        private const val MAX_CONCURRENT_CHUNKS = 3
        private const val RETRY_DELAY = 1000L  // 1秒
    }

    // 上传任务队列
    private val uploadTasks = ConcurrentHashMap<String, UploadTask>()

    // 上传状态
    private val _uploadStates = ConcurrentHashMap<String, MutableStateFlow<UploadState>>()

    // 上传进度
    private val _uploadProgress = ConcurrentHashMap<String, MutableStateFlow<Float>>()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 获取上传任务状态
     */
    fun getUploadState(taskId: String): StateFlow<UploadState> {
        return _uploadStates.getOrPut(taskId) { MutableStateFlow(UploadState.PENDING) }.asStateFlow()
    }

    /**
     * 获取上传进度
     */
    fun getUploadProgress(taskId: String): StateFlow<Float> {
        return _uploadProgress.getOrPut(taskId) { MutableStateFlow(0f) }.asStateFlow()
    }

    /**
     * 获取上传任务
     */
    fun getUploadTask(taskId: String): UploadTask? {
        return uploadTasks[taskId]
    }

    /**
     * 创建上传任务
     */
    fun createUploadTask(
        context: Context,
        fileUri: android.net.Uri,
        fileName: String? = null,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): UploadTask {
        val contentResolver = context.contentResolver

        // 获取文件信息
        val actualFileName = fileName ?: getFileName(contentResolver, fileUri) ?: "unknown"
        val mimeType = contentResolver.getType(fileUri) ?: getMimeType(actualFileName)
        val fileSize = getFileSize(contentResolver, fileUri)

        // 计算分片
        val actualChunkSize = chunkSize.coerceIn(1024 * 1024, MAX_CHUNK_SIZE)
        val chunks = createChunks(fileSize, actualChunkSize)

        val taskId = UUID.randomUUID().toString()
        val task = UploadTask(
            id = taskId,
            filePath = fileUri.toString(),
            fileName = actualFileName,
            fileSize = fileSize,
            mimeType = mimeType,
            chunks = chunks
        )

        uploadTasks[taskId] = task
        _uploadStates[taskId] = MutableStateFlow(UploadState.PENDING)
        _uploadProgress[taskId] = MutableStateFlow(0f)

        Log.d(TAG, "创建上传任务: $taskId, 文件: $actualFileName, 大小: $fileSize, 分片数: ${chunks.size}")
        return task
    }

    /**
     * 开始上传
     */
    fun startUpload(
        taskId: String,
        context: Context,
        listener: UploadProgressListener? = null
    ) {
        val task = uploadTasks[taskId] ?: run {
            Log.e(TAG, "上传任务不存在: $taskId")
            return
        }

        if (task.state == UploadState.UPLOADING) {
            Log.w(TAG, "任务已在上传中: $taskId")
            return
        }

        scope.launch {
            try {
                // 更新状态为上传中
                updateTaskState(taskId, UploadState.UPLOADING)

                // 初始化上传（获取uploadId）
                val initResponse = initUpload(task)
                task.uploadUrl = initResponse.uploadId

                // 标记已上传的分片（断点续传）
                initResponse.uploadedChunks.forEach { chunkIndex ->
                    if (chunkIndex < task.chunks.size) {
                        task.chunks[chunkIndex].state = UploadState.COMPLETED
                        task.uploadedBytes += task.chunks[chunkIndex].size
                    }
                }
                updateProgress(taskId, task.progress)

                // 并发上传未完成的分片
                uploadChunks(taskId, context)

                // 检查是否所有分片都完成
                if (task.chunks.all { it.state == UploadState.COMPLETED }) {
                    // 完成上传
                    val result = completeUpload(task)
                    if (result.success) {
                        task.fileUrl = result.fileUrl
                        updateTaskState(taskId, UploadState.COMPLETED)
                        listener?.onCompleted(taskId, UploadResult(true, fileUrl = result.fileUrl))
                    } else {
                        throw Exception("完成上传失败")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传失败: ${e.message}")
                updateTaskState(taskId, UploadState.FAILED)
                listener?.onFailed(taskId, e.message ?: "上传失败")
            }
        }
    }

    /**
     * 暂停上传
     */
    fun pauseUpload(taskId: String) {
        val task = uploadTasks[taskId] ?: return
        if (task.state == UploadState.UPLOADING) {
            updateTaskState(taskId, UploadState.PAUSED)
            Log.d(TAG, "暂停上传: $taskId")
        }
    }

    /**
     * 恢复上传
     */
    fun resumeUpload(taskId: String, context: Context, listener: UploadProgressListener? = null) {
        val task = uploadTasks[taskId] ?: return
        if (task.state == UploadState.PAUSED) {
            startUpload(taskId, context, listener)
        }
    }

    /**
     * 取消上传
     */
    fun cancelUpload(taskId: String) {
        val task = uploadTasks[taskId] ?: return
        scope.launch {
            try {
                uploadApi.cancelUpload(CompleteUploadRequest(uploadId = task.uploadUrl))
            } catch (e: Exception) {
                Log.e(TAG, "取消上传失败: ${e.message}")
            }
            uploadTasks.remove(taskId)
            _uploadStates.remove(taskId)
            _uploadProgress.remove(taskId)
        }
    }

    /**
     * 初始化上传
     */
    private suspend fun initUpload(task: UploadTask): InitUploadResponse {
        val api = uploadApi
        return api.initUpload(
            InitUploadRequest(
                fileName = task.fileName,
                fileSize = task.fileSize,
                mimeType = task.mimeType,
                chunkSize = task.chunks.firstOrNull()?.size?.toInt() ?: DEFAULT_CHUNK_SIZE
            )
        )
    }

    /**
     * 并发上传分片
     */
    private suspend fun uploadChunks(taskId: String, context: Context) {
        val task = uploadTasks[taskId] ?: return
        val pendingChunks = task.chunks.filter { it.state != UploadState.COMPLETED }

        // 使用信号量控制并发数
        val semaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

        coroutineScope {
            pendingChunks.forEach { chunk ->
                launch {
                    semaphore.withPermit {
                        if (task.state != UploadState.PAUSED && task.state != UploadState.FAILED) {
                            uploadChunkWithRetry(taskId, chunk, context)
                        }
                    }
                }
            }
        }
    }

    /**
     * 上传单个分片（带重试）
     */
    private suspend fun uploadChunkWithRetry(taskId: String, chunk: ChunkInfo, context: Context) {
        val task = uploadTasks[taskId] ?: return
        val api = uploadApi

        var retryCount = 0
        var lastException: Exception? = null

        while (retryCount <= MAX_RETRY_COUNT) {
            try {
                if (task.state == UploadState.PAUSED) {
                    return
                }

                // 读取分片数据
                val chunkData = readChunkData(context, task.filePath, chunk)

                // 上传分片
                val requestBody = object : RequestBody() {
                    override fun contentType() = task.mimeType.toMediaTypeOrNull()
                    override fun contentLength() = chunkData.size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(chunkData)
                    }
                }

                val multipartBody = MultipartBody.Part.createFormData(
                    "chunk",
                    "${task.fileName}.part${chunk.index}",
                    requestBody
                )

                val response = api.uploadChunk(
                    uploadId = okhttp3.RequestBody.create(
                        "text/plain".toMediaTypeOrNull(),
                        task.uploadUrl
                    ),
                    chunkIndex = okhttp3.RequestBody.create(
                        "text/plain".toMediaTypeOrNull(),
                        chunk.index.toString()
                    ),
                    chunk = multipartBody
                )

                if (response.success) {
                    chunk.state = UploadState.COMPLETED
                    task.uploadedBytes += chunk.size
                    updateProgress(taskId, task.progress)
                    Log.d(TAG, "分片上传成功: ${chunk.index}")
                    return
                } else {
                    throw Exception("分片上传失败: ${chunk.index}")
                }
            } catch (e: Exception) {
                lastException = e
                retryCount++
                chunk.retryCount = retryCount
                Log.w(TAG, "分片上传失败，重试 $retryCount/$MAX_RETRY_COUNT: ${chunk.index}, 错误: ${e.message}")

                if (retryCount <= MAX_RETRY_COUNT) {
                    delay(RETRY_DELAY * retryCount)  // 指数退避
                }
            }
        }

        // 重试次数用完，标记为失败
        chunk.state = UploadState.FAILED
        throw lastException ?: Exception("分片上传失败: ${chunk.index}")
    }

    /**
     * 完成上传
     */
    private suspend fun completeUpload(task: UploadTask): CompleteUploadResponse {
        val api = uploadApi
        return api.completeUpload(CompleteUploadRequest(uploadId = task.uploadUrl))
    }

    /**
     * 读取分片数据
     */
    private suspend fun readChunkData(context: Context, filePath: String, chunk: ChunkInfo): ByteArray {
        return withContext(Dispatchers.IO) {
            val uri = android.net.Uri.parse(filePath)
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("无法打开文件")

            inputStream.use { stream ->
                stream.skip(chunk.startByte)
                val buffer = ByteArray(chunk.size.toInt())
                stream.read(buffer)
                buffer
            }
        }
    }

    /**
     * 创建分片列表
     */
    private fun createChunks(fileSize: Long, chunkSize: Int): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        var startByte = 0L
        var index = 0

        while (startByte < fileSize) {
            val endByte = minOf(startByte + chunkSize - 1, fileSize - 1)
            val size = endByte - startByte + 1

            chunks.add(
                ChunkInfo(
                    index = index,
                    startByte = startByte,
                    endByte = endByte,
                    size = size
                )
            )

            startByte = endByte + 1
            index++
        }

        return chunks
    }

    /**
     * 更新任务状态
     */
    private fun updateTaskState(taskId: String, state: UploadState) {
        uploadTasks[taskId]?.state = state
        _uploadStates[taskId]?.value = state
    }

    /**
     * 更新上传进度
     */
    private fun updateProgress(taskId: String, progress: Float) {
        _uploadProgress[taskId]?.value = progress
    }

    /**
     * 获取文件名
     */
    private fun getFileName(contentResolver: android.content.ContentResolver, uri: android.net.Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }
        return fileName
    }

    /**
     * 获取文件大小
     */
    private fun getFileSize(contentResolver: android.content.ContentResolver, uri: android.net.Uri): Long {
        // file:// URI 需要用 File.length() 获取大小
        if (uri.scheme == "file") {
            return java.io.File(uri.path!!).length()
        }
        var fileSize = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            cursor.moveToFirst()
            fileSize = cursor.getLong(sizeIndex)
        }
        return fileSize
    }

    /**
     * 获取MIME类型
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    /**
     * 清理完成的任务
     */
    fun cleanupCompletedTasks() {
        uploadTasks.entries.removeIf { it.value.state == UploadState.COMPLETED }
        _uploadStates.entries.removeIf { it.value.value == UploadState.COMPLETED }
        _uploadProgress.entries.removeIf { true }  // 清理所有进度
    }

    /**
     * 销毁管理器
     */
    fun destroy() {
        scope.cancel()
        uploadTasks.clear()
        _uploadStates.clear()
        _uploadProgress.clear()
    }
}
