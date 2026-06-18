package com.hermes.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.*
import com.hermes.agent.data.ChaquopyBridge.toKMap
import com.hermes.agent.data.ChaquopyBridge.toKList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onNavigateToAdd: () -> Unit,
    onNavigateToEdit: (ProviderConfig) -> Unit
) {
    val context = LocalContext.current
    val storage = remember { ProviderStorage(context) }
    var providers by remember { mutableStateOf(storage.getAllConfigs()) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("模型与供应商", style = MaterialTheme.typography.headlineLarge) },
            actions = {
                IconButton(onClick = onNavigateToAdd) {
                    Icon(Icons.Default.Add, contentDescription = "添加供应商")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        bannerMessage?.let {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (providers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("尚未配置任何模型供应商", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNavigateToAdd) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加供应商")
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(providers) { provider ->
                    ProviderCard(
                        provider = provider,
                        isTesting = testingId == provider.id,
                        onTest = {
                            testingId = provider.id
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val py = Python.getInstance()
                                        val pm = py.getModule("provider_manager")
                                        val apiKey = storage.getApiKey(provider.id)
                                        @Suppress("UNCHECKED_CAST")
                                        (pm.callAttr("test_connectivity", provider.baseUrl, apiKey, provider.defaultModel) as com.chaquo.python.PyObject).toKMap()
                                    } catch (e: Exception) {
                                        mapOf("success" to false, "error" to (e.message ?: "Unknown error"), "models" to emptyList<String>(), "latency_ms" to 0)
                                    }
                                }
                                val success = result["success"]?.toString()?.toBoolean() ?: false
                                val latency = result["latency_ms"]?.toString() ?: "0"
                                val error = result["error"]?.toString()
                                bannerMessage = if (success) "${provider.name} 连通成功 (${latency}ms)" else "${provider.name} 测试失败: ${error ?: "unknown"}"
                                storage.updateTestResult(provider.id, success)
                                providers = storage.getAllConfigs()
                                testingId = null
                            }
                        },
                        onEdit = { onNavigateToEdit(provider) },
                        onDelete = {
                            storage.deleteProvider(provider.id)
                            providers = storage.getAllConfigs()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ProviderCard(provider: ProviderConfig, isTesting: Boolean, onTest: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    if (provider.defaultModel.isNotEmpty()) {
                        Text(provider.defaultModel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (provider.lastTestOk) {
                    Icon(Icons.Default.Check, contentDescription = "连通", tint = Color(0xFF059669), modifier = Modifier.size(20.dp))
                } else if (provider.lastTestTime > 0) {
                    Icon(Icons.Default.Close, contentDescription = "断开", tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !isTesting, modifier = Modifier.weight(1f)) {
                    if (isTesting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp)); Text("测试中…")
                    } else {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("测试连通性")
                    }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑", Modifier.size(20.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除", tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(existingProvider: ProviderConfig? = null, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { ProviderStorage(context) }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existingProvider?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existingProvider?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(existingProvider?.let { storage.getApiKey(it.id) } ?: "") }
    var defaultModel by remember { mutableStateOf(existingProvider?.defaultModel ?: "") }
    var showBuiltinPicker by remember { mutableStateOf(existingProvider == null) }
    var isCustom by remember { mutableStateOf(existingProvider?.type == ProviderType.CUSTOM) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (existingProvider != null) "编辑供应商" else "添加供应商") },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "取消") } },
            actions = {
                TextButton(onClick = {
                    if (name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                        val id = existingProvider?.id ?: UUID.randomUUID().toString()
                        val config = ProviderConfig(id, name, if (isCustom) ProviderType.CUSTOM else ProviderType.BUILTIN, baseUrl, "key_$id", availableModels, defaultModel, true)
                        storage.saveProvider(config, apiKey)
                        onDone()
                    }
                }) { Text("保存") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        if (showBuiltinPicker && existingProvider == null) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Text("选择内置供应商", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp)) }
                items(BuiltinProviders.templates) { template ->
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable { name = template.name; baseUrl = template.baseUrl; showBuiltinPicker = false }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(template.name, style = MaterialTheme.typography.titleMedium)
                            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = { isCustom = true; showBuiltinPicker = false }, modifier = Modifier.fillMaxWidth()) { Text("自定义 (OpenAI 兼容)") } }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = isCustom || existingProvider == null)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                // Model field with refresh button
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = defaultModel, onValueChange = { defaultModel = it },
                        label = { Text("模型 ID") }, placeholder = { Text("例如: deepseek-chat") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    IconButton(onClick = {
                        if (baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                            isLoadingModels = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val py = Python.getInstance()
                                        val pm = py.getModule("provider_manager")
                                        @Suppress("UNCHECKED_CAST")
                                        (pm.callAttr("fetch_models", baseUrl, apiKey) as com.chaquo.python.PyObject).toKMap()
                                    } catch (e: Exception) { mapOf("success" to false, "models" to emptyList<String>(), "error" to e.message) }
                                }
                                val success = result["success"]?.toString()?.toBoolean() ?: false
                                if (success) {
                                    @Suppress("UNCHECKED_CAST")
                                    availableModels = (result["models"] as? List<*>)?.map { it.toString() } ?: emptyList()
                                    if (availableModels.isNotEmpty() && defaultModel.isBlank()) {
                                        defaultModel = availableModels[0]
                                    }
                                } else {
                                    testResult = result["error"]?.toString()
                                }
                                isLoadingModels = false
                            }
                        }
                    }, enabled = !isLoadingModels && baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                        if (isLoadingModels) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "刷新模型列表")
                    }
                }

                // Available models dropdown
                if (availableModels.isNotEmpty()) {
                    Text("已拉取 ${availableModels.size} 个模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("可用模型 (${availableModels.size}):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                        items(availableModels) { model ->
                            Text(
                                model,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().clickable { defaultModel = model }.padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        isTesting = true; testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val py = Python.getInstance()
                                    val pm = py.getModule("provider_manager")
                                    @Suppress("UNCHECKED_CAST")
                                    (pm.callAttr("test_connectivity", baseUrl, apiKey, defaultModel) as com.chaquo.python.PyObject).toKMap()
                                } catch (e: Exception) { mapOf("success" to false, "error" to e.message) }
                            }
                            val success = result["success"]?.toString()?.toBoolean() ?: false
                            val latency = result["latency_ms"]?.toString() ?: "0"
                            val models = result["models"]
                            testResult = if (success) "连通成功 (${latency}ms)" else "失败: ${result["error"]}"
                            if (success && models != null) {
                                @Suppress("UNCHECKED_CAST")
                                availableModels = (models as? com.chaquo.python.PyObject)?.toKList()?.map { it.toString() } ?: emptyList()
                            }
                            isTesting = false
                        }
                    },
                    enabled = !isTesting && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)); Text("测试中…") }
                    else { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("测试连通性") }
                }

                testResult?.let { result ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(result, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
