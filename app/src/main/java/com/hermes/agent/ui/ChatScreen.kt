package com.hermes.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.hermes.agent.data.ChaquopyBridge.toKMap
import com.hermes.agent.data.ChaquopyBridge.toKList
import com.hermes.agent.data.ChatStore
import com.hermes.agent.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ChatMessage(
    val role: String,
    val content: String,
    val toolName: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val chatStore = remember { ChatStore(context) }

    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var isProcessing by remember { mutableStateOf(false) }
    var providerConfigured by remember { mutableStateOf(false) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var currentStatus by remember { mutableStateOf("idle") }
    var lastError by remember { mutableStateOf<String?>(null) }

    var isListening by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    val voiceHelper = remember { VoiceHelper(context) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceHelper.startListening { recognized ->
                inputText = recognized; partialText = ""; isListening = false
            }
        } else {
            messages.add(ChatMessage("system", "需要录音权限才能使用语音输入"))
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(Unit) {
        SettingsManager.initialize(context)
        voiceHelper.onPartialResult = { text -> partialText = text; inputText = text }
        voiceHelper.onListeningStateChanged = { listening -> isListening = listening }
        voiceHelper.onError = { error -> messages.add(ChatMessage("system", error)); isListening = false; currentStatus = "error"; lastError = error }
        voiceHelper.initialize()

        val sessions = chatStore.getSessions()
        currentSessionId = if (sessions.isNotEmpty()) sessions[0].id else chatStore.createSession()

        currentSessionId?.let { sid ->
            val stored = chatStore.getMessages(sid)
            if (stored.isNotEmpty()) {
                messages.clear()
                stored.forEach { row -> messages.add(ChatMessage(row.role, row.content, row.toolName, row.timestamp)) }
            }
        }

        try {
            val py = Python.getInstance()
            val agentLoop = py.getModule("agent_loop")
            val model = SettingsManager.model.value
            val maxTokens = SettingsManager.maxTokens.value
            val maxIter = SettingsManager.maxIterations.value
            val temp = SettingsManager.temperature.value
            val apiKey = SettingsManager.getSecureString("deepseek_api_key") ?: "sk-06a0fc1cc4f94aebb808b0286c8fc2f9"
            val baseUrl = "https://api.deepseek.com/v1"
            agentLoop.callAttr("configure", baseUrl, apiKey, model, maxIter, maxTokens, temp)
            providerConfigured = true
            currentStatus = "ready"
            if (messages.isEmpty()) messages.add(ChatMessage("system", "Hermes Agent 就绪 ($model)"))
        } catch (e: Exception) {
            val err = "初始化失败: ${e.message}"
            messages.add(ChatMessage("system", err))
            currentStatus = "error"
            lastError = err
        }
    }

    DisposableEffect(Unit) { onDispose { voiceHelper.shutdown() } }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || isProcessing) return

        inputText = ""
        partialText = ""
        messages.add(ChatMessage("user", text))
        currentSessionId?.let { chatStore.addMessage(it, "user", text) }
        isProcessing = true
        currentStatus = "requesting"
        lastError = null

        scope.launch {
            try {
                val result = withTimeoutOrNull(120_000L) {
                    withContext(Dispatchers.IO) {
                        val py = Python.getInstance()
                        val agentLoop = py.getModule("agent_loop")
                        val history = py.builtins.callAttr("list")
                        for (msg in messages.filter { it.role != "system" && it.role != "tool" }) {
                            val dict = py.builtins.callAttr("dict")
                            dict.callAttr("__setitem__", "role", msg.role)
                            dict.callAttr("__setitem__", "content", msg.content)
                            history.callAttr("append", dict)
                        }
                        @Suppress("UNCHECKED_CAST")
                        (agentLoop.callAttr("run_agent", text, history) as com.chaquo.python.PyObject).toKMap()
                    }
                }

                if (result == null) {
                    val errMsg = "请求超时 (120s)，请检查网络或模型可用性"
                    messages.add(ChatMessage("system", errMsg))
                    currentSessionId?.let { chatStore.addMessage(it, "system", errMsg) }
                    currentStatus = "timeout"
                    lastError = errMsg
                    return@launch
                }

                val response = result["response"]?.toString() ?: ""
                val toolCalls = result["tool_calls"]
                val iterations = result["iterations"]?.toString()?.toIntOrNull() ?: 0
                val totalTokens = result["total_tokens"]?.toString()?.toIntOrNull() ?: 0
                val latencyMs = result["latency_ms"]?.toString()?.toIntOrNull() ?: 0
                val error = result["error"]?.toString()

                if (toolCalls != null) {
                    currentStatus = "tooling"
                    for (tc in (toolCalls as com.chaquo.python.PyObject).toKList()) {
                        val tcMap = tc as? Map<String, Any?> ?: emptyMap()
                        val name = tcMap["name"]?.toString() ?: "unknown"
                        val toolMsg = ChatMessage("tool", "[$name]", toolName = name)
                        messages.add(toolMsg)
                        currentSessionId?.let { chatStore.addMessage(it, "tool", "[$name]", name) }
                    }
                }

                if (response.isNotEmpty()) {
                    messages.add(ChatMessage("assistant", response))
                    currentSessionId?.let { chatStore.addMessage(it, "assistant", response) }
                    currentStatus = "done"
                } else if (error != null) {
                    val errMsg = formatError(error)
                    messages.add(ChatMessage("system", errMsg))
                    currentSessionId?.let { chatStore.addMessage(it, "system", errMsg) }
                    currentStatus = "error"
                    lastError = errMsg
                }

                if (iterations > 0 && response.isNotEmpty()) {
                    val stats = "$iterations 轮 | $totalTokens tokens | ${latencyMs}ms"
                    messages.add(ChatMessage("system", stats))
                }
            } catch (e: Exception) {
                val errMsg = formatError(e.message ?: e.toString())
                messages.add(ChatMessage("system", errMsg))
                currentSessionId?.let { chatStore.addMessage(it, "system", errMsg) }
                currentStatus = "error"
                lastError = errMsg
            } finally {
                isProcessing = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Status panel
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("状态: $currentStatus", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("模型: ${SettingsManager.model.value}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lastError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(lastError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (messages.isEmpty() && !isProcessing) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Hermes Agent", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(if (providerConfigured) "已连接" else "正在连接...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            items(messages) { msg -> MessageBubble(msg) }

            if (isListening) {
                item {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("正在聆听...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        if (partialText.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(partialText.take(50), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (isProcessing && !isListening) {
                item {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("处理中...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 4.dp)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { }) { Icon(Icons.Outlined.AttachFile, contentDescription = "附件") }

                IconButton(
                    onClick = {
                        if (isListening) voiceHelper.stopListening()
                        else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                voiceHelper.startListening { recognized -> inputText = recognized; partialText = "" }
                            } else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Icon(if (isListening) Icons.Filled.Stop else Icons.Outlined.Mic, contentDescription = if (isListening) "停止" else "语音", tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }

                TextField(
                    value = inputText, onValueChange = { inputText = it },
                    placeholder = { Text(if (isListening) "正在听..." else "输入消息...") },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surface
                    ),
                    singleLine = false, maxLines = 4
                )

                IconButton(onClick = { sendMessage() }, enabled = inputText.isNotBlank() && !isProcessing) {
                    Icon(Icons.Outlined.Send, contentDescription = "发送", tint = if (inputText.isNotBlank() && !isProcessing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatError(error: String): String {
    return when {
        "Authentication" in error || "401" in error -> "鉴权失败：API Key 无效或已过期"
        "Connection" in error || "Connect" in error -> "网络连接失败：请检查网络"
        "timeout" in error.lowercase() || "Timeout" in error -> "请求超时：模型响应超过 60 秒"
        "NotFound" in error || "404" in error || "model" in error.lowercase() -> "模型不存在：请检查模型名称"
        "RateLimit" in error || "429" in error -> "请求频率超限：请稍后重试"
        else -> error
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool"
    val isSystem = msg.role == "system"

    val bubbleColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.surfaceVariant
        isSystem -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        isTool -> MaterialTheme.colorScheme.onSurfaceVariant
        isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isUser) 16.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 16.dp),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = msg.content,
                style = if (isSystem) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                color = textColor,
                fontFamily = if (msg.content.contains("```")) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
