"""
Hermes Android Gateway Bridge — external platform integration placeholder.

This is a DEFERRED module. The interface is defined here for future use.
When a user wants to connect Hermes to Telegram/Discord/WhatsApp/etc,
they can implement a PlatformAdapter and register it.

For now, the App's native UI is the primary and only interface.
"""

import json
import logging
import os
from typing import Any, Callable, Dict, List, Optional

logger = logging.getLogger(__name__)

# ── Platform Adapter Interface ─────────────────────────────────────────────

class PlatformAdapter:
    """Base class for platform integrations.
    
    To connect a new platform (Telegram, Discord, etc.):
    1. Subclass PlatformAdapter
    2. Implement connect(), send_message(), disconnect()
    3. Call register_adapter() to register it
    """
    
    @property
    def name(self) -> str:
        """Platform name (e.g., 'telegram', 'discord')."""
        raise NotImplementedError
    
    @property
    def description(self) -> str:
        """Human-readable description."""
        return ""
    
    def connect(self, config: dict) -> dict:
        """Connect to the platform.
        
        Args:
            config: Platform-specific config (tokens, endpoints, etc.)
        
        Returns:
            {"status": "connected"|"error", "error": str}
        """
        raise NotImplementedError
    
    def send_message(self, recipient: str, content: str, **kwargs) -> dict:
        """Send a message to a recipient on this platform.
        
        Args:
            recipient: Platform-specific recipient ID
            content: Message text
        
        Returns:
            {"status": "sent"|"error", "message_id": str}
        """
        raise NotImplementedError
    
    def disconnect(self):
        """Disconnect from the platform."""
        pass
    
    def is_connected(self) -> bool:
        """Check if currently connected."""
        return False
    
    def get_config_schema(self) -> List[dict]:
        """Return configuration fields needed for setup.
        
        Returns list of:
        {"name": "bot_token", "type": "string", "required": True, "description": "..."}
        """
        return []


# ── Registry ───────────────────────────────────────────────────────────────

_adapters: Dict[str, PlatformAdapter] = {}
_on_message_callback: Optional[Callable] = None


def register_adapter(adapter: PlatformAdapter) -> dict:
    """Register a platform adapter.
    
    Args:
        adapter: PlatformAdapter instance
    
    Returns:
        dict with registration status
    """
    _adapters[adapter.name] = adapter
    logger.info("Registered platform adapter: %s", adapter.name)
    return {"name": adapter.name, "status": "registered"}


def unregister_adapter(name: str) -> bool:
    """Unregister a platform adapter."""
    if name in _adapters:
        adapter = _adapters[name]
        adapter.disconnect()
        del _adapters[name]
        return True
    return False


def get_adapter(name: str) -> Optional[PlatformAdapter]:
    """Get a registered adapter by name."""
    return _adapters.get(name)


def list_adapters() -> List[dict]:
    """List all registered adapters."""
    return [
        {
            "name": a.name,
            "description": a.description,
            "connected": a.is_connected(),
            "config_schema": a.get_config_schema(),
        }
        for a in _adapters.values()
    ]


def set_on_message_callback(callback: Callable):
    """Set callback for incoming messages from any platform.
    
    Signature: callback(platform: str, sender: str, content: str, metadata: dict)
    """
    global _on_message_callback
    _on_message_callback = callback


def broadcast(content: str, exclude_platform: str = None) -> dict:
    """Send a message to all connected platforms.
    
    Args:
        content: Message text
        exclude_platform: Platform to exclude (e.g., source platform)
    
    Returns:
        dict with results per platform
    """
    results = {}
    for name, adapter in _adapters.items():
        if name == exclude_platform:
            continue
        if not adapter.is_connected():
            results[name] = {"status": "skipped", "reason": "not_connected"}
            continue
        try:
            result = adapter.send_message("*", content)  # Broadcast
            results[name] = result
        except Exception as e:
            results[name] = {"status": "error", "error": str(e)[:200]}
    
    return {"platforms": len(results), "results": results}


def get_stats() -> dict:
    """Get gateway statistics."""
    connected = sum(1 for a in _adapters.values() if a.is_connected())
    return {
        "total_adapters": len(_adapters),
        "connected": connected,
        "adapter_names": list(_adapters.keys()),
    }