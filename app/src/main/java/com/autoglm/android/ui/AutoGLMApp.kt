package com.autoglm.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autoglm.android.ui.chat.ChatScreen
import com.autoglm.android.ui.chat.ChatViewModel
import com.autoglm.android.ui.chat.ConversationDrawer
import com.autoglm.android.ui.logs.LogScreen
import com.autoglm.android.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object Logs : Screen("logs")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoGLMApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
    val uiState by chatViewModel.uiState.collectAsState()
    val conversations by chatViewModel.allConversations.collectAsState(initial = emptyList())
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentConversationId = uiState.currentConversation?.id,
                onNewConversation = {
                    chatViewModel.createNewConversation()
                    scope.launch { drawerState.close() }
                },
                onSelectConversation = { id ->
                    chatViewModel.loadConversation(id)
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { id ->
                    chatViewModel.deleteConversation(id)
                }
            )
        },
        gesturesEnabled = drawerState.isOpen
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = chatViewModel,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Logs.route) {
                LogScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
