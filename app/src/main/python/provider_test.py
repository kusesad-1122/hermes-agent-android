"""
Provider connectivity test — uses httpx (pure Python, no Rust deps)
to send a minimal request to an OpenAI-compatible endpoint.
"""

import json
import httpx
import time


def test_provider(base_url: str, api_key: str, model: str = "") -> dict:
    """
    Test connectivity to an OpenAI-compatible API endpoint.
    
    Returns:
        {
            "success": bool,
            "message": str,
            "latency_ms": int,
            "models": [str],  # available models (if /models endpoint works)
            "error_code": str  # HTTP status or error type
        }
    """
    result = {
        "success": False,
        "message": "",
        "latency_ms": 0,
        "models": [],
        "error_code": ""
    }

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }

    # Step 1: Test /models endpoint
    models_url = f"{base_url.rstrip('/')}/models"
    start = time.time()
    try:
        with httpx.Client(timeout=10.0) as client:
            resp = client.get(models_url, headers=headers)
            latency = int((time.time() - start) * 1000)
            result["latency_ms"] = latency

            if resp.status_code == 200:
                data = resp.json()
                items = data if isinstance(data, list) else data.get("data", [])
                model_ids = [m.get("id", "") for m in items if isinstance(m, dict) and "id" in m]
                result["models"] = model_ids[:50]  # Cap at 50
                result["success"] = True
                result["message"] = f"连通成功 ({latency}ms)，{len(model_ids)} 个模型可用"
            elif resp.status_code == 401:
                result["message"] = "认证失败 (401): API Key 无效"
                result["error_code"] = "401"
            elif resp.status_code == 403:
                result["message"] = "权限不足 (403): API Key 权限不够"
                result["error_code"] = "403"
            elif resp.status_code == 429:
                result["message"] = "请求过多 (429): 触发限流"
                result["error_code"] = "429"
            else:
                result["message"] = f"HTTP {resp.status_code}: {resp.text[:200]}"
                result["error_code"] = str(resp.status_code)

    except httpx.ConnectTimeout:
        result["message"] = "连接超时: 无法连接到服务器"
        result["error_code"] = "timeout"
    except httpx.ConnectError as e:
        result["message"] = f"连接错误: {str(e)[:200]}"
        result["error_code"] = "connect_error"
    except Exception as e:
        result["message"] = f"未知错误: {str(e)[:200]}"
        result["error_code"] = "unknown"

    return result


def test_chat_completion(base_url: str, api_key: str, model: str) -> dict:
    """
    Send a minimal chat completion request to verify the model works.
    """
    result = {
        "success": False,
        "message": "",
        "response_text": "",
        "latency_ms": 0,
        "error_code": ""
    }

    url = f"{base_url.rstrip('/')}/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": "Say hello in one word."}],
        "max_tokens": 10,
        "stream": False
    }

    start = time.time()
    try:
        with httpx.Client(timeout=30.0) as client:
            resp = client.post(url, headers=headers, json=payload)
            latency = int((time.time() - start) * 1000)
            result["latency_ms"] = latency

            if resp.status_code == 200:
                data = resp.json()
                choices = data.get("choices", [])
                if choices:
                    content = choices[0].get("message", {}).get("content", "")
                    result["success"] = True
                    result["response_text"] = content.strip()
                    result["message"] = f"模型响应正常 ({latency}ms): \"{content.strip()[:50]}\""
                else:
                    result["message"] = "响应为空"
            elif resp.status_code == 401:
                result["message"] = "认证失败 (401)"
                result["error_code"] = "401"
            elif resp.status_code == 404:
                result["message"] = f"模型不存在 (404): {model}"
                result["error_code"] = "404"
            else:
                result["message"] = f"HTTP {resp.status_code}: {resp.text[:200]}"
                result["error_code"] = str(resp.status_code)

    except httpx.ConnectTimeout:
        result["message"] = "连接超时"
        result["error_code"] = "timeout"
    except Exception as e:
        result["message"] = f"错误: {str(e)[:200]}"
        result["error_code"] = "unknown"

    return result