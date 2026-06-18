package com.hermes.agent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.agent.data.ChatStore
import com.hermes.agent.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Data models ─────────────────────────────────────────────────────────────

data class WorkflowStep(
    val id: Int,
    val type: String,        // "thinking" | "tool" | "tool_result" | "error" | "done"
    val title: String,
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val tokens: Int = 0,
    val latencyMs: Int = 0,
)

data class TaskRecord(
    val id: String,
    val summary: String,
    val startTime: Long,
    val endTime: Long,
    val status: String,      // "running" | "success" | "failed" | "interrupted"
    val totalTokens: Int,
    val stepCount: Int,
)

// ── Workflow Screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen() {
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Live, 1=History
    val liveSteps = remember { mutableStateListOf<WorkflowStep>() }
    var currentStatus by remember { mutableStateOf("idle") }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var goalInput by remember { mutableStateOf("") }
    var showGoalDialog by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("chat") } // "chat" | "goal" | "wolfpack"

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Workflow", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                if (isRunning) {
                    IconButton(onClick = { isRunning = false; currentStatus = "interrupted" }) {
                        Icon(Icons.Filled.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = { showGoalDialog = true }) {
                        Icon(Icons.Filled.PlayArrow, "启动任务")
                    }
                }
            }
        )

        // Mode selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("Live") }
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = { Text("History") }
            )
        }

        // Status bar
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = currentStatus,
                style = MaterialTheme.typography.labelMedium,
                color = when (currentStatus) {
                    "idle" -> MaterialTheme.colorScheme.onSurfaceVariant
                    "thinking" -> MaterialTheme.colorScheme.primary
                    "error", "interrupted" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        AnimatedContent(targetState = selectedTab, transitionSpec = {
            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
        }, label = "wf_tab") { tab ->
            when (tab) {
                0 -> {
                    // Live timeline
                    if (liveSteps.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.Bolt, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text("暂无运行中的任务", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("点击播放按钮启动 Goal 或 Wolfpack 任务", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(liveSteps) { step -> StepItem(step) }
                        }
                    }
                }
                1 -> {
                    // History (placeholder - uses ChatStore sessions as task records)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("任务历史", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("已完成的任务将在此显示", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // Goal/Wolfpack launch dialog
    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("启动任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模式:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == "goal", onClick = { mode = "goal" }, label = { Text("Goal 循环") })
                        FilterChip(selected = mode == "wolfpack", onClick = { mode = "wolfpack" }, label = { Text("Wolfpack") })
                    }
                    OutlinedTextField(
                        value = goalInput, onValueChange = { goalInput = it },
                        label = { Text("目标/任务描述") },
                        minLines = 2, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGoalDialog = false
                        if (goalInput.isNotBlank()) {
                            isRunning = true
                            liveSteps.clear()
                            currentStatus = "thinking"
                            scope.launch {
                                // Run goal or wolfpack via Python
                                withContext(Dispatchers.IO) {
                                    try {
                                        val py = com.chaquo.python.Python.getInstance()
                                        val apiKey = SettingsManager.getSecureString("deepseek_api_key") ?: ""
                                        val model = SettingsManager.model.value
                                        val baseUrl = "https://api.deepseek.com/v1"

                                        if (mode == "goal") {
                                            val gl = py.getModule("goal_loop")
                                            gl.callAttr("configure", baseUrl, apiKey, model)
                                            val result = gl.callAttr("run_goal", goalInput)
                                            @Suppress("UNCHECKED_CAST")
                                            val resultMap = (result as com.chaquo.python.PyObject).asMap() as Map<String, Any?>
                                            val achieved = resultMap["achieved"]?.toString()?.toBoolean() ?: false
                                            val iterations = resultMap["iterations"]?.toString()?.toIntOrNull() ?: 0
                                            val tokens = resultMap["total_tokens"]?.toString()?.toIntOrNull() ?: 0
                                            val steps = resultMap["steps"] as? List<*>
                                            steps?.forEachIndexed { i, s ->
                                                val sm = s as? Map<String, Any?> ?: return@forEachIndexed
                                                liveSteps.add(WorkflowStep(
                                                    id = i + 1,
                                                    type = sm["type"]?.toString() ?: "executor",
                                                    title = sm["summary"]?.toString()?.take(100) ?: "Step ${i+1}",
                                                    detail = sm["summary"]?.toString() ?: "",
                                                    tokens = sm["tokens"]?.toString()?.toIntOrNull() ?: 0,
                                                ))
                                            }
                                            currentStatus = if (achieved) "done" else "failed"
                                        } else {
                                            val wp = py.getModule("wolfpack")
                                            wp.callAttr("configure", baseUrl, apiKey, model)
                                            val result = wp.callAttr("run_wolfpack", goalInput)
                                            @Suppress("UNCHECKED_CAST")
                                            val resultMap = (result as com.chaquo.python.PyObject).asMap() as Map<String, Any?>
                                            val subtasks = resultMap["subtasks"] as? List<*>
                                            subtasks?.forEachIndexed { i, s ->
                                                val sm = s as? Map<String, Any?> ?: return@forEachIndexed
                                                liveSteps.add(WorkflowStep(
                                                    id = i + 1,
                                                    type = "tool",
                                                    title = "Subtask: ${sm["name"]?.toString() ?: ""}",
                                                    detail = sm["description"]?.toString() ?: "",
                                                ))
                                            }
                                            val aggregated = resultMap["aggregated"]?.toString() ?: ""
                                            liveSteps.add(WorkflowStep(
                                                id = liveSteps.size + 1,
                                                type = "done",
                                                title = "Aggregated Result",
                                                detail = aggregated.take(500),
                                            ))
                                            currentStatus = "done"
                                        }
                                    } catch (e: Exception) {
                                        liveSteps.add(WorkflowStep(
                                            id = liveSteps.size + 1,
                                            type = "error",
                                            title = "Error",
                                            detail = e.message ?: e.toString(),
                                        ))
                                        currentStatus = "error"
                                    }
                                }
                                isRunning = false
                            }
                        }
                    },
                    enabled = goalInput.isNotBlank()
                ) { Text("启动") }
            },
            dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun StepItem(step: WorkflowStep) {
    val (icon, color) = when (step.type) {
        "error" -> Icons.Outlined.Error to MaterialTheme.colorScheme.error
        "done" -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        "tool", "tool_result" -> Icons.Outlined.Tune to MaterialTheme.colorScheme.primary
        else -> Icons.Outlined.Bolt to MaterialTheme.colorScheme.onSurfaceVariant
    }
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline dot
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = color)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = if (step.type == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (expanded && step.detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(step.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (step.tokens > 0) {
                Text("${step.tokens} tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
