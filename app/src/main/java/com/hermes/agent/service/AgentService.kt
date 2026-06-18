package com.hermes.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.hermes.agent.MainActivity
import kotlinx.coroutines.*

/**
 * AgentService — 前台服务，承载 Hermes Python Agent 常驻运行。
 *
 * 功能：
 * 1. 前台通知常驻，防止系统杀死 Agent 进程
 * 2. WakeLock 保持 CPU 唤醒（可选，仅在有活跃任务时）
 * 3. Python 初始化和健康检查
 * 4. 提供 Binder 接口供 Activity 通信
 * 5. 通知栏点击回到主界面
 * 6. 状态回调：初始化完成、Agent 运行中、错误等
 */
class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "hermes_agent"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.hermes.agent.STOP_SERVICE"
        const val ACTION_STATUS = "com.hermes.agent.STATUS"

        // Service state
        const val STATE_IDLE = "idle"
        const val STATE_INITIALIZING = "initializing"
        const val STATE_READY = "ready"
        const val STATE_RUNNING = "running"
        const val STATE_ERROR = "error"
    }

    // Binder for Activity communication
    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private val binder = AgentBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State
    private var currentState = STATE_IDLE
    private var pythonInitialized = false
    private var agentLoopModule: com.chaquo.python.PyObject? = null
    private var memoryModule: com.chaquo.python.PyObject? = null
    private var lastError: String? = null

    // Callbacks for UI
    var onStateChanged: ((String) -> Unit)? = null
    var onNotificationUpdate: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Hermes Agent 启动中..."))
        acquireWakeLock()
        updateState(STATE_INITIALIZING)

        // Initialize Python asynchronously
        serviceScope.launch {
            initializePython()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STATUS -> {
                // Just return current status
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Python Initialization ────────────────────────────────────────────

    private fun initializePython() {
        try {
            updateNotification("正在初始化 Python 环境...")

            val py = Python.getInstance()

            // Initialize agent loop
            agentLoopModule = py.getModule("agent_loop")
            agentLoopModule?.callAttr(
                "configure",
                "https://api.deepseek.com/v1",
                "sk-06a0fc1cc4f94aebb808b0286c8fc2f9",
                "deepseek-chat",
                10,
                4096,
                0.7
            )

            // Initialize memory system
            memoryModule = py.getModule("memory_system")
            val dbPath = getDatabasePath("hermes_memory.db").absolutePath
            val initResult = memoryModule?.callAttr("initialize", dbPath)
            val resultDict = initResult?.asMap()

            val sessions = resultDict?.get("sessions")?.toString()?.toIntOrNull() ?: 0
            val messages = resultDict?.get("messages")?.toString()?.toIntOrNull() ?: 0

            pythonInitialized = true
            updateState(STATE_READY)
            updateNotification("Agent 就绪 | $sessions 会话 | $messages 消息")

        } catch (e: Exception) {
            lastError = e.message
            updateState(STATE_ERROR)
            updateNotification("初始化失败: ${e.message}")
        }
    }

    // ── Public API for Activity ──────────────────────────────────────────

    fun isReady(): Boolean = currentState == STATE_READY || currentState == STATE_RUNNING

    fun getState(): String = currentState

    fun getLastError(): String? = lastError

    fun getAgentLoop(): com.chaquo.python.PyObject? = agentLoopModule

    fun getMemory(): com.chaquo.python.PyObject? = memoryModule

    fun sendMessage(message: String, callback: (Map<Any?, Any?>) -> Unit) {
        if (!isReady()) {
            callback(mapOf("error" to "Agent not ready: $currentState"))
            return
        }

        serviceScope.launch {
            try {
                updateState(STATE_RUNNING)
                updateNotification("Agent 运行中...")

                val result = agentLoopModule?.callAttr("run_agent", message)
                val resultMap = result?.asMap() ?: mapOf("error" to "No result")

                updateState(STATE_READY)

                withContext(Dispatchers.Main) {
                    callback(resultMap)
                }

                // Update notification with stats
                val iterations = resultMap["iterations"]?.toString()?.toIntOrNull() ?: 0
                val tokens = resultMap["total_tokens"]?.toString()?.toIntOrNull() ?: 0
                val latency = resultMap["latency_ms"]?.toString()?.toIntOrNull() ?: 0
                updateNotification("Agent 就绪 | ${iterations}轮 | ${tokens}tokens | ${latency}ms")

            } catch (e: Exception) {
                lastError = e.message
                updateState(STATE_ERROR)
                updateNotification("错误: ${e.message}")

                withContext(Dispatchers.Main) {
                    callback(mapOf("error" to e.message))
                }
            }
        }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent 运行状态"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        onNotificationUpdate?.invoke(text)
    }

    // ── State Management ─────────────────────────────────────────────────

    private fun updateState(newState: String) {
        currentState = newState
        onStateChanged?.invoke(newState)
    }

    // ── WakeLock ─────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "hermes:agent_service"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
}