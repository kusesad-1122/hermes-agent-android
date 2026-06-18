package com.hermes.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.agent.data.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToProviders: () -> Unit = {},
    onNavigateToRoot: () -> Unit = {}
) {
    val temperature by SettingsManager.temperature.collectAsState()
    val maxTokens by SettingsManager.maxTokens.collectAsState()
    val maxIterations by SettingsManager.maxIterations.collectAsState()
    val autoStart by SettingsManager.autoStart.collectAsState()
    val notifyOnComplete by SettingsManager.notifyOnComplete.collectAsState()
    val fontScale by SettingsManager.fontScale.collectAsState()
    val model by SettingsManager.model.collectAsState()

    var showTempDialog by remember { mutableStateOf(false) }
    var showTokensDialog by remember { mutableStateOf(false) }
    var showIterDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    // Dialogs
    if (showTempDialog) {
        SliderDialog(
            title = "Temperature",
            value = temperature,
            valueRange = 0f..2f,
            steps = 19,
            onDismiss = { showTempDialog = false },
            onConfirm = { SettingsManager.setTemperature(it) }
        )
    }
    if (showTokensDialog) {
        NumberDialog(
            title = "Max Tokens",
            value = maxTokens,
            options = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768),
            onDismiss = { showTokensDialog = false },
            onConfirm = { SettingsManager.setMaxTokens(it) }
        )
    }
    if (showIterDialog) {
        NumberDialog(
            title = "Max Iterations",
            value = maxIterations,
            options = listOf(3, 5, 10, 15, 20, 30),
            onDismiss = { showIterDialog = false },
            onConfirm = { SettingsManager.setMaxIterations(it) }
        )
    }
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Hermes Agent") },
            text = {
                Column {
                    Text("版本: 0.1.0 (Phase 1 MVP)")
                    Spacer(Modifier.height(8.dp))
                    Text("基于 NousResearch/hermes-agent v0.16.0")
                    Text("移植路线: Chaquopy 内嵌 Python")
                    Text("UI: Jetpack Compose + 纯白主题")
                    Spacer(Modifier.height(8.dp))
                    Text("GitHub: kusesad-1122/hermes-agent-android")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("确定") }
            }
        )
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认设置？") },
            text = { Text("这将重置所有设置到默认值。API Key 等加密数据不会被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    SettingsManager.resetToDefaults()
                    showResetDialog = false
                }) { Text("确认重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("设置", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // ── 账户与模型 ─────────────────────────────────────────────────
        SectionHeader("账户与模型")
        SettingsItem(
            title = "模型供应商",
            subtitle = "配置 API Key 和端点",
            icon = Icons.Outlined.Cloud,
            onClick = onNavigateToProviders
        )
        SettingsItem(
            title = "模型",
            subtitle = model,
            icon = Icons.Outlined.SmartToy,
            onClick = onNavigateToProviders
        )
        SettingsItem(
            title = "Temperature",
            subtitle = String.format("%.1f", temperature),
            icon = Icons.Outlined.Thermostat,
            onClick = { showTempDialog = true }
        )
        SettingsItem(
            title = "Max Tokens",
            subtitle = "$maxTokens",
            icon = Icons.Outlined.DataUsage,
            onClick = { showTokensDialog = true }
        )
        SettingsItem(
            title = "Max Iterations",
            subtitle = "$maxIterations",
            icon = Icons.Outlined.Repeat,
            onClick = { showIterDialog = true }
        )

        // ── 通知与行为 ─────────────────────────────────────────────────
        SectionHeader("权限与安全")
        SettingsItem(
            title = "Root 与权限",
            subtitle = "Root 状态、确认模式、能力开关、审计日志",
            icon = Icons.Outlined.Security,
            onClick = onNavigateToRoot
        )

        // ── 通知与行为 ─────────────────────────────────────────────────
        SectionHeader("通知与行为")
        SwitchItem(
            title = "自动启动服务",
            subtitle = "开机时自动启动 Agent 前台服务",
            icon = Icons.Outlined.PowerSettingsNew,
            checked = autoStart,
            onCheckedChange = { SettingsManager.setAutoStart(it) }
        )
        SwitchItem(
            title = "任务完成通知",
            subtitle = "Agent 完成任务后发送通知",
            icon = Icons.Outlined.Notifications,
            checked = notifyOnComplete,
            onCheckedChange = { SettingsManager.setNotifyOnComplete(it) }
        )

        // ── 显示 ──────────────────────────────────────────────────────
        SectionHeader("显示")
        SettingsItem(
            title = "字体大小",
            subtitle = String.format("%.1fx", fontScale),
            icon = Icons.Outlined.FormatSize,
            onClick = { }
        )

        // ── 数据与存储 ────────────────────────────────────────────────
        SectionHeader("数据与存储")
        SettingsItem(
            title = "记忆与存储",
            subtitle = "记忆检索、存储阈值",
            icon = Icons.Outlined.Storage,
            onClick = { }
        )
        SettingsItem(
            title = "恢复默认设置",
            subtitle = "重置所有非加密设置",
            icon = Icons.Outlined.Restore,
            onClick = { showResetDialog = true }
        )

        // ── 关于 ──────────────────────────────────────────────────────
        SectionHeader("关于")
        SettingsItem(
            title = "关于 Hermes Agent",
            subtitle = "版本 0.1.0 · Phase 1 MVP",
            icon = Icons.Outlined.Info,
            onClick = { showAboutDialog = true }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
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
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun SwitchItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
        shape = MaterialTheme.shapes.small,
        onClick = { onCheckedChange(!checked) }
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
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        )
    }
}

@Composable
private fun SliderDialog(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(String.format("%.2f", sliderValue))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = valueRange,
                    steps = steps
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sliderValue); onDismiss() }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun NumberDialog(
    title: String,
    value: Int,
    options: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selected by remember { mutableIntStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == option,
                            onClick = { selected = option }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("$option")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected); onDismiss() }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}