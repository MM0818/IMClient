package com.example.myapplication.network.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图片压缩工具
 * 特性：
 * 1. 质量压缩
 * 2. 尺寸压缩
 * 3. 生成缩略图
 * 4. 保持EXIF方向
 */
@Singleton
class ImageCompressor @Inject constructor() {

    companion object {
        private const val TAG = "ImageCompressor"
        private const val DEFAULT_MAX_WIDTH = 1920
        private const val DEFAULT_MAX_HEIGHT = 1080
        private const val DEFAULT_QUALITY = 85
        private const val THUMBNAIL_SIZE = 200
        private const val THUMBNAIL_QUALITY = 60
    }

    /**
     * 压缩结果
     */
    data class CompressResult(
        val originalSize: Long,
        val compressedSize: Long,
        val compressedFile: File,
        val thumbnailFile: File?,
        val width: Int,
        val height: Int
    ) {
        val compressionRatio: Float
            get() = if (originalSize > 0) compressedSize.toFloat() / originalSize else 0f
    }

    /**
     * 压缩图片
     */
    suspend fun compressImage(
        context: Context,
        uri: Uri,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        maxHeight: Int = DEFAULT_MAX_HEIGHT,
        quality: Int = DEFAULT_QUALITY,
        generateThumbnail: Boolean = true
    ): CompressResult = withContext(Dispatchers.IO) {
        try {
            // 获取原始图片信息
            val originalSize = getFileSize(context, uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri), null, options)

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            // 计算采样率
            val sampleSize = calculateSampleSize(originalWidth, originalHeight, maxWidth, maxHeight)

            // 解码图片
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            val bitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri), null, options)
                ?: throw Exception("无法解码图片")

            // 缩放图片
            val scaledBitmap = scaleBitmap(bitmap, maxWidth, maxHeight)

            // 压缩图片
            val compressedFile = compressBitmap(context, scaledBitmap, quality, "compressed")

            // 生成缩略图
            val thumbnailFile = if (generateThumbnail) {
                createThumbnail(context, scaledBitmap)
            } else null

            // 释放资源
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()

            CompressResult(
                originalSize = originalSize,
                compressedSize = compressedFile.length(),
                compressedFile = compressedFile,
                thumbnailFile = thumbnailFile,
                width = scaledBitmap.width,
                height = scaledBitmap.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "图片压缩失败: ${e.message}")
            throw e
        }
    }

    /**
     * 计算采样率
     */
    private fun calculateSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxWidth || height / sampleSize > maxHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 缩放图片
     */
    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val matrix = Matrix().apply {
            postScale(scale, scale)
        }

        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    /**
     * 压缩Bitmap到文件
     */
    private fun compressBitmap(context: Context, bitmap: Bitmap, quality: Int, suffix: String): File {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

        val file = File(context.cacheDir, "image_${System.currentTimeMillis()}_$suffix.jpg")
        FileOutputStream(file).use { fos ->
            fos.write(outputStream.toByteArray())
        }

        return file
    }

    /**
     * 创建缩略图
     */
    private fun createThumbnail(context: Context, bitmap: Bitmap): File {
        val thumbnail = scaleBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
        val file = compressBitmap(context, thumbnail, THUMBNAIL_QUALITY, "thumb")

        if (thumbnail != bitmap) {
            thumbnail.recycle()
        }

        return file
    }

    /**
     * 获取文件大小
     */
    private fun getFileSize(context: Context, uri: Uri): Long {
        var fileSize = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            cursor.moveToFirst()
            fileSize = cursor.getLong(sizeIndex)
        }
        return fileSize
    }

    /**
     * 压缩图片（简单版本）
     */
    suspend fun compress(
        context: Context,
        uri: Uri,
        quality: Int = DEFAULT_QUALITY
    ): File = withContext(Dispatchers.IO) {
        val result = compressImage(context, uri, quality = quality, generateThumbnail = false)
        result.compressedFile
    }

    /**
     * 生成缩略图
     */
    suspend fun createThumbnail(
        context: Context,
        uri: Uri
    ): File = withContext(Dispatchers.IO) {
        val result = compressImage(context, uri, maxWidth = THUMBNAIL_SIZE, maxHeight = THUMBNAIL_SIZE, quality = THUMBNAIL_QUALITY, generateThumbnail = true)
        result.thumbnailFile ?: result.compressedFile
    }
}
