package com.example.myapplication.page.login

import android.util.Log
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
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

        Log.d("IM_DEBUG", "=== 登录开始 ===")
        Log.d("IM_DEBUG", "username=$username")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("IM_DEBUG", "发起HTTP请求: authService.login")
                val response = authService.login(LoginRequest(username, password))
                Log.d("IM_DEBUG", "登录响应: success=${response.success}, message=${response.message}, data=${response.data}")

                withContext(Dispatchers.Main) {
                    if (response.success && response.data != null) {
                        saveToken(response.data)
                        Log.d("IM_DEBUG", "登录成功: userId=${response.data.userId}, token=${response.data.token.take(20)}...")
                        _loginSuccess.value = true
                    } else {
                        Log.w("IM_DEBUG", "登录失败: message=${response.message}")
                        _errorMessage.value = response.message ?: "登录失败"
                    }
                }
            } catch (e: Exception) {
                Log.e("IM_DEBUG", "登录异常: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("IM_DEBUG", "异常详情", e)
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

        Log.d("IM_DEBUG", "=== 注册开始 ===")
        Log.d("IM_DEBUG", "username=$username, email=$email, passwordLength=${password.length}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = RegisterRequest(username, password, email)
                Log.d("IM_DEBUG", "发起HTTP请求: POST api/auth/register")
                Log.d("IM_DEBUG", "请求体: username=${request.username}, email=${request.email}, phone=${request.phone}")
                val response = authService.register(request)
                Log.d("IM_DEBUG", "收到响应: success=${response.success}, message=${response.message}, data=${response.data}")

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        _errorMessage.value = "注册成功，请登录"
                        _loginSuccess.value = true
                    } else {
                        _errorMessage.value = response.message ?: "注册失败"
                    }
                }
            } catch (e: Exception) {
                Log.e("IM_DEBUG", "注册异常: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("IM_DEBUG", "异常详情", e)
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
                val response = userService.getUsers("Bearer ${Token.TOKEN}")
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
                val response = userService.searchUsers("Bearer ${Token.TOKEN}", keyword)
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

    /**
     * 诊断网络连通性，逐层排查：
     * 1. DNS解析
     * 2. TCP Socket连接
     * 3. HttpURLConnection（绕过OkHttp）
     */
    fun diagnoseNetwork() {
        Log.d("IM_DEBUG", "========== 网络诊断开始 ==========")
        viewModelScope.launch(Dispatchers.IO) {
            val host = "192.168.181.15"
            val port = 8081

            // 测试1: DNS解析
            try {
                val addr = java.net.InetAddress.getByName(host)
                Log.d("IM_DEBUG", "[DNS] 解析成功: ${addr.hostAddress}, canonicalName=${addr.canonicalHostName}")
            } catch (e: Exception) {
                Log.e("IM_DEBUG", "[DNS] 解析失败: ${e.message}")
            }

            // 测试2: 原始TCP Socket连接
            try {
                Log.d("IM_DEBUG", "[TCP] 开始连接 $host:$port ...")
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                Log.d("IM_DEBUG", "[TCP] 连接成功! localPort=${socket.localPort}, remoteSocketAddress=${socket.remoteSocketAddress}")
                socket.close()
            } catch (e: Exception) {
                Log.e("IM_DEBUG", "[TCP] 连接失败: ${e.javaClass.simpleName}: ${e.message}", e)
            }

            // 测试3: HttpURLConnection（不经过OkHttp）
            try {
                Log.d("IM_DEBUG", "[HTTP] 发起 HttpURLConnection 请求...")
                val url = URL("http://$host:$port/api/auth/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true

                val body = """{"username":"diag_test","password":"123456","email":"diag@test.com"}"""
                val writer = PrintWriter(OutputStreamWriter(conn.outputStream, "UTF-8"))
                writer.write(body)
                writer.flush()
                writer.close()

                val code = conn.responseCode
                Log.d("IM_DEBUG", "[HTTP] 响应状态码: $code")

                val reader = BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else conn.errorStream, "UTF-8"
                ))
                val response = reader.readText()
                reader.close()
                Log.d("IM_DEBUG", "[HTTP] 响应内容: $response")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("IM_DEBUG", "[HTTP] 请求失败: ${e.javaClass.simpleName}: ${e.message}", e)
            }

            // 测试4: 检查本机网络信息
            try {
                val localAddr = java.net.InetAddress.getLocalHost()
                Log.d("IM_DEBUG", "[LOCAL] 本机地址: ${localAddr.hostAddress}, hostName=${localAddr.hostName}")
            } catch (e: Exception) {
                Log.e("IM_DEBUG", "[LOCAL] 获取本机地址失败: ${e.message}")
            }

            Log.d("IM_DEBUG", "========== 网络诊断结束 ==========")
        }
    }
}