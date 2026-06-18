"""
Hermes Android App Data Backup — backup/restore app data via Root.

Uses root_gateway.execute() for safe data operations.
"""

import json
import logging
import os
import subprocess
import time
from pathlib import Path
from typing import List, Optional

logger = logging.getLogger(__name__)

_root_gateway = None
_backup_dir = None


def initialize(backup_dir: str = None):
    """Initialize the backup system."""
    global _root_gateway, _backup_dir
    _backup_dir = backup_dir or os.path.join(
        os.environ.get("HOME", "/data"), "hermes_backups"
    )
    Path(_backup_dir).mkdir(parents=True, exist_ok=True)
    try:
        import root_gateway
        _root_gateway = root_gateway
    except ImportError:
        logger.error("root_gateway not available")


def _execute(cmd: str, timeout: int = 120) -> dict:
    if _root_gateway and _root_gateway.is_root_available():
        return _root_gateway.execute(cmd, capability="backup_restore", timeout=timeout)
    try:
        result = subprocess.run(cmd.split(), capture_output=True, text=True, timeout=timeout)
        return {"status": "success" if result.returncode == 0 else "error",
                "exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    except Exception as e:
        return {"status": "error", "error": str(e)}


def backup_app(package: str, include_apk: bool = False) -> dict:
    """Backup an app's data.
    
    Args:
        package: Package name (e.g., com.example.app)
        include_apk: Also backup the APK file
    
    Returns:
        dict with backup path and size
    """
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    backup_path = os.path.join(_backup_dir, f"{package}_{timestamp}")
    Path(backup_path).mkdir(parents=True, exist_ok=True)
    
    # Use tar to backup /data/data/<package>
    data_dir = f"/data/data/{package}"
    tar_file = os.path.join(backup_path, "data.tar.gz")
    
    result = _execute(f"tar czf {tar_file} -C {data_dir} .", timeout=300)
    
    if result.get("status") != "success":
        return {"error": f"Backup failed: {result.get('stderr', '')[:500]}"}
    
    # Backup APK if requested
    apk_result = _execute(f"pm path {package}", timeout=10)
    apk_path = apk_result.get("stdout", "").strip()
    if include_apk and apk_path.startswith("package:"):
        apk_src = apk_path[8:]
        apk_dst = os.path.join(backup_path, "base.apk")
        _execute(f"cp {apk_src} {apk_dst}", timeout=120)
    
    # Save metadata
    meta = {
        "package": package,
        "timestamp": time.time(),
        "backup_path": backup_path,
        "include_apk": include_apk,
    }
    meta_file = os.path.join(backup_path, "meta.json")
    with open(meta_file, 'w') as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    
    # Get size
    size_result = _execute(f"du -sh {tar_file}", timeout=10)
    size_str = size_result.get("stdout", "").split()[0] if size_result.get("stdout") else "unknown"
    
    return {
        "status": "success",
        "package": package,
        "backup_path": backup_path,
        "size": size_str,
    }


def restore_app(package: str, backup_path: str) -> dict:
    """Restore an app's data from backup.
    
    Args:
        package: Package name
        backup_path: Path to the backup directory
    """
    tar_file = os.path.join(backup_path, "data.tar.gz")
    if not os.path.exists(tar_file):
        return {"error": f"Backup file not found: {tar_file}"}
    
    data_dir = f"/data/data/{package}"
    
    # Clear existing data first
    _execute(f"rm -rf {data_dir}/*", timeout=30)
    
    # Extract backup
    result = _execute(f"tar xzf {tar_file} -C {data_dir}", timeout=300)
    
    if result.get("status") != "success":
        return {"error": f"Restore failed: {result.get('stderr', '')[:500]}"}
    
    # Fix permissions
    _execute(f"chown -R $(stat -c '%u:%g' {data_dir}) {data_dir}", timeout=30)
    
    # Restore APK if present
    apk_file = os.path.join(backup_path, "base.apk")
    if os.path.exists(apk_file):
        install_result = _execute(f"pm install -r -g {apk_file}", timeout=120)
        if "Success" not in install_result.get("stdout", ""):
            return {"warning": "Data restored but APK install failed", "status": "partial"}
    
    return {"status": "success", "package": package, "restored_from": backup_path}


def clear_app_data(package: str) -> dict:
    """Clear all data for an app (equivalent to Settings → Clear Data)."""
    result = _execute(f"pm clear {package}", timeout=30)
    return {
        "status": "success" if result.get("status") == "success" else "error",
        "package": package,
        "stdout": result.get("stdout", ""),
    }


def list_backups(package: str = None) -> List[dict]:
    """List available backups."""
    backups = []
    if not _backup_dir or not os.path.exists(_backup_dir):
        return backups
    
    for item in os.listdir(_backup_dir):
        meta_file = os.path.join(_backup_dir, item, "meta.json")
        if os.path.exists(meta_file):
            try:
                with open(meta_file) as f:
                    meta = json.load(f)
                if package and meta.get("package") != package:
                    continue
                meta["dir"] = os.path.join(_backup_dir, item)
                backups.append(meta)
            except:
                pass
    
    return sorted(backups, key=lambda x: x.get("timestamp", 0), reverse=True)


def delete_backup(backup_path: str) -> dict:
    """Delete a backup."""
    import shutil
    try:
        shutil.rmtree(backup_path)
        return {"status": "success", "deleted": backup_path}
    except Exception as e:
        return {"status": "error", "error": str(e)}


def get_stats() -> dict:
    """Get backup statistics."""
    backups = list_backups()
    return {
        "total_backups": len(backups),
        "backup_dir": _backup_dir,
    }