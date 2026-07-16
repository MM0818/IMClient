package com.example.myapplication.page.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.Const.Token
import com.example.myapplication.network.auth.AuthData
import com.example.myapplication.network.auth.AuthService
import com.example.myapplication.network.auth.LoginRequest
import com.example.myapplication.network.auth.RegisterRequest
import com.example.myapplication.network.user.UserService
import com.example.myapplication.network.user.UserItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authService: AuthService,
    private val userService: UserService
) : ViewModel() {

    private val _username = MutableStateFlow("")
    val username = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password = _password.asStateFlow()

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword = _confirmPassword.asStateFlow()

    private val _email = MutableStateFlow("")
    val email = _email.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage = _errorMessage.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess = _loginSuccess.asStateFlow()

    private val _users = MutableStateFlow<List<UserItem>>(emptyList())
    val users = _users.asStateFlow()

    private val _isLoadingUsers = MutableStateFlow(false)
    val isLoadingUsers = _isLoadingUsers.asStateFlow()

    fun updateUsername(text: String) { _username.value = text }
    fun updatePassword(text: String) { _password.value = text }
    fun updateConfirmPassword(text: String) { _confirmPassword.value = text }
    fun updateEmail(text: String) { _email.value = text }
    fun clearError() { _errorMessage.value = "" }

    fun login() {
        val username = _username.value.trim()
        val password = _password.value

        if (username.isEmpty()) {
            _errorMessage.value = "请输入用户名"
            return
        }
        if (password.isEmpty()) {
            _errorMessage.value = "请输入密码"
            return
        }

        _isLoading.value = true
        _errorMessage.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = authService.login(LoginRequest(username, password))

                withContext(Dispatchers.Main) {
                    if (response.success && response.data != null) {
                        saveToken(response.data)
                        _loginSuccess.value = true
                    } else {
                        _errorMessage.value = response.message ?: "登录失败"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "网络错误: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun register() {
        val username = _username.value.trim()
        val password = _password.value
        val confirmPassword = _confirmPassword.value
        val email = _email.value.trim()

        if (username.isEmpty()) {
            _errorMessage.value = "请输入用户名"
            return
        }
        if (password.isEmpty()) {
            _errorMessage.value = "请输入密码"
            return
        }
        if (password != confirmPassword) {
            _errorMessage.value = "两次输入的密码不一致"
            return
        }
        if (email.isEmpty()) {
            _errorMessage.value = "请输入邮箱"
            return
        }

        _isLoading.value = true
        _errorMessage.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = authService.register(RegisterRequest(username, password, email))

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        _errorMessage.value = "注册成功，请登录"
                        _loginSuccess.value = true
                    } else {
                        _errorMessage.value = response.message ?: "注册失败"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "网络错误: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun fetchUsers() {
        _isLoadingUsers.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = userService.getUsers()
                withContext(Dispatchers.Main) {
                    if (response.success && response.data != null) {
                        _users.value = response.data.filter { it.userId != Token.USER_ID }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "获取用户列表失败"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isLoadingUsers.value = false
                }
            }
        }
    }

    fun searchUsers(keyword: String) {
        if (keyword.isEmpty()) {
            fetchUsers()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = userService.searchUsers(keyword)
                withContext(Dispatchers.Main) {
                    if (response.success && response.data != null) {
                        _users.value = response.data.filter { it.userId != Token.USER_ID }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "搜索失败"
                }
            }
        }
    }

    private fun saveToken(data: AuthData) {
        Token.TOKEN = data.token
        Token.USER_ID = data.userId
        Token.USERNAME = data.username
    }

    fun resetLoginSuccess() {
        _loginSuccess.value = false
    }

    fun logout() {
        Token.TOKEN = ""
        Token.USER_ID = ""
        Token.USERNAME = ""
    }

    fun isLoggedIn(): Boolean {
        return Token.TOKEN.isNotEmpty() && Token.USER_ID.isNotEmpty()
    }
}