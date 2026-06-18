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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val role: String,       // "user" | "assistant" | "tool" | "system"
    val content: String,
    val toolName: String = "",
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var isProcessing by remember { mutableStateOf(false) }
    var providerConfigured by remember { mutableStateOf(false) }

    // Voice state
    var isListening by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    val voiceHelper = remember { VoiceHelper(context) }

    // Permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceHelper.startListening { recognized ->
                inputText = recognized
                partialText = ""
                isListening = false
            }
        } else {
            messages.add(ChatMessage("system", "⚠️ 需要录音权限才能使用语音输入"))
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Initialize voice + DeepSeek
    LaunchedEffect(Unit) {
        voiceHelper.onPartialResult = { text ->
            partialText = text
            inputText = text
        }
        voiceHelper.onListeningStateChanged = { listening ->
            isListening = listening
        }
        voiceHelper.onError = { error ->
            messages.add(ChatMessage("system", "🎤 $error"))
            isListening = false
        }
        voiceHelper.initialize()

        try {
            val py = Python.getInstance()
            val agentLoop = py.getModule("agent_loop")
            agentLoop.callAttr(
                "configure",
                "https://api.deepseek.com/v1",
                "sk-06a0fc1cc4f94aebb808b0286c8fc2f9",
                "deepseek-chat",
                10,
                4096,
                0.7
            )
            providerConfigured = true
            messages.add(ChatMessage("system", "Hermes Agent 已就绪 (DeepSeek)"))
        } catch (e: Exception) {
            messages.add(ChatMessage("system", "初始化失败: ${e.message}"))
        }
    }

    // Cleanup voice on dispose
    DisposableEffect(Unit) {
        onDispose {
            voiceHelper.shutdown()
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || isProcessing) return

        inputText = ""
        partialText = ""
        messages.add(ChatMessage("user", text))
        isProcessing = true

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val py = Python.getInstance()
                    val agentLoop = py.getModule("agent_loop")
                    val history = py.builtins.callAttr("list")
                    for (msg in messages.filter { it.role != "system" }) {
                        val dict = py.builtins.callAttr("dict")
                        dict.callAttr("__setitem__", "role", msg.role)
                        dict.callAttr("__setitem__", "content", msg.content)
                        history.callAttr("append", dict)
                    }
                    @Suppress("UNCHECKED_CAST")
                    (agentLoop.callAttr("run_agent", text, history) as com.chaquo.python.PyObject).asMap() as Map<Any?, Any?>
                }

                val response = result["response"]?.toString() ?: ""
                val toolCalls = result["tool_calls"]
                val iterations = result["iterations"]?.toString()?.toIntOrNull() ?: 0
                val totalTokens = result["total_tokens"]?.toString()?.toIntOrNull() ?: 0
                val latencyMs = result["latency_ms"]?.toString()?.toIntOrNull() ?: 0
                val error = result["error"]?.toString()

                if (toolCalls != null) {
                    for (tc in (toolCalls as com.chaquo.python.PyObject).asList()) {
                        val tcMap: Map<Any?, Any?> = (tc as com.chaquo.python.PyObject).asMap()
                        val name = tcMap["name"]?.toString() ?: "unknown"
                        messages.add(ChatMessage("tool", "🔧 $name", toolName = name))
                    }
                }

                if (response.isNotEmpty()) {
                    messages.add(ChatMessage("assistant", response))
                } else if (error != null) {
                    messages.add(ChatMessage("system", "⚠️ $error"))
                }

                if (iterations > 0) {
                    messages.add(ChatMessage("system", "📊 $iterations 轮 | $totalTokens tokens | ${latencyMs}ms"))
                }
            } catch (e: Exception) {
                messages.add(ChatMessage("system", "❌ 错误: ${e.message}"))
            } finally {
                isProcessing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (messages.isEmpty() && !isProcessing) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Hermes Agent",
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (providerConfigured) "DeepSeek 已连接" else "正在连接...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(messages) { msg ->
                MessageBubble(msg)
            }

            // Listening indicator
            if (isListening) {
                item {
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在聆听...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (partialText.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                partialText.take(50),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isProcessing && !isListening) {
                item {
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "思考中...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Input bar
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, top = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { }) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "附件")
                }

                // Voice button with permission check
                IconButton(
                    onClick = {
                        if (isListening) {
                            voiceHelper.stopListening()
                        } else if (isSpeaking) {
                            voiceHelper.stopSpeaking()
                            isSpeaking = false
                        } else {
                            // Check permission
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                voiceHelper.startListening { recognized ->
                                    inputText = recognized
                                    partialText = ""
                                }
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Icon(
                        if (isListening) Icons.Filled.Stop else Icons.Outlined.Mic,
                        contentDescription = if (isListening) "停止" else "语音",
                        tint = if (isListening)
                            MaterialTheme.colorScheme.error
                        else if (!isProcessing)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }

                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            if (isListening) "正在听..." else "输入消息..."
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surface
                    ),
                    singleLine = false,
                    maxLines = 4
                )

                IconButton(
                    onClick = { sendMessage() },
                    enabled = inputText.isNotBlank() && !isProcessing
                ) {
                    Icon(
                        Icons.Outlined.Send,
                        contentDescription = "发送",
                        tint = if (inputText.isNotBlank() && !isProcessing)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
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