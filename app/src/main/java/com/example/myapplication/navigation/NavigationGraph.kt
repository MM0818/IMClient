package com.example.myapplication.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.myapplication.page.chatPage.ChatPage
import com.example.myapplication.page.conversation.ConversationListPage
import com.example.myapplication.page.imchat.IMChatPage
import com.example.myapplication.page.login.LoginPage
import com.example.myapplication.page.login.RegisterPage
import com.example.myapplication.page.modelPage.ModelPage
import com.example.myapplication.page.recordPage.RecordPage

@Composable
fun NavigationGraph(
    navHostController: NavHostController,
    startDestination: String = Screen.Login.route,
){
    NavHost(navController = navHostController, startDestination = startDestination){
        composable(Screen.Login.route) {
            LoginPage(navController = navHostController)
        }
        composable(Screen.Register.route) {
            RegisterPage(navController = navHostController)
        }
        composable(Screen.ConversationList.route){
            ConversationListPage(
                onNavigateToChat = { conversationId, contactName ->
                    navHostController.navigate("${Screen.IMChat.route}/$conversationId/$contactName")
                }
            )
        }
        composable(
            route = Screen.IMChat.route + "/{conversationId}/{contactName}",
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("contactName") { type = NavType.StringType }
            )
        ) {
            IMChatPage(onNavigateBack = { navHostController.popBackStack() })
        }
        composable(Screen.RecordPage.route){
            RecordPage(navHostController)
        }
        composable(Screen.ModelPage.route){
            ModelPage(navHostController)
        }
        composable(
            route = Screen.ChatPage.route + "/{model}" + "/{id}",
            arguments = listOf(navArgument("model") { type = NavType.StringType }, navArgument("id"){type = NavType.IntType})
        ) {
            val model = it.arguments?.getString("model") ?: "GPT-4o"
            val chatId = it.arguments?.getInt("id")?:0
            ChatPage(navHostController, model,chatId)
        }
    }
}