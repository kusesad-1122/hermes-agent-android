"""
Hermes Android Goal Loop — goal-driven autonomous iteration with judge agent.

Given a goal, the executor agent works toward it while an independent
judge agent evaluates completion. Judge feedback is fed back to the
executor until the goal is achieved or limits are hit.
"""
import json
import time
import os
from typing import Any, Optional, Callable
import openai

_config: dict = {}
_executor_client: Optional[openai.OpenAI] = None
_judge_client: Optional[openai.OpenAI] = None


def configure(base_url: str, api_key: str, model: str = "",
               judge_model: str = "", max_iterations: int = 15,
               max_time_seconds: int = 300, max_cost_tokens: int = 100000):
    """Configure the goal loop.
    
    Args:
        base_url: API endpoint
        api_key: API key
        model: Executor agent model
        judge_model: Judge agent model (defaults to same as executor)
        max_iterations: Max executor iterations
        max_time_seconds: Wall clock limit
        max_cost_tokens: Total token budget
    """
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


EXECUTOR_SYSTEM = """You are a goal-driven executor agent on Android.
Your job is to work toward the given goal step by step.
Use available tools when needed. Be concise and action-oriented.
After each step, summarize what you did and what remains."""

JUDGE_SYSTEM = """You are an independent judge agent.
Your job is to evaluate whether the executor has achieved the stated goal.
You must be strict and fair — do not declare success unless the goal is truly met.
Respond in JSON format:
{"achieved": true/false, "reason": "...", "feedback": "..."}
If not achieved, explain what's missing in the feedback field."""


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
        try: return _tool_executor(name, args)
        except Exception as e: return f"[Error] {e}"
    if name == "get_device_info":
        return json.dumps({"platform": "Android"})
    elif name == "read_file":
        try:
            with open(args.get("path", ""), "r") as f: return f.read(5000)
        except Exception as e: return f"[Error] {e}"
    elif name == "execute_shell":
        import subprocess
        try:
            r = subprocess.run(args.get("command", ""), shell=True, capture_output=True, text=True, timeout=10)
            return r.stdout + r.stderr
        except Exception as e: return f"[Error] {e}"
    return f"[Unknown tool: {name}]"


def run_goal(goal: str, on_step: Callable = None, on_judge: Callable = None) -> dict:
    """Run the goal loop.
    
    Args:
        goal: The goal to achieve
        on_step: callback(step_num, action_summary, tool_calls) for each executor step
        on_judge: callback(achieved, reason, feedback) after each judge evaluation
    
    Returns:
        {"achieved": bool, "iterations": int, "total_tokens": int,
         "latency_ms": int, "steps": list, "judge_verdicts": list, "error": str|None}
    """
    if not _executor_client or not _judge_client:
        return {"achieved": False, "iterations": 0, "total_tokens": 0, "latency_ms": 0,
                "steps": [], "judge_verdicts": [], "error": "Not configured"}

    start = time.time()
    total_tokens = 0
    steps = []
    judge_verdicts = []
    messages = [{"role": "system", "content": EXECUTOR_SYSTEM}]
    messages.append({"role": "user", "content": f"Goal: {goal}\n\nWork toward this goal step by step."})

    try:
        for iteration in range(_config["max_iterations"]):
            # Check time limit
            elapsed = time.time() - start
            if elapsed > _config["max_time_seconds"]:
                steps.append({"iteration": iteration + 1, "type": "limit", "reason": "time_exceeded"})
                break
            if total_tokens > _config["max_cost_tokens"]:
                steps.append({"iteration": iteration + 1, "type": "limit", "reason": "cost_exceeded"})
                break

            # Executor step
            response = _executor_client.chat.completions.create(
                model=_config["model"], messages=messages, tools=TOOLS,
                max_tokens=2048, temperature=0.7,
            )
            msg = response.choices[0].message
            if response.usage:
                total_tokens += response.usage.total_tokens

            # Execute tool calls if any
            tool_results = []
            if msg.tool_calls:
                messages.append({"role": "assistant", "content": msg.content, "tool_calls": [
                    {"id": tc.id, "type": "function", "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                    for tc in msg.tool_calls
                ]})
                for tc in msg.tool_calls:
                    import json as _json
                    try: args = _json.loads(tc.function.arguments)
                    except: args = {}
                    result = _execute_tool(tc.function.name, args)
                    tool_results.append({"name": tc.function.name, "result": result[:500]})
                    messages.append({"role": "tool", "tool_call_id": tc.id, "content": result[:3000]})
            else:
                messages.append({"role": "assistant", "content": msg.content or ""})

            action_summary = msg.content or "(tool calls only)"
            steps.append({
                "iteration": iteration + 1,
                "type": "executor",
                "summary": action_summary[:500],
                "tools": [t["name"] for t in tool_results],
                "tokens": total_tokens,
            })
            if on_step:
                on_step(iteration + 1, action_summary[:200], tool_results)

            # Judge evaluation
            judge_messages = [
                {"role": "system", "content": JUDGE_SYSTEM},
                {"role": "user", "content": f"Goal: {goal}\n\nExecutor's latest action:\n{action_summary}\n\nHas the goal been achieved?"},
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
            if on_judge:
                on_judge(achieved, judge_text[:200], feedback)

            if achieved:
                break

            # Feed judge feedback back to executor
            messages.append({"role": "user", "content": f"Judge feedback: {feedback}\n\nContinue working toward the goal."})

    except Exception as e:
        return {"achieved": False, "iterations": len(steps), "total_tokens": total_tokens,
                "latency_ms": int((time.time() - start) * 1000), "steps": steps,
                "judge_verdicts": judge_verdicts, "error": f"{type(e).__name__}: {e}"}

    return {
        "achieved": len(judge_verdicts) > 0 and judge_verdicts[-1]["achieved"],
        "iterations": len(steps),
        "total_tokens": total_tokens,
        "latency_ms": int((time.time() - start) * 1000),
        "steps": steps,
        "judge_verdicts": judge_verdicts,
        "error": None,
    }
