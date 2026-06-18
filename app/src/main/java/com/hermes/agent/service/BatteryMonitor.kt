package com.hermes.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * BatteryMonitor — 监听电量变化，动态调整 Agent 行为。
 *
 * 行为策略：
 *   > 50%  → 正常模式（全速运行）
 *   20-50% → 低功耗模式（减少 cron 频率，暂停非关键任务）
 *   < 20%  → 省电模式（暂停所有非紧急任务，仅保留用户主动交互）
 *
 * 使用：在 AgentService.onCreate() 注册，onDestroy() 取消注册。
 */
class BatteryMonitor(private val context: Context) {

    enum class PowerMode {
        NORMAL,      // > 50%
        LOW_POWER,   // 20-50%
        SAVER        // < 20%
    }

    private var currentLevel = 100
    private var isCharging = false
    private var currentMode = PowerMode.NORMAL

    var onModeChanged: ((PowerMode, Int) -> Unit)? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return

            currentLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale > 0) {
                currentLevel = (currentLevel * 100) / scale
            }

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

            val newMode = when {
                isCharging || currentLevel > 50 -> PowerMode.NORMAL
                currentLevel > 20 -> PowerMode.LOW_POWER
                else -> PowerMode.SAVER
            }

            if (newMode != currentMode) {
                val oldMode = currentMode
                currentMode = newMode
                onModeChanged?.invoke(newMode, currentLevel)
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
    }

    fun stop() {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    fun getLevel(): Int = currentLevel
    fun isCharging(): Boolean = isCharging
    fun getMode(): PowerMode = currentMode

    /**
     * Cron 频率倍数：省电模式下降低任务频率。
     * @return 乘以此倍数得到实际执行间隔（秒）
     */
    fun getCronMultiplier(): Float = when (currentMode) {
        PowerMode.NORMAL -> 1.0f
        PowerMode.LOW_POWER -> 2.0f
        PowerMode.SAVER -> 5.0f
    }

    /** 是否允许非关键任务执行 */
    fun allowNonCritical(): Boolean = currentMode != PowerMode.SAVER

    /** 是否允许后台 Cron 任务 */
    fun allowCron(): Boolean = currentMode != PowerMode.SAVER

    fun getModeDescription(): String = when (currentMode) {
        PowerMode.NORMAL -> "正常 (${currentLevel}%)"
        PowerMode.LOW_POWER -> "低功耗 (${currentLevel}%)"
        PowerMode.SAVER -> "省电模式 (${currentLevel}%)"
    }
}