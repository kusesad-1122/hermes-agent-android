"""
Hermes Android Privacy Auditor — privacy monitoring via Root.

Monitors:
1. Permission usage (which apps access camera/mic/location/contacts)
2. Network connections (active connections by app)
3. Logcat analysis for privacy-relevant events
"""

import json
import logging
import os
import re
import subprocess
import time
from typing import Dict, List, Optional

logger = logging.getLogger(__name__)

_root_gateway = None


def initialize():
    global _root_gateway
    try:
        import root_gateway
        _root_gateway = root_gateway
    except ImportError:
        logger.error("root_gateway not available")


def _execute(cmd: str, timeout: int = 30) -> dict:
    if _root_gateway and _root_gateway.is_root_available():
        return _root_gateway.execute(cmd, capability="privacy", timeout=timeout)
    try:
        result = subprocess.run(cmd.split(), capture_output=True, text=True, timeout=timeout)
        return {"status": "success" if result.returncode == 0 else "error",
                "exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    except Exception as e:
        return {"status": "error", "error": str(e)}


# ── Sensitive Permissions ──────────────────────────────────────────────────

SENSITIVE_PERMISSIONS = {
    "CAMERA": "android.permission.CAMERA",
    "RECORD_AUDIO": "android.permission.RECORD_AUDIO",
    "ACCESS_FINE_LOCATION": "android.permission.ACCESS_FINE_LOCATION",
    "ACCESS_COARSE_LOCATION": "android.permission.ACCESS_COARSE_LOCATION",
    "READ_CONTACTS": "android.permission.READ_CONTACTS",
    "READ_SMS": "android.permission.READ_SMS",
    "READ_CALL_LOG": "android.permission.READ_CALL_LOG",
    "READ_PHONE_STATE": "android.permission.READ_PHONE_STATE",
    "READ_EXTERNAL_STORAGE": "android.permission.READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE": "android.permission.WRITE_EXTERNAL_STORAGE",
    "ACCESS_BACKGROUND_LOCATION": "android.permission.ACCESS_BACKGROUND_LOCATION",
}


def scan_app_permissions(package: str) -> dict:
    """Scan a specific app's permissions."""
    result = _execute(f"dumpsys package {package}", timeout=15)
    stdout = result.get("stdout", "")
    
    if not stdout or "Unable to find" in stdout:
        return {"error": f"Package not found: {package}"}
    
    granted = []
    denied = []
    
    in_granted = False
    for line in stdout.splitlines():
        line = line.strip()
        if "granted=true" in line.lower():
            # Extract permission name
            perm = line.split(":")[0].strip() if ":" in line else line.split()[0]
            granted.append(perm)
        if "runtime permissions:" in line.lower():
            in_granted = True
    
    # Filter for sensitive permissions
    sensitive_granted = []
    for perm in granted:
        for cat, full_perm in SENSITIVE_PERMISSIONS.items():
            if full_perm in perm:
                sensitive_granted.append({"category": cat, "permission": perm})
    
    return {
        "package": package,
        "total_granted": len(granted),
        "sensitive_granted": sensitive_granted,
        "all_granted": granted,
    }


def scan_all_sensitive_apps() -> List[dict]:
    """Find all apps that have sensitive permissions granted."""
    result = _execute("pm list packages -3", timeout=15)
    stdout = result.get("stdout", "")
    
    packages = []
    for line in stdout.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            packages.append(line[8:])
    
    apps_with_sensitive = []
    for pkg in packages:
        info = scan_app_permissions(pkg)
        if info.get("sensitive_granted"):
            apps_with_sensitive.append({
                "package": pkg,
                "sensitive_count": len(info["sensitive_granted"]),
                "sensitive": info["sensitive_granted"],
            })
    
    return sorted(apps_with_sensitive, key=lambda x: x["sensitive_count"], reverse=True)


# ── Network Monitoring ─────────────────────────────────────────────────────

def get_active_connections() -> List[dict]:
    """Get active network connections with process info."""
    result = _execute("cat /proc/net/tcp", timeout=10)
    stdout = result.get("stdout", "")
    
    connections = []
    for line in stdout.splitlines()[1:]:  # Skip header
        parts = line.split()
        if len(parts) >= 8:
            local = parts[1]
            remote = parts[2]
            state = parts[3]
            uid = parts[7]
            
            # Parse IP:port
            local_parts = local.split(":")
            remote_parts = remote.split(":")
            
            connections.append({
                "local": f"{_hex_to_ip(local_parts[0])}:{int(local_parts[1], 16)}" if len(local_parts) == 2 else local,
                "remote": f"{_hex_to_ip(remote_parts[0])}:{int(remote_parts[1], 16)}" if len(remote_parts) == 2 else remote,
                "state": _tcp_state(state),
                "uid": uid,
            })
    
    return connections[:100]  # Limit to 100


def _hex_to_ip(hex_ip: str) -> str:
    """Convert hex IP to dotted notation."""
    try:
        return ".".join(str(int(hex_ip[i:i+2], 16)) for i in (6, 4, 2, 0))
    except:
        return hex_ip


def _tcp_state(state_hex: str) -> str:
    states = {
        "01": "ESTABLISHED", "02": "SYN_SENT", "03": "SYN_RECV",
        "04": "FIN_WAIT1", "05": "FIN_WAIT2", "06": "TIME_WAIT",
        "07": "CLOSE", "08": "CLOSE_WAIT", "09": "LAST_ACK",
        "0A": "LISTEN", "0B": "CLOSING",
    }
    return states.get(state_hex.upper(), state_hex)


# ── Logcat Analysis ────────────────────────────────────────────────────────

def scan_logcat_privacy(duration_seconds: int = 10) -> List[dict]:
    """Scan logcat for privacy-relevant events.
    
    Looks for: permission checks, camera/mic access, location requests.
    """
    result = _execute(
        f"logcat -d -t {duration_seconds} | grep -iE '(permission|camera|microphone|location|contacts)'",
        timeout=duration_seconds + 10,
    )
    stdout = result.get("stdout", "")
    
    events = []
    for line in stdout.splitlines()[:200]:
        events.append({"raw": line.strip()[:300]})
    
    return events


# ── Privacy Report ─────────────────────────────────────────────────────────

def generate_privacy_report() -> dict:
    """Generate a comprehensive privacy audit report."""
    report = {
        "timestamp": time.time(),
        "apps_with_sensitive_permissions": scan_all_sensitive_apps(),
        "active_connections": len(get_active_connections()),
    }
    
    return report


def get_stats() -> dict:
    return {"status": "ready"}