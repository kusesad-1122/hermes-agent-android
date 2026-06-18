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

class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "hermes_agent"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.hermes.agent.STOP_SERVICE"
        const val STATE_IDLE = "idle"
        const val STATE_INITIALIZING = "initializing"
        const val STATE_READY = "ready"
        const val STATE_RUNNING = "running"
        const val STATE_ERROR = "error"
    }

    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private val binder = AgentBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentState = STATE_IDLE
    private var pythonInitialized = false
    private var agentLoopModule: com.chaquo.python.PyObject? = null
    private var memoryModule: com.chaquo.python.PyObject? = null
    private var lastError: String? = null
    var onStateChanged: ((String) -> Unit)? = null
    var onNotificationUpdate: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Hermes Agent 启动中..."))
        acquireWakeLock()
        updateState(STATE_INITIALIZING)
        serviceScope.launch { initializePython() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    @Suppress("UNCHECKED_CAST")
    private fun initializePython() {
        try {
            updateNotification("正在初始化 Python 环境...")
            val py = Python.getInstance()

            agentLoopModule = py.getModule("agent_loop")
            agentLoopModule?.callAttr("configure", "https://api.deepseek.com/v1",
                "sk-06a0fc1cc4f94aebb808b0286c8fc2f9", "deepseek-chat", 10, 4096, 0.7)

            memoryModule = py.getModule("memory_system")
            val dbPath = getDatabasePath("hermes_memory.db").absolutePath
            val initResult = memoryModule?.callAttr("initialize", dbPath)
            val resultDict: Map<String, Any?>? = if (initResult is com.chaquo.python.PyObject) initResult.asMap() as? Map<String, Any?> else null

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

    fun isReady(): Boolean = currentState == STATE_READY || currentState == STATE_RUNNING
    fun getState(): String = currentState
    fun getLastError(): String? = lastError
    fun getAgentLoop(): com.chaquo.python.PyObject? = agentLoopModule
    fun getMemory(): com.chaquo.python.PyObject? = memoryModule

    @Suppress("UNCHECKED_CAST")
    fun sendMessage(message: String, callback: (Map<Any?, Any?>) -> Unit) {
        if (!isReady()) { callback(mapOf<Any?, Any?>("error" to "Agent not ready: $currentState")); return }

        serviceScope.launch {
            try {
                updateState(STATE_RUNNING)
                updateNotification("Agent 运行中...")
                val result = agentLoopModule?.callAttr("run_agent", message)
                val resultMap: Map<Any?, Any?> = if (result is com.chaquo.python.PyObject) result.asMap() else mapOf<Any?, Any?>("error" to "No result")
                updateState(STATE_READY)
                withContext(Dispatchers.Main) { callback(resultMap) }
                val iterations = (resultMap as? Map<String, Any?>)?.get("iterations")?.toString()?.toIntOrNull() ?: 0
                val tokens = (resultMap as? Map<String, Any?>)?.get("total_tokens")?.toString()?.toIntOrNull() ?: 0
                val latency = (resultMap as? Map<String, Any?>)?.get("latency_ms")?.toString()?.toIntOrNull() ?: 0
                updateNotification("Agent 就绪 | ${iterations}轮 | ${tokens}tokens | ${latency}ms")
            } catch (e: Exception) {
                lastError = e.message
                updateState(STATE_ERROR)
                updateNotification("错误: ${e.message}")
                withContext(Dispatchers.Main) { callback(mapOf<Any?, Any?>("error" to e.message)) }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Hermes Agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Agent 运行状态"; setShowBadge(false); enableVibration(false); enableLights(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Agent").setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent).setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        onNotificationUpdate?.invoke(text)
    }

    private fun updateState(newState: String) { currentState = newState; onStateChanged?.invoke(newState) }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:agent_service").apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
}