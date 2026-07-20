package com.example.myapplication.network.user

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface UserService {

    @GET("api/users")
    suspend fun getUsers(@Header("Authorization") token: String): UserListResponse

    @GET("api/users/search")
    suspend fun searchUsers(@Header("Authorization") token: String, @Query("keyword") keyword: String): UserListResponse

    @GET("api/users/online")
    suspend fun getOnlineUsers(@Header("Authorization") token: String): UserListResponse
}