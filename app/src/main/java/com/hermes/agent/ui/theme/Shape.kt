package com.hermes.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// 间距系统: 8dp 网格 (4/8/12/16/24/32)
// 圆角: 卡片 16dp / 气泡 12dp / 按钮 8dp
val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),       // 按钮
    medium = RoundedCornerShape(12.dp),     // 气泡
    large = RoundedCornerShape(16.dp),      // 卡片
    extraLarge = RoundedCornerShape(24.dp),
)