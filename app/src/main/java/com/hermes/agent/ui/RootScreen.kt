package com.hermes.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.ChaquopyBridge.toKMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RootStatus(
    val backend: String = "UNKNOWN",
    val isAvailable: Boolean = false,
    val confirmationMode: String = "only_dangerous",
    val capabilities: Map<String, Boolean> = emptyMap(),
    val auditLogCount: Int = 0,
    val lastError: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(RootStatus()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedMode by remember { mutableStateOf("only_dangerous") }

    fun loadStatus() {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val py = Python.getInstance()
                val rg = py.getModule("root_gateway")
                val init = (rg.callAttr("initialize") as com.chaquo.python.PyObject).toKMap()
                val stats = (rg.callAttr("get_stats") as com.chaquo.python.PyObject).toKMap()
                val caps = (stats["capabilities"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value.toString().toBoolean() } ?: emptyMap()
                status = RootStatus(
                    backend = init["backend_name"]?.toString() ?: "UNKNOWN",
                    isAvailable = init["is_root_available"]?.toString()?.toBoolean() ?: false,
                    confirmationMode = stats["confirmation_mode"]?.toString() ?: "only_dangerous",
                    capabilities = caps,
                    auditLogCount = stats["audit_logs"]?.toString()?.toIntOrNull() ?: 0,
                    lastError = init["error"]?.toString()
                )
                selectedMode = status.confirmationMode
            } catch (e: Exception) {
                status = RootStatus(lastError = e.message)
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadStatus() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Root & 权限", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AdminPanelSettings, null, tint = if (status.isAvailable) Color(0xFF059669) else MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text("Root 状态", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("后端: ${status.backend}", style = MaterialTheme.typography.bodyMedium)
                            Text("可用: ${if (status.isAvailable) "是" else "否"}", style = MaterialTheme.typography.bodyMedium)
                            Text("审计记录: ${status.auditLogCount}", style = MaterialTheme.typography.bodyMedium)
                            if (!status.lastError.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(status.lastError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                item {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Security, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("权限确认模式", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(12.dp))
                            listOf(
                                "only_dangerous" to "仅危险操作确认",
                                "all_root" to "所有 Root 操作确认",
                                "fully_auto" to "全自动",
                            ).forEach { (value, label) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selectedMode == value, onClick = { selectedMode = value })
                                    Spacer(Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val py = Python.getInstance()
                                        val rg = py.getModule("root_gateway")
                                        rg.callAttr("set_confirmation_mode", selectedMode)
                                        loadStatus()
                                    } catch (_: Exception) {}
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("保存模式")
                            }
                        }
                    }
                }

                item {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("能力开关", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(12.dp))
                            status.capabilities.toList().sortedBy { it.first }.forEach { (name, enabled) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                    Text(if (enabled) "ON" else "OFF", style = MaterialTheme.typography.labelMedium, color = if (enabled) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
