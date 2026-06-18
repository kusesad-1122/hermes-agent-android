"""
Hermes Android MCP Client — lightweight HTTP/SSE MCP client.

Simplified from the full Hermes MCP system (tools/mcp_tool.py 4156 lines +
hermes_cli/mcp_config.py + mcp_startup.py + mcp_security.py + mcp_catalog.py)
into a single ~400 line module focused on Android MVP needs.

Supports HTTP Streamable HTTP transport (the modern MCP standard).
Stdio transport is not supported on Android (no subprocess management).

MCP (Model Context Protocol) lets the agent connect to external tool servers
and use their tools as if they were built-in.
"""

import json
import logging
import os
import re
import threading
import time
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

import httpx

logger = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────
_servers: Dict[str, Dict[str, Any]] = {}  # name -> config
_tools: Dict[str, Dict[str, Any]] = {}   # "server__tool" -> tool schema
_connected: Dict[str, bool] = {}          # name -> connected
_initialized = False
_lock = threading.Lock()

# Credentials pattern for error sanitization
_CRED_PATTERN = re.compile(
    r"(?:sk-[A-Za-z0-9_]{1,255}|Bearer\s+\S+|token=[^\s&]+|API_KEY=[^\s&]+)",
    re.IGNORECASE,
)

# ── Initialization ─────────────────────────────────────────────────────────

def initialize(servers_config: List[Dict[str, Any]] = None) -> dict:
    """Initialize the MCP client with server configurations.
    
    Args:
        servers_config: List of server configs:
            [{"name": "server1", "url": "https://...", "headers": {...}, "timeout": 30}]
    
    Returns:
        dict with status info
    """
    global _initialized
    
    if servers_config:
        for cfg in servers_config:
            name = cfg.get("name", f"server_{len(_servers)}")
            _servers[name] = {
                "name": name,
                "url": cfg.get("url", ""),
                "headers": cfg.get("headers", {}),
                "timeout": cfg.get("timeout", 30),
                "connected": False,
                "tools": [],
                "error": None,
            }
    
    _initialized = True
    
    return {
        "status": "initialized",
        "servers": len(_servers),
        "tools": len(_tools),
    }


def add_server(
    name: str,
    url: str,
    headers: Dict[str, str] = None,
    timeout: int = 30,
) -> dict:
    """Add an MCP server configuration.
    
    Args:
        name: Server name
        url: MCP server URL (http/https)
        headers: Optional HTTP headers (e.g., Authorization)
        timeout: Request timeout in seconds
    
    Returns:
        dict with server info
    """
    with _lock:
        _servers[name] = {
            "name": name,
            "url": url,
            "headers": headers or {},
            "timeout": timeout,
            "connected": False,
            "tools": [],
            "error": None,
        }
    
    return {"name": name, "url": url, "status": "added"}


def remove_server(name: str) -> bool:
    """Remove an MCP server."""
    with _lock:
        if name in _servers:
            # Remove tools from this server
            to_remove = [k for k in _tools if k.startswith(f"{name}__")]
            for k in to_remove:
                del _tools[k]
            del _servers[name]
            _connected.pop(name, None)
            return True
    return False


# ── Connection & Discovery ─────────────────────────────────────────────────

def _sanitize_error(text: str) -> str:
    """Strip credentials from error messages."""
    return _CRED_PATTERN.sub("[REDACTED]", text)


def _mcp_request(
    url: str,
    method: str,
    params: dict = None,
    headers: dict = None,
    timeout: int = 30,
) -> dict:
    """Send a JSON-RPC request to an MCP server.
    
    Args:
        url: Server URL
        method: MCP method name (e.g., "initialize", "tools/list", "tools/call")
        params: Method parameters
        headers: HTTP headers
        timeout: Request timeout
    
    Returns:
        JSON-RPC response dict
    """
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": method,
    }
    if params:
        payload["params"] = params
    
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    
    with httpx.Client(timeout=timeout) as client:
        resp = client.post(url, json=payload, headers=req_headers)
        resp.raise_for_status()
        return resp.json()


def connect_server(name: str) -> dict:
    """Connect to an MCP server and discover its tools.
    
    Args:
        name: Server name
    
    Returns:
        dict with connection result and discovered tools
    """
    if name not in _servers:
        return {"error": f"Server '{name}' not configured"}
    
    config = _servers[name]
    url = config["url"]
    headers = config.get("headers", {})
    timeout = config.get("timeout", 30)
    
    try:
        # Validate URL
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https"):
            return {"error": f"Invalid URL scheme: {parsed.scheme} (must be http/https)"}
        
        # Step 1: Initialize
        init_result = _mcp_request(
            url, "initialize",
            params={
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "hermes-android", "version": "0.1.0"},
            },
            headers=headers,
            timeout=timeout,
        )
        
        if "error" in init_result:
            error_msg = _sanitize_error(str(init_result["error"]))
            config["error"] = error_msg
            return {"error": f"Initialize failed: {error_msg}"}
        
        # Step 2: Send initialized notification (no response expected)
        try:
            _mcp_request(url, "notifications/initialized", headers=headers, timeout=5)
        except:
            pass  # Notification may not return
        
        # Step 3: List tools
        tools_result = _mcp_request(
            url, "tools/list",
            headers=headers,
            timeout=timeout,
        )
        
        if "error" in tools_result:
            error_msg = _sanitize_error(str(tools_result["error"]))
            config["error"] = error_msg
            return {"error": f"tools/list failed: {error_msg}"}
        
        # Parse tools
        tools_data = tools_result.get("result", {})
        tools_list = tools_data.get("tools", [])
        
        config["tools"] = []
        with _lock:
            for tool in tools_list:
                tool_name = tool.get("name", "")
                full_name = f"{name}__{tool_name}"
                
                tool_schema = {
                    "name": full_name,
                    "original_name": tool_name,
                    "server": name,
                    "description": tool.get("description", ""),
                    "parameters": tool.get("inputSchema", {}),
                }
                
                config["tools"].append(tool_schema)
                _tools[full_name] = tool_schema
            
            config["connected"] = True
            config["error"] = None
            _connected[name] = True
        
        return {
            "status": "connected",
            "server": name,
            "tools_count": len(tools_list),
            "tools": [t.get("name", "") for t in tools_list],
        }
    
    except httpx.ConnectTimeout:
        error = f"Connection timeout ({timeout}s)"
        config["error"] = error
        return {"error": error}
    except httpx.ConnectError as e:
        error = _sanitize_error(f"Connection error: {e}")
        config["error"] = error
        return {"error": error}
    except Exception as e:
        error = _sanitize_error(f"{type(e).__name__}: {e}")
        config["error"] = error
        return {"error": error}


def connect_all() -> Dict[str, dict]:
    """Connect to all configured servers."""
    results = {}
    for name in _servers:
        results[name] = connect_server(name)
    return results


# ── Tool Calling ───────────────────────────────────────────────────────────

def call_tool(
    full_name: str,
    arguments: dict = None,
    timeout: int = None,
) -> dict:
    """Call an MCP tool.
    
    Args:
        full_name: Tool name in format "server__toolname"
        arguments: Tool arguments
        timeout: Override timeout (uses server default if None)
    
    Returns:
        dict with tool result
    """
    if full_name not in _tools:
        return {"error": f"Tool '{full_name}' not found. Available: {list(_tools.keys())}"}
    
    tool_info = _tools[full_name]
    server_name = tool_info["server"]
    original_name = tool_info["original_name"]
    
    if server_name not in _servers:
        return {"error": f"Server '{server_name}' not configured"}
    
    config = _servers[server_name]
    if not config.get("connected"):
        return {"error": f"Server '{server_name}' not connected. Call connect_server() first."}
    
    url = config["url"]
    headers = config.get("headers", {})
    call_timeout = timeout or config.get("timeout", 30)
    
    try:
        result = _mcp_request(
            url, "tools/call",
            params={
                "name": original_name,
                "arguments": arguments or {},
            },
            headers=headers,
            timeout=call_timeout,
        )
        
        if "error" in result:
            return {"error": _sanitize_error(str(result["error"]))}
        
        # Extract content from result
        content = result.get("result", {})
        content_items = content.get("content", [])
        
        # Combine text content
        text_parts = []
        for item in content_items:
            if isinstance(item, dict):
                if item.get("type") == "text":
                    text_parts.append(item.get("text", ""))
                elif item.get("type") == "image":
                    text_parts.append(f"[Image: {item.get('mimeType', 'unknown')}]")
                else:
                    text_parts.append(str(item))
            else:
                text_parts.append(str(item))
        
        return {
            "status": "success",
            "tool": full_name,
            "content": "\n".join(text_parts),
            "is_error": content.get("isError", False),
            "raw": content_items,
        }
    
    except httpx.TimeoutException:
        return {"error": f"Tool call timeout ({call_timeout}s)"}
    except Exception as e:
        return {"error": _sanitize_error(f"{type(e).__name__}: {e}")}


# ── Query Functions ────────────────────────────────────────────────────────

def list_servers() -> List[Dict[str, Any]]:
    """List all configured MCP servers."""
    return [
        {
            "name": cfg["name"],
            "url": cfg["url"],
            "connected": cfg.get("connected", False),
            "tools_count": len(cfg.get("tools", [])),
            "error": cfg.get("error"),
        }
        for cfg in _servers.values()
    ]


def list_tools(server: str = None) -> List[Dict[str, Any]]:
    """List all available MCP tools.
    
    Args:
        server: Optional server name filter
    
    Returns:
        List of tool schemas
    """
    if server:
        return [t for t in _tools.values() if t["server"] == server]
    return list(_tools.values())


def get_tool_schema(full_name: str) -> Optional[Dict[str, Any]]:
    """Get the schema for a specific tool."""
    return _tools.get(full_name)


def get_tool_schemas_for_agent() -> List[Dict[str, Any]]:
    """Get all MCP tool schemas formatted for the agent's tool list.
    
    Returns tools in OpenAI function calling format.
    """
    schemas = []
    for tool in _tools.values():
        schemas.append({
            "type": "function",
            "function": {
                "name": tool["name"],
                "description": tool["description"],
                "parameters": tool["parameters"],
            }
        })
    return schemas


# ── Statistics ─────────────────────────────────────────────────────────────

def get_stats() -> dict:
    """Get MCP client statistics."""
    connected_count = sum(1 for s in _servers.values() if s.get("connected"))
    
    return {
        "servers": len(_servers),
        "connected": connected_count,
        "tools": len(_tools),
        "tool_names": list(_tools.keys()),
    }


# ── Config persistence ─────────────────────────────────────────────────────

def export_config() -> List[Dict[str, Any]]:
    """Export server configurations for persistence."""
    configs = []
    for name, cfg in _servers.items():
        configs.append({
            "name": name,
            "url": cfg["url"],
            "headers": {k: v for k, v in cfg.get("headers", {}).items()
                       if k.lower() != "authorization"},  # Don't export secrets
            "timeout": cfg.get("timeout", 30),
        })
    return configs


def import_config(configs: List[Dict[str, Any]]) -> dict:
    """Import server configurations."""
    count = 0
    for cfg in configs:
        name = cfg.get("name")
        url = cfg.get("url")
        if name and url:
            add_server(
                name=name,
                url=url,
                headers=cfg.get("headers"),
                timeout=cfg.get("timeout", 30),
            )
            count += 1
    return {"imported": count}