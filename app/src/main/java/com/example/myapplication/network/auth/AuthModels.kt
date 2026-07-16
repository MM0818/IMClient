package com.example.myapplication.network.auth

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val phone: String? = null
)

data class LogoutRequest(
    val userId: String
)

data class AuthResponse(
    val success: Boolean,
    val message: String,
    val data: AuthData?
)

data class AuthData(
    val userId: String,
    val username: String,
    val token: String,
    val avatar: String? = ""
)

data class LogoutResponse(
    val success: Boolean,
    val message: String
)