"""
Hermes Android Goal Loop — goal-driven autonomous iteration with judge agent.

Real-time event emission via Python queue.Queue so Kotlin can poll
individual steps as they happen rather than waiting for the whole run.
"""
import json
import time
import queue
import threading
from typing import Any, Optional, Callable
import openai

_config: dict = {}
_executor_client: Optional[openai.OpenAI] = None
_judge_client: Optional[openai.OpenAI] = None

# Global event queue — one per run; Kotlin polls this via drain_events()
_event_queue: Optional[queue.Queue] = None
_run_done = threading.Event()


def configure(base_url: str, api_key: str, model: str = "",
               judge_model: str = "", max_iterations: int = 15,
               max_time_seconds: int = 300, max_cost_tokens: int = 100000):
    global _config, _executor_client, _judge_client
    _config = {
        "base_url": base_url.rstrip("/"),
        "api_key": api_key,
        "model": model,
        "judge_model": judge_model or model,
        "max_iterations": max_iterations,
        "max_time_seconds": max_time_seconds,
        "max_cost_tokens": max_cost_tokens,
    }
    _executor_client = openai.OpenAI(base_url=_config["base_url"], api_key=api_key, timeout=60.0)
    _judge_client = openai.OpenAI(base_url=_config["base_url"], api_key=api_key, timeout=30.0)
    return _config


def drain_events(timeout_ms: int = 200) -> list:
    """Poll pending events from the queue without blocking.
    
    Returns a list of event dicts. Each event has at minimum:
      {"type": str, ...}
    
    Special sentinel: {"type": "done", "result": {...}} marks end of run.
    Returns empty list if no events available within timeout_ms.
    """
    global _event_queue
    if _event_queue is None:
        return []
    events = []
    deadline = time.time() + timeout_ms / 1000.0
    while time.time() < deadline:
        try:
            evt = _event_queue.get_nowait()
            events.append(evt)
            if evt.get("type") == "done":
                break
        except queue.Empty:
            time.sleep(0.02)
            break
    return events


def is_running() -> bool:
    """Return True if a run is in progress."""
    return _event_queue is not None and not _run_done.is_set()


EXECUTOR_SYSTEM = """You are a goal-driven executor agent on Android.
Your job is to work toward the given goal step by step.
Use available tools when needed. Be concise and action-oriented.
After each step, summarize what you did and what remains."""

JUDGE_SYSTEM = """You are an independent judge agent.
Your job is to evaluate whether the executor has achieved the stated goal.
Respond in JSON: {"achieved": true/false, "reason": "...", "feedback": "..."}
If not achieved, explain what is missing in the feedback field."""

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_device_info",
            "description": "Get Android device information.",
            "parameters": {"type": "object", "properties": {}, "required": []}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read a file.",
            "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "execute_shell",
            "description": "Execute a shell command.",
            "parameters": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}
        }
    },
]

_tool_executor: Optional[Callable] = None


def set_tool_executor(executor):
    global _tool_executor
    _tool_executor = executor


def _execute_tool(name: str, args: dict) -> str:
    if _tool_executor:
        try:
            return _tool_executor(name, args)
        except Exception as e:
            return f"[Error] {e}"
    if name == "get_device_info":
        return json.dumps({"platform": "Android"})
    elif name == "read_file":
        try:
            with open(args.get("path", ""), "r") as f:
                return f.read(5000)
        except Exception as e:
            return f"[Error] {e}"
    elif name == "execute_shell":
        import subprocess
        try:
            r = subprocess.run(
                args.get("command", ""), shell=True,
                capture_output=True, text=True, timeout=10
            )
            return r.stdout + r.stderr
        except Exception as e:
            return f"[Error] {e}"
    return f"[Unknown tool: {name}]"


def _emit(evt: dict):
    """Push an event onto the queue (no-op if no queue)."""
    global _event_queue
    if _event_queue is not None:
        _event_queue.put(evt)


def run_goal_async(goal: str) -> None:
    """Start a goal run in a background thread, emitting events to the queue.
    
    Call drain_events() repeatedly from Kotlin (on IO dispatcher) to read steps.
    The final event has type='done' and contains the full result dict.
    """
    global _event_queue, _run_done
    _event_queue = queue.Queue()
    _run_done = threading.Event()

    def _run():
        result = run_goal(goal)
        _emit({"type": "done", "result": result})
        _run_done.set()

    t = threading.Thread(target=_run, daemon=True)
    t.start()


def run_goal(goal: str) -> dict:
    """Run the goal loop synchronously, emitting step events via the queue.
    
    Returns full result dict when complete.
    """
    if not _executor_client or not _judge_client:
        _emit({"type": "error", "message": "Not configured"})
        return {"achieved": False, "iterations": 0, "total_tokens": 0, "latency_ms": 0,
                "steps": [], "judge_verdicts": [], "error": "Not configured"}

    start = time.time()
    total_tokens = 0
    steps = []
    judge_verdicts = []
    messages = [{"role": "system", "content": EXECUTOR_SYSTEM}]
    messages.append({"role": "user", "content": f"Goal: {goal}\n\nWork toward this goal step by step."})

    _emit({"type": "start", "goal": goal})

    try:
        for iteration in range(_config["max_iterations"]):
            elapsed = time.time() - start
            if elapsed > _config["max_time_seconds"]:
                steps.append({"iteration": iteration + 1, "type": "limit", "reason": "time_exceeded"})
                _emit({"type": "limit", "reason": "time_exceeded", "iteration": iteration + 1})
                break
            if total_tokens > _config["max_cost_tokens"]:
                steps.append({"iteration": iteration + 1, "type": "limit", "reason": "cost_exceeded"})
                _emit({"type": "limit", "reason": "cost_exceeded", "iteration": iteration + 1})
                break

            _emit({"type": "thinking", "iteration": iteration + 1,
                   "title": f"思考中… (轮 {iteration + 1})"})

            response = _executor_client.chat.completions.create(
                model=_config["model"], messages=messages, tools=TOOLS,
                max_tokens=2048, temperature=0.7,
            )
            msg = response.choices[0].message
            if response.usage:
                total_tokens += response.usage.total_tokens

            tool_results = []
            if msg.tool_calls:
                messages.append({"role": "assistant", "content": msg.content, "tool_calls": [
                    {"id": tc.id, "type": "function",
                     "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                    for tc in msg.tool_calls
                ]})
                for tc in msg.tool_calls:
                    try:
                        args = json.loads(tc.function.arguments)
                    except Exception:
                        args = {}
                    _emit({"type": "tool_call", "iteration": iteration + 1,
                           "name": tc.function.name, "args": str(args)[:200]})
                    result = _execute_tool(tc.function.name, args)
                    tool_results.append({"name": tc.function.name, "result": result[:500]})
                    messages.append({"role": "tool", "tool_call_id": tc.id, "content": result[:3000]})
                    _emit({"type": "tool_result", "iteration": iteration + 1,
                           "name": tc.function.name, "result": result[:200]})
            else:
                messages.append({"role": "assistant", "content": msg.content or ""})

            action_summary = msg.content or "(tool calls only)"
            step = {
                "iteration": iteration + 1,
                "type": "executor",
                "summary": action_summary[:500],
                "tools": [t["name"] for t in tool_results],
                "tokens": total_tokens,
            }
            steps.append(step)
            _emit({"type": "executor_step", "iteration": iteration + 1,
                   "summary": action_summary[:300],
                   "tools": [t["name"] for t in tool_results],
                   "tokens": total_tokens})

            # Judge evaluation
            judge_messages = [
                {"role": "system", "content": JUDGE_SYSTEM},
                {"role": "user", "content":
                 f"Goal: {goal}\n\nExecutor's latest action:\n{action_summary}\n\nAchieved?"},
            ]
            judge_resp = _judge_client.chat.completions.create(
                model=_config["judge_model"], messages=judge_messages,
                max_tokens=512, temperature=0.3,
            )
            if judge_resp.usage:
                total_tokens += judge_resp.usage.total_tokens

            judge_text = judge_resp.choices[0].message.content or ""
            achieved = False
            feedback = ""
            try:
                verdict = json.loads(judge_text)
                achieved = verdict.get("achieved", False)
                feedback = verdict.get("feedback", verdict.get("reason", ""))
            except json.JSONDecodeError:
                achieved = "yes" in judge_text.lower()[:50]
                feedback = judge_text[:300]

            judge_verdicts.append({
                "iteration": iteration + 1,
                "achieved": achieved,
                "reason": judge_text[:300],
                "feedback": feedback[:300],
            })
            _emit({"type": "judge", "iteration": iteration + 1,
                   "achieved": achieved, "feedback": feedback[:200]})

            if achieved:
                _emit({"type": "achieved", "iteration": iteration + 1})
                break

            messages.append({"role": "user",
                              "content": f"Judge feedback: {feedback}\n\nContinue toward the goal."})

    except Exception as e:
        err_msg = f"{type(e).__name__}: {e}"
        _emit({"type": "error", "message": err_msg})
        return {"achieved": False, "iterations": len(steps), "total_tokens": total_tokens,
                "latency_ms": int((time.time() - start) * 1000),
                "steps": steps, "judge_verdicts": judge_verdicts, "error": err_msg}

    return {
        "achieved": len(judge_verdicts) > 0 and judge_verdicts[-1]["achieved"],
        "iterations": len(steps),
        "total_tokens": total_tokens,
        "latency_ms": int((time.time() - start) * 1000),
        "steps": steps,
        "judge_verdicts": judge_verdicts,
        "error": None,
    }
