package com.example.myapplication.Const

object Token {
    var TOKEN = ""
    var USER_ID = ""
    var USERNAME = ""

    /** userId → username 缓存，用于自动创建会话时解析联系人名称 */
    private val _userNameCache = mutableMapOf<String, String>()
    val userNameCache: Map<String, String> get() = _userNameCache

    fun cacheUserNames(users: List<Pair<String, String>>) {
        users.forEach { (id, name) -> _userNameCache[id] = name }
    }

    fun getUserName(userId: String): String? = _userNameCache[userId]

    fun clearCache() {
        _userNameCache.clear()
    }
}
