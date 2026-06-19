package com.hermes.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermes.agent.navigation.Screen
import com.hermes.agent.ui.ChatScreen
import com.hermes.agent.ui.SkillsScreen
import com.hermes.agent.ui.MemoryScreen
import com.hermes.agent.ui.SettingsScreen
import com.hermes.agent.ui.ProviderListScreen
import com.hermes.agent.ui.ProviderEditScreen
import com.hermes.agent.ui.RootScreen
import com.hermes.agent.ui.WorkflowScreen
import com.hermes.agent.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesTheme {
                HermesAppContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesAppContent() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                Screen.all.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title, style = MaterialTheme.typography.labelSmall) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Skills.route) { SkillsScreen() }
            composable(Screen.Memory.route) { MemoryScreen() }
            composable(Screen.Workflow.route) { WorkflowScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToProviders = { navController.navigate("providers") },
                    onNavigateToRoot = { navController.navigate("root") }
                )
            }
            composable("providers") {
                ProviderListScreen(
                    onNavigateToAdd = { navController.navigate("provider_add") },
                    onNavigateToEdit = { provider ->
                        navController.navigate("provider_edit/${provider.id}")
                    }
                )
            }
            composable("provider_add") {
                ProviderEditScreen(
                    existingProvider = null,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() }
                )
            }
            composable("provider_edit/{providerId}") { backStackEntry ->
                val providerId = backStackEntry.arguments?.getString("providerId") ?: ""
                val context = androidx.compose.ui.platform.LocalContext.current
                val provider = remember(providerId) {
                    com.hermes.agent.data.ProviderStorage(context).getProvider(providerId)
                }
                ProviderEditScreen(
                    existingProvider = provider,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() }
                )
            }
            composable("root") {
                RootScreen()
            }
        }
    }
}