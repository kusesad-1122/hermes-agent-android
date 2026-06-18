"""
Hermes Android Error Recovery — task queue persistence + crash recovery.

When the app crashes or is killed mid-task, this module ensures:
1. Tasks are persisted to SQLite before execution starts
2. On next startup, incomplete tasks are detected and retried
3. Each task has a status lifecycle: pending → running → completed/failed
4. Failed tasks can be retried with exponential backoff

This is a simplified version of Hermes's error recovery system,
focused on the "task queue survives crashes" requirement.
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
_task_executor: Optional[Callable] = None

# Task status constants
STATUS_PENDING = "pending"
STATUS_RUNNING = "running"
STATUS_COMPLETED = "completed"
STATUS_FAILED = "failed"
STATUS_RETRYING = "retrying"

# Defaults
MAX_RETRIES = 3
RETRY_BASE_DELAY = 5.0  # seconds, doubles each retry


def initialize(db_path: str = None) -> dict:
    """Initialize the error recovery system.
    
    Args:
        db_path: Path to SQLite database. If None, uses app's internal storage.
    
    Returns:
        dict with status info including recovered tasks count
    """
    global _db_path, _conn, _initialized
    
    if _initialized:
        return {"status": "already_initialized"}
    
    _db_path = db_path or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_recovery.db"
    )
    
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
        _init_schema()
        _initialized = True
    
    # Check for recoverable tasks
    recoverable = get_recoverable_tasks()
    stats = get_stats()
    
    return {
        "status": "initialized",
        "path": _db_path,
        "recoverable_tasks": len(recoverable),
        "total_tasks": stats.get("total_tasks", 0),
    }


def _init_schema():
    """Create recovery tables."""
    _conn.executescript("""
        CREATE TABLE IF NOT EXISTS task_queue (
            id TEXT PRIMARY KEY,
            task_type TEXT NOT NULL,
            payload TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at REAL NOT NULL,
            started_at REAL,
            completed_at REAL,
            error_message TEXT,
            retry_count INTEGER NOT NULL DEFAULT 0,
            max_retries INTEGER NOT NULL DEFAULT 3,
            next_retry_at REAL,
            metadata TEXT
        );
        
        CREATE TABLE IF NOT EXISTS task_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL,
            action TEXT NOT NULL,
            timestamp REAL NOT NULL,
            detail TEXT
        );
        
        CREATE INDEX IF NOT EXISTS idx_task_queue_status 
            ON task_queue(status, next_retry_at);
        CREATE INDEX IF NOT EXISTS idx_task_queue_created 
            ON task_queue(created_at DESC);
        CREATE INDEX IF NOT EXISTS idx_task_history_task 
            ON task_history(task_id, timestamp DESC);
    """)


# ── Task Lifecycle ─────────────────────────────────────────────────────────

def set_executor(executor: Callable):
    """Set the task executor function.
    
    The executor receives (task_type, payload) and should return:
        {"status": "success"|"error", "result": Any, "error": str}
    """
    global _task_executor
    _task_executor = executor


def enqueue(
    task_type: str,
    payload: dict,
    max_retries: int = MAX_RETRIES,
    metadata: dict = None,
) -> dict:
    """Add a task to the queue.
    
    Args:
        task_type: Type identifier (e.g., "agent_message", "cron_job", "skill_create")
        payload: Task data (arbitrary dict, will be JSON serialized)
        max_retries: Maximum retry attempts
        metadata: Optional metadata
    
    Returns:
        dict with task_id and status
    """
    task_id = str(uuid.uuid4())[:8]
    now = time.time()
    
    with _lock:
        _conn.execute(
            """INSERT INTO task_queue 
               (id, task_type, payload, status, created_at, max_retries, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (
                task_id, task_type, json.dumps(payload),
                STATUS_PENDING, now, max_retries,
                json.dumps(metadata) if metadata else None,
            ),
        )
        _log_history(task_id, "enqueued", f"Type: {task_type}")
    
    return {"task_id": task_id, "status": STATUS_PENDING}


def mark_running(task_id: str):
    """Mark a task as currently running."""
    with _lock:
        _conn.execute(
            """UPDATE task_queue 
               SET status = ?, started_at = ?
               WHERE id = ?""",
            (STATUS_RUNNING, time.time(), task_id),
        )
        _log_history(task_id, "started")


def mark_completed(task_id: str, result: Any = None):
    """Mark a task as completed."""
    with _lock:
        _conn.execute(
            """UPDATE task_queue 
               SET status = ?, completed_at = ?
               WHERE id = ?""",
            (STATUS_COMPLETED, time.time(), task_id),
        )
        detail = json.dumps(result, ensure_ascii=False)[:2000] if result else None
        _log_history(task_id, "completed", detail)


def mark_failed(task_id: str, error: str = None, retry: bool = True):
    """Mark a task as failed. If retry=True and retries remain, schedule retry.
    
    Args:
        task_id: Task ID
        error: Error message
        retry: Whether to schedule a retry (if retries remain)
    """
    with _lock:
        row = _conn.execute(
            "SELECT retry_count, max_retries FROM task_queue WHERE id = ?",
            (task_id,),
        ).fetchone()
        
        if not row:
            return
        
        retry_count = row["retry_count"]
        max_retries = row["max_retries"]
        
        if retry and retry_count < max_retries:
            # Schedule retry with exponential backoff
            delay = RETRY_BASE_DELAY * (2 ** retry_count)
            next_retry = time.time() + delay
            
            _conn.execute(
                """UPDATE task_queue 
                   SET status = ?, error_message = ?, retry_count = retry_count + 1,
                       next_retry_at = ?
                   WHERE id = ?""",
                (STATUS_RETRYING, error[:2000] if error else None, next_retry, task_id),
            )
            _log_history(task_id, "retry_scheduled", f"Attempt {retry_count + 1}/{max_retries}, delay={delay:.0f}s")
        else:
            # Permanent failure
            _conn.execute(
                """UPDATE task_queue 
                   SET status = ?, error_message = ?, completed_at = ?
                   WHERE id = ?""",
                (STATUS_FAILED, error[:2000] if error else None, time.time(), task_id),
            )
            _log_history(task_id, "failed", error[:500] if error else None)


def _log_history(task_id: str, action: str, detail: str = None):
    """Log a task history event."""
    try:
        _conn.execute(
            "INSERT INTO task_history (task_id, action, timestamp, detail) VALUES (?, ?, ?, ?)",
            (task_id, action, time.time(), detail),
        )
    except Exception as e:
        logger.warning("Failed to log history: %s", e)


# ── Recovery ───────────────────────────────────────────────────────────────

def get_recoverable_tasks() -> List[dict]:
    """Get tasks that need recovery (were running or retrying when app died).
    
    Returns:
        List of task dicts that should be retried
    """
    now = time.time()
    
    with _lock:
        # Tasks that were running when app died (no completed_at)
        running = _conn.execute(
            """SELECT * FROM task_queue 
               WHERE status = ? AND completed_at IS NULL
               ORDER BY created_at""",
            (STATUS_RUNNING,),
        ).fetchall()
        
        # Tasks scheduled for retry
        retrying = _conn.execute(
            """SELECT * FROM task_queue 
               WHERE status = ? AND next_retry_at IS NOT NULL AND next_retry_at <= ?
               ORDER BY next_retry_at""",
            (STATUS_RETRYING, now),
        ).fetchall()
        
        # Tasks still pending (never started)
        pending = _conn.execute(
            """SELECT * FROM task_queue 
               WHERE status = ?
               ORDER BY created_at""",
            (STATUS_PENDING,),
        ).fetchall()
    
    results = []
    for row in list(running) + list(retrying) + list(pending):
        results.append(_row_to_task(row))
    
    return results


def recover_tasks() -> dict:
    """Recover and execute all recoverable tasks.
    
    Should be called on app startup after initialization.
    
    Returns:
        dict with recovery results
    """
    tasks = get_recoverable_tasks()
    results = {"recovered": 0, "failed": 0, "skipped": 0, "tasks": []}
    
    for task in tasks:
        task_id = task["id"]
        task_type = task["task_type"]
        
        try:
            payload = task["payload"]
            if isinstance(payload, str):
                payload = json.loads(payload)
        except (json.JSONDecodeError, TypeError):
            mark_failed(task_id, "Invalid payload JSON", retry=False)
            results["failed"] += 1
            continue
        
        if _task_executor:
            mark_running(task_id)
            
            try:
                result = _task_executor(task_type, payload)
                
                if isinstance(result, dict) and result.get("status") == "error":
                    mark_failed(task_id, result.get("error", "Unknown error"))
                    results["failed"] += 1
                else:
                    mark_completed(task_id, result)
                    results["recovered"] += 1
                
                results["tasks"].append({
                    "id": task_id,
                    "type": task_type,
                    "status": "recovered" if result != "failed" else "failed",
                })
            
            except Exception as e:
                mark_failed(task_id, str(e))
                results["failed"] += 1
                results["tasks"].append({
                    "id": task_id,
                    "type": task_type,
                    "status": "failed",
                    "error": str(e)[:200],
                })
        else:
            # No executor, just mark as pending for later
            results["skipped"] += 1
            results["tasks"].append({
                "id": task_id,
                "type": task_type,
                "status": "skipped",
            })
    
    return results


def cleanup_old_tasks(max_age_days: int = 7) -> int:
    """Remove completed/failed tasks older than max_age_days.
    
    Returns:
        Number of tasks removed
    """
    cutoff = time.time() - (max_age_days * 86400)
    
    with _lock:
        # Archive to history first
        _conn.execute(
            """INSERT INTO task_history (task_id, action, timestamp, detail)
               SELECT id, 'cleanup', ?, payload 
               FROM task_queue 
               WHERE status IN (?, ?) AND completed_at < ?""",
            (time.time(), STATUS_COMPLETED, STATUS_FAILED, cutoff),
        )
        
        cursor = _conn.execute(
            "DELETE FROM task_queue WHERE status IN (?, ?) AND completed_at < ?",
            (STATUS_COMPLETED, STATUS_FAILED, cutoff),
        )
        
        # Also cleanup old history
        _conn.execute(
            "DELETE FROM task_history WHERE timestamp < ?",
            (cutoff,),
        )
    
    return cursor.rowcount


# ── Query Functions ────────────────────────────────────────────────────────

def get_task(task_id: str) -> Optional[dict]:
    """Get a task by ID."""
    with _lock:
        row = _conn.execute(
            "SELECT * FROM task_queue WHERE id = ?", (task_id,)
        ).fetchone()
    return _row_to_task(row) if row else None


def list_tasks(
    status: str = None,
    task_type: str = None,
    limit: int = 50,
) -> List[dict]:
    """List tasks with optional filters."""
    conditions = []
    params = []
    
    if status:
        conditions.append("status = ?")
        params.append(status)
    if task_type:
        conditions.append("task_type = ?")
        params.append(task_type)
    
    where = "WHERE " + " AND ".join(conditions) if conditions else ""
    params.append(limit)
    
    with _lock:
        rows = _conn.execute(
            f"SELECT * FROM task_queue {where} ORDER BY created_at DESC LIMIT ?",
            params,
        ).fetchall()
    
    return [_row_to_task(row) for row in rows]


def get_task_history(task_id: str, limit: int = 20) -> List[dict]:
    """Get history for a task."""
    with _lock:
        rows = _conn.execute(
            "SELECT * FROM task_history WHERE task_id = ? ORDER BY timestamp DESC LIMIT ?",
            (task_id, limit),
        ).fetchall()
    return [dict(row) for row in rows]


# ── Helper Functions ───────────────────────────────────────────────────────

def _row_to_task(row) -> dict:
    """Convert a database row to a task dict."""
    d = dict(row)
    if d.get("payload"):
        try:
            d["payload"] = json.loads(d["payload"])
        except:
            pass
    if d.get("metadata"):
        try:
            d["metadata"] = json.loads(d["metadata"])
        except:
            pass
    return d


# ── Statistics ─────────────────────────────────────────────────────────────

def get_stats() -> dict:
    """Get recovery system statistics."""
    with _lock:
        total = _conn.execute("SELECT COUNT(*) as c FROM task_queue").fetchone()["c"]
        by_status = {}
        for s in [STATUS_PENDING, STATUS_RUNNING, STATUS_COMPLETED, STATUS_FAILED, STATUS_RETRYING]:
            count = _conn.execute(
                "SELECT COUNT(*) as c FROM task_queue WHERE status = ?", (s,)
            ).fetchone()["c"]
            by_status[s] = count
        
        history = _conn.execute("SELECT COUNT(*) as c FROM task_history").fetchone()["c"]
    
    return {
        "total_tasks": total,
        "by_status": by_status,
        "history_entries": history,
        "db_path": _db_path,
    }


def close():
    """Close the database connection."""
    global _conn, _initialized
    with _lock:
        if _conn:
            _conn.close()
            _conn = None
        _initialized = False