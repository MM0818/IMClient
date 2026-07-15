package com.example.myapplication.network.upload

import kotlinx.serialization.Serializable

/**
 * 上传状态枚举
 */
enum class UploadState {
    PENDING,      // 等待上传
    UPLOADING,    // 上传中
    PAUSED,       // 已暂停
    COMPLETED,    // 上传完成
    FAILED        // 上传失败
}

/**
 * 分片信息
 */
@Serializable
data class ChunkInfo(
    val index: Int,           // 分片索引
    val startByte: Long,      // 起始字节
    val endByte: Long,        // 结束字节
    val size: Long,           // 分片大小
    var state: UploadState = UploadState.PENDING,
    var retryCount: Int = 0   // 重试次数
)

/**
 * 上传任务
 */
data class UploadTask(
    val id: String,                    // 任务ID
    val filePath: String,              // 文件路径
    val fileName: String,              // 文件名
    val fileSize: Long,                // 文件大小
    val mimeType: String,              // 文件类型
    val chunks: List<ChunkInfo>,       // 分片列表
    var state: UploadState = UploadState.PENDING,
    var uploadedBytes: Long = 0,       // 已上传字节数
    var uploadUrl: String = "",        // 上传URL
    var fileUrl: String = "",          // 上传完成后的文件URL
    val createdAt: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (fileSize > 0) uploadedBytes.toFloat() / fileSize else 0f

    val progressPercent: Int
        get() = (progress * 100).toInt()
}

/**
 * 上传结果
 */
data class UploadResult(
    val success: Boolean,
    val fileUrl: String = "",
    val fileId: String = "",
    val errorMessage: String = ""
)

/**
 * 上传进度回调
 */
interface UploadProgressListener {
    fun onProgressChanged(taskId: String, progress: Float, uploadedBytes: Long, totalBytes: Long)
    fun onStateChanged(taskId: String, state: UploadState)
    fun onCompleted(taskId: String, result: UploadResult)
    fun onFailed(taskId: String, error: String)
}

/**
 * 分片上传API接口
 */
@Serializable
data class InitUploadRequest(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val chunkSize: Int
)

@Serializable
data class InitUploadResponse(
    val uploadId: String,
    val fileUrl: String,
    val chunkSize: Int,
    val uploadedChunks: List<Int> = emptyList()  // 已上传的分片（断点续传用）
)

@Serializable
data class UploadChunkRequest(
    val uploadId: String,
    val chunkIndex: Int
)

@Serializable
data class UploadChunkResponse(
    val success: Boolean,
    val chunkIndex: Int
)

@Serializable
data class CompleteUploadRequest(
    val uploadId: String
)

@Serializable
data class CompleteUploadResponse(
    val success: Boolean,
    val fileUrl: String,
    val fileSize: Long
)
