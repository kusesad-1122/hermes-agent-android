"""
Hermes Android Agent Loop — lightweight httpx-based agent loop.

This replaces the full run_agent.py dependency chain (which requires
openai SDK → jiter Rust extension). Uses httpx directly against
OpenAI-compatible endpoints (DeepSeek, OpenRouter, GLM, etc.).

The loop:
1. Build messages (system + history + user input)
2. Send chat/completions request with tool definitions
3. If model returns tool_calls → execute tools → feed results back → repeat
4. If model returns plain text → done
5. Max iterations configurable (default 10)
"""

import json
import httpx
import time
import os
import traceback
from typing import Any, Optional

# Default provider config (overridden by Kotlin bridge)
_DEFAULT_CONFIG = {
    "base_url": "https://api.deepseek.com/v1",
    "api_key": "",
    "model": "deepseek-v4-flash",
    "max_iterations": 10,
    "max_tokens": 4096,
    "temperature": 0.7,
}

# Current active config (set from Kotlin)
_config: dict = {}

def configure(base_url: str, api_key: str, model: str = "", max_iterations: int = 10, max_tokens: int = 4096, temperature: float = 0.7):
    """Set the provider configuration (called from Kotlin)."""
    global _config
    _config = {
        "base_url": base_url.rstrip("/"),
        "api_key": api_key,
        "model": model or _DEFAULT_CONFIG["model"],
        "max_iterations": max_iterations,
        "max_tokens": max_tokens,
        "temperature": temperature,
    }
    return _config

def get_config():
    return _config or _DEFAULT_CONFIG.copy()


# ── System prompt ──────────────────────────────────────────────────────────
SYSTEM_PROMPT = """You are Hermes Agent, an AI assistant running on Android.
You can help the user with various tasks. Be helpful, concise, and accurate.
When you need to perform actions, use the available tools."""


# ── Tool definitions (minimal set for MVP) ─────────────────────────────────
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_device_info",
            "description": "Get basic Android device information (model, Android version, battery level).",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the contents of a file at the given path.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Absolute file path to read"}
                },
                "required": ["path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "execute_shell",
            "description": "Execute a shell command and return stdout+stderr.",
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "Shell command to execute"}
                },
                "required": ["command"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_web",
            "description": "Search the web for information.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Search query"}
                },
                "required": ["query"]
            }
        }
    }
]


# ── Tool execution (delegates to Kotlin via callbacks) ─────────────────────
# In the real app, tool results come from Kotlin. For MVP, we use stubs.
_tool_executor = None

def set_tool_executor(executor):
    """Set a callable that executes tools. Signature: executor(name, args) -> str"""
    global _tool_executor
    _tool_executor = executor

def _execute_tool(name: str, args: dict) -> str:
    """Execute a tool and return the result as a string."""
    if _tool_executor:
        try:
            return _tool_executor(name, args)
        except Exception as e:
            return f"[Tool Error] {name}: {e}"
    
    # Fallback stubs for testing
    if name == "get_device_info":
        return json.dumps({"platform": "Android", "python": "3.12", "note": "Tool executor not connected"})
    elif name == "read_file":
        path = args.get("path", "")
        try:
            with open(path, "r") as f:
                return f.read(10000)
        except Exception as e:
            return f"[Error] Cannot read {path}: {e}"
    elif name == "execute_shell":
        import subprocess
        try:
            r = subprocess.run(args.get("command", ""), shell=True, capture_output=True, text=True, timeout=10)
            return r.stdout + r.stderr
        except Exception as e:
            return f"[Error] {e}"
    else:
        return f"[Unknown tool: {name}]"


# ── Core agent loop ────────────────────────────────────────────────────────

def _send_chat_request(messages: list, stream: bool = False) -> Any:
    """Send a chat/completions request to the configured provider."""
    cfg = get_config()
    url = f"{cfg['base_url']}/chat/completions"
    headers = {
        "Authorization": f"Bearer {cfg['api_key']}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": cfg["model"],
        "messages": messages,
        "tools": TOOLS,
        "max_tokens": cfg["max_tokens"],
        "temperature": cfg["temperature"],
        "stream": stream
    }
    
    client = httpx.Client(timeout=60.0)
    
    if stream:
        return client.stream("POST", url, headers=headers, json=payload)
    else:
        resp = client.post(url, headers=headers, json=payload)
        resp.raise_for_status()
        return resp.json()


def run_agent(user_message: str, history: list = None, stream_callback=None) -> dict:
    """
    Run the agent loop for a single user message.
    
    Args:
        user_message: The user's input text
        history: Previous messages (list of {role, content, ...})
        stream_callback: Optional callable(delta_text: str) for streaming
    
    Returns:
        {
            "response": str,           # Final assistant text
            "tool_calls": list,        # All tool calls made
            "iterations": int,         # Number of loop iterations
            "total_tokens": int,       # Total tokens used
            "latency_ms": int,         # Total latency
            "error": str|None          # Error message if failed
        }
    """
    cfg = get_config()
    start_time = time.time()
    
    result = {
        "response": "",
        "tool_calls": [],
        "iterations": 0,
        "total_tokens": 0,
        "latency_ms": 0,
        "error": None
    }
    
    # Build message list
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    if history:
        messages.extend(history)
    messages.append({"role": "user", "content": user_message})
    
    try:
        for iteration in range(cfg["max_iterations"]):
            result["iterations"] = iteration + 1
            
            # Send request
            response_data = _send_chat_request(messages, stream=False)
            
            choice = response_data["choices"][0]
            message = choice["message"]
            finish_reason = choice.get("finish_reason", "")
            
            # Track tokens
            usage = response_data.get("usage", {})
            result["total_tokens"] += usage.get("total_tokens", 0)
            
            # No tool calls — we're done
            if not message.get("tool_calls"):
                result["response"] = message.get("content", "")
                
                # Stream callback for the final text
                if stream_callback and result["response"]:
                    stream_callback(result["response"])
                break
            
            # Has tool calls — execute them
            messages.append(message)  # Add assistant message with tool_calls
            
            for tc in message["tool_calls"]:
                func = tc["function"]
                tool_name = func["name"]
                try:
                    tool_args = json.loads(func["arguments"])
                except json.JSONDecodeError:
                    tool_args = {}
                
                result["tool_calls"].append({
                    "name": tool_name,
                    "arguments": tool_args,
                    "iteration": iteration + 1
                })
                
                # Execute
                tool_result = _execute_tool(tool_name, tool_args)
                
                # Feed result back
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "content": tool_result
                })
                
                if stream_callback:
                    stream_callback(f"[{tool_name}] ")
            
            # If max iterations hit
            if iteration == cfg["max_iterations"] - 1:
                result["response"] = "[Max iterations reached]"
                result["error"] = "max_iterations"
    
    except httpx.HTTPStatusError as e:
        result["error"] = f"HTTP {e.response.status_code}: {e.response.text[:200]}"
    except httpx.ConnectTimeout:
        result["error"] = "Connection timeout"
    except httpx.ConnectError as e:
        result["error"] = f"Connection error: {e}"
    except Exception as e:
        result["error"] = f"{type(e).__name__}: {e}"
    
    result["latency_ms"] = int((time.time() - start_time) * 1000)
    return result


def run_agent_streaming(user_message: str, history: list = None, on_token=None, on_tool=None):
    """
    Streaming version of the agent loop.
    
    Args:
        user_message: User input
        history: Previous messages
        on_token: callback(delta_text: str) — called for each streamed token
        on_tool: callback(tool_name: str, args: dict, result: str) — called when tool executes
    
    Yields:
        {"type": "token", "text": "..."} or {"type": "tool", ...} or {"type": "done", ...}
    """
    cfg = get_config()
    start_time = time.time()
    total_tokens = 0
    
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    if history:
        messages.extend(history)
    messages.append({"role": "user", "content": user_message})
    
    try:
        for iteration in range(cfg["max_iterations"]):
            # Stream the response
            url = f"{cfg['base_url']}/chat/completions"
            headers = {
                "Authorization": f"Bearer {cfg['api_key']}",
                "Content-Type": "application/json"
            }
            payload = {
                "model": cfg["model"],
                "messages": messages,
                "tools": TOOLS,
                "max_tokens": cfg["max_tokens"],
                "temperature": cfg["temperature"],
                "stream": True
            }
            
            tool_calls_buffer = {}
            assistant_content = []
            finish_reason = None
            
            with httpx.stream("POST", url, headers=headers, json=payload, timeout=60.0) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines():
                    if not line.startswith("data: ") or line == "data: [DONE]":
                        if line == "data: [DONE]":
                            break
                        continue
                    
                    try:
                        chunk = json.loads(line[6:])
                    except json.JSONDecodeError:
                        continue
                    
                    delta = chunk["choices"][0].get("delta", {})
                    finish_reason = chunk["choices"][0].get("finish_reason")
                    
                    # Usage in last chunk
                    if "usage" in chunk:
                        total_tokens += chunk["usage"].get("total_tokens", 0)
                    
                    # Content delta
                    if "content" in delta and delta["content"]:
                        text = delta["content"]
                        assistant_content.append(text)
                        if on_token:
                            on_token(text)
                        yield {"type": "token", "text": text}
                    
                    # Tool call delta
                    if "tool_calls" in delta:
                        for tc_delta in delta["tool_calls"]:
                            idx = tc_delta.get("index", 0)
                            if idx not in tool_calls_buffer:
                                tool_calls_buffer[idx] = {
                                    "id": tc_delta.get("id", ""),
                                    "function": {"name": "", "arguments": ""}
                                }
                            buf = tool_calls_buffer[idx]
                            if "id" in tc_delta and tc_delta["id"]:
                                buf["id"] = tc_delta["id"]
                            if "function" in tc_delta:
                                if "name" in tc_delta["function"] and tc_delta["function"]["name"]:
                                    buf["function"]["name"] = tc_delta["function"]["name"]
                                if "arguments" in tc_delta["function"]:
                                    buf["function"]["arguments"] += tc_delta["function"]["arguments"]
            
            # No tool calls — done
            if not tool_calls_buffer:
                full_text = "".join(assistant_content)
                yield {
                    "type": "done",
                    "response": full_text,
                    "iterations": iteration + 1,
                    "total_tokens": total_tokens,
                    "latency_ms": int((time.time() - start_time) * 1000)
                }
                return
            
            # Build assistant message with tool calls
            tool_calls_list = []
            for idx in sorted(tool_calls_buffer.keys()):
                tc = tool_calls_buffer[idx]
                tool_calls_list.append(tc)
            
            assistant_msg = {
                "role": "assistant",
                "content": "".join(assistant_content) if assistant_content else None,
                "tool_calls": tool_calls_list
            }
            messages.append(assistant_msg)
            
            # Execute each tool call
            for tc in tool_calls_list:
                func_name = tc["function"]["name"]
                try:
                    func_args = json.loads(tc["function"]["arguments"])
                except json.JSONDecodeError:
                    func_args = {}
                
                result_str = _execute_tool(func_name, func_args)
                
                if on_tool:
                    on_tool(func_name, func_args, result_str)
                yield {
                    "type": "tool",
                    "name": func_name,
                    "arguments": func_args,
                    "result": result_str[:2000]
                }
                
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "content": result_str[:5000]
                })
        
        yield {
            "type": "done",
            "response": "[Max iterations reached]",
            "iterations": cfg["max_iterations"],
            "total_tokens": total_tokens,
            "latency_ms": int((time.time() - start_time) * 1000),
            "error": "max_iterations"
        }
    
    except Exception as e:
        yield {
            "type": "error",
            "error": f"{type(e).__name__}: {e}",
            "latency_ms": int((time.time() - start_time) * 1000)
        }