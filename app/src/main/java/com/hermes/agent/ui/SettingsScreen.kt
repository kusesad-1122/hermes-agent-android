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
    onNavigateToRoot: () -> Unit = {},
    onNavigateToWorkflow: () -> Unit = {}
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

    if (showTempDialog) SliderDialog("Temperature", temperature, 0f..2f, 19, { showTempDialog = false }, { SettingsManager.setTemperature(it) })
    if (showTokensDialog) NumberDialog("Max Tokens", maxTokens, listOf(512, 1024, 2048, 4096, 8192, 16384, 32768), { showTokensDialog = false }, { SettingsManager.setMaxTokens(it) })
    if (showIterDialog) NumberDialog("Max Iterations", maxIterations, listOf(3, 5, 10, 15, 20, 30), { showIterDialog = false }, { SettingsManager.setMaxIterations(it) })
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Hermes Agent") },
            text = {
                Column {
                    Text("版本: 0.2.0")
                    Spacer(Modifier.height(8.dp))
                    Text("基于 NousResearch/hermes-agent v0.16.0")
                    Text("Chaquopy 内嵌 Python + Jetpack Compose")
                    Text("CI: Run #40 SUCCESS")
                    Spacer(Modifier.height(8.dp))
                    Text("GitHub: kusesad-1122/hermes-agent-android")
                }
            },
            confirmButton = { TextButton(onClick = { showAboutDialog = false }) { Text("确定") } }
        )
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认设置？") },
            text = { Text("这将重置所有非加密设置。API Key 等加密数据不会被清除。") },
            confirmButton = { TextButton(onClick = { SettingsManager.resetToDefaults(); showResetDialog = false }) { Text("确认重置", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("取消") } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("设置", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        SectionHeader("控制台入口")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickActionCard("Provider", "模型供应商", Icons.Outlined.Cloud, Modifier.weight(1f), onNavigateToProviders)
            QuickActionCard("Root", "权限与确认", Icons.Outlined.AdminPanelSettings, Modifier.weight(1f), onNavigateToRoot)
            QuickActionCard("Workflow", "任务流视图", Icons.Outlined.Timeline, Modifier.weight(1f), onNavigateToWorkflow)
        }

        SectionHeader("账户与模型")
        SettingsItem("模型供应商", "配置 API Key、端点、模型列表", Icons.Outlined.Cloud, onNavigateToProviders)
        SettingsItem("当前模型", model, Icons.Outlined.SmartToy, onNavigateToProviders)
        SettingsItem("Temperature", String.format("%.1f", temperature), Icons.Outlined.Thermostat, { showTempDialog = true })
        SettingsItem("Max Tokens", "$maxTokens", Icons.Outlined.DataUsage, { showTokensDialog = true })
        SettingsItem("Max Iterations", "$maxIterations", Icons.Outlined.Repeat, { showIterDialog = true })

        SectionHeader("Root 与安全")
        SettingsItem("Root & 权限", "查看 Root 状态、确认模式、能力开关", Icons.Outlined.AdminPanelSettings, onNavigateToRoot)
        SettingsItem("工作流视图", "查看 Goal/Wolfpack 实时任务与历史", Icons.Outlined.Timeline, onNavigateToWorkflow)

        SectionHeader("通知与行为")
        SwitchItem("自动启动服务", "开机时自动启动 Agent 前台服务", Icons.Outlined.PowerSettingsNew, autoStart) { SettingsManager.setAutoStart(it) }
        SwitchItem("任务完成通知", "Agent 完成任务后发送通知", Icons.Outlined.Notifications, notifyOnComplete) { SettingsManager.setNotifyOnComplete(it) }

        SectionHeader("显示")
        SettingsItem("字体大小", String.format("%.1fx", fontScale), Icons.Outlined.FormatSize, { })

        SectionHeader("数据与存储")
        SettingsItem("记忆与存储", "记忆检索、存储阈值、历史会话", Icons.Outlined.Storage, { })
        SettingsItem("恢复默认设置", "重置所有非加密设置", Icons.Outlined.Restore, { showResetDialog = true })

        SectionHeader("关于")
        SettingsItem("关于 Hermes Agent", "版本 0.2.0 · P2 完成", Icons.Outlined.Info, { showAboutDialog = true })

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun QuickActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, onClick = onClick) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SettingsItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp), shape = MaterialTheme.shapes.small, onClick = onClick) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        )
    }
}

@Composable
private fun SwitchItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp), shape = MaterialTheme.shapes.small, onClick = { onCheckedChange(!checked) }) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
        )
    }
}

@Composable
private fun SliderDialog(title: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, steps: Int, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { Text(String.format("%.2f", sliderValue)); Slider(value = sliderValue, onValueChange = { sliderValue = it }, valueRange = valueRange, steps = steps) } },
        confirmButton = { TextButton(onClick = { onConfirm(sliderValue); onDismiss() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun NumberDialog(title: String, value: Int, options: List<Int>, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var selected by remember { mutableIntStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == option, onClick = { selected = option })
                        Spacer(Modifier.width(8.dp))
                        Text("$option")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected); onDismiss() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
