"""
Hermes Android Offline Queue — message queuing when network is unavailable.

When the device is offline, messages are queued locally and automatically
sent when connectivity is restored. Integrates with the agent loop.

This is critical for mobile: network drops happen constantly (elevator,
subway, airplane mode). The agent must not lose user input.
"""

import json
import logging
import os
import sqlite3
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

logger = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────
_db_path: Optional[str] = None
_conn: Optional[sqlite3.Connection] = None
_lock = threading.Lock()
_initialized = False
_send_callback: Optional[Callable] = None
_is_online = True  # Updated by network state listener

# Status constants
QUEUED = "queued"
SENDING = "sending"
SENT = "sent"
FAILED = "failed"


def initialize(db_path: str = None) -> dict:
    """Initialize the offline queue."""
    global _db_path, _conn, _initialized
    
    if _initialized:
        return {"status": "already_initialized"}
    
    _db_path = db_path or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_offline_queue.db"
    )
    Path(_db_path).parent.mkdir(parents=True, exist_ok=True)
    
    with _lock:
        _conn = sqlite3.connect(
            _db_path, check_same_thread=False, timeout=5.0, isolation_level=None,
        )
        _conn.row_factory = sqlite3.Row
        _conn.execute("PRAGMA journal_mode=WAL")
        _conn.executescript("""
            CREATE TABLE IF NOT EXISTS queued_messages (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                session_id TEXT,
                status TEXT NOT NULL DEFAULT 'queued',
                created_at REAL NOT NULL,
                sent_at REAL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                max_retries INTEGER NOT NULL DEFAULT 5,
                error TEXT,
                metadata TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_queue_status ON queued_messages(status, created_at);
        """)
        _initialized = True
    
    pending = list_pending()
    return {
        "status": "initialized",
        "path": _db_path,
        "pending_count": len(pending),
    }


def set_send_callback(callback: Callable):
    """Set the callback that actually sends a message to the agent.
    
    Signature: callback(content: str, session_id: str) -> dict
    Returns: {"status": "success"|"error", "response": str}
    """
    global _send_callback
    _send_callback = callback


def set_online(online: bool):
    """Update network state. Called from Kotlin when connectivity changes."""
    global _is_online
    was_offline = not _is_online
    _is_online = online
    
    # Auto-flush when coming back online
    if online and was_offline:
        logger.info("Network restored, flushing %d queued messages", count_pending())
        flush_queue()


def enqueue(content: str, session_id: str = None, metadata: dict = None) -> dict:
    """Queue a message for sending.
    
    If online, attempts immediate send. If offline or send fails, queues locally.
    """
    msg_id = str(uuid.uuid4())[:8]
    now = time.time()
    
    # Try immediate send if online
    if _is_online and _send_callback:
        try:
            result = _send_callback(content, session_id)
            if isinstance(result, dict) and result.get("status") == "success":
                # Sent successfully, still record it
                with _lock:
                    _conn.execute(
                        """INSERT INTO queued_messages 
                           (id, content, session_id, status, created_at, sent_at, metadata)
                           VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        (msg_id, content, session_id, SENT, now, now,
                         json.dumps(metadata) if metadata else None),
                    )
                return {"id": msg_id, "status": "sent_immediately", "result": result}
        except Exception as e:
            logger.warning("Immediate send failed, queuing: %s", e)
    
    # Queue for later
    with _lock:
        _conn.execute(
            """INSERT INTO queued_messages 
               (id, content, session_id, status, created_at, max_retries, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (msg_id, content, session_id, QUEUED, now, 5,
             json.dumps(metadata) if metadata else None),
        )
    
    return {"id": msg_id, "status": "queued" if not _is_online else "queued_retry"}


def flush_queue() -> dict:
    """Attempt to send all queued messages.
    
    Should be called when network is restored.
    Returns: {"sent": int, "failed": int, "remaining": int}
    """
    if not _send_callback:
        return {"sent": 0, "failed": 0, "remaining": count_pending(), "error": "no_callback"}
    
    results = {"sent": 0, "failed": 0, "remaining": 0, "errors": []}
    
    pending = list_pending()
    
    for msg in pending:
        msg_id = msg["id"]
        content = msg["content"]
        session_id = msg.get("session_id")
        retry_count = msg.get("retry_count", 0)
        max_retries = msg.get("max_retries", 5)
        
        if retry_count >= max_retries:
            with _lock:
                _conn.execute(
                    "UPDATE queued_messages SET status = ? WHERE id = ?",
                    (FAILED, msg_id),
                )
            results["failed"] += 1
            continue
        
        # Mark as sending
        with _lock:
            _conn.execute(
                "UPDATE queued_messages SET status = ? WHERE id = ?",
                (SENDING, msg_id),
            )
        
        try:
            result = _send_callback(content, session_id)
            
            if isinstance(result, dict) and result.get("status") == "success":
                with _lock:
                    _conn.execute(
                        "UPDATE queued_messages SET status = ?, sent_at = ? WHERE id = ?",
                        (SENT, time.time(), msg_id),
                    )
                results["sent"] += 1
            else:
                with _lock:
                    _conn.execute(
                        """UPDATE queued_messages 
                           SET status = ?, retry_count = retry_count + 1, error = ?
                           WHERE id = ?""",
                        (QUEUED, str(result)[:500] if result else "Unknown error", msg_id),
                    )
                results["failed"] += 1
        
        except Exception as e:
            with _lock:
                _conn.execute(
                    """UPDATE queued_messages 
                       SET status = ?, retry_count = retry_count + 1, error = ?
                       WHERE id = ?""",
                    (QUEUED, str(e)[:500], msg_id),
                )
            results["failed"] += 1
            results["errors"].append(str(e)[:200])
    
    results["remaining"] = count_pending()
    return results


# ── Query Functions ────────────────────────────────────────────────────────

def list_pending(limit: int = 100) -> List[dict]:
    """Get all queued messages."""
    with _lock:
        rows = _conn.execute(
            """SELECT * FROM queued_messages 
               WHERE status IN (?, ?)
               ORDER BY created_at LIMIT ?""",
            (QUEUED, SENDING, limit),
        ).fetchall()
    return [dict(row) for row in rows]


def list_all(limit: int = 200) -> List[dict]:
    """Get all messages (any status)."""
    with _lock:
        rows = _conn.execute(
            "SELECT * FROM queued_messages ORDER BY created_at DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return [dict(row) for row in rows]


def count_pending() -> int:
    """Count pending messages."""
    with _lock:
        row = _conn.execute(
            "SELECT COUNT(*) as c FROM queued_messages WHERE status IN (?, ?)",
            (QUEUED, SENDING),
        ).fetchone()
    return row["c"]


def clear_sent(older_than_hours: int = 24) -> int:
    """Remove sent messages older than specified hours."""
    cutoff = time.time() - (older_than_hours * 3600)
    with _lock:
        cursor = _conn.execute(
            "DELETE FROM queued_messages WHERE status = ? AND sent_at < ?",
            (SENT, cutoff),
        )
    return cursor.rowcount


def clear_all() -> int:
    """Remove all messages."""
    with _lock:
        cursor = _conn.execute("DELETE FROM queued_messages")
    return cursor.rowcount


def get_stats() -> dict:
    """Get queue statistics."""
    with _lock:
        total = _conn.execute("SELECT COUNT(*) as c FROM queued_messages").fetchone()["c"]
        queued = _conn.execute(
            "SELECT COUNT(*) as c FROM queued_messages WHERE status = ?", (QUEUED,)
        ).fetchone()["c"]
        sent = _conn.execute(
            "SELECT COUNT(*) as c FROM queued_messages WHERE status = ?", (SENT,)
        ).fetchone()["c"]
        failed = _conn.execute(
            "SELECT COUNT(*) as c FROM queued_messages WHERE status = ?", (FAILED,)
        ).fetchone()["c"]
    
    return {
        "total": total,
        "queued": queued,
        "sent": sent,
        "failed": failed,
        "online": _is_online,
    }


def close():
    """Close the database."""
    global _conn, _initialized
    with _lock:
        if _conn:
            _conn.close()
            _conn = None
        _initialized = False