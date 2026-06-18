# Stage 0: 归因核查 — 结论

> 产出日期: 2026-06-18
> 结论: **当前产物是"自写的简化代理壳"，不是"Hermes 核心移植"。**

---

## 逐项归因

### 1. 对话发送 → 模型响应（主链路）

| 维度 | 判定 | 证据 |
|:-----|:-----|:-----|
| Agent Loop | **【空壳/自造】** | `agent_loop.py` 第1行 docstring 明确写着: "This **replaces** the full run_agent.py dependency chain (which requires openai SDK → jiter Rust extension). Uses httpx directly" |
| 供应商接入 | **【空壳/自造】** | 自己用 httpx 发 POST 到 `/chat/completions`，不经过 Hermes 的 `providers/` 体系。供应商只有 configure(base_url, api_key, model) 三个参数的扁平配置 |
| 工具调用 | **【空壳/自造】** | 自定义 4 个硬编码 tool schema（get_device_info, read_file, execute_shell, search_web），不复用 Hermes 的 tool registry |
| 流式输出 | **【空壳/自造】** | 自己解析 SSE `data: ` 行，不复用 Hermes 的 streaming adapter |

**改为复用 Hermes 的接入点：**
- `agent/agent_init.py` → Hermes 主初始化
- `agent/conversation_loop.py` → Hermes 对话主循环
- `providers/base.py` → Hermes 供应商抽象基类
- `agent/chat_completion_helpers.py` → Hermes 请求发送/流式解析
- `tools/*.py` → Hermes 工具注册表

### 2. 记忆系统（memory）

| 维度 | 判定 | 证据 |
|:-----|:-----|:-----|
| Session 管理 | **【空壳/自造】** | `memory_system.py` 第2行: "Simplified from the full hermes_state.py (4805 lines) with ~300 lines" |
| FTS5 搜索 | **【部分实现】** | 有 FTS5 virtual table 创建，但不复用 Hermes 的 `hermes_state.py` SessionDB 类 |
| KV 记忆 | **【空壳/自造】** | 自定义 memory_snapshots 表，不复用 Hermes MemoryManager + MemoryProvider 体系 |
| 对话召回 | **【缺失】** | 无 recall_for_context() 机制，不把历史对话注入 agent context |
| 摘要 | **【缺失】** | 无 conversation_compression / summary 功能 |

**改为复用 Hermes 的接入点：**
- `hermes_state.py` → 4805 行的 SessionDB，应作为记忆层的底座
- `agent/memory_manager.py` → 记忆编排层
- `agent/memory_provider.py` → 可插拔记忆提供者接口
- `agent/conversation_compression.py` → 对话压缩/摘要

### 3. Skills 引擎

| 维度 | 判定 | 证据 |
|:-----|:-----|:-----|
| Skill 发现/加载 | **【空壳/自造】** | `skills_engine.py` 第2行: "Simplified from tools/skills_tool.py 1638 lines + agent/skill_commands.py 612 lines + tools/skills_hub.py 3888 lines into ~500 lines" |
| YAML 解析 | **【自造】** | 纯手写 YAML parser（无 PyYAML），仅支持简单键值对 |
| Skill 激活 | **【部分实现】** | 有 activate_skill() 但不注入 system prompt，也不连接 agent loop |
| Skill Hub | **【缺失】** | 不复用 tools/skills_hub.py（3888 行的在线 Skill Hub） |
| 自创建/自改进 | **【缺失】** | 有 create_skill() 但不连接 agent 的自我改进逻辑 |

**改为复用 Hermes 的接入点：**
- `tools/skills_tool.py` → 1638 行的完整 skill tool
- `agent/skill_commands.py` → 612 行的 slash command 处理
- `tools/skills_hub.py` → 3888 行的 Hub 机制

### 4. MCP 客户端

| 维度 | 判定 | 证据 |
|:-----|:-----|:-----|
| MCP 连接 | **【空壳/自造】** | `mcp_client.py` 第2行: "Simplified from tools/mcp_tool.py 4156 lines into ~400 lines" |
| 传输 | **【部分实现】** | 仅支持 HTTP Streamable，不支持 stdio（Android 限制合理） |
| Server 发现 | **【空壳/自造】** | 自定义配置格式，不复用 Hermes 的 mcp_config / mcp_startup |
| 安全 | **【缺失】** | 不复用 mcp_security.py 的安全检查 |

**改为复用 Hermes 的接入点：**
- `tools/mcp_tool.py` → 4156 行的完整 MCP 客户端（需适配 HTTP-only）

### 5. Cron 调度

| 维度 | 判定 | 证据 |
|:-----|:-----|:-----|
| Cron 解析 | **【空壳/自造】** | `cron_system.py` 第2行: "Simplified from cron/jobs.py 1304 lines + cron/scheduler.py 2213 lines into ~450 lines" |
| 持久化 | **【自造】** | 自定义 SQLite 表，不复用 Hermes 的 jobs.json 格式 |
| 调度器 | **【空壳/自造】** | Tick-based，不复用 Hermes scheduler |

**改为复用 Hermes 的接入点：**
- `cron/jobs.py` → 1304 行 job CRUD
- `cron/scheduler.py` → 2213 行调度器

### 6. 模型切换

| 维度 | 判定 | 证据 |
|:-----|:-----|:-----|
| `hermes model` | **【缺失】** | 无模型切换 UI 或命令，configure() 硬编码在 AgentService.onCreate() |
| 供应商管理 | **【缺失】** | 无可视化供应商配置页（仅有 ProviderScreen.kt 的空壳 UI） |
| Keystore 加密 | **【空壳/自造】** | SettingsManager.kt 有 EncryptedSharedPreferences，但 key 实际写死在 AgentService 里 |

### 7. UI 层

| 维度 | 判定 | 证据 |
|:-----|:-----|:-----|
| ChatScreen | **【自造，但合理】** | Compose UI 本身是新写的，这没问题——关键问题在底层 |
| 对话持久化 | **【缺失】** | messages 只存 mutableStateListOf，退出即丢 |
| 模型/供应商 UI | **【空壳】** | ProviderScreen.kt 存在但不连接真实数据 |
| 错误显示 | **【缺失】** | 错误吞在 catch 里，UI 无限转圈 |

---

## 总结矩阵

| 模块 | 判定 | 功能性 | 根因 |
|:-----|:-----|:-------|:-----|
| Agent Loop (主链路) | 🔴 空壳/自造 | 基本能对话 | 自写 httpx 代替 Hermes conversation_loop |
| 供应商系统 | 🔴 空壳/自造 | 可用但不完整 | 不复用 providers/ 体系 |
| Memory | 🔴 空壳/自造 | 表结构在但不连通 | 不复用 hermes_state.py |
| Skills | 🔴 空壳/自造 | UI 显示但不注入 | 不复用 skills_tool.py |
| MCP | 🔴 空壳/自造 | 基础 HTTP 连接 | 不复用 mcp_tool.py |
| Cron | 🔴 空壳/自造 | 解析 + 持久化有 | 不复用 cron/ 体系 |
| 模型切换 | 🔴 缺失 | 完全没有 | 未实现 |
| 对话持久化 | 🔴 缺失 | 完全没有 | 未实现 |
| Root Gateway | 🟢 自造但合理 | 完整实现 | Hermes 无此功能，Android 原生合理 |
| Voice I/O | 🟢 自造但合理 | 完整实现 | Hermes 无此功能，Android 原生合理 |
| ChaquopyBridge | 🟢 自造但合理 | 类型转换层 | 无对应 Hermes 模块 |

---

## 根因分析

**为什么全部是空壳？** 因为移植 v3 prompt 里写的是"按功能对标重写"，而不是"接入 Hermes 源码"。

Hermes 的核心模块（conversation_loop / providers / memory_manager / skills_tool / mcp_tool）都有深层依赖链：
- conversation_loop → providers → chat_completion_helpers → openai SDK → **jiter (Rust)** ← Android 无法编译
- memory_manager → hermes_state → 无 Android 适配
- skills_tool → skills_hub → 需要网络下载 → 无离线支持

**所以之前选择了绕过**：写 300 行简化版代替 4800 行原版，结果就是功能集体缺失。

---

## 修复主线

**后续修复的主线不是"补功能"，而是"把每条链路换成真正的 Hermes 模块"。**

### 可行的接入路径

**方案 A：全量移植 Hermes 核心（理想但高风险）**
- 把 `agent/`、`providers/`、`tools/`、`cron/` 全部嵌入
- 解决 jiter 依赖：替换 openai SDK 为 httpx（保持 Hermes 内部接口不变）
- 工作量：~2-3 周，需要大量适配

**方案 B：薄壳 + 关键链路替换（推荐，渐进式）**
- 保留 Android 特有层（ChaquopyBridge、VoiceHelper、RootGateway）
- 逐个替换核心链路：
  1. **P0-1**：agent_loop → 接入 Hermes conversation_loop（绕过 jiter）
  2. **P0-2**：memory_system → 接入 hermes_state.py 的 SessionDB
  3. **P1-1**：供应商 → 接入 providers/ 体系
  4. **P1-2**：skills → 接入 skills_tool.py
  5. **P1-3**：mcp → 接入 mcp_tool.py
  6. **P1-4**：对话持久化（自造，Hermes 原版桌面端有类似需求）

**方案 C：Hermes 上游 PR（最彻底）**
- 给 Hermes 上游提 PR，把 jiter/openai 依赖改为可选 httpx 后端
- 然后直接 import 整个 Hermes 包
- 工作量最小但依赖上游接受

### 建议路径

**执行方案 B，阶段化推进。** 理由：
- 方案 A 太重，可能要 2-3 周不保证成功
- 方案 C 依赖上游，不可控
- 方案 B 可以每个阶段独立验证，失败代价低