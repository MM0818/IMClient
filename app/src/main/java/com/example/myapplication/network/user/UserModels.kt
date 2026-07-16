package com.example.myapplication.network.user

data class UserListResponse(
    val success: Boolean,
    val message: String,
    val data: List<UserItem>?
)

data class UserItem(
    val userId: String,
    val username: String,
    val avatar: String? = "",
    val online: Boolean = false
)