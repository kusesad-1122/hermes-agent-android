"""
Hermes Android Cron System — lightweight task scheduler.

Simplified from the full Hermes cron system (cron/jobs.py 1304 lines +
cron/scheduler.py 2213 lines) into a single ~450 line module focused on
Android MVP needs.

Features:
- Job CRUD with SQLite persistence
- Simple cron expression parsing (no croniter dependency)
- Tick-based scheduler (called from AgentService background thread)
- Job execution via agent_loop integration
- One-shot and recurring jobs
- Job output persistence
"""

import json
import logging
import os
import re
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────
_db_path: Optional[str] = None
_conn: Optional[sqlite3.Connection] = None
_lock = threading.Lock()
_initialized = False
_tick_running = False
_on_execute: Optional[Callable] = None  # Callback to run job via agent_loop


def initialize(db_path: str = None) -> dict:
    """Initialize the cron system.
    
    Args:
        db_path: Path to SQLite database. If None, uses app's internal storage.
    
    Returns:
        dict with status info
    """
    global _db_path, _conn, _initialized
    
    if _initialized:
        return {"status": "already_initialized"}
    
    _db_path = db_path or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_cron.db"
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
    
    jobs = list_jobs()
    return {
        "status": "initialized",
        "path": _db_path,
        "jobs": len(jobs),
    }


def _init_schema():
    """Create cron tables."""
    _conn.executescript("""
        CREATE TABLE IF NOT EXISTS cron_jobs (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            description TEXT,
            cron_expr TEXT NOT NULL,
            prompt TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at REAL NOT NULL,
            last_run_at REAL,
            next_run_at REAL,
            run_count INTEGER NOT NULL DEFAULT 0,
            last_status TEXT,
            last_error TEXT,
            tags TEXT,
            metadata TEXT
        );
        
        CREATE TABLE IF NOT EXISTS cron_outputs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            job_id TEXT NOT NULL REFERENCES cron_jobs(id),
            run_at REAL NOT NULL,
            status TEXT NOT NULL,
            output TEXT,
            error TEXT,
            duration_ms INTEGER,
            tokens_used INTEGER
        );
        
        CREATE INDEX IF NOT EXISTS idx_cron_jobs_next_run 
            ON cron_jobs(next_run_at) WHERE enabled = 1;
        CREATE INDEX IF NOT EXISTS idx_cron_outputs_job 
            ON cron_outputs(job_id, run_at DESC);
    """)


# ── Cron Expression Parser ─────────────────────────────────────────────────
# Simple parser supporting:
#   * / N   - every N
#   N       - exact value
#   N,M     - list
#   N-M     - range
# Fields: minute hour day-of-month month day-of-week

def parse_cron_field(field: str, min_val: int, max_val: int) -> List[int]:
    """Parse a single cron field into a list of valid values."""
    values = set()
    
    for part in field.split(','):
        part = part.strip()
        
        if part == '*':
            values.update(range(min_val, max_val + 1))
        elif part.startswith('*/'):
            step = int(part[2:])
            values.update(range(min_val, max_val + 1, step))
        elif '-' in part and '/' not in part:
            start, end = part.split('-', 1)
            values.update(range(int(start), int(end) + 1))
        elif '-' in part and '/' in part:
            range_part, step_part = part.split('/', 1)
            start, end = range_part.split('-', 1)
            step = int(step_part)
            values.update(range(int(start), int(end) + 1, step))
        else:
            values.add(int(part))
    
    return sorted(values)


def parse_cron_expr(expr: str) -> Tuple[List[int], List[int], List[int], List[int], List[int]]:
    """Parse a cron expression (5 fields: min hour dom month dow).
    
    Returns tuple of (minutes, hours, days, months, weekdays).
    """
    parts = expr.strip().split()
    if len(parts) != 5:
        raise ValueError(f"Cron expression must have 5 fields, got {len(parts)}: {expr}")
    
    minutes = parse_cron_field(parts[0], 0, 59)
    hours = parse_cron_field(parts[1], 0, 23)
    days = parse_cron_field(parts[2], 1, 31)
    months = parse_cron_field(parts[3], 1, 12)
    weekdays = parse_cron_field(parts[4], 0, 6)
    
    return minutes, hours, days, months, weekdays


def matches_cron(dt: datetime, cron_expr: str) -> bool:
    """Check if a datetime matches a cron expression."""
    try:
        minutes, hours, days, months, weekdays = parse_cron_expr(cron_expr)
        return (
            dt.minute in minutes
            and dt.hour in hours
            and dt.day in days
            and dt.month in months
            and dt.weekday() in weekdays  # 0=Monday in Python
        )
    except Exception as e:
        logger.warning("Invalid cron expression '%s': %s", cron_expr, e)
        return False


def next_cron_time(after: datetime, cron_expr: str, max_search_minutes: int = 1440 * 7) -> Optional[datetime]:
    """Find the next time a cron expression will match.
    
    Args:
        after: Start searching after this time
        cron_expr: Cron expression
        max_search_minutes: Maximum minutes to search (default: 7 days)
    
    Returns:
        Next matching datetime, or None if not found within search range
    """
    try:
        minutes, hours, days, months, weekdays = parse_cron_expr(cron_expr)
    except Exception:
        return None
    
    # Simple brute-force search (good enough for Android MVP)
    current = after.replace(second=0, microsecond=0) + timedelta(minutes=1)
    
    for _ in range(max_search_minutes):
        if (
            current.minute in minutes
            and current.hour in hours
            and current.day in days
            and current.month in months
            and current.weekday() in weekdays
        ):
            return current
        current += timedelta(minutes=1)
    
    return None


# ── Job CRUD ───────────────────────────────────────────────────────────────

def create_job(
    name: str,
    cron_expr: str,
    prompt: str,
    description: str = None,
    tags: List[str] = None,
    enabled: bool = True,
    metadata: dict = None,
) -> dict:
    """Create a new cron job.
    
    Args:
        name: Job name
        cron_expr: Cron expression (5 fields: min hour dom month dow)
        prompt: The prompt to send to the agent when the job fires
        description: Optional description
        tags: Optional tags for categorization
        enabled: Whether the job is active
        metadata: Optional metadata dict
    
    Returns:
        dict with created job info
    """
    # Validate cron expression
    try:
        parse_cron_expr(cron_expr)
    except ValueError as e:
        return {"error": f"Invalid cron expression: {e}"}
    
    job_id = str(uuid.uuid4())[:8]
    now = datetime.now()
    next_run = next_cron_time(now, cron_expr) if enabled else None
    
    with _lock:
        _conn.execute(
            """INSERT INTO cron_jobs 
               (id, name, description, cron_expr, prompt, enabled, created_at, 
                next_run_at, tags, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                job_id, name, description, cron_expr, prompt,
                1 if enabled else 0, time.time(),
                next_run.timestamp() if next_run else None,
                json.dumps(tags) if tags else None,
                json.dumps(metadata) if metadata else None,
            ),
        )
    
    return {
        "id": job_id,
        "name": name,
        "cron_expr": cron_expr,
        "enabled": enabled,
        "next_run_at": str(next_run) if next_run else None,
    }


def get_job(job_id: str) -> Optional[dict]:
    """Get a job by ID."""
    with _lock:
        row = _conn.execute(
            "SELECT * FROM cron_jobs WHERE id = ?", (job_id,)
        ).fetchone()
    return _row_to_job(row) if row else None


def list_jobs(enabled_only: bool = False, tag: str = None) -> List[dict]:
    """List all jobs."""
    with _lock:
        if enabled_only:
            rows = _conn.execute(
                "SELECT * FROM cron_jobs WHERE enabled = 1 ORDER BY next_run_at"
            ).fetchall()
        else:
            rows = _conn.execute(
                "SELECT * FROM cron_jobs ORDER BY created_at DESC"
            ).fetchall()
    
    jobs = [_row_to_job(row) for row in rows]
    
    if tag:
        jobs = [j for j in jobs if tag in (j.get("tags") or [])]
    
    return jobs


def update_job(job_id: str, **kwargs) -> Optional[dict]:
    """Update a job's fields."""
    allowed = {"name", "description", "cron_expr", "prompt", "enabled", "tags", "metadata"}
    updates = {k: v for k, v in kwargs.items() if k in allowed}
    
    if not updates:
        return get_job(job_id)
    
    # Recalculate next_run if cron_expr or enabled changed
    if "cron_expr" in updates or "enabled" in updates:
        job = get_job(job_id)
        if job:
            expr = updates.get("cron_expr", job["cron_expr"])
            enabled = updates.get("enabled", job["enabled"])
            if enabled:
                next_run = next_cron_time(datetime.now(), expr)
                updates["next_run_at"] = next_run.timestamp() if next_run else None
            else:
                updates["next_run_at"] = None
    
    set_parts = []
    values = []
    for k, v in updates.items():
        if k == "tags" or k == "metadata":
            set_parts.append(f"{k} = ?")
            values.append(json.dumps(v) if v else None)
        elif k == "enabled":
            set_parts.append("enabled = ?")
            values.append(1 if v else 0)
        else:
            set_parts.append(f"{k} = ?")
            values.append(v)
    
    values.append(job_id)
    
    with _lock:
        _conn.execute(
            f"UPDATE cron_jobs SET {', '.join(set_parts)} WHERE id = ?",
            values,
        )
    
    return get_job(job_id)


def delete_job(job_id: str) -> bool:
    """Delete a job and its outputs."""
    with _lock:
        _conn.execute("DELETE FROM cron_outputs WHERE job_id = ?", (job_id,))
        cursor = _conn.execute("DELETE FROM cron_jobs WHERE id = ?", (job_id,))
    return cursor.rowcount > 0


def enable_job(job_id: str) -> bool:
    """Enable a job."""
    job = get_job(job_id)
    if not job:
        return False
    next_run = next_cron_time(datetime.now(), job["cron_expr"])
    with _lock:
        _conn.execute(
            "UPDATE cron_jobs SET enabled = 1, next_run_at = ? WHERE id = ?",
            (next_run.timestamp() if next_run else None, job_id),
        )
    return True


def disable_job(job_id: str) -> bool:
    """Disable a job."""
    with _lock:
        cursor = _conn.execute(
            "UPDATE cron_jobs SET enabled = 0, next_run_at = NULL WHERE id = ?",
            (job_id,),
        )
    return cursor.rowcount > 0


# ── Job Execution ──────────────────────────────────────────────────────────

def set_executor(executor: Callable):
    """Set the job executor function.
    
    The executor receives (job_id, prompt) and should return:
        {"status": "success"|"error", "output": str, "tokens": int}
    """
    global _on_execute
    _on_execute = executor


def mark_job_run(job_id: str, status: str, output: str = None, error: str = None,
                 duration_ms: int = 0, tokens: int = 0):
    """Record a job execution result."""
    now = time.time()
    
    with _lock:
        _conn.execute(
            """INSERT INTO cron_outputs 
               (job_id, run_at, status, output, error, duration_ms, tokens_used)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (job_id, now, status, output, error, duration_ms, tokens),
        )
        
        # Update job's last run info
        next_run_expr = _conn.execute(
            "SELECT cron_expr FROM cron_jobs WHERE id = ?", (job_id,)
        ).fetchone()
        
        next_run = None
        if next_run_expr:
            try:
                next_run = next_cron_time(datetime.now(), next_run_expr["cron_expr"])
            except:
                pass
        
        _conn.execute(
            """UPDATE cron_jobs 
               SET last_run_at = ?, run_count = run_count + 1, 
                   last_status = ?, last_error = ?, next_run_at = ?
               WHERE id = ?""",
            (now, status, error, next_run.timestamp() if next_run else None, job_id),
        )


def get_job_outputs(job_id: str, limit: int = 10) -> List[dict]:
    """Get recent outputs for a job."""
    with _lock:
        rows = _conn.execute(
            "SELECT * FROM cron_outputs WHERE job_id = ? ORDER BY run_at DESC LIMIT ?",
            (job_id, limit),
        ).fetchall()
    
    return [dict(row) for row in rows]


# ── Scheduler ──────────────────────────────────────────────────────────────

def tick() -> dict:
    """Check for due jobs and execute them.
    
    Should be called periodically (e.g., every 60 seconds from AgentService).
    
    Returns:
        dict with tick results: {executed: int, errors: int, skipped: int}
    """
    global _tick_running
    
    if _tick_running:
        return {"status": "already_running"}
    
    _tick_running = True
    results = {"executed": 0, "errors": 0, "skipped": 0, "jobs": []}
    
    try:
        now = datetime.now()
        now_ts = now.timestamp()
        
        # Get enabled jobs that are due
        with _lock:
            due_jobs = _conn.execute(
                """SELECT * FROM cron_jobs 
                   WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?
                   ORDER BY next_run_at""",
                (now_ts,),
            ).fetchall()
        
        for row in due_jobs:
            job = _row_to_job(row)
            job_id = job["id"]
            job_name = job["name"]
            prompt = job["prompt"]
            
            logger.info("Running cron job: %s (%s)", job_name, job_id)
            
            start_time = time.time()
            
            try:
                if _on_execute:
                    result = _on_execute(job_id, prompt)
                    
                    status = result.get("status", "success")
                    output = result.get("output", "")
                    error = result.get("error")
                    tokens = result.get("tokens", 0)
                else:
                    status = "no_executor"
                    output = ""
                    error = "No executor configured"
                    tokens = 0
                
                duration_ms = int((time.time() - start_time) * 1000)
                
                mark_job_run(
                    job_id, status,
                    output=output[:5000] if output else None,
                    error=error[:2000] if error else None,
                    duration_ms=duration_ms,
                    tokens=tokens,
                )
                
                if status == "success":
                    results["executed"] += 1
                else:
                    results["errors"] += 1
                
                results["jobs"].append({
                    "id": job_id,
                    "name": job_name,
                    "status": status,
                    "duration_ms": duration_ms,
                })
            
            except Exception as e:
                duration_ms = int((time.time() - start_time) * 1000)
                mark_job_run(job_id, "error", error=str(e)[:2000], duration_ms=duration_ms)
                results["errors"] += 1
                results["jobs"].append({
                    "id": job_id,
                    "name": job_name,
                    "status": "error",
                    "error": str(e)[:200],
                })
    
    finally:
        _tick_running = False
    
    return results


def get_due_jobs() -> List[dict]:
    """Get jobs that are due now (for preview/dry-run)."""
    now_ts = datetime.now().timestamp()
    with _lock:
        rows = _conn.execute(
            """SELECT * FROM cron_jobs 
               WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?
               ORDER BY next_run_at""",
            (now_ts,),
        ).fetchall()
    return [_row_to_job(row) for row in rows]


# ── Helper functions ───────────────────────────────────────────────────────

def _row_to_job(row) -> dict:
    """Convert a database row to a job dict."""
    d = dict(row)
    if d.get("tags"):
        try:
            d["tags"] = json.loads(d["tags"])
        except:
            d["tags"] = []
    if d.get("metadata"):
        try:
            d["metadata"] = json.loads(d["metadata"])
        except:
            pass
    d["enabled"] = bool(d.get("enabled"))
    # Format next_run_at as readable string
    if d.get("next_run_at"):
        try:
            d["next_run_at_str"] = datetime.fromtimestamp(d["next_run_at"]).strftime(
                "%Y-%m-%d %H:%M"
            )
        except:
            d["next_run_at_str"] = str(d["next_run_at"])
    return d


# ── Statistics ─────────────────────────────────────────────────────────────

def get_stats() -> dict:
    """Get cron system statistics."""
    with _lock:
        total = _conn.execute("SELECT COUNT(*) as c FROM cron_jobs").fetchone()["c"]
        enabled = _conn.execute(
            "SELECT COUNT(*) as c FROM cron_jobs WHERE enabled = 1"
        ).fetchone()["c"]
        runs = _conn.execute("SELECT COUNT(*) as c FROM cron_outputs").fetchone()["c"]
        successes = _conn.execute(
            "SELECT COUNT(*) as c FROM cron_outputs WHERE status = 'success'"
        ).fetchone()["c"]
    
    return {
        "total_jobs": total,
        "enabled_jobs": enabled,
        "total_runs": runs,
        "successful_runs": successes,
        "success_rate": f"{successes/runs*100:.1f}%" if runs > 0 else "N/A",
    }


def close():
    """Close the database connection."""
    global _conn, _initialized
    with _lock:
        if _conn:
            _conn.close()
            _conn = None
        _initialized = False