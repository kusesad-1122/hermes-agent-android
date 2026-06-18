package com.hermes.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class SkillItem(
    val name: String,
    val description: String,
    val category: String?,
    val tags: List<String>,
    val path: String,
)

@Suppress("UNCHECKED_CAST")
private fun parseSkillItem(raw: Any): SkillItem {
    val py = raw as com.chaquo.python.PyObject
    val m = py.asMap() as Map<String, Any?>
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
    var skills by remember { mutableStateOf(listOf<SkillItem>()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSkill by remember { mutableStateOf<SkillItem?>(null) }
    var skillContent by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }

    fun loadSkills() {
        scope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val se = py.getModule("skills_engine")
                se.callAttr("initialize")
                val list = se.callAttr("list_skills").asList()
                skills = list.map { parseSkillItem(it) }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadSkills() }

    LaunchedEffect(selectedSkill) {
        selectedSkill?.let { skill ->
            scope.launch(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val se = py.getModule("skills_engine")
                    val result = (se.callAttr("view_skill", skill.name) as? com.chaquo.python.PyObject)
                    skillContent = result?.asMap()?.get("body")?.toString() ?: "无法加载内容"
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
                        val list = se.callAttr("list_skills").asList()
                        skills = list.map { parseSkillItem(it) }
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
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "创建 Skill")
                }
            }
        )

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