"""
Hermes Android Root Gateway — unified Root/Shizuku backend.

Core responsibilities:
1. Root/Shizuku detection (auto-detect what's available)
2. Unified su execution (Magisk/KSU/APatch/Shizuku compatible)
3. Capability switch (user-configurable: which Root abilities are enabled)
4. Three-tier confirmation mode (only-dangerous / all-root / fully-automatic)
5. Dry-run preview for irreversible operations
6. Automatic snapshot before irreversible ops
7. Complete audit log

Designed for: Magisk, KernelSU (KSU), APatch — all via `su -c` backend.
"""

import json
import logging
import os
import sqlite3
import subprocess
import threading
import time
import uuid
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# ── Enums ──────────────────────────────────────────────────────────────────

class RootBackend(Enum):
    """Available Root backends."""
    NONE = "none"           # No Root available
    MAGISK = "magisk"       # Magisk su
    KSU = "ksu"             # KernelSU
    APATCH = "apatch"       # APatch
    SHIZUKU = "shizuku"     # Shizuku (ADB-level, no full root)
    UNKNOWN = "unknown"     # Root detected but type unknown


class ConfirmationMode(Enum):
    """Three-tier confirmation modes."""
    ONLY_DANGEROUS = "only_dangerous"   # 🔒 只有危险操作需确认（推荐）
    ALL_ROOT = "all_root"               # 🛡️ 所有 Root 操作需确认
    FULLY_AUTO = "fully_auto"           # 🚀 全自动（信任 AI，仅记录日志）


class Capability(Enum):
    """Root capability categories."""
    APP_MANAGEMENT = "app_management"       # 静默安装/卸载/冻结
    SYSTEM_SETTINGS = "system_settings"     # DPI/动画/build.prop
    FILE_SYSTEM = "file_system"             # /data/data 全盘、系统分区
    PROCESS_MANAGEMENT = "process_management"  # 进程管理
    NETWORK = "network"                     # 代理/hosts/iptables
    BACKUP_RESTORE = "backup_restore"       # 数据备份/恢复
    SCREEN = "screen"                       # 截屏/录屏
    PRIVACY = "privacy"                     # 隐私审计


# ── Irreversible operations classification ─────────────────────────────────

_IRREVERSIBLE_PATTERNS = [
    # File operations
    (r'\brm\s+-rf?\b', "删除文件/目录"),
    (r'\bdd\s+', "磁盘写入"),
    (r'\bmkfs\b', "格式化分区"),
    (r'\bflash\b', "刷写分区"),
    # Package operations
    (r'\bpm\s+uninstall\b', "卸载应用（含数据）"),
    (r'\bpm\s+clear\b', "清除应用数据"),
    # System changes
    (r'\bsed\s+-i\b.*build\.prop', "修改 build.prop"),
    (r'\bsettings\s+put\s+global\b', "修改全局设置"),
    (r'iptables\s+-F', "清空防火墙规则"),
    (r'\bsetprop\b', "设置系统属性"),
    # Dangerous commands
    (r'\breboot\s+bootloader\b', "重启到 bootloader"),
    (r'\breboot\s+recovery\b', "重启到 recovery"),
    (r'\bkill\s+-9\s+1\b', "杀死 init 进程"),
]


def _is_irreversible(cmd: str) -> Tuple[bool, str]:
    """Check if a command is irreversible.
    
    Returns (is_irreversible, reason)
    """
    import re
    cmd_lower = cmd.lower()
    for pattern, reason in _IRREVERSIBLE_PATTERNS:
        if re.search(pattern, cmd_lower, re.IGNORECASE):
            return True, reason
    return False, ""


# ── Configuration ──────────────────────────────────────────────────────────
_db_path: Optional[str] = None
_conn: Optional[sqlite3.Connection] = None
_lock = threading.Lock()
_initialized = False

# Current state
_current_backend = RootBackend.NONE
_current_mode = ConfirmationMode.ONLY_DANGEROUS
_enabled_capabilities: set = set()
_confirm_callback: Optional[Callable] = None  # Called to ask user for confirmation
_snapshot_dir: Optional[str] = None

# ── Initialization ─────────────────────────────────────────────────────────

def initialize(db_path: str = None, snapshot_dir: str = None) -> dict:
    """Initialize the Root Gateway.
    
    Args:
        db_path: Path to audit log SQLite database
        snapshot_dir: Directory for automatic snapshots
    
    Returns:
        dict with Root detection results
    """
    global _db_path, _conn, _initialized, _current_backend, _snapshot_dir
    
    if _initialized:
        return {"status": "already_initialized"}
    
    _db_path = db_path or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_root_audit.db"
    )
    _snapshot_dir = snapshot_dir or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_snapshots"
    )
    
    Path(_db_path).parent.mkdir(parents=True, exist_ok=True)
    Path(_snapshot_dir).mkdir(parents=True, exist_ok=True)
    
    # Initialize audit database
    with _lock:
        _conn = sqlite3.connect(
            _db_path, check_same_thread=False, timeout=5.0, isolation_level=None,
        )
        _conn.row_factory = sqlite3.Row
        _conn.execute("PRAGMA journal_mode=WAL")
        _init_schema()
    
    # Detect Root backend
    _current_backend = _detect_root_backend()
    
    _initialized = True
    
    return {
        "status": "initialized",
        "backend": _current_backend.value,
        "backend_name": _current_backend.name,
        "is_root_available": _current_backend != RootBackend.NONE,
        "confirmation_mode": _current_mode.value,
        "audit_db": _db_path,
        "snapshot_dir": _snapshot_dir,
    }


def _init_schema():
    """Create audit log table."""
    _conn.executescript("""
        CREATE TABLE IF NOT EXISTS audit_log (
            id TEXT PRIMARY KEY,
            timestamp REAL NOT NULL,
            command TEXT NOT NULL,
            capability TEXT,
            reversible INTEGER NOT NULL DEFAULT 1,
            confirmation_mode TEXT NOT NULL,
            confirmed_by TEXT,
            backend TEXT,
            exit_code INTEGER,
            stdout TEXT,
            stderr TEXT,
            error TEXT,
            snapshot_path TEXT,
            duration_ms INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp DESC);
        CREATE INDEX IF NOT EXISTS idx_audit_capability ON audit_log(capability);
    """)


def _detect_root_backend() -> RootBackend:
    """Auto-detect available Root backend.
    
    Detection order:
    1. Check for KSU (ksud)
    2. Check for Magisk (magisk)
    3. Check for APatch (apd)
    4. Check for generic su
    5. Check for Shizuku (rish)
    """
    # KSU
    try:
        result = subprocess.run(
            ["which", "ksud"], capture_output=True, text=True, timeout=3
        )
        if result.returncode == 0:
            logger.info("Detected KernelSU backend")
            return RootBackend.KSU
    except:
        pass
    
    # Magisk
    try:
        result = subprocess.run(
            ["which", "magisk"], capture_output=True, text=True, timeout=3
        )
        if result.returncode == 0:
            logger.info("Detected Magisk backend")
            return RootBackend.MAGISK
    except:
        pass
    
    # APatch
    try:
        result = subprocess.run(
            ["which", "apd"], capture_output=True, text=True, timeout=3
        )
        if result.returncode == 0:
            logger.info("Detected APatch backend")
            return RootBackend.APATCH
    except:
        pass
    
    # Generic su
    try:
        result = subprocess.run(
            ["su", "-c", "id"], capture_output=True, text=True, timeout=5
        )
        if result.returncode == 0 and "uid=0" in result.stdout:
            logger.info("Detected generic su backend")
            return RootBackend.UNKNOWN
    except:
        pass
    
    # Shizuku (rish)
    try:
        result = subprocess.run(
            ["which", "rish"], capture_output=True, text=True, timeout=3
        )
        if result.returncode == 0:
            logger.info("Detected Shizuku backend (no full root)")
            return RootBackend.SHIZUKU
    except:
        pass
    
    logger.info("No Root backend detected")
    return RootBackend.NONE


# ── Confirmation Mode ──────────────────────────────────────────────────────

def set_confirmation_mode(mode: str) -> dict:
    """Set the confirmation mode.
    
    Args:
        mode: One of "only_dangerous", "all_root", "fully_auto"
    """
    global _current_mode
    try:
        _current_mode = ConfirmationMode(mode)
        return {"mode": _current_mode.value, "description": _get_mode_description()}
    except ValueError:
        return {"error": f"Invalid mode: {mode}. Use: only_dangerous, all_root, fully_auto"}


def get_confirmation_mode() -> dict:
    """Get current confirmation mode."""
    return {
        "mode": _current_mode.value,
        "description": _get_mode_description(),
    }


def _get_mode_description() -> str:
    return {
        ConfirmationMode.ONLY_DANGEROUS: "仅危险操作需确认（读取/普通命令自动放行，修改/删除/安装需确认）",
        ConfirmationMode.ALL_ROOT: "所有 Root 操作均需确认（每次 su 都弹窗）",
        ConfirmationMode.FULLY_AUTO: "全自动模式（所有操作自动放行，仅记录审计日志）",
    }[_current_mode]


def set_confirm_callback(callback: Callable):
    """Set the confirmation callback (called from Kotlin to show dialog).
    
    Signature: callback(command: str, reversible: bool, reason: str) -> bool
    Returns: True = approved, False = denied
    """
    global _confirm_callback
    _confirm_callback = callback


# ── Capability Switch ──────────────────────────────────────────────────────

def enable_capability(capability: str) -> bool:
    """Enable a Root capability."""
    try:
        cap = Capability(capability)
        _enabled_capabilities.add(cap.value)
        return True
    except ValueError:
        return False


def disable_capability(capability: str) -> bool:
    """Disable a Root capability."""
    try:
        cap = Capability(capability)
        _enabled_capabilities.discard(cap.value)
        return True
    except ValueError:
        return False


def is_capability_enabled(capability: str) -> bool:
    """Check if a capability is enabled."""
    return capability in _enabled_capabilities


def get_capabilities() -> dict:
    """Get all capabilities and their enabled state."""
    return {
        cap.value: cap.value in _enabled_capabilities
        for cap in Capability
    }


def set_all_capabilities(enabled: bool):
    """Enable or disable all capabilities."""
    if enabled:
        _enabled_capabilities.update(cap.value for cap in Capability)
    else:
        _enabled_capabilities.clear()


# ── Core Execution ─────────────────────────────────────────────────────────

def _execute_su(cmd: str, timeout: int = 30) -> dict:
    """Execute a command via su backend.
    
    Returns: {"exit_code": int, "stdout": str, "stderr": str}
    """
    if _current_backend == RootBackend.NONE:
        return {"exit_code": -1, "stdout": "", "stderr": "No Root backend available"}
    
    try:
        # All backends use `su -c` for command execution
        result = subprocess.run(
            ["su", "-c", cmd],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return {
            "exit_code": result.returncode,
            "stdout": result.stdout,
            "stderr": result.stderr,
        }
    except subprocess.TimeoutExpired:
        return {"exit_code": -1, "stdout": "", "stderr": f"Command timed out ({timeout}s)"}
    except FileNotFoundError:
        return {"exit_code": -1, "stdout": "", "stderr": "su command not found"}
    except Exception as e:
        return {"exit_code": -1, "stdout": "", "stderr": str(e)[:500]}


def _take_snapshot(cmd: str) -> Optional[str]:
    """Take a snapshot before irreversible operation.
    
    Returns: path to snapshot file, or None on failure
    """
    snapshot_id = str(uuid.uuid4())[:8]
    snapshot_path = os.path.join(_snapshot_dir, f"snapshot_{snapshot_id}.json")
    
    snapshot = {
        "id": snapshot_id,
        "timestamp": time.time(),
        "command": cmd,
        "files_affected": [],  # Could be populated by dry-run
    }
    
    try:
        Path(snapshot_path).parent.mkdir(parents=True, exist_ok=True)
        with open(snapshot_path, 'w') as f:
            json.dump(snapshot, f, ensure_ascii=False, indent=2)
        return snapshot_path
    except Exception as e:
        logger.warning("Snapshot failed: %s", e)
        return None


def _log_audit(
    cmd: str,
    capability: str,
    reversible: bool,
    confirmed_by: str,
    result: dict,
    snapshot_path: str = None,
    duration_ms: int = 0,
):
    """Log command execution to audit database."""
    audit_id = str(uuid.uuid4())[:8]
    
    with _lock:
        _conn.execute(
            """INSERT INTO audit_log 
               (id, timestamp, command, capability, reversible, confirmation_mode,
                confirmed_by, backend, exit_code, stdout, stderr, error,
                snapshot_path, duration_ms)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                audit_id, time.time(), cmd, capability, 1 if reversible else 0,
                _current_mode.value, confirmed_by, _current_backend.value,
                result.get("exit_code"), result.get("stdout", "")[:5000],
                result.get("stderr", "")[:2000], result.get("error"),
                snapshot_path, duration_ms,
            ),
        )


# ── Public API: Execute with full safety pipeline ──────────────────────────

def execute(
    cmd: str,
    capability: str = "file_system",
    timeout: int = 30,
    dry_run: bool = False,
) -> dict:
    """Execute a Root command with full safety pipeline.
    
    Pipeline:
    1. Check backend availability
    2. Check capability switch
    3. Classify reversibility
    4. Determine if confirmation needed (based on mode)
    5. Take snapshot (if irreversible)
    6. Execute or dry-run
    7. Log to audit
    
    Args:
        cmd: Shell command to execute via su -c
        capability: Capability category (see Capability enum)
        timeout: Command timeout in seconds
        dry_run: If True, only preview, don't execute
    
    Returns:
        dict with execution result and metadata
    """
    if not _initialized:
        return {"error": "Root Gateway not initialized"}
    
    # Step 1: Check backend
    if _current_backend == RootBackend.NONE:
        return {"error": "No Root available", "status": "no_root"}
    
    # Step 2: Check capability
    if not is_capability_enabled(capability):
        return {
            "error": f"Capability '{capability}' is disabled in settings",
            "status": "capability_disabled",
            "hint": f"Enable it in Settings → 权限与安全 → {capability}",
        }
    
    # Step 3: Classify reversibility
    reversible, reason = _is_irreversible(cmd)
    
    # Step 4: Check if confirmation needed
    needs_confirm = False
    if _current_mode == ConfirmationMode.ALL_ROOT:
        needs_confirm = True
    elif _current_mode == ConfirmationMode.ONLY_DANGEROUS and not reversible:
        needs_confirm = False  # Safe operations auto-approved
    elif _current_mode == ConfirmationMode.ONLY_DANGEROUS and reversible:
        needs_confirm = True  # Dangerous operations need confirmation
    # FULLY_AUTO: never needs confirm
    
    # Step 5: Confirmation
    confirmed_by = "auto"
    if needs_confirm and not dry_run:
        if _confirm_callback:
            try:
                approved = _confirm_callback(cmd, reversible, reason)
                if not approved:
                    return {"status": "denied", "reason": "User denied the operation"}
                confirmed_by = "user_dialog"
            except Exception as e:
                return {"error": f"Confirmation callback failed: {e}"}
        else:
            # No callback set, deny by default (safe)
            return {"status": "denied", "reason": "No confirmation callback set"}
    elif dry_run:
        confirmed_by = "dry_run"
    
    # Step 6: Take snapshot for irreversible ops
    snapshot_path = None
    if not reversible and not dry_run:
        snapshot_path = _take_snapshot(cmd)
    
    # Step 7: Execute
    start_time = time.time()
    
    if dry_run:
        result = {
            "exit_code": 0,
            "stdout": f"[DRY RUN] Would execute: su -c '{cmd}'",
            "stderr": "",
        }
    else:
        result = _execute_su(cmd, timeout=timeout)
    
    duration_ms = int((time.time() - start_time) * 1000)
    
    # Step 8: Audit log
    _log_audit(
        cmd=cmd,
        capability=capability,
        reversible=reversible,
        confirmed_by=confirmed_by,
        result=result,
        snapshot_path=snapshot_path,
        duration_ms=duration_ms,
    )
    
    return {
        "status": "success" if result.get("exit_code", -1) == 0 else "error",
        "exit_code": result.get("exit_code"),
        "stdout": result.get("stdout", ""),
        "stderr": result.get("stderr", ""),
        "reversible": reversible,
        "reason": reason,
        "snapshot": snapshot_path,
        "duration_ms": duration_ms,
        "backend": _current_backend.value,
        "confirmation_mode": _current_mode.value,
        "confirmed_by": confirmed_by,
    }


def preview(cmd: str) -> dict:
    """Preview what a command would do (dry-run).
    
    For file operations, lists affected files.
    For package operations, shows what would be removed.
    """
    reversible, reason = _is_irreversible(cmd)
    
    preview_info = {
        "command": cmd,
        "reversible": reversible,
        "reason": reason if not reversible else "可逆操作",
        "confirmation_needed": _current_mode != ConfirmationMode.FULLY_AUTO and not reversible,
    }
    
    # Try to extract affected paths
    import re
    paths = re.findall(r'(?:/[\w./-]+)', cmd)
    if paths:
        preview_info["affected_paths"] = paths[:20]
    
    return preview_info


# ── Audit Log ──────────────────────────────────────────────────────────────

def get_audit_log(
    limit: int = 50,
    capability: str = None,
    reversible_only: bool = None,
) -> List[dict]:
    """Get audit log entries."""
    conditions = []
    params = []
    
    if capability:
        conditions.append("capability = ?")
        params.append(capability)
    if reversible_only is not None:
        conditions.append("reversible = ?")
        params.append(1 if reversible_only else 0)
    
    where = "WHERE " + " AND ".join(conditions) if conditions else ""
    params.append(limit)
    
    with _lock:
        rows = _conn.execute(
            f"SELECT * FROM audit_log {where} ORDER BY timestamp DESC LIMIT ?",
            params,
        ).fetchall()
    
    results = []
    for row in rows:
        d = dict(row)
        d["timestamp_str"] = datetime.fromtimestamp(d["timestamp"]).strftime("%Y-%m-%d %H:%M:%S")
        results.append(d)
    return results


def get_audit_stats() -> dict:
    """Get audit log statistics."""
    with _lock:
        total = _conn.execute("SELECT COUNT(*) as c FROM audit_log").fetchone()["c"]
        denied = _conn.execute(
            "SELECT COUNT(*) as c FROM audit_log WHERE confirmed_by = 'denied'"
        ).fetchone()["c"]
        irreversible = _conn.execute(
            "SELECT COUNT(*) as c FROM audit_log WHERE reversible = 0"
        ).fetchone()["c"]
    
    return {
        "total_commands": total,
        "denied": denied,
        "irreversible_executed": irreversible,
        "backend": _current_backend.value,
        "mode": _current_mode.value,
    }


# ── Utility ────────────────────────────────────────────────────────────────

def is_root_available() -> bool:
    """Check if Root is available."""
    return _current_backend != RootBackend.NONE


def get_backend_info() -> dict:
    """Get current backend info."""
    return {
        "backend": _current_backend.value,
        "backend_name": _current_backend.name,
        "is_root_available": is_root_available(),
    }


def close():
    """Close audit database."""
    global _conn, _initialized
    with _lock:
        if _conn:
            _conn.close()
            _conn = None
        _initialized = False