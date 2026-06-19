# Hermes Android — 验证闭环指南

> 对应修复文档第四章验收项。每项均为可实际 FAIL 的检查。

---

## 探针 1 — 模型目录是真是假

**操作**：
```bash
# 手动 curl 拉取真实模型列表（替换为你的实际配置）
curl -s -H "Authorization: Bearer $API_KEY" "$BASE_URL/v1/models" | python3 -m json.tool | grep '"id"'
```

**对比**：打开 APP → 设置 → 模型供应商 → 点"刷新模型"，观察显示的模型数量。

**通过条件**：APP 显示的模型列表与 curl 结果一致（同一批 ID），数量相同。

---

## 探针 2 — 对话链路是真是假

**操作**：配置好供应商后，在对话页发一条消息。同时开 logcat：
```bash
adb logcat -s "Python" | grep -E "openai|http|request|POST"
```

**通过条件**：logcat 或 agent_loop.py 的日志中可见真实 HTTP 请求发往配置的 base_url，包含正确 Authorization 头；返回后有真实 assistant 回复。

**错 key 验收**：填入错误 API Key，发消息，应在气泡中看到明确的鉴权失败提示，不转圈。

---

## 探针 3 — Skill 路径

**操作**：
```bash
adb shell
grep -ri operit /data/data/com.hermes.agent/files/.hermes/ 2>/dev/null || echo "operit_clean"
```

**通过条件**：输出 `operit_clean`，无任何 operit 路径残留。Skills 页显示从 `<filesDir>/.hermes/skills/` 加载。

---

## 探针 4 — Workflow 事件流

**操作**：打开 Workflow → 点启动 → 输入简单目标（如"计算 1+1"）→ 观察 Live 时间线。

**通过条件**：
- 点击后页面不崩溃
- 时间线实时出现步骤（思考中→执行→裁判评估→完成），而不是等待很久后一次性全部出现
- 状态栏随步骤实时更新
- 有错误时在时间线中显示错误卡片，不崩溃

**Wolfpack 额外验收**：切换 Wolfpack 模式，观察"拆分任务→子任务启动→子任务完成→汇总"各阶段依次出现。

---

## 探针 5 — MCP 服务器

**操作**：Skills 页切换到 MCP tab，点 + 添加一个真实 MCP 服务器（如本地运行的 mcp server）→ 点连接。

**通过条件**：
- 添加后卡片出现（名称、URL、初始工具数 0）
- 点连接后工具数更新为真实数量
- 连接状态显示绿色对勾

---

## 全局 UI 验收

| 项目 | 检查方式 | 通过条件 |
|------|---------|---------|
| 无 emoji 图标 | 视觉检查所有页面 | 所有图标均为 Material Icons 矢量图标 |
| 间距一致 | 检查卡片/列表间距 | 均为 4/8/16dp 倍数 |
| 错误可见 | 配错 key，刷新模型 | 显示红色 errorContainer 提示，含具体错误信息 |
| 空态完整 | 清空 Skills 目录，打开 Skills | 显示"暂无 Skills"和"点击 + 创建" |
| 加载态完整 | 慢速网络测试 | 加载中显示 CircularProgressIndicator |
| 无双层 TopBar | 观察每个页面顶部 | 每页只有一个 TopAppBar（无全局+局部重叠） |

---

## 崩溃日志位置

- **文件**：`<filesDir>/hermes_crash.log`（HermesApp.kt UncaughtExceptionHandler 写入）
- **logcat 标签**：`HermesCrash`
- **实时查看**：`adb logcat -s HermesCrash`

---

*生成时间：2026-06-19*
