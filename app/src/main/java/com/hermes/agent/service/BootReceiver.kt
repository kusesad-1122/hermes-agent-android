package com.hermes.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver — 开机自启动 AgentService。
 *
 * 注册：在 AndroidManifest.xml 中添加：
 *   <receiver android:name=".service.BootReceiver"
 *             android:enabled="true"
 *             android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.BOOT_COMPLETED" />
 *           <action android:name="android.intent.action.QUICKBOOT_POWERON" />
 *       </intent-filter>
 *   </receiver>
 *
 * 需要权限：<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 * 注意：在 SettingsManager 中有 auto_start 开关。
 *       如果用户关闭了自动启动，此 Receiver 不应启动服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        // Check if auto-start is enabled in settings
        try {
            val prefs = context.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_service", false)

            if (!autoStart) {
                return
            }
        } catch (e: Exception) {
            // If we can't read settings, default to not starting
            return
        }

        // Start the AgentService
        try {
            val serviceIntent = Intent(context, AgentService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Some devices restrict background service starts
            // This is expected on Android 14+ with FGS restrictions
        }
    }
}