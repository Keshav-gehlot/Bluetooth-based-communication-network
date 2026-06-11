package com.meshchat.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.meshchat.ui.features.broadcast.BroadcastScreen
import com.meshchat.ui.features.chat.ChatScreen
import com.meshchat.ui.features.chats.ChatsScreen
import com.meshchat.ui.features.contacts.NearbyScreen
import com.meshchat.ui.features.settings.SettingsScreen
import com.meshchat.ui.features.splash.SplashScreen
import com.meshchat.ui.features.setup.SetupScreen

data class TopLevelDestination(
    val route: Any,
    val title: String,
    val icon: ImageVector
)

val topLevelDestinations = listOf(
    TopLevelDestination(Chats, "Chats", Icons.AutoMirrored.Filled.Chat),
    TopLevelDestination(Broadcast, "Group", Icons.Filled.WifiTethering),
    TopLevelDestination(Network, "Network", Icons.Filled.Group),
    TopLevelDestination(Settings, "Settings", Icons.Filled.Settings)
)

@Composable
fun MeshChatNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determine if navigation bar should be shown
    val showNavBar = currentDestination?.let { dest ->
        !dest.hasRoute<Chat>() && !dest.hasRoute<Splash>() && !dest.hasRoute<Setup>()
    } ?: false

    Scaffold(
        bottomBar = {
            if (showNavBar) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(destination.route::class)
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.title) },
                            label = { Text(destination.title) }
                        )
                    }
                }
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = Splash
        ) {
            composable<Splash> {
                SplashScreen(
                    onNavigateToSetup = {
                        navController.navigate(Setup) {
                            popUpTo(Splash) { inclusive = true }
                        }
                    },
                    onNavigateToChats = {
                        navController.navigate(Chats) {
                            popUpTo(Splash) { inclusive = true }
                        }
                    }
                )
            }
            composable<Setup> {
                SetupScreen(
                    onNavigateToChats = {
                        navController.navigate(Chats) {
                            popUpTo(Setup) { inclusive = true }
                        }
                    }
                )
            }
            composable<Chats> {
                ChatsScreen(
                    onNavigateToChat = { id, peerId, name ->
                        navController.navigate(Chat(id, peerId, name))
                    }
                )
            }
            composable<Chat> {
                ChatScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable<Broadcast> {
                BroadcastScreen()
            }
            composable<Network> {
                NearbyScreen(
                    onNavigateToChat = { id, peerId, name ->
                        navController.navigate(Chat(id, peerId, name))
                    }
                )
            }
            composable<Settings> {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onResetIdentity = {
                        navController.navigate(Setup) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
