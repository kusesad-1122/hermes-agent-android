package com.hermes.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.ChaquopyBridge.toKList
import com.hermes.agent.data.ChaquopyBridge.toKMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backendInfo by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var modeInfo by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var capabilities by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var stats by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var audit by remember { mutableStateOf(listOf<Map<String, Any?>>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun loadRootState() {
        scope.launch(Dispatchers.IO) {
            loading = true
            error = null
            try {
                val py = Python.getInstance()
                val root = py.getModule("root_gateway")
                val filesDir = context.filesDir.absolutePath
                root.callAttr("initialize", "$filesDir/hermes_root_audit.db", "$filesDir/hermes_snapshots")
                backendInfo = (root.callAttr("get_backend_info") as com.chaquo.python.PyObject).toKMap()
                modeInfo = (root.callAttr("get_confirmation_mode") as com.chaquo.python.PyObject).toKMap()
                capabilities = (root.callAttr("get_capabilities") as com.chaquo.python.PyObject).toKMap()
                stats = (root.callAttr("get_audit_stats") as com.chaquo.python.PyObject).toKMap()
                audit = (root.callAttr("get_audit_log", 20) as com.chaquo.python.PyObject).toKList().mapNotNull { it as? Map<String, Any?> }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadRootState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Root 与权限") },
                actions = {
                    TextButton(onClick = { loadRootState() }) { Text("刷新") }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前状态", style = MaterialTheme.typography.titleMedium)
                        Text("Backend: ${backendInfo["backend_name"] ?: backendInfo["backend"] ?: "unknown"}")
                        Text("Root 可用: ${backendInfo["is_root_available"] ?: false}")
                        Text("确认模式: ${modeInfo["mode"] ?: "unknown"}")
                        Text(modeInfo["description"]?.toString() ?: "")
                    }
                }
            }

            if (loading) {
                item { CircularProgressIndicator() }
            }

            error?.let { err ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(err, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            item {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("能力开关", style = MaterialTheme.typography.titleMedium)
                        capabilities.forEach { (name, enabled) ->
                            Text("• $name: $enabled")
                        }
                    }
                }
            }

            item {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("审计统计", style = MaterialTheme.typography.titleMedium)
                        Text("总命令数: ${stats["total_commands"] ?: 0}")
                        Text("拒绝数: ${stats["denied"] ?: 0}")
                        Text("危险操作数: ${stats["irreversible_executed"] ?: 0}")
                    }
                }
            }

            item {
                Text("最近审计", style = MaterialTheme.typography.titleMedium)
            }

            items(audit) { row ->
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row["command"]?.toString() ?: "", style = MaterialTheme.typography.bodyMedium)
                        Text("状态: ${row["exit_code"]} · ${row["timestamp_str"] ?: ""}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
