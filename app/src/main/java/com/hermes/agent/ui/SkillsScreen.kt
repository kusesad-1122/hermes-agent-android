package com.hermes.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.hermes.agent.data.ChaquopyBridge.toKMap
import com.hermes.agent.data.ChaquopyBridge.toKList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


data class McpServerItem(
    val name: String,
    val url: String,
    val connected: Boolean,
    val toolCount: Int,
)
data class SkillItem(
    val name: String,
    val description: String,
    val category: String?,
    val tags: List<String>,
    val path: String,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf(listOf<SkillItem>()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSkill by remember { mutableStateOf<SkillItem?>(null) }
    var skillContent by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Skills, 1=MCP
    var mcpServers by remember { mutableStateOf(listOf<McpServerItem>()) }
    var mcpLoading by remember { mutableStateOf(false) }
    var mcpError by remember { mutableStateOf<String?>(null) }
    var mcpBanner by remember { mutableStateOf<String?>(null) }
    var showAddMcpDialog by remember { mutableStateOf(false) }
    var mcpServerUrl by remember { mutableStateOf("") }
    var mcpServerName by remember { mutableStateOf("") }

    fun loadSkills() {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            error = null
            banner = null
            try {
                val py = Python.getInstance()
                val se = py.getModule("skills_engine")
                // Hermes uses ~/.hermes/skills/ — on Android, map to app internal hermes directory
                val skillsDir = "${context.filesDir.absolutePath}/.hermes/skills"
                se.callAttr("initialize", skillsDir)
                val list = (se.callAttr("list_skills") as com.chaquo.python.PyObject).toKList()
                skills = list.mapNotNull { item -> val m = item as? Map<String, Any?> ?: return@mapNotNull null; SkillItem(name = m["name"]?.toString() ?: "", description = m["description"]?.toString() ?: "", category = m["category"]?.toString(), tags = try { (m["tags"] as? List<*>)?.map { t -> t.toString() } ?: emptyList() } catch (_: Exception) { emptyList() }, path = m["path"]?.toString() ?: "") }
                banner = "已加载 ${skills.size} 个技能 (从 $skillsDir)"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            isLoading = false
        }
    }


    fun loadMcpServers() {
        scope.launch(Dispatchers.IO) {
            mcpLoading = true
            mcpError = null
            mcpBanner = null
            try {
                val py = Python.getInstance()
                val mcp = py.getModule("mcp_client")
                mcp.callAttr("initialize")
                val list = (mcp.callAttr("list_servers") as com.chaquo.python.PyObject).toKList()
                mcpServers = list.mapNotNull { item ->
                    val m = item as? Map<String, Any?> ?: return@mapNotNull null
                    McpServerItem(
                        name = m["name"]?.toString() ?: "",
                        url = m["url"]?.toString() ?: "",
                        connected = m["connected"] == true,
                        toolCount = try { (m["tools"] as? List<*>)?.size ?: 0 } catch (_: Exception) { 0 }
                    )
                }
                mcpBanner = "MCP: ${mcpServers.size} 个服务器"
            } catch (e: Exception) {
                mcpError = e.message ?: e.toString()
            }
            mcpLoading = false
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
                    skillContent = (result as? com.chaquo.python.PyObject)?.toKMap()?.get("body")?.toString() ?: "无法加载内容"
                } catch (e: Exception) {
                    skillContent = "加载失败: ${e.message}"
                }
            }
        }
    }

    val filtered = if (searchQuery.isBlank()) skills
    else skills.filter {
        it.name.contains(searchQuery, true) ||
        it.description.contains(searchQuery, true) ||
        it.tags.any { tag -> tag.contains(searchQuery, true) }
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
                        skills = list.mapNotNull { item -> val m = item as? Map<String, Any?> ?: return@mapNotNull null; SkillItem(name = m["name"]?.toString() ?: "", description = m["description"]?.toString() ?: "", category = m["category"]?.toString(), tags = try { (m["tags"] as? List<*>)?.map { t -> t.toString() } ?: emptyList() } catch (_: Exception) { emptyList() }, path = m["path"]?.toString() ?: "") }
                    } catch (_: Exception) {}
                }
                showCreateDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Skills", style = MaterialTheme.typography.headlineLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                IconButton(onClick = { loadSkills() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新 Skills")
                }
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "创建 Skill")
                }
            }
        )

        banner?.let {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(it, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        error?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(it, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // Tab switcher: Skills / MCP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("Skills") }
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1; loadMcpServers() },
                label = { Text("MCP") }
            )
        }

        if (selectedTab == 0) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索 Skills...") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无 Skills", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("点击 + 创建新 Skill", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { skill ->
                    SkillCard(skill, isSelected = selectedSkill?.name == skill.name) {
                        selectedSkill = if (selectedSkill?.name == skill.name) null else skill
                    }
                }
            }
        }
        } // end if selectedTab == 0

        if (selectedTab == 1) {
            mcpBanner?.let {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(it, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            mcpError?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(it, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (mcpLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (mcpServers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无 MCP 服务器", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("点击 + 添加 MCP 服务器", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(mcpServers) { server ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    if (server.connected) {
                                        Icon(Icons.Default.Check, contentDescription = "已连接", tint = Color(0xFF059669), modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text("工具数: ${server.toolCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } // end if selectedTab == 1
    }

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
                                skill.tags.forEach { tag ->
                                    SuggestionChip(onClick = {}, label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(skillContent, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedSkill = null; skillContent = "" }) { Text("关闭") }
                }
            )
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
        Column(modifier = Modifier.padding(16.dp)) {
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
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank() && content.isNotBlank()) onCreate(name, description, content, category) }, enabled = name.isNotBlank() && content.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}