package com.hermes.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.ChaquopyBridge.toKMap
import com.hermes.agent.data.ChaquopyBridge.toKList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch





data class MemoryEntry(
    val key: String,
    val value: String,
    val category: String,
    val updatedAt: String,
)

data class SessionInfo(
    val id: String,
    val title: String?,
    val messageCount: Int,
    val startedAt: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen() {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var memories by remember { mutableStateOf(listOf<MemoryEntry>()) }
    var sessions by remember { mutableStateOf(listOf<SessionInfo>()) }
    var stats by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    // Load data
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val ms = py.getModule("memory_system")
                ms.callAttr("initialize")

                // Memories
                val memList = (ms.callAttr("memory_list") as com.chaquo.python.PyObject).asList()
                memories = memList.map { m ->
                    val d = m.asPyMap()
                    MemoryEntry(
                        key = d["key"]?.toString() ?: "",
                        value = d["value"]?.toString() ?: "",
                        category = d["category"]?.toString() ?: "",
                        updatedAt = try {
                            val ts = d["updated_at"]?.toString()?.toDoubleOrNull() ?: 0.0
                            java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date((ts * 1000).toLong()))
                        } catch (_: Exception) { "" },
                    )
                }

                // Sessions
                val sessList = (ms.callAttr("list_sessions", 50, true) as com.chaquo.python.PyObject).asList()
                sessions = sessList.map { s ->
                    val d = s.asPyMap()
                    SessionInfo(
                        id = d["id"]?.toString() ?: "",
                        title = d["title"]?.toString(),
                        messageCount = d["message_count"]?.toString()?.toIntOrNull() ?: 0,
                        startedAt = try {
                            val ts = d["started_at"]?.toString()?.toDoubleOrNull() ?: 0.0
                            java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date((ts * 1000).toLong()))
                        } catch (_: Exception) { "" },
                    )
                }

                // Stats
                val s = ms.callAttr("get_stats").asPyMap()
                stats = s.entries.associate { (k, v) -> k.toString() to v?.toString() }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    val filtered = if (searchQuery.isBlank()) memories
    else memories.filter {
        it.key.contains(searchQuery, true) || it.value.contains(searchQuery, true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Memory", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        // Stats bar
        if (stats.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip("${stats["sessions"] ?: "?"} 会话")
                StatChip("${stats["messages"] ?: "?"} 消息")
                StatChip("${stats["memories"] ?: "?"} 记忆")
            }
        }

        // Tabs
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("KV 记忆") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("会话历史") })
        }

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(if (tab == 0) "搜索记忆..." else "搜索会话...") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else when (tab) {
            0 -> MemoryTab(filtered)
            1 -> SessionTab(sessions, searchQuery)
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun MemoryTab(entries: List<MemoryEntry>) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无记忆数据", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries) { entry ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(entry.key, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            if (entry.category.isNotBlank()) {
                                SuggestionChip(onClick = {}, label = { Text(entry.category, style = MaterialTheme.typography.labelSmall) })
                            }
                            Spacer(Modifier.weight(1f))
                            Text(entry.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(entry.value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTab(sessions: List<SessionInfo>, query: String) {
    val filtered = if (query.isBlank()) sessions
    else sessions.filter { (it.title ?: it.id).contains(query, true) }

    if (filtered.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无会话记录", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { session ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(session.title ?: "Session ${session.id}", style = MaterialTheme.typography.titleSmall)
                            Text("${session.messageCount} 条消息 · ${session.startedAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}