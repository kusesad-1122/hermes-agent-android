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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.*
import com.hermes.agent.data.ChaquopyBridge.toKList
import com.hermes.agent.data.ChaquopyBridge.toKMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onNavigateToAdd: () -> Unit,
    onNavigateToEdit: (ProviderConfig) -> Unit
) {
    val context = LocalContext.current
    val storage = remember { ProviderStorage(context) }
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(storage.getAllConfigs()) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }

    fun refreshProviders() {
        providers = storage.getAllConfigs()
    }

    fun testProvider(provider: ProviderConfig) {
        scope.launch(Dispatchers.IO) {
            testingId = provider.id
            banner = null
            try {
                val py = Python.getInstance()
                val mod = py.getModule("provider_test")
                val apiKey = storage.getApiKey(provider.id)
                val result = (mod.callAttr("test_provider", provider.baseUrl, apiKey, provider.defaultModel) as com.chaquo.python.PyObject).toKMap()
                val ok = result["success"] == true
                val models = when (val raw = result["models"]) {
                    is com.chaquo.python.PyObject -> raw.toKList().map { it.toString() }
                    is List<*> -> raw.map { it.toString() }
                    else -> emptyList()
                }
                storage.updateTestResult(provider.id, ok)
                if (models.isNotEmpty()) {
                    val chosen = if (provider.defaultModel.isNotBlank()) provider.defaultModel else models.first()
                    storage.updateModels(provider.id, models, chosen)
                }
                if (ok) storage.setActiveProvider(provider.id)
                refreshProviders()
                banner = result["message"]?.toString() ?: if (ok) "连通成功" else "测试失败"
            } catch (e: Exception) {
                banner = "测试失败: ${e.message}"
            } finally {
                testingId = null
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("模型与供应商", style = MaterialTheme.typography.headlineLarge) },
            actions = {
                IconButton(onClick = onNavigateToAdd) {
                    Icon(Icons.Default.Add, contentDescription = "添加供应商")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        banner?.let {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        if (providers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "尚未配置任何模型供应商",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onNavigateToAdd) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加供应商")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(providers) { provider ->
                    ProviderCard(
                        provider = provider,
                        isTesting = testingId == provider.id,
                        onTest = { testProvider(provider) },
                        onEdit = { onNavigateToEdit(provider) },
                        onDelete = {
                            storage.deleteProvider(provider.id)
                            refreshProviders()
                        },
                        onActivate = {
                            storage.setActiveProvider(provider.id)
                            refreshProviders()
                            banner = "已切换当前供应商: ${provider.name}"
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ProviderCard(
    provider: ProviderConfig,
    isTesting: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onActivate: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = provider.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (provider.defaultModel.isNotBlank()) provider.defaultModel else "未选择模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (provider.isActive) {
                    AssistChip(onClick = onActivate, label = { Text("当前") })
                    Spacer(Modifier.width(8.dp))
                }
                if (provider.lastTestOk) {
                    Icon(Icons.Default.Check, contentDescription = "连通", tint = Color(0xFF059669), modifier = Modifier.size(20.dp))
                } else if (provider.lastTestTime > 0) {
                    Icon(Icons.Default.Close, contentDescription = "断开", tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = provider.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "模型数: ${provider.models.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !isTesting, modifier = Modifier.weight(1f)) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("刷新中…")
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新模型")
                    }
                }
                TextButton(onClick = onActivate) { Text("设为当前") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    existingProvider: ProviderConfig? = null,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
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
    var modelOptions by remember { mutableStateOf(existingProvider?.models ?: emptyList()) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (existingProvider != null) "编辑供应商" else "添加供应商") },
            navigationIcon = {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "取消") }
            },
            actions = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                            val id = existingProvider?.id ?: UUID.randomUUID().toString()
                            val config = ProviderConfig(
                                id = id,
                                name = name,
                                type = if (isCustom) ProviderType.CUSTOM else ProviderType.BUILTIN,
                                baseUrl = baseUrl,
                                apiKeyRef = "key_$id",
                                models = modelOptions,
                                defaultModel = defaultModel,
                                isActive = true,
                                lastTestOk = existingProvider?.lastTestOk ?: false,
                                lastTestTime = existingProvider?.lastTestTime ?: 0L,
                                createdAt = existingProvider?.createdAt ?: System.currentTimeMillis()
                            )
                            storage.saveProvider(config, apiKey)
                            storage.setActiveProvider(id)
                            onDone()
                        }
                    }
                ) { Text("保存") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        if (showBuiltinPicker && existingProvider == null) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text("选择内置供应商", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
                }
                items(BuiltinProviders.templates) { template ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                name = template.name
                                baseUrl = template.baseUrl
                                showBuiltinPicker = false
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(template.name, style = MaterialTheme.typography.titleMedium)
                            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            isCustom = true
                            showBuiltinPicker = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("自定义 (OpenAI 兼容)")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = isCustom || existingProvider == null)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = defaultModel, onValueChange = { defaultModel = it }, label = { Text("默认模型 (可选)") }, placeholder = { Text("例如: gpt-4o, glm-5.2") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                if (modelOptions.isNotEmpty()) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("已拉取模型 (${modelOptions.size})", style = MaterialTheme.typography.labelLarge)
                            Text(modelOptions.take(10).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            isTesting = true
                            testResult = null
                            try {
                                val py = Python.getInstance()
                                val mod = py.getModule("provider_test")
                                val result = (mod.callAttr("test_provider", baseUrl, apiKey, defaultModel) as com.chaquo.python.PyObject).toKMap()
                                val models = when (val raw = result["models"]) {
                                    is com.chaquo.python.PyObject -> raw.toKList().map { it.toString() }
                                    is List<*> -> raw.map { it.toString() }
                                    else -> emptyList()
                                }
                                modelOptions = models
                                if (defaultModel.isBlank() && models.isNotEmpty()) defaultModel = models.first()
                                testResult = result["message"]?.toString() ?: "已完成"
                            } catch (e: Exception) {
                                testResult = "测试失败: ${e.message}"
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("测试并拉取模型")
                    }
                }

                testResult?.let { result ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(text = result, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}