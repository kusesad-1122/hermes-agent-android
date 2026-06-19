# 取证核查报告 — 01-forensics.md

## 探针 1: 模型目录是真是假
**结论: 部分桩**
- ProviderScreen.kt 已接真实 provider_test.py（httpx GET /v1/models），不再是假实现
- 但 AgentService.kt 仍有硬编码 deepseek key/model（line 78-79）
- SettingsManager.kt 默认模型硬编码为 deepseek-chat（line 50, 100, 203）
- ProviderConfig.kt BuiltinProviders 仍是写死的模板列表（非运行时拉取）
- 接真接入点: 删除 AgentService 硬编码，改为从 ProviderStorage 读取；SettingsManager 默认模型改为空或从活跃 provider 获取

## 探针 2: 对话链路是真是假
**结论: 真接（git 仓库版本）**
- git 仓库的 agent_loop.py 已用真实 openai SDK（import openai, openai.OpenAI client）
- 但 Android 存储工作副本的 agent_loop.py 仍是旧 httpx 版本（两份不同步）
- ChatScreen.kt 已从 ProviderStorage 读取（上一轮修复），但 AgentService.kt 仍硬编码
- WorkflowScreen.kt 的 goal/wolfpack 启动也硬编码 baseUrl/apiKey
- 接真接入点: 同步 agent_loop.py 到工作副本；清除 AgentService 和 WorkflowScreen 的硬编码

## 探针 3: Skill 来源是真是假
**结论: 幻觉路径（operit 残留）**
- SkillsScreen.kt line: val operitSkills = "/storage/emulated/0/Download/Operit/skills"
- operit 与 Hermes 毫无关系，是幻觉残留
- Hermes 真实路径: ~/.hermes/skills/（由 hermes_constants.py get_hermes_home() 定义）
- Android 上应映射到 app 内部存储或外部存储的 hermes 子目录
- Hermes 源码有大量真实 SKILL.md 文件（skills/ 和 optional-skills/ 目录）
- 接真接入点: 删除 operit 路径，改为 app filesDir/hermes/skills/；可考虑打包 Hermes 真实 skills

## 探针 4: Workflow 事件流是真是假
**结论: 桩**
- WorkflowScreen.kt 的 liveSteps 是 mutableStateListOf<WorkflowStep>()（内存假数据）
- 启动任务时直接调 goal_loop/wolfpack Python 模块，但硬编码 baseUrl/apiKey
- 没有订阅 agent loop 的真实事件流/trajectory
- History tab 是纯空态占位
- 接真接入点: 让 agent_loop.py 发出真实事件（通过 callback 或 stream），WorkflowScreen 订阅

## 探针 5: MCP 是真是假
**结论: 缺失**
- Kotlin 侧完全没有 MCP 相关代码（grep mcp/MCP 在 java/ 目录下无结果）
- Python 侧有 mcp_client.py（自写简化版），但未接入任何 UI
- Hermes 源码有完整 MCP 客户端（tools/mcp_tool.py，支持 stdio/HTTP/SSE 传输）
- 接真接入点: 在 SkillsScreen 或独立 MCP 页接入 mcp_client.py；或嵌入 Hermes 真实 mcp_tool.py
