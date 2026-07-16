package com.example.myapplication.network.user

import retrofit2.http.GET
import retrofit2.http.Query

interface UserService {

    @GET("api/users")
    suspend fun getUsers(): UserListResponse

    @GET("api/users/search")
    suspend fun searchUsers(@Query("keyword") keyword: String): UserListResponse

    @GET("api/users/online")
    suspend fun getOnlineUsers(): UserListResponse
}