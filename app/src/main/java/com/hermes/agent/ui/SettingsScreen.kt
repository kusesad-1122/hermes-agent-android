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
fun SettingsScreen() {
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
            "账户与模型" to "模型供应商、API Key、模型切换",
            "显示与主题" to "字体、字号、主题色",
            "通知" to "推送分级、通知渠道",
            "Skills 与 MCP" to "技能管理、MCP 服务器",
            "记忆与存储" to "记忆检索、存储阈值",
            "权限与安全" to "Root 能力开关、可逆性闸门",
            "Cron" to "定时任务管理",
            "Root" to "Root Gateway、审计日志",
            "手势" to "手势操作配置",
            "语音" to "STT/TTS 设置",
            "Gateway" to "多平台消息接入",
            "关于" to "版本、许可、更新"
        )

        sections.forEach { (title, subtitle) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = MaterialTheme.shapes.small
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