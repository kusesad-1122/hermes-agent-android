"""
Hermes Android Binary Toolbox — bundled binaries + doctor self-check.

Detects available system binaries (rg, git, ffmpeg, etc.) and manages
bundled ARM64 binaries for tools that need them on Android.
"""

import json
import logging
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional

logger = logging.getLogger(__name__)

# ── Known tools and their commands ─────────────────────────────────────────
TOOL_REGISTRY = {
    "ripgrep": {
        "commands": ["rg"],
        "description": "Code search (grep alternative)",
        "required": True,
        "android_hint": "Pre-built ARM64 binary or Termux package",
    },
    "git": {
        "commands": ["git"],
        "description": "Version control",
        "required": True,
        "android_hint": "Pre-built ARM64 binary or Termux package",
    },
    "ffmpeg": {
        "commands": ["ffmpeg", "ffprobe"],
        "description": "Media processing",
        "required": False,
        "android_hint": "Pre-built ARM64 binary",
    },
    "node": {
        "commands": ["node", "npm", "npx"],
        "description": "JavaScript runtime (for MCP stdio servers)",
        "required": False,
        "android_hint": "Termux package or bundled binary",
    },
    "curl": {
        "commands": ["curl"],
        "description": "HTTP client",
        "required": False,
        "android_hint": "Usually available on Android",
    },
    "python3": {
        "commands": ["python3"],
        "description": "Python runtime",
        "required": True,
        "android_hint": "Chaquopy provides this",
    },
    "sqlite3": {
        "commands": ["sqlite3"],
        "description": "SQLite CLI",
        "required": False,
        "android_hint": "Usually available or bundled with Chaquopy",
    },
    "find": {
        "commands": ["find"],
        "description": "File search",
        "required": True,
        "android_hint": "Standard Android utility",
    },
    "grep": {
        "commands": ["grep"],
        "description": "Text search",
        "required": True,
        "android_hint": "Standard Android utility",
    },
}

_initialized = False
_binary_cache: Dict[str, dict] = {}


def initialize(bundled_dir: str = None) -> dict:
    """Initialize the binary toolbox.
    
    Args:
        bundled_dir: Path to bundled ARM64 binaries directory
    
    Returns:
        dict with status and available tools count
    """
    global _initialized, _binary_cache
    
    _binary_cache = {}
    
    for tool_name, info in TOOL_REGISTRY.items():
        for cmd in info["commands"]:
            found_path = shutil.which(cmd)
            _binary_cache[cmd] = {
                "name": tool_name,
                "command": cmd,
                "found": found_path is not None,
                "path": found_path,
                "required": info["required"],
                "description": info["description"],
            }
    
    _initialized = True
    stats = get_stats()
    
    return {
        "status": "initialized",
        "total_tools": len(TOOL_REGISTRY),
        "available": stats["available"],
        "missing": stats["missing"],
    }


def check_binary(command: str) -> dict:
    """Check if a binary is available.
    
    Returns:
        dict with found, path, version info
    """
    path = shutil.which(command)
    result = {
        "command": command,
        "found": path is not None,
        "path": path,
    }
    
    if path:
        try:
            version = subprocess.run(
                [command, "--version"],
                capture_output=True, text=True, timeout=5
            )
            result["version"] = (version.stdout + version.stderr).strip()[:200]
        except:
            result["version"] = "unknown"
    
    return result


def list_binaries() -> List[dict]:
    """List all known binaries and their status."""
    results = []
    for tool_name, info in TOOL_REGISTRY.items():
        found_paths = []
        for cmd in info["commands"]:
            path = shutil.which(cmd)
            if path:
                found_paths.append(path)
        
        results.append({
            "tool": tool_name,
            "commands": info["commands"],
            "description": info["description"],
            "required": info["required"],
            "available": len(found_paths) > 0,
            "paths": found_paths,
        })
    
    return results


def get_missing_required() -> List[str]:
    """Get list of missing required tools."""
    missing = []
    for tool_name, info in TOOL_REGISTRY.items():
        if not info["required"]:
            continue
        found = any(shutil.which(cmd) for cmd in info["commands"])
        if not found:
            missing.append(tool_name)
    return missing


def get_stats() -> dict:
    """Get toolbox statistics."""
    available = 0
    missing = 0
    required_missing = 0
    
    for tool_name, info in TOOL_REGISTRY.items():
        found = any(shutil.which(cmd) for cmd in info["commands"])
        if found:
            available += 1
        else:
            missing += 1
            if info["required"]:
                required_missing += 1
    
    return {
        "total_tools": len(TOOL_REGISTRY),
        "available": available,
        "missing": missing,
        "required_missing": required_missing,
        "all_required_met": required_missing == 0,
    }


def run_doctor() -> dict:
    """Run a full diagnostic check.
    
    Returns:
        dict with detailed diagnostic report
    """
    report = {
        "status": "ok",
        "platform": sys.platform,
        "python_version": sys.version,
        "tools": [],
        "warnings": [],
        "errors": [],
    }
    
    for tool_name, info in TOOL_REGISTRY.items():
        tool_report = {
            "name": tool_name,
            "required": info["required"],
            "commands": [],
            "status": "missing",
        }
        
        for cmd in info["commands"]:
            result = check_binary(cmd)
            tool_report["commands"].append(result)
        
        found = any(c["found"] for c in tool_report["commands"])
        if found:
            tool_report["status"] = "available"
        elif info["required"]:
            tool_report["status"] = "required_missing"
            report["errors"].append(f"Required tool '{tool_name}' not found")
        else:
            tool_report["status"] = "optional_missing"
            report["warnings"].append(f"Optional tool '{tool_name}' not found")
        
        report["tools"].append(tool_report)
    
    if report["errors"]:
        report["status"] = "error"
    elif report["warnings"]:
        report["status"] = "warning"
    
    return report


def doctor_text() -> str:
    """Run doctor and return formatted text report."""
    report = run_doctor()
    lines = []
    lines.append(f"=== Hermes Doctor ===")
    lines.append(f"Platform: {report['platform']}")
    lines.append(f"Python: {report['python_version'][:50]}")
    lines.append("")
    
    for tool in report["tools"]:
        if tool["status"] == "available":
            lines.append(f"  ✅ {tool['name']}: available")
        elif tool["status"] == "required_missing":
            lines.append(f"  ❌ {tool['name']}: MISSING (required)")
        else:
            lines.append(f"  ⚠️  {tool['name']}: not found (optional)")
    
    lines.append("")
    
    if report["errors"]:
        lines.append("ERRORS:")
        for e in report["errors"]:
            lines.append(f"  ! {e}")
    
    if report["warnings"]:
        lines.append("WARNINGS:")
        for w in report["warnings"]:
            lines.append(f"  - {w}")
    
    if report["status"] == "ok":
        lines.append("All checks passed!")
    elif report["status"] == "warning":
        lines.append(f"Some optional tools missing ({len(report['warnings'])})")
    else:
        lines.append(f"Required tools missing ({len(report['errors'])})")
    
    return "\n".join(lines)