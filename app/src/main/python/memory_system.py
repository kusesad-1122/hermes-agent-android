"""
Hermes Android Memory System — Simplified SQLite + FTS5

MVP version of hermes_state.py for Android. Provides:
- Session management (create/end/list)
- Message storage with FTS5 full-text search
- Conversation recall for agent context
- Memory snapshots (key-value persistent memory)

This replaces the full hermes_state.py (4805 lines) with ~300 lines
focused on Android MVP needs. The full system can be integrated later.
"""

import json
import os
import sqlite3
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# ── Database path ──────────────────────────────────────────────────────────
# On Android, use app's internal storage (passed from Kotlin)
_db_path: Optional[str] = None
_conn: Optional[sqlite3.Connection] = None
_lock = threading.Lock()
_initialized = False


def initialize(db_path: str = None) -> dict:
    """Initialize the memory database. Called from Kotlin on app startup.
    
    Args:
        db_path: Path to SQLite database file. If None, uses default.
    
    Returns:
        dict with status info
    """
    global _db_path, _conn, _initialized
    
    if _initialized and _conn:
        return {"status": "already_initialized", "path": _db_path}
    
    _db_path = db_path or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_memory.db"
    )
    
    # Ensure directory exists
    Path(_db_path).parent.mkdir(parents=True, exist_ok=True)
    
    with _lock:
        _conn = sqlite3.connect(
            _db_path,
            check_same_thread=False,
            timeout=5.0,
            isolation_level=None,
        )
        _conn.row_factory = sqlite3.Row
        _conn.execute("PRAGMA journal_mode=WAL")
        _conn.execute("PRAGMA foreign_keys=ON")
        _init_schema()
        _initialized = True
    
    # Count existing data
    stats = get_stats()
    return {
        "status": "initialized",
        "path": _db_path,
        "sessions": stats.get("sessions", 0),
        "messages": stats.get("messages", 0),
    }


def _init_schema():
    """Create tables if they don't exist."""
    global _conn
    
    _conn.executescript("""
        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            source TEXT NOT NULL DEFAULT 'android',
            title TEXT,
            started_at REAL NOT NULL,
            ended_at REAL,
            message_count INTEGER DEFAULT 0,
            metadata TEXT
        );
        
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL REFERENCES sessions(id),
            role TEXT NOT NULL,
            content TEXT,
            tool_name TEXT,
            tool_call_id TEXT,
            tool_calls TEXT,
            timestamp REAL NOT NULL,
            token_count INTEGER,
            metadata TEXT
        );
        
        CREATE INDEX IF NOT EXISTS idx_messages_session 
            ON messages(session_id, timestamp);
        CREATE INDEX IF NOT EXISTS idx_sessions_started 
            ON sessions(started_at DESC);
        
        CREATE TABLE IF NOT EXISTS memory_kv (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            category TEXT DEFAULT 'general',
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL
        );
    """)
    
    # FTS5 virtual table (may fail if FTS5 not compiled in)
    try:
        _conn.executescript("""
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                content,
                content=messages,
                content_rowid=id
            );
            
            CREATE TRIGGER IF NOT EXISTS messages_fts_insert AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(rowid, content) VALUES (
                    new.id,
                    COALESCE(new.content, '') || ' ' || COALESCE(new.tool_name, '')
                );
            END;
            
            CREATE TRIGGER IF NOT EXISTS messages_fts_delete AFTER DELETE ON messages BEGIN
                DELETE FROM messages_fts WHERE rowid = old.id;
            END;
            
            CREATE TRIGGER IF NOT EXISTS messages_fts_update AFTER UPDATE ON messages BEGIN
                DELETE FROM messages_fts WHERE rowid = old.id;
                INSERT INTO messages_fts(rowid, content) VALUES (
                    new.id,
                    COALESCE(new.content, '') || ' ' || COALESCE(new.tool_name, '')
                );
            END;
        """)
    except sqlite3.OperationalError as e:
        if "fts5" in str(e).lower():
            # FTS5 not available, create fallback trigram table
            try:
                _conn.executescript("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                        content,
                        tokenize='trigram'
                    );
                """)
            except:
                pass  # No FTS at all, search will use LIKE fallback


# ── Session management ─────────────────────────────────────────────────────

def create_session(title: str = None, source: str = "android") -> str:
    """Create a new conversation session. Returns session_id."""
    session_id = str(uuid.uuid4())[:8]
    now = time.time()
    
    with _lock:
        _conn.execute(
            "INSERT INTO sessions (id, source, title, started_at) VALUES (?, ?, ?, ?)",
            (session_id, source, title, now),
        )
    
    return session_id


def end_session(session_id: str) -> None:
    """Mark a session as ended."""
    with _lock:
        _conn.execute(
            "UPDATE sessions SET ended_at = ? WHERE id = ? AND ended_at IS NULL",
            (time.time(), session_id),
        )


def list_sessions(limit: int = 50, include_ended: bool = False) -> List[dict]:
    """List recent sessions."""
    with _lock:
        if include_ended:
            rows = _conn.execute(
                "SELECT * FROM sessions ORDER BY started_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
        else:
            rows = _conn.execute(
                "SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
    
    return [dict(row) for row in rows]


def get_session(session_id: str) -> Optional[dict]:
    """Get a single session by ID."""
    with _lock:
        row = _conn.execute(
            "SELECT * FROM sessions WHERE id = ?", (session_id,)
        ).fetchone()
    return dict(row) if row else None


# ── Message storage ────────────────────────────────────────────────────────

def add_message(
    session_id: str,
    role: str,
    content: str,
    tool_name: str = None,
    tool_call_id: str = None,
    tool_calls: str = None,
    token_count: int = None,
    metadata: dict = None,
) -> int:
    """Add a message to a session. Returns message ID."""
    now = time.time()
    
    with _lock:
        cursor = _conn.execute(
            """INSERT INTO messages 
               (session_id, role, content, tool_name, tool_call_id, tool_calls, 
                timestamp, token_count, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                session_id, role, content, tool_name, tool_call_id,
                json.dumps(tool_calls) if isinstance(tool_calls, (list, dict)) else tool_calls,
                now, token_count,
                json.dumps(metadata) if metadata else None,
            ),
        )
        msg_id = cursor.lastrowid
        
        # Update session message count
        _conn.execute(
            "UPDATE sessions SET message_count = message_count + 1 WHERE id = ?",
            (session_id,),
        )
    
    return msg_id


def get_messages(
    session_id: str,
    limit: int = 100,
    offset: int = 0,
    role: str = None,
) -> List[dict]:
    """Get messages from a session."""
    with _lock:
        if role:
            rows = _conn.execute(
                "SELECT * FROM messages WHERE session_id = ? AND role = ? ORDER BY timestamp ASC LIMIT ? OFFSET ?",
                (session_id, role, limit, offset),
            ).fetchall()
        else:
            rows = _conn.execute(
                "SELECT * FROM messages WHERE session_id = ? ORDER BY timestamp ASC LIMIT ? OFFSET ?",
                (session_id, limit, offset),
            ).fetchall()
    
    results = []
    for row in rows:
        d = dict(row)
        # Parse JSON fields
        if d.get("tool_calls"):
            try:
                d["tool_calls"] = json.loads(d["tool_calls"])
            except:
                pass
        if d.get("metadata"):
            try:
                d["metadata"] = json.loads(d["metadata"])
            except:
                pass
        results.append(d)
    
    return results


def get_recent_messages(session_id: str, count: int = 20) -> List[dict]:
    """Get the most recent messages from a session (for context)."""
    with _lock:
        rows = _conn.execute(
            """SELECT * FROM messages WHERE session_id = ? 
               ORDER BY timestamp DESC LIMIT ?""",
            (session_id, count),
        ).fetchall()
    
    # Reverse to chronological order
    results = []
    for row in reversed(rows):
        d = dict(row)
        if d.get("tool_calls"):
            try:
                d["tool_calls"] = json.loads(d["tool_calls"])
            except:
                pass
        results.append(d)
    
    return results


# ── Full-text search ───────────────────────────────────────────────────────

def search_messages(
    query: str,
    session_id: str = None,
    limit: int = 20,
) -> List[dict]:
    """Search messages using FTS5. Falls back to LIKE if FTS unavailable.
    
    Args:
        query: Search text (supports FTS5 syntax: AND, OR, NOT, "phrase")
        session_id: Optional filter by session
        limit: Max results
    
    Returns:
        List of matching messages with relevance
    """
    results = []
    
    with _lock:
        try:
            if session_id:
                rows = _conn.execute(
                    """SELECT m.*, rank FROM messages_fts fts
                       JOIN messages m ON m.id = fts.rowid
                       WHERE messages_fts MATCH ? AND m.session_id = ?
                       ORDER BY rank LIMIT ?""",
                    (query, session_id, limit),
                ).fetchall()
            else:
                rows = _conn.execute(
                    """SELECT m.*, rank FROM messages_fts fts
                       JOIN messages m ON m.id = fts.rowid
                       WHERE messages_fts MATCH ?
                       ORDER BY rank LIMIT ?""",
                    (query, limit),
                ).fetchall()
        except sqlite3.OperationalError:
            # FTS not available, use LIKE fallback
            pattern = f"%{query}%"
            if session_id:
                rows = _conn.execute(
                    """SELECT * FROM messages 
                       WHERE content LIKE ? AND session_id = ?
                       ORDER BY timestamp DESC LIMIT ?""",
                    (pattern, session_id, limit),
                ).fetchall()
            else:
                rows = _conn.execute(
                    """SELECT * FROM messages 
                       WHERE content LIKE ?
                       ORDER BY timestamp DESC LIMIT ?""",
                    (pattern, limit),
                ).fetchall()
    
    for row in rows:
        d = dict(row)
        if d.get("tool_calls"):
            try:
                d["tool_calls"] = json.loads(d["tool_calls"])
            except:
                pass
        results.append(d)
    
    return results


def search_memory(query: str, limit: int = 10) -> str:
    """Search messages and return formatted context for agent recall.
    
    This is the main interface for the agent loop to recall past context.
    Returns formatted text suitable for injection into conversation.
    """
    results = search_messages(query, limit=limit)
    
    if not results:
        return ""
    
    parts = []
    for msg in results:
        role = msg.get("role", "?")
        content = msg.get("content", "")
        ts = msg.get("timestamp", 0)
        session = msg.get("session_id", "?")
        
        # Truncate long content
        if len(content) > 300:
            content = content[:300] + "..."
        
        # Format timestamp
        time_str = time.strftime("%Y-%m-%d %H:%M", time.localtime(ts))
        
        parts.append(f"[{time_str}] ({role}) {content}")
    
    return "\n".join(parts)


# ── Key-value memory (for persistent facts) ───────────────────────────────

def memory_write(key: str, value: str, category: str = "general") -> None:
    """Write a key-value memory entry (upsert)."""
    now = time.time()
    with _lock:
        _conn.execute(
            """INSERT INTO memory_kv (key, value, category, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(key) DO UPDATE SET
                 value = excluded.value,
                 category = excluded.category,
                 updated_at = excluded.updated_at""",
            (key, value, category, now, now),
        )


def memory_read(key: str) -> Optional[str]:
    """Read a memory entry by key."""
    with _lock:
        row = _conn.execute(
            "SELECT value FROM memory_kv WHERE key = ?", (key,)
        ).fetchone()
    return row["value"] if row else None


def memory_list(category: str = None, limit: int = 100) -> List[dict]:
    """List memory entries."""
    with _lock:
        if category:
            rows = _conn.execute(
                "SELECT * FROM memory_kv WHERE category = ? ORDER BY updated_at DESC LIMIT ?",
                (category, limit),
            ).fetchall()
        else:
            rows = _conn.execute(
                "SELECT * FROM memory_kv ORDER BY updated_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
    return [dict(row) for row in rows]


def memory_delete(key: str) -> bool:
    """Delete a memory entry. Returns True if deleted."""
    with _lock:
        cursor = _conn.execute(
            "DELETE FROM memory_kv WHERE key = ?", (key,)
        )
    return cursor.rowcount > 0


def memory_search(query: str, limit: int = 10) -> List[dict]:
    """Search memory entries by value content."""
    pattern = f"%{query}%"
    with _lock:
        rows = _conn.execute(
            "SELECT * FROM memory_kv WHERE value LIKE ? OR key LIKE ? ORDER BY updated_at DESC LIMIT ?",
            (pattern, pattern, limit),
        ).fetchall()
    return [dict(row) for row in rows]


# ── Statistics ─────────────────────────────────────────────────────────────

def get_stats() -> dict:
    """Get database statistics."""
    with _lock:
        sessions = _conn.execute("SELECT COUNT(*) as c FROM sessions").fetchone()["c"]
        messages = _conn.execute("SELECT COUNT(*) as c FROM messages").fetchone()["c"]
        memories = _conn.execute("SELECT COUNT(*) as c FROM memory_kv").fetchone()["c"]
        active = _conn.execute(
            "SELECT COUNT(*) as c FROM sessions WHERE ended_at IS NULL"
        ).fetchone()["c"]
    
    return {
        "sessions": sessions,
        "messages": messages,
        "memories": memories,
        "active_sessions": active,
        "db_path": _db_path,
    }


# ── Export / backup ───────────────────────────────────────────────────────

def export_session(session_id: str) -> dict:
    """Export a full session with all messages."""
    session = get_session(session_id)
    if not session:
        return {"error": "Session not found"}
    
    messages = get_messages(session_id, limit=10000)
    return {
        "session": session,
        "messages": messages,
        "exported_at": time.time(),
    }


def export_all() -> dict:
    """Export entire database for backup."""
    sessions = list_sessions(limit=10000, include_ended=True)
    all_messages = {}
    
    with _lock:
        rows = _conn.execute(
            "SELECT * FROM messages ORDER BY session_id, timestamp"
        ).fetchall()
    
    for row in rows:
        d = dict(row)
        sid = d["session_id"]
        if sid not in all_messages:
            all_messages[sid] = []
        all_messages[sid].append(d)
    
    memories = memory_list(limit=10000)
    
    return {
        "sessions": sessions,
        "messages": all_messages,
        "memories": memories,
        "exported_at": time.time(),
        "stats": get_stats(),
    }


# ── Cleanup ────────────────────────────────────────────────────────────────

def close():
    """Close the database connection."""
    global _conn, _initialized
    with _lock:
        if _conn:
            _conn.close()
            _conn = None
        _initialized = False


def vacuum():
    """Reclaim space from deleted data."""
    with _lock:
        _conn.execute("VACUUM")
