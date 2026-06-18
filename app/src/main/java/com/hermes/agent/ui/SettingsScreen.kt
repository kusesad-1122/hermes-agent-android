package com.hermes.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToProviders: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Settings", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        val sections = listOf(
            Triple("账户与模型", "模型供应商、API Key、模型切换", onNavigateToProviders),
            Triple("显示与主题", "字体、字号、主题色", {}),
            Triple("通知", "推送分级、通知渠道", {}),
            Triple("Skills 与 MCP", "技能管理、MCP 服务器", {}),
            Triple("记忆与存储", "记忆检索、存储阈值", {}),
            Triple("权限与安全", "Root 能力开关、可逆性闸门", {}),
            Triple("Cron", "定时任务管理", {}),
            Triple("Root", "Root Gateway、审计日志", {}),
            Triple("手势", "手势操作配置", {}),
            Triple("语音", "STT/TTS 设置", {}),
            Triple("Gateway", "多平台消息接入", {}),
            Triple("关于", "版本、许可、更新", {})
        )

        sections.forEach { (title, subtitle, onClick) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = MaterialTheme.shapes.small,
                onClick = onClick
            ) {
                ListItem(
                    headlineContent = {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForwardIos,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}