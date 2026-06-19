"""
Hermes Android Wolfpack — multi-agent concurrent task execution.

Real-time event emission via a shared queue.Queue so Kotlin can render
each phase (split / subtask start / subtask done / aggregate) as it happens.
"""
import json
import time
import queue
import threading
import concurrent.futures
from typing import Optional, Callable
import openai

_config: dict = {}
_client: Optional[openai.OpenAI] = None

_event_queue: Optional[queue.Queue] = None
_run_done = threading.Event()


def configure(base_url: str, api_key: str, model: str = "",
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


def drain_events(timeout_ms: int = 200) -> list:
    """Poll pending events. Returns list of event dicts."""
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
    return _event_queue is not None and not _run_done.is_set()


def _emit(evt: dict):
    global _event_queue
    if _event_queue is not None:
        _event_queue.put(evt)


SPLITTER_SYSTEM = """You are a task decomposition agent.
Break the given task into 2-6 independent parallelizable subtasks.
Respond in JSON: {"subtasks": [{"id": "1", "name": "...", "description": "..."}, ...]}"""

AGGREGATOR_SYSTEM = """You are a result aggregation agent.
Given multiple sub-agent results, combine them into a coherent final answer.
Be concise."""

SUBAGENT_SYSTEM = """You are a focused sub-agent. Complete your specific subtask fully and concisely."""


def _run_subagent(subtask: dict) -> dict:
    """Run a single sub-agent synchronously; emits events."""
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
                result = {
                    "id": subtask.get("id", ""), "name": subtask.get("name", ""),
                    "result": msg.content or "", "tokens": total_tokens,
                    "latency_ms": int((time.time() - start) * 1000), "error": None,
                }
                _emit({"type": "subtask_done", "subtask_id": subtask.get("id", ""),
                       "name": subtask.get("name", ""), "result": (msg.content or "")[:200],
                       "tokens": total_tokens})
                return result
            messages.append({"role": "assistant", "content": msg.content, "tool_calls": [
                {"id": tc.id, "type": "function",
                 "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                for tc in msg.tool_calls]})
            for tc in msg.tool_calls:
                messages.append({"role": "tool", "tool_call_id": tc.id, "content": "[tool_result]"})
        result = {"id": subtask.get("id", ""), "name": subtask.get("name", ""),
                  "result": "[max iterations reached]", "tokens": total_tokens,
                  "latency_ms": int((time.time() - start) * 1000), "error": "max_iterations"}
        _emit({"type": "subtask_done", "subtask_id": subtask.get("id", ""),
               "name": subtask.get("name", ""), "result": "[max iterations]", "tokens": total_tokens})
        return result
    except Exception as e:
        err = f"{type(e).__name__}: {e}"
        _emit({"type": "subtask_error", "subtask_id": subtask.get("id", ""),
               "name": subtask.get("name", ""), "error": err})
        return {"id": subtask.get("id", ""), "name": subtask.get("name", ""),
                "result": "", "tokens": total_tokens,
                "latency_ms": int((time.time() - start) * 1000), "error": err}


def run_wolfpack_async(task: str) -> None:
    """Start wolfpack in background thread, emitting events to queue."""
    global _event_queue, _run_done
    _event_queue = queue.Queue()
    _run_done = threading.Event()

    def _run():
        result = run_wolfpack(task)
        _emit({"type": "done", "result": result})
        _run_done.set()

    t = threading.Thread(target=_run, daemon=True)
    t.start()


def run_wolfpack(task: str) -> dict:
    """Run wolfpack synchronously, emitting events throughout."""
    if not _client:
        _emit({"type": "error", "message": "Not configured"})
        return {"task": task, "subtasks": [], "results": [], "aggregated": "",
                "total_tokens": 0, "latency_ms": 0, "concurrency": 0, "error": "Not configured"}

    start = time.time()
    total_tokens = 0

    _emit({"type": "start", "task": task})

    # Phase 1: Split
    try:
        _emit({"type": "splitting", "task": task})
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

        _emit({"type": "split_done", "subtasks": [
            {"id": s.get("id", ""), "name": s.get("name", "")} for s in subtasks
        ]})

    except Exception as e:
        err = f"Split failed: {e}"
        _emit({"type": "error", "message": err})
        return {"task": task, "subtasks": [], "results": [], "aggregated": "",
                "total_tokens": total_tokens, "latency_ms": int((time.time() - start) * 1000),
                "concurrency": 0, "error": err}

    # Phase 2: Run sub-agents concurrently
    for st in subtasks:
        _emit({"type": "subtask_start", "subtask_id": st.get("id", ""), "name": st.get("name", ""),
               "description": st.get("description", "")[:200]})

    results = []
    max_workers = min(_config["max_concurrency"], len(subtasks))

    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {pool.submit(_run_subagent, st): st for st in subtasks}
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            total_tokens += result.get("tokens", 0)

    results.sort(key=lambda r: r.get("id", ""))

    # Phase 3: Aggregate
    aggregated = ""
    try:
        _emit({"type": "aggregating"})
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
        _emit({"type": "aggregate_done", "summary": aggregated[:300]})
    except Exception as e:
        aggregated = f"[Aggregation failed: {e}]"
        _emit({"type": "aggregate_error", "error": str(e)})

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