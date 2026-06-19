"""
Hermes Android Agent Loop — now using the real openai SDK.

Replaced the hand-rolled httpx calls with openai.OpenAI client.
This gives us:
- Proper streaming via SDK
- Tool calling via SDK
- Error handling via SDK exceptions
- Provider switching via client configuration

The loop logic (tool execution, iteration control) remains ours,
as it's Android-specific (delegates to Kotlin for tool execution).
"""
import json
import time
import os
import traceback
from typing import Any, Optional

# Use the real openai SDK (embedded as source, with jiter shim)
import openai

# Default provider config (overridden by Kotlin bridge)
_DEFAULT_CONFIG = {
    "base_url": "",
    "api_key": "",
    "model": "",
    "max_iterations": 10,
    "max_tokens": 4096,
    "temperature": 0.7,
}

_config: dict = {}
_client: Optional[openai.OpenAI] = None


def configure(base_url: str, api_key: str, model: str = "", max_iterations: int = 10,
               max_tokens: int = 4096, temperature: float = 0.7):
    """Set provider config and create a new OpenAI client."""
    global _config, _client
    _config = {
        "base_url": base_url.rstrip("/"),
        "api_key": api_key,
        "model": model or _DEFAULT_CONFIG["model"],
        "max_iterations": max_iterations,
        "max_tokens": max_tokens,
        "temperature": temperature,
    }
    _client = openai.OpenAI(
        base_url=_config["base_url"],
        api_key=_config["api_key"],
        timeout=60.0,
    )
    return _config


def get_config():
    return _config or _DEFAULT_CONFIG.copy()


def get_available_models():
    """Fetch available models from the current provider via SDK."""
    if not _client:
        return []
    try:
        models = _client.models.list()
        return [m.id for m in models.data]
    except Exception:
        return []


SYSTEM_PROMPT = """You are Hermes Agent, an AI assistant running on Android.
You can help the user with various tasks. Be helpful, concise, and accurate.
When you need to perform actions, use the available tools."""


TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_device_info",
            "description": "Get basic Android device information.",
            "parameters": {"type": "object", "properties": {}, "required": []}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the contents of a file at the given path.",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string", "description": "Absolute file path"}},
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
                "properties": {"command": {"type": "string", "description": "Shell command"}},
                "required": ["command"]
            }
        }
    },
]


_tool_executor = None


def set_tool_executor(executor):
    """Set a callable: executor(name: str, args: dict) -> str"""
    global _tool_executor
    _tool_executor = executor


def _execute_tool(name: str, args: dict) -> str:
    if _tool_executor:
        try:
            return _tool_executor(name, args)
        except Exception as e:
            return f"[Tool Error] {name}: {e}"
    if name == "get_device_info":
        return json.dumps({"platform": "Android", "python": "3.12"})
    elif name == "read_file":
        try:
            with open(args.get("path", ""), "r") as f:
                return f.read(10000)
        except Exception as e:
            return f"[Error] {e}"
    elif name == "execute_shell":
        import subprocess
        try:
            r = subprocess.run(args.get("command", ""), shell=True, capture_output=True, text=True, timeout=10)
            return r.stdout + r.stderr
        except Exception as e:
            return f"[Error] {e}"
    return f"[Unknown tool: {name}]"


def run_agent(user_message: str, history: list = None, stream_callback=None) -> dict:
    """Run agent loop using the real openai SDK."""
    cfg = get_config()
    start_time = time.time()
    result = {"response": "", "tool_calls": [], "iterations": 0, "total_tokens": 0, "latency_ms": 0, "error": None}

    if not _client:
        result["error"] = "Not configured. Call configure() first."
        result["latency_ms"] = int((time.time() - start_time) * 1000)
        return result

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    if history:
        messages.extend(history)
    messages.append({"role": "user", "content": user_message})

    try:
        for iteration in range(cfg["max_iterations"]):
            result["iterations"] = iteration + 1

            response = _client.chat.completions.create(
                model=cfg["model"],
                messages=messages,
                tools=TOOLS,
                max_tokens=cfg["max_tokens"],
                temperature=cfg["temperature"],
            )

            choice = response.choices[0]
            msg = choice.message

            if response.usage:
                result["total_tokens"] += response.usage.total_tokens

            if not msg.tool_calls:
                result["response"] = msg.content or ""
                if stream_callback and result["response"]:
                    stream_callback(result["response"])
                break

            messages.append({
                "role": "assistant",
                "content": msg.content,
                "tool_calls": [{
                    "id": tc.id,
                    "type": "function",
                    "function": {"name": tc.function.name, "arguments": tc.function.arguments}
                } for tc in msg.tool_calls]
            })

            for tc in msg.tool_calls:
                tool_name = tc.function.name
                try:
                    tool_args = json.loads(tc.function.arguments)
                except json.JSONDecodeError:
                    tool_args = {}

                result["tool_calls"].append({"name": tool_name, "arguments": tool_args, "iteration": iteration + 1})
                tool_result = _execute_tool(tool_name, tool_args)
                messages.append({"role": "tool", "tool_call_id": tc.id, "content": tool_result})

                if stream_callback:
                    stream_callback(f"[{tool_name}] ")

            if iteration == cfg["max_iterations"] - 1:
                result["response"] = "[Max iterations reached]"
                result["error"] = "max_iterations"

    except openai.AuthenticationError as e:
        result["error"] = f"Authentication failed: {e}"
    except openai.APIConnectionError as e:
        result["error"] = f"Connection error: {e}"
    except openai.APITimeoutError:
        result["error"] = "Request timeout (60s)"
    except openai.NotFoundError as e:
        result["error"] = f"Model not found: {e}"
    except openai.RateLimitError as e:
        result["error"] = f"Rate limited: {e}"
    except openai.APIStatusError as e:
        result["error"] = f"API error {e.status_code}: {e.message}"
    except Exception as e:
        result["error"] = f"{type(e).__name__}: {e}"

    result["latency_ms"] = int((time.time() - start_time) * 1000)
    return result


def run_agent_streaming(user_message: str, history: list = None, on_token=None, on_tool=None):
    """Streaming version using openai SDK streaming."""
    cfg = get_config()
    start_time = time.time()
    total_tokens = 0

    if not _client:
        yield {"type": "error", "error": "Not configured"}
        return

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    if history:
        messages.extend(history)
    messages.append({"role": "user", "content": user_message})

    try:
        for iteration in range(cfg["max_iterations"]):
            tool_calls_buffer = {}
            assistant_content = []

            stream = _client.chat.completions.create(
                model=cfg["model"],
                messages=messages,
                tools=TOOLS,
                max_tokens=cfg["max_tokens"],
                temperature=cfg["temperature"],
                stream=True,
                stream_options={"include_usage": True},
            )

            for chunk in stream:
                if not chunk.choices:
                    if chunk.usage:
                        total_tokens += chunk.usage.total_tokens
                    continue

                delta = chunk.choices[0].delta
                finish = chunk.choices[0].finish_reason

                if delta.content:
                    assistant_content.append(delta.content)
                    if on_token:
                        on_token(delta.content)
                    yield {"type": "token", "text": delta.content}

                if delta.tool_calls:
                    for tc_delta in delta.tool_calls:
                        idx = tc_delta.index or 0
                        if idx not in tool_calls_buffer:
                            tool_calls_buffer[idx] = {"id": "", "function": {"name": "", "arguments": ""}}
                        buf = tool_calls_buffer[idx]
                        if tc_delta.id:
                            buf["id"] = tc_delta.id
                        if tc_delta.function:
                            if tc_delta.function.name:
                                buf["function"]["name"] = tc_delta.function.name
                            if tc_delta.function.arguments:
                                buf["function"]["arguments"] += tc_delta.function.arguments

            if not tool_calls_buffer:
                full_text = "".join(assistant_content)
                yield {"type": "done", "response": full_text, "iterations": iteration + 1,
                       "total_tokens": total_tokens, "latency_ms": int((time.time() - start_time) * 1000)}
                return

            tool_calls_list = [tool_calls_buffer[i] for i in sorted(tool_calls_buffer.keys())]
            messages.append({"role": "assistant", "content": "".join(assistant_content) or None,
                             "tool_calls": tool_calls_list})

            for tc in tool_calls_list:
                func_name = tc["function"]["name"]
                try:
                    func_args = json.loads(tc["function"]["arguments"])
                except json.JSONDecodeError:
                    func_args = {}

                result_str = _execute_tool(func_name, func_args)
                if on_tool:
                    on_tool(func_name, func_args, result_str)
                yield {"type": "tool", "name": func_name, "arguments": func_args, "result": result_str[:2000]}
                messages.append({"role": "tool", "tool_call_id": tc["id"], "content": result_str[:5000]})

        yield {"type": "done", "response": "[Max iterations]", "iterations": cfg["max_iterations"],
               "total_tokens": total_tokens, "latency_ms": int((time.time() - start_time) * 1000), "error": "max_iterations"}

    except openai.AuthenticationError as e:
        yield {"type": "error", "error": f"Authentication failed: {e}", "latency_ms": int((time.time() - start_time) * 1000)}
    except openai.APIConnectionError as e:
        yield {"type": "error", "error": f"Connection error: {e}", "latency_ms": int((time.time() - start_time) * 1000)}
    except openai.APITimeoutError:
        yield {"type": "error", "error": "Request timeout (60s)", "latency_ms": int((time.time() - start_time) * 1000)}
    except Exception as e:
        yield {"type": "error", "error": f"{type(e).__name__}: {e}", "latency_ms": int((time.time() - start_time) * 1000)}
