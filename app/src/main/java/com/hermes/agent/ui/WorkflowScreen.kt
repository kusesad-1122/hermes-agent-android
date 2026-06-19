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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.ProviderStorage
import com.hermes.agent.data.ChaquopyBridge.toKList
import com.hermes.agent.data.ChaquopyBridge.toKMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Data models ──────────────────────────────────────────────────────────────

data class WorkflowStep(
    val id: Int,
    val type: String,   // "start"|"thinking"|"tool_call"|"tool_result"|"executor_step"
                        // |"judge"|"achieved"|"subtask_start"|"subtask_done"|"aggregate_done"
                        // |"limit"|"error"|"done"
    val title: String,
    val detail: String = "",
    val tokens: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

// ── WorkflowScreen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedTab by remember { mutableIntStateOf(0) }  // 0=Live, 1=History
    val liveSteps = remember { mutableStateListOf<WorkflowStep>() }
    var currentStatus by remember { mutableStateOf("idle") }
    var isRunning by remember { mutableStateOf(false) }
    var goalInput by remember { mutableStateOf("") }
    var showGoalDialog by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("goal") }  // "goal" | "wolfpack"
    var stepCounter by remember { mutableIntStateOf(0) }

    // Auto-scroll to bottom as steps arrive
    LaunchedEffect(liveSteps.size) {
        if (liveSteps.isNotEmpty()) {
            listState.animateScrollToItem(liveSteps.size - 1)
        }
    }

    // ── Event-polling loop ───────────────────────────────────────────────────
    // When isRunning, poll drain_events() every 300ms on IO, push to liveSteps on Main.
    LaunchedEffect(isRunning, mode) {
        if (!isRunning) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val py = Python.getInstance()
            val pyModule = if (mode == "goal") py.getModule("goal_loop")
                           else py.getModule("wolfpack")

            while (isActive && isRunning) {
                try {
                    val events = (pyModule.callAttr("drain_events", 300) as com.chaquo.python.PyObject)
                        .toKList()
                    for (evtRaw in events) {
                        val evt = evtRaw as? Map<String, Any?> ?: continue
                        val type = evt["type"]?.toString() ?: continue
                        val step = eventToStep(evt, ++stepCounter)
                        withContext(Dispatchers.Main) {
                            if (step != null) liveSteps.add(step)
                            // Update status bar
                            currentStatus = when (type) {
                                "start" -> "运行中…"
                                "thinking" -> "思考中 (轮 ${evt["iteration"]})"
                                "tool_call" -> "调用工具: ${evt["name"]}"
                                "executor_step" -> "执行中 (轮 ${evt["iteration"]})"
                                "judge" -> if (evt["achieved"] == true) "目标达成" else "裁判评估…"
                                "achieved" -> "目标达成"
                                "splitting" -> "任务拆分中…"
                                "split_done" -> "子任务已拆分"
                                "subtask_start" -> "子任务启动: ${evt["name"]}"
                                "subtask_done" -> "子任务完成: ${evt["name"]}"
                                "aggregating" -> "汇总结果…"
                                "aggregate_done" -> "汇总完成"
                                "error" -> "错误: ${evt["message"]}"
                                "limit" -> "达到限制: ${evt["reason"]}"
                                "done" -> {
                                    isRunning = false
                                    val result = evt["result"] as? Map<String, Any?>
                                    val err = result?.get("error")?.toString()
                                    if (err != null && err != "None" && err != "null") "结束 (错误)" else "完成"
                                }
                                else -> currentStatus
                            }
                        }
                        if (type == "done") break
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        liveSteps.add(WorkflowStep(
                            id = ++stepCounter, type = "error",
                            title = "事件读取失败",
                            detail = "${e.javaClass.simpleName}: ${e.message}"
                        ))
                        currentStatus = "错误"
                        isRunning = false
                    }
                    break
                }
                delay(50)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Workflow", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                if (isRunning) {
                    IconButton(onClick = {
                        isRunning = false
                        currentStatus = "已中止"
                    }) {
                        Icon(Icons.Filled.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = { showGoalDialog = true }) {
                        Icon(Icons.Filled.PlayArrow, "启动任务")
                    }
                }
            }
        )

        // Tab selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 }, label = { Text("Live") })
            FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 }, label = { Text("历史") })
        }

        // Status bar
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = currentStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        currentStatus.startsWith("错误") -> MaterialTheme.colorScheme.error
                        currentStatus == "完成" || currentStatus == "目标达成" -> Color(0xFF059669)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "wf_tab"
        ) { tab ->
            when (tab) {
                0 -> {
                    if (liveSteps.isEmpty()) {
                        // Empty state
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.Bolt, null,
                                    Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text("暂无运行中的任务",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("点击播放按钮启动 Goal 或 Wolfpack 任务",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(24.dp))
                                FilledTonalButton(onClick = { showGoalDialog = true }) {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("启动任务")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(liveSteps, key = { it.id }) { step ->
                                StepItem(step)
                            }
                        }
                    }
                }
                1 -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.History, null,
                                Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("任务历史",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("已完成的任务将在此显示",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // ── Launch dialog ────────────────────────────────────────────────────────
    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("启动任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == "goal",
                            onClick = { mode = "goal" },
                            label = { Text("Goal 循环") })
                        FilterChip(selected = mode == "wolfpack",
                            onClick = { mode = "wolfpack" },
                            label = { Text("Wolfpack") })
                    }
                    Text(
                        text = if (mode == "goal")
                            "Goal 循环：执行器 + 裁判，迭代直到目标达成"
                        else
                            "Wolfpack：拆分为子任务并发执行，聚合结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = goalInput,
                        onValueChange = { goalInput = it },
                        label = { Text("目标 / 任务描述") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGoalDialog = false
                        if (goalInput.isNotBlank()) {
                            liveSteps.clear()
                            stepCounter = 0
                            currentStatus = "启动中…"
                            isRunning = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val py = Python.getInstance()
                                    val storage = ProviderStorage(context)
                                    val provider = storage.getDefaultProvider()
                                    val apiKey = provider?.let { storage.getApiKey(it.id) } ?: ""
                                    val model = if (provider?.defaultModel?.isNotBlank() == true)
                                        provider.defaultModel
                                    else provider?.models?.firstOrNull() ?: ""
                                    val baseUrl = provider?.baseUrl ?: ""

                                    if (mode == "goal") {
                                        val gl = py.getModule("goal_loop")
                                        gl.callAttr("configure", baseUrl, apiKey, model)
                                        gl.callAttr("run_goal_async", goalInput)
                                    } else {
                                        val wp = py.getModule("wolfpack")
                                        wp.callAttr("configure", baseUrl, apiKey, model)
                                        wp.callAttr("run_wolfpack_async", goalInput)
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        liveSteps.add(WorkflowStep(
                                            id = ++stepCounter,
                                            type = "error",
                                            title = "启动失败",
                                            detail = "${e.javaClass.simpleName}: ${e.message}"
                                        ))
                                        currentStatus = "启动失败"
                                        isRunning = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = goalInput.isNotBlank()
                ) { Text("启动") }
            },
            dismissButton = {
                TextButton(onClick = { showGoalDialog = false }) { Text("取消") }
            }
        )
    }
}

// ── Event → WorkflowStep mapping ─────────────────────────────────────────────

private fun eventToStep(evt: Map<String, Any?>, id: Int): WorkflowStep? {
    return when (val type = evt["type"]?.toString() ?: return null) {
        "start" -> WorkflowStep(id, "start",
            title = "任务启动",
            detail = evt["goal"]?.toString() ?: evt["task"]?.toString() ?: "")
        "thinking" -> WorkflowStep(id, "thinking",
            title = "思考中… (轮 ${evt["iteration"]})")
        "tool_call" -> WorkflowStep(id, "tool_call",
            title = "[工具] ${evt["name"]}",
            detail = "参数: ${evt["args"]}")
        "tool_result" -> WorkflowStep(id, "tool_result",
            title = "[结果] ${evt["name"]}",
            detail = evt["result"]?.toString() ?: "")
        "executor_step" -> WorkflowStep(id, "executor_step",
            title = "执行 (轮 ${evt["iteration"]})",
            detail = evt["summary"]?.toString() ?: "",
            tokens = evt["tokens"]?.toString()?.toIntOrNull() ?: 0)
        "judge" -> WorkflowStep(id, "judge",
            title = if (evt["achieved"] == true) "[裁判] 目标达成" else "[裁判] 继续",
            detail = evt["feedback"]?.toString() ?: "")
        "achieved" -> WorkflowStep(id, "achieved",
            title = "目标达成 (轮 ${evt["iteration"]})")
        "splitting" -> WorkflowStep(id, "splitting", title = "拆分任务中…")
        "split_done" -> {
            val subtasks = (evt["subtasks"] as? List<*>)?.joinToString(", ") {
                (it as? Map<*, *>)?.get("name")?.toString() ?: ""
            } ?: ""
            WorkflowStep(id, "split_done", title = "任务已拆分", detail = subtasks)
        }
        "subtask_start" -> WorkflowStep(id, "subtask_start",
            title = "子任务 #${evt["subtask_id"]}: ${evt["name"]}",
            detail = evt["description"]?.toString() ?: "")
        "subtask_done" -> WorkflowStep(id, "subtask_done",
            title = "完成 #${evt["subtask_id"]}: ${evt["name"]}",
            detail = evt["result"]?.toString() ?: "",
            tokens = evt["tokens"]?.toString()?.toIntOrNull() ?: 0)
        "subtask_error" -> WorkflowStep(id, "error",
            title = "子任务失败: ${evt["name"]}",
            detail = evt["error"]?.toString() ?: "")
        "aggregating" -> WorkflowStep(id, "aggregating", title = "汇总中…")
        "aggregate_done" -> WorkflowStep(id, "aggregate_done",
            title = "汇总完成",
            detail = evt["summary"]?.toString() ?: "")
        "aggregate_error" -> WorkflowStep(id, "error",
            title = "汇总失败",
            detail = evt["error"]?.toString() ?: "")
        "limit" -> WorkflowStep(id, "limit",
            title = "达到限制: ${evt["reason"]} (轮 ${evt["iteration"]})")
        "error" -> WorkflowStep(id, "error",
            title = "错误",
            detail = evt["message"]?.toString() ?: "")
        "done" -> {
            val result = evt["result"] as? Map<String, Any?>
            val err = result?.get("error")?.toString()?.takeIf { it != "None" && it != "null" }
            val tokens = result?.get("total_tokens")?.toString()?.toIntOrNull() ?: 0
            val latency = result?.get("latency_ms")?.toString()?.toIntOrNull() ?: 0
            WorkflowStep(id, "done",
                title = if (err != null) "结束 (错误)" else "任务结束",
                detail = if (err != null) err else "$tokens tokens · ${latency}ms",
                tokens = tokens)
        }
        else -> null  // "is_running" heartbeat etc. — not rendered
    }
}

// ── StepItem ──────────────────────────────────────────────────────────────────

@Composable
fun StepItem(step: WorkflowStep) {
    val (iconVec, tintColor) = when (step.type) {
        "error" -> Icons.Outlined.Error to MaterialTheme.colorScheme.error
        "done", "achieved", "aggregate_done", "subtask_done" ->
            Icons.Outlined.CheckCircle to Color(0xFF059669)
        "tool_call", "tool_result" ->
            Icons.Outlined.Tune to MaterialTheme.colorScheme.primary
        else -> Icons.Outlined.Bolt to MaterialTheme.colorScheme.onSurfaceVariant
    }

    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = step.detail.isNotEmpty()) { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline dot
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(tintColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconVec, null, Modifier.size(14.dp), tint = tintColor)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (step.type == "error") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
            )
            if (expanded && step.detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (step.tokens > 0) {
                Text(
                    "${step.tokens} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}