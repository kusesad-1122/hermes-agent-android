package com.hermes.agent.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * ChatStore — 对话持久化存储。
 *
 * 解决 P0-2：退出对话页再进入，历史对话丢失。
 * 使用 SQLite 存储会话和消息，杀进程后恢复。
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "hermes_chat.db"
        private const val DB_VERSION = 1

        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_MESSAGES = "messages"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SESSIONS (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                message_count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_name TEXT DEFAULT '',
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES $TABLE_SESSIONS(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_messages_session ON $TABLE_MESSAGES(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        onCreate(db)
    }

    // ── Sessions ──────────────────────────────────────────────────────────

    fun createSession(title: String = ""): String {
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
            put("message_count", 0)
        }
        writableDatabase.insert(TABLE_SESSIONS, null, cv)
        return id
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        val cv = ContentValues().apply {
            put("title", title)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_SESSIONS, cv, "id = ?", arrayOf(sessionId))
    }

    fun deleteSession(sessionId: String) {
        writableDatabase.delete(TABLE_MESSAGES, "session_id = ?", arrayOf(sessionId))
        writableDatabase.delete(TABLE_SESSIONS, "id = ?", arrayOf(sessionId))
    }

    fun getSessions(): List<SessionRow> {
        val rows = mutableListOf<SessionRow>()
        val c = readableDatabase.query(
            TABLE_SESSIONS, null, null, null, null, null, "updated_at DESC"
        )
        c.use {
            while (it.moveToNext()) {
                rows.add(SessionRow(
                    id = it.getString(it.getColumnIndexOrThrow("id")),
                    title = it.getString(it.getColumnIndexOrThrow("title")),
                    createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                    updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at")),
                    messageCount = it.getInt(it.getColumnIndexOrThrow("message_count"))
                ))
            }
        }
        return rows
    }

    // ── Messages ──────────────────────────────────────────────────────────

    fun addMessage(sessionId: String, role: String, content: String, toolName: String = ""): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("content", content)
            put("tool_name", toolName)
            put("timestamp", now)
        }
        val id = writableDatabase.insert(TABLE_MESSAGES, null, cv)

        // Update session count + timestamp
        val cv2 = ContentValues().apply {
            put("updated_at", now)
        }
        writableDatabase.execSQL(
            "UPDATE $TABLE_SESSIONS SET message_count = message_count + 1, updated_at = ? WHERE id = ?",
            arrayOf(now, sessionId)
        )

        // Auto-title from first user message
        if (role == "user") {
            val c = readableDatabase.rawQuery(
                "SELECT message_count FROM $TABLE_SESSIONS WHERE id = ?", arrayOf(sessionId)
            )
            c.use {
                if (it.moveToFirst() && it.getInt(0) <= 1) {
                    updateSessionTitle(sessionId, content.take(50))
                }
            }
        }

        return id
    }

    fun getMessages(sessionId: String, limit: Int = 200): List<MessageRow> {
        val rows = mutableListOf<MessageRow>()
        val c = readableDatabase.query(
            TABLE_MESSAGES, null, "session_id = ?", arrayOf(sessionId),
            null, null, "timestamp ASC", limit.toString()
        )
        c.use {
            while (it.moveToNext()) {
                rows.add(MessageRow(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    sessionId = it.getString(it.getColumnIndexOrThrow("session_id")),
                    role = it.getString(it.getColumnIndexOrThrow("role")),
                    content = it.getString(it.getColumnIndexOrThrow("content")),
                    toolName = it.getString(it.getColumnIndexOrThrow("tool_name")) ?: "",
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                ))
            }
        }
        return rows
    }

    fun getMessageCount(sessionId: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE session_id = ?", arrayOf(sessionId)
        )
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }
}

data class SessionRow(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

data class MessageRow(
    val id: Long,
    val sessionId: String,
    val role: String,
    val content: String,
    val toolName: String,
    val timestamp: Long
)
