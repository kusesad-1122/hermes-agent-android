package com.hermes.agent.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Chat : Screen("chat", "Chat", Icons.Outlined.Chat)
    data object Skills : Screen("skills", "Skills", Icons.Outlined.Extension)
    data object Memory : Screen("memory", "Memory", Icons.Outlined.Memory)
    data object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)

    companion object {
        val all = listOf(Chat, Skills, Memory, Settings)
    }
}