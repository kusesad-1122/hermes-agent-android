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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermes.agent.data.*
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
                        onTest = {
                            testingId = provider.id
                            // TODO: Call Python bridge to test connectivity
                            // For now, simulate
                            storage.updateTestResult(provider.id, true)
                            providers = storage.getAllConfigs()
                            testingId = null
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
fun ProviderCard(
    provider: ProviderConfig,
    isTesting: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (provider.defaultModel.isNotEmpty()) {
                        Text(
                            text = provider.defaultModel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 状态指示器
                if (provider.lastTestOk) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "连通",
                        tint = Color(0xFF059669),
                        modifier = Modifier.size(20.dp)
                    )
                } else if (provider.lastTestTime > 0) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "断开",
                        tint = Color(0xFFDC2626),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Base URL
            Text(
                text = provider.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("测试中…")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("测试连通性")
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * 添加/编辑供应商页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    existingProvider: ProviderConfig? = null,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val storage = remember { ProviderStorage(context) }

    var name by remember { mutableStateOf(existingProvider?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existingProvider?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(existingProvider?.let { storage.getApiKey(it.id) } ?: "") }
    var defaultModel by remember { mutableStateOf(existingProvider?.defaultModel ?: "") }
    var showBuiltinPicker by remember { mutableStateOf(existingProvider == null) }
    var isCustom by remember { mutableStateOf(existingProvider?.type == ProviderType.CUSTOM) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (existingProvider != null) "编辑供应商" else "添加供应商") },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "取消")
                }
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
                                defaultModel = defaultModel,
                                isActive = true
                            )
                            storage.saveProvider(config, apiKey)
                            onDone()
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        if (showBuiltinPicker && existingProvider == null) {
            // 内置供应商选择列表
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(
                        "选择内置供应商",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
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
                            Text(
                                template.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
            // 配置表单
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = isCustom || existingProvider == null
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("默认模型 (可选)") },
                    placeholder = { Text("例如: gpt-4o, glm-5.2") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                // 测试连通性按钮
                Button(
                    onClick = {
                        isTesting = true
                        testResult = null
                        // TODO: 调用 Python 桥接测试
                        testResult = "需连接 Python 桥接后实现"
                        isTesting = false
                    },
                    enabled = !isTesting && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("测试连通性")
                    }
                }

                testResult?.let { result ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}