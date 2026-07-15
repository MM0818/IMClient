package com.example.myapplication.network.upload

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

/**
 * 文件上传API接口
 */
interface FileUploadApi {

    /**
     * 初始化分片上传
     */
    @POST("api/upload/init")
    suspend fun initUpload(@Body request: InitUploadRequest): InitUploadResponse

    /**
     * 上传分片
     */
    @Multipart
    @POST("api/upload/chunk")
    suspend fun uploadChunk(
        @Part("uploadId") uploadId: RequestBody,
        @Part("chunkIndex") chunkIndex: RequestBody,
        @Part chunk: MultipartBody.Part
    ): UploadChunkResponse

    /**
     * 完成上传
     */
    @POST("api/upload/complete")
    suspend fun completeUpload(@Body request: CompleteUploadRequest): CompleteUploadResponse

    /**
     * 取消上传
     */
    @POST("api/upload/cancel")
    suspend fun cancelUpload(@Body request: CompleteUploadRequest)
}
