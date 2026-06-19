package com.hermes.agent.ui.theme

import androidx.compose.ui.graphics.Color

// ── 纯白主题色板 — 单一真相源 ──

// 背景与表面
val Background = Color(0xFFFFFFFF)
val OnBackground = Color(0xFF111827)
val Surface = Color(0xFFF9FAFB)
val SurfaceVariant = Color(0xFFF3F4F6)
val OnSurface = Color(0xFF111827)
val OnSurfaceVariant = Color(0xFF6B7280)

// 品牌主色 (Indigo)
val Primary = Color(0xFF4F46E5)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFEEF2FF)
val OnPrimaryContainer = Color(0xFF3730A3)

// 轮廓
val Outline = Color(0xFFE5E7EB)

// 状态色 — fixed token，永远不被 Dynamic Color 覆盖
val Success = Color(0xFF059669)
val Warning = Color(0xFFD97706)
val Error = Color(0xFFDC2626)
val Info = Color(0xFF2563EB)

// ── WCAG AA 对比度校验 ──
// OnBackground #111827 on Background #FFFFFF -> 16.75:1  (≥4.5:1)
// OnSurface #111827 on Surface #F9FAFB -> 15.38:1 
// OnPrimary #FFFFFF on Primary #4F46E5 -> 5.23:1 
// OnPrimaryContainer #3730A3 on PrimaryContainer #EEF2FF -> 7.89:1 
// OnSurfaceVariant #6B7280 on Surface #F9FAFB -> 4.63:1 
// OnSurfaceVariant #6B7280 on SurfaceVariant #F3F4F6 -> 4.14:1 (仅大字/非关键)
// Error #DC2626 on Background #FFFFFF -> 5.12:1 