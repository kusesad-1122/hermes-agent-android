"""
Hermes Agent Android Bridge

This module is the entry point called from Kotlin via Chaquopy.
It initializes the Agent and provides methods callable from Android.
"""

import os
import sys
import threading

# Hermes home directory in Android app's private storage
HERMES_HOME = os.environ.get("HERMES_HOME", "/data/data/com.hermes.agent/files/.hermes")

# Initialize Hermes home
os.makedirs(HERMES_HOME, exist_ok=True)
os.environ["HERMES_HOME"] = HERMES_HOME


def get_version():
    """Return Hermes version string."""
    return "0.1.0-android-mvp"


def get_python_info():
    """Return Python runtime info for diagnostics."""
    return {
        "version": sys.version,
        "platform": sys.platform,
        "executable": sys.executable,
        "hermes_home": HERMES_HOME,
    }


def test_imports():
    """Test that core Hermes modules can be imported."""
    results = {}
    modules = [
        "hermes_constants",
        "hermes_state",
        "toolsets",
        "tools.registry",
        "agent",
        "providers",
    ]
    for mod_name in modules:
        try:
            __import__(mod_name)
            results[mod_name] = "OK"
        except Exception as e:
            results[mod_name] = f"FAIL: {e}"
    return results