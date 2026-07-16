package com.example.myapplication.navigation

sealed class Screen(val route:String, val description:String){
    data object Login : Screen("login","登录页面")
    data object Register : Screen("register","注册页面")
    data object RecordPage : Screen("RecordPage","主页面")
    data object ModelPage:Screen("ModelPage","模型选择页面")
    data object ChatPage : Screen("ChatPage","聊天页面")
    data object ConversationList : Screen("conversationList","会话列表")
    data object IMChat : Screen("IMChat","IM聊天页面")
}