"""
Hermes Android Wolfpack — multi-agent concurrent task execution.

Splits a large task into subtasks, dispatches each to a sub-agent
running concurrently, then aggregates results.

Concurrency is bounded by a configurable max (default 3) to avoid OOM
on mobile. Each sub-agent uses the same provider but independent
conversation context.
"""
import json
import time
import concurrent.futures
from typing import Any, Optional, Callable
import openai

_config: dict = {}
_client: Optional[openai.OpenAI] = None


def configure(base_url: str, api_key: str, model: str = "deepseek-chat",
               max_concurrency: int = 3, max_iterations: int = 5):
    global _config, _client
    _config = {
        "base_url": base_url.rstrip("/"),
        "api_key": api_key,
        "model": model,
        "max_concurrency": max_concurrency,
        "max_iterations": max_iterations,
    }
    _client = openai.OpenAI(base_url=_config["base_url"], api_key=api_key, timeout=60.0)
    return _config


SPLITTER_SYSTEM = """You are a task decomposition agent.
Given a large task, break it into independent subtasks that can be
executed concurrently. Respond in JSON:
{"subtasks": [{"id": "1", "name": "...", "description": "..."}, ...]}
Keep subtasks independent and parallelizable. 2-6 subtasks is ideal."""

AGGREGATOR_SYSTEM = """You are a result aggregation agent.
Given multiple sub-agent results, combine them into a coherent final answer.
Acknowledge each subtask's contribution. Be concise."""

SUBAGENT_SYSTEM = """You are a focused sub-agent working on a specific subtask.
Be thorough but concise. Complete your subtask fully."""


def _run_subagent(subtask: dict) -> dict:
    """Run a single sub-agent on a subtask."""
    if not _client:
        return {"id": subtask.get("id", ""), "name": subtask.get("name", ""),
                "result": "", "error": "Not configured", "tokens": 0}

    start = time.time()
    messages = [
        {"role": "system", "content": SUBAGENT_SYSTEM},
        {"role": "user", "content": f"Subtask: {subtask.get('name', '')}\n{subtask.get('description', '')}"},
    ]
    total_tokens = 0

    try:
        for i in range(_config["max_iterations"]):
            resp = _client.chat.completions.create(
                model=_config["model"], messages=messages,
                max_tokens=2048, temperature=0.7,
            )
            if resp.usage:
                total_tokens += resp.usage.total_tokens
            msg = resp.choices[0].message
            if not msg.tool_calls:
                return {
                    "id": subtask.get("id", ""), "name": subtask.get("name", ""),
                    "result": msg.content or "", "tokens": total_tokens,
                    "latency_ms": int((time.time() - start) * 1000), "error": None,
                }
            messages.append({"role": "assistant", "content": msg.content, "tool_calls": [
                {"id": tc.id, "type": "function", "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                for tc in msg.tool_calls]})
            for tc in msg.tool_calls:
                messages.append({"role": "tool", "tool_call_id": tc.id, "content": "[stub]"})
        return {"id": subtask.get("id", ""), "name": subtask.get("name", ""),
                "result": "[max iterations]", "tokens": total_tokens,
                "latency_ms": int((time.time() - start) * 1000), "error": "max_iterations"}
    except Exception as e:
        return {"id": subtask.get("id", ""), "name": subtask.get("name", ""),
                "result": "", "tokens": total_tokens,
                "latency_ms": int((time.time() - start) * 1000), "error": f"{type(e).__name__}: {e}"}


def run_wolfpack(task: str, on_split: Callable = None, on_subtask_start: Callable = None,
                  on_subtask_done: Callable = None, on_aggregate: Callable = None) -> dict:
    """Run the wolfpack multi-agent system.
    
    Returns:
        {"task": str, "subtasks": list, "results": list, "aggregated": str,
         "total_tokens": int, "latency_ms": int, "concurrency": int, "error": str|None}
    """
    if not _client:
        return {"task": task, "subtasks": [], "results": [], "aggregated": "",
                "total_tokens": 0, "latency_ms": 0, "concurrency": 0, "error": "Not configured"}

    start = time.time()
    total_tokens = 0

    # Phase 1: Split task
    try:
        split_resp = _client.chat.completions.create(
            model=_config["model"],
            messages=[
                {"role": "system", "content": SPLITTER_SYSTEM},
                {"role": "user", "content": f"Task: {task}"},
            ],
            max_tokens=1024, temperature=0.3,
        )
        if split_resp.usage:
            total_tokens += split_resp.usage.total_tokens
        split_text = split_resp.choices[0].message.content or ""

        subtasks = []
        try:
            parsed = json.loads(split_text)
            subtasks = parsed.get("subtasks", [])
        except json.JSONDecodeError:
            subtasks = [{"id": "1", "name": "main", "description": task}]

        if not subtasks:
            subtasks = [{"id": "1", "name": "main", "description": task}]

        if on_split:
            on_split(subtasks)

    except Exception as e:
        return {"task": task, "subtasks": [], "results": [], "aggregated": "",
                "total_tokens": total_tokens, "latency_ms": int((time.time() - start) * 1000),
                "concurrency": 0, "error": f"Split failed: {e}"}

    # Phase 2: Run sub-agents concurrently
    results = []
    max_workers = min(_config["max_concurrency"], len(subtasks))

    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {}
        for st in subtasks:
            if on_subtask_start:
                on_subtask_start(st)
            futures[pool.submit(_run_subagent, st)] = st

        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            total_tokens += result.get("tokens", 0)
            if on_subtask_done:
                on_subtask_done(result)

    # Sort results by id
    results.sort(key=lambda r: r.get("id", ""))

    # Phase 3: Aggregate
    aggregated = ""
    try:
        results_text = "\n\n".join([
            f"[Subtask {r['id']}: {r['name']}]\n{r.get('result', '')}"
            for r in results
        ])
        agg_resp = _client.chat.completions.create(
            model=_config["model"],
            messages=[
                {"role": "system", "content": AGGREGATOR_SYSTEM},
                {"role": "user", "content": f"Original task: {task}\n\nSub-agent results:\n{results_text}"},
            ],
            max_tokens=2048, temperature=0.5,
        )
        if agg_resp.usage:
            total_tokens += agg_resp.usage.total_tokens
        aggregated = agg_resp.choices[0].message.content or ""
        if on_aggregate:
            on_aggregate(aggregated)
    except Exception as e:
        aggregated = f"[Aggregation failed: {e}]"

    return {
        "task": task,
        "subtasks": subtasks,
        "results": results,
        "aggregated": aggregated,
        "total_tokens": total_tokens,
        "latency_ms": int((time.time() - start) * 1000),
        "concurrency": max_workers,
        "error": None,
    }
