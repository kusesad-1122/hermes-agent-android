"""
Hermes Android Silent Installer — APK installation via Root.

Uses root_gateway.execute() for safe APK installation.
Supports: install, uninstall, list packages, get package info.
"""

import json
import logging
import os
import re
import subprocess
from typing import Optional, Dict, List, Any

logger = logging.getLogger(__name__)

# Import root_gateway for safe execution
_root_gateway = None


def initialize():
    """Initialize the silent installer."""
    global _root_gateway
    try:
        import root_gateway
        _root_gateway = root_gateway
    except ImportError:
        logger.error("root_gateway not available")


def _execute(cmd: str, timeout: int = 120) -> dict:
    """Execute a command via root_gateway."""
    if _root_gateway and _root_gateway.is_root_available():
        return _root_gateway.execute(
            cmd,
            capability="app_management",
            timeout=timeout,
        )
    else:
        # Fallback: direct pm command (no Root, uses ADB-level)
        try:
            result = subprocess.run(
                cmd.split(), capture_output=True, text=True, timeout=timeout
            )
            return {
                "status": "success" if result.returncode == 0 else "error",
                "exit_code": result.returncode,
                "stdout": result.stdout,
                "stderr": result.stderr,
            }
        except Exception as e:
            return {"status": "error", "error": str(e)}


def install_apk(apk_path: str, replace: bool = True, grant_permissions: bool = True) -> dict:
    """Silently install an APK via Root.
    
    Args:
        apk_path: Path to the APK file
        replace: Replace existing installation
        grant_permissions: Auto-grant all permissions
    
    Returns:
        dict with installation result
    """
    if not os.path.exists(apk_path):
        return {"error": f"APK not found: {apk_path}"}
    
    flags = []
    if replace:
        flags.append("-r")
    if grant_permissions:
        flags.append("-g")
    
    cmd = f"pm install {' '.join(flags)} {apk_path}"
    result = _execute(cmd, timeout=120)
    
    # Check for success in output
    stdout = result.get("stdout", "")
    if "Success" in stdout:
        result["installed"] = True
        # Try to extract package name
        pkg = _get_apk_package(apk_path)
        if pkg:
            result["package"] = pkg
    else:
        result["installed"] = False
    
    return result


def uninstall_apk(package: str, keep_data: bool = False) -> dict:
    """Silently uninstall an APK via Root.
    
    Args:
        package: Package name (e.g., com.example.app)
        keep_data: Keep app data after uninstall
    """
    flags = "" if not keep_data else " -k"
    cmd = f"pm uninstall{flags} {package}"
    return _execute(cmd, timeout=60)


def list_packages(filter_str: str = None, system: bool = False) -> List[str]:
    """List installed packages.
    
    Args:
        filter_str: Optional filter string
        system: Include system packages
    """
    cmd = "pm list packages"
    if not system:
        cmd += " -3"  # Third-party only
    if filter_str:
        cmd += f" {filter_str}"
    
    result = _execute(cmd, timeout=30)
    stdout = result.get("stdout", "")
    
    packages = []
    for line in stdout.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            packages.append(line[8:])
    
    return packages


def get_package_info(package: str) -> Optional[dict]:
    """Get detailed info about an installed package."""
    result = _execute(f"dumpsys package {package}", timeout=15)
    stdout = result.get("stdout", "")
    
    if not stdout or "Unable to find" in stdout:
        return None
    
    info = {"package": package}
    
    # Parse key fields
    version_match = re.search(r"versionName=(\S+)", stdout)
    if version_match:
        info["version"] = version_match.group(1)
    
    code_match = re.search(r"versionCode=(\d+)", stdout)
    if code_match:
        info["version_code"] = code_match.group(1)
    
    # First install time
    time_match = re.search(r"firstInstallTime=(\S+)", stdout)
    if time_match:
        info["first_install"] = time_match.group(1)
    
    # Permissions
    perms = []
    in_perms = False
    for line in stdout.splitlines():
        if "requested permissions:" in line.lower():
            in_perms = True
            continue
        if in_perms:
            if line.strip().startswith("android.permission.") or line.strip().startswith("com."):
                perms.append(line.strip())
            elif line.strip() and not line.strip().startswith(" "):
                in_perms = False
    
    if perms:
        info["permissions"] = perms
    
    return info


def is_installed(package: str) -> bool:
    """Check if a package is installed."""
    result = _execute(f"pm path {package}", timeout=10)
    return bool(result.get("stdout", "").strip())


def get_apk_path(package: str) -> Optional[str]:
    """Get the APK file path for an installed package."""
    result = _execute(f"pm path {package}", timeout=10)
    stdout = result.get("stdout", "").strip()
    if stdout.startswith("package:"):
        return stdout[8:]
    return None


def _get_apk_package(apk_path: str) -> Optional[str]:
    """Extract package name from APK file using aapt or dump."""
    try:
        result = subprocess.run(
            ["aapt", "dump", "badging", apk_path],
            capture_output=True, text=True, timeout=15
        )
        if result.returncode == 0:
            match = re.search(r"package: name='([^']+)'", result.stdout)
            if match:
                return match.group(1)
    except:
        pass
    return None


def get_stats() -> dict:
    """Get installation statistics."""
    third_party = list_packages(system=False)
    return {
        "third_party_apps": len(third_party),
    }