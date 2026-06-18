package com.hermes.agent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.ChaquopyBridge.toKMap
import com.hermes.agent.data.ChaquopyBridge.toKList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class SkillItem(
    val name: String, val description: String, val category: String?,
    val tags: List<String>, val path: String,
)

data class McpServerItem(
    val name: String, val url: String, val connected: Boolean,
    val toolsCount: Int, val error: String?,
)

@Suppress("UNCHECKED_CAST")
private fun parseSkillItem(raw: Any?): SkillItem {
    val m = raw as? Map<String, Any?> ?: emptyMap()
    return SkillItem(
        name = m["name"]?.toString() ?: "",
        description = m["description"]?.toString() ?: "",
        category = m["category"]?.toString(),
        tags = try { (m["tags"] as? List<*>)?.map { it.toString() } ?: emptyList() } catch (_: Exception) { emptyList() },
        path = m["path"]?.toString() ?: "",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen() {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Skills, 1=MCP

    // Skills state
    var skills by remember { mutableStateOf(listOf<SkillItem>()) }
    var isLoadingSkills by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSkill by remember { mutableStateOf<SkillItem?>(null) }
    var skillContent by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }

    // MCP state
    var mcpServers by remember { mutableStateOf(listOf<McpServerItem>()) }
    var isLoadingMcp by remember { mutableStateOf(false) }

    fun loadSkills() {
        scope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val se = py.getModule("skills_engine")
                se.callAttr("initialize")
                val list = (se.callAttr("list_skills") as com.chaquo.python.PyObject).toKList()
                skills = list.mapNotNull { item ->
                    val m = item as? Map<String, Any?> ?: return@mapNotNull null
                    SkillItem(m["name"]?.toString() ?: "", m["description"]?.toString() ?: "",
                        m["category"]?.toString(),
                        try { (m["tags"] as? List<*>)?.map { it.toString() } ?: emptyList() } catch (_: Exception) { emptyList() },
                        m["path"]?.toString() ?: "")
                }
                refreshMessage = "Skills 已刷新：${skills.size} 项"
            } catch (e: Exception) { refreshMessage = "Skills 刷新失败: ${e.message}" }
            isLoadingSkills = false
        }
    }

    fun loadMcpServers() {
        scope.launch(Dispatchers.IO) {
            isLoadingMcp = true
            try {
                val py = Python.getInstance()
                val mcp = py.getModule("mcp_client")
                mcp.callAttr("initialize")
                val list = (mcp.callAttr("list_servers") as com.chaquo.python.PyObject).toKList()
                mcpServers = list.mapNotNull { item ->
                    val m = item as? Map<String, Any?> ?: return@mapNotNull null
                    McpServerItem(
                        m["name"]?.toString() ?: "",
                        m["url"]?.toString() ?: "",
                        m["connected"]?.toString()?.toBoolean() ?: false,
                        m["tools_count"]?.toString()?.toIntOrNull() ?: 0,
                        m["error"]?.toString(),
                    )
                }
                refreshMessage = "MCP 已刷新：${mcpServers.size} 个 server"
            } catch (e: Exception) { refreshMessage = "MCP 刷新失败: ${e.message}" }
            isLoadingMcp = false
        }
    }

    LaunchedEffect(Unit) { loadSkills() }

    LaunchedEffect(selectedSkill) {
        selectedSkill?.let { skill ->
            scope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val se = py.getModule("skills_engine")
                    val result = se.callAttr("view_skill", skill.name)
                    skillContent = (result as? com.chaquo.python.PyObject)?.toKMap()?.get("body")?.toString() ?: "无法加载"
                } catch (e: Exception) { skillContent = "加载失败: ${e.message}" }
            }
        }
    }

    if (showCreateDialog) {
        CreateSkillDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, desc, content, category ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val py = Python.getInstance()
                        val se = py.getModule("skills_engine")
                        se.callAttr("create_skill", name, desc, content, category)
                        val list = (se.callAttr("list_skills") as com.chaquo.python.PyObject).toKList()
                        skills = list.mapNotNull { item ->
                            val m = item as? Map<String, Any?> ?: return@mapNotNull null
                            SkillItem(m["name"]?.toString() ?: "", m["description"]?.toString() ?: "",
                                m["category"]?.toString(),
                                try { (m["tags"] as? List<*>)?.map { it.toString() } ?: emptyList() } catch (_: Exception) { emptyList() },
                                m["path"]?.toString() ?: "")
                        }
                    } catch (_: Exception) {}
                }
                showCreateDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Skills & MCP", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                IconButton(onClick = { if (selectedTab == 0) loadSkills() else loadMcpServers() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
                if (selectedTab == 0) {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "创建 Skill")
                    }
                }
            }
        )

        refreshMessage?.let {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Pill segmented control
        PillSegmentedControl(
            items = listOf("Skills (${skills.size})", "MCP (${mcpServers.size})"),
            selectedIndex = selectedTab,
            onSelected = { selectedTab = it; if (it == 1 && mcpServers.isEmpty()) loadMcpServers() }
        )

        // Search bar
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text(if (selectedTab == 0) "搜索 Skills..." else "搜索 MCP Servers...") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true, shape = RoundedCornerShape(12.dp)
        )

        // Content area with animation
        AnimatedContent(targetState = selectedTab, transitionSpec = {
            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
        }, label = "tab") { tab ->
            when (tab) {
                0 -> {
                    val filtered = if (searchQuery.isBlank()) skills
                    else skills.filter {
                        it.name.contains(searchQuery, true) || it.description.contains(searchQuery, true) ||
                        it.tags.any { tag -> tag.contains(searchQuery, true) }
                    }
                    if (isLoadingSkills) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("暂无 Skills", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("点击 + 创建新 Skill", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered) { skill ->
                                SkillCard(skill, isSelected = selectedSkill?.name == skill.name) {
                                    selectedSkill = if (selectedSkill?.name == skill.name) null else skill
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val filtered = if (searchQuery.isBlank()) mcpServers
                    else mcpServers.filter { it.name.contains(searchQuery, true) || it.url.contains(searchQuery, true) }
                    if (isLoadingMcp) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("暂无 MCP Servers", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("在设置中添加 MCP Server", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered) { server -> McpServerCard(server) }
                        }
                    }
                }
            }
        }
    }

    // Skill detail dialog
    selectedSkill?.let { skill ->
        if (skillContent.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { selectedSkill = null; skillContent = "" },
                title = { Text(skill.name) },
                text = {
                    Column {
                        Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        if (skill.tags.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                skill.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(tag, style = MaterialTheme.typography.labelSmall) }) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(skillContent, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { selectedSkill = null; skillContent = "" }) { Text("关闭") } }
            )
        }
    }
}

@Composable
fun PillSegmentedControl(items: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f).padding(4.dp).clickable { onSelected(index) }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SkillCard(skill: SkillItem, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(skill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (skill.category != null) {
                    Spacer(Modifier.width(8.dp))
                    SuggestionChip(onClick = {}, label = { Text(skill.category!!, style = MaterialTheme.typography.labelSmall) })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun McpServerCard(server: McpServerItem) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SettingsEthernet, null, tint = if (server.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (server.connected) "${server.toolsCount} tools" else "offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (server.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (server.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(server.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
        }
    }
}

@Composable
fun CreateSkillDialog(onDismiss: () -> Unit, onCreate: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建新 Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("描述") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("分类 (可选)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("内容 (Markdown)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank() && content.isNotBlank()) onCreate(name, description, content, category) }, enabled = name.isNotBlank() && content.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
