"""
Hermes Android Provider Manager — supplier testing and model discovery.

Uses the real openai SDK to:
- Test connectivity (minimal chat request)
- Fetch available models (/v1/models)
- Switch provider at runtime
"""
import json
import time
import openai


def test_connectivity(base_url: str, api_key: str, model: str = "") -> dict:
    """Test provider connectivity with a minimal request.
    
    Returns:
        {"success": bool, "latency_ms": int, "error": str|None, "models": list}
    """
    start = time.time()
    try:
        client = openai.OpenAI(
            base_url=base_url.rstrip("/"),
            api_key=api_key,
            timeout=15.0,
        )
        
        # Try to list models first
        models = []
        try:
            resp = client.models.list()
            models = [m.id for m in resp.data]
        except Exception:
            pass
        
        # If we have a model, try a minimal chat request
        if model:
            try:
                chat = client.chat.completions.create(
                    model=model,
                    messages=[{"role": "user", "content": "Hi"}],
                    max_tokens=5,
                    temperature=0,
                )
                latency = int((time.time() - start) * 1000)
                return {
                    "success": True,
                    "latency_ms": latency,
                    "error": None,
                    "models": models,
                    "response": chat.choices[0].message.content or "",
                }
            except Exception as e:
                # Model chat failed but model list worked
                if models:
                    return {
                        "success": True,
                        "latency_ms": int((time.time() - start) * 1000),
                        "error": f"Model list OK but chat failed: {e}",
                        "models": models,
                        "response": "",
                    }
                return {
                    "success": False,
                    "latency_ms": int((time.time() - start) * 1000),
                    "error": f"{type(e).__name__}: {e}",
                    "models": [],
                    "response": "",
                }
        else:
            # No model specified, just check model list
            if models:
                return {
                    "success": True,
                    "latency_ms": int((time.time() - start) * 1000),
                    "error": None,
                    "models": models,
                    "response": "",
                }
            return {
                "success": False,
                "latency_ms": int((time.time() - start) * 1000),
                "error": "No models available and no model specified",
                "models": [],
                "response": "",
            }
    except openai.AuthenticationError as e:
        return {"success": False, "latency_ms": int((time.time() - start) * 1000),
                "error": "Authentication failed: invalid API key", "models": [], "response": ""}
    except openai.APIConnectionError as e:
        return {"success": False, "latency_ms": int((time.time() - start) * 1000),
                "error": f"Connection failed: {e}", "models": [], "response": ""}
    except openai.APITimeoutError:
        return {"success": False, "latency_ms": int((time.time() - start) * 1000),
                "error": "Timeout (15s)", "models": [], "response": ""}
    except Exception as e:
        return {"success": False, "latency_ms": int((time.time() - start) * 1000),
                "error": f"{type(e).__name__}: {e}", "models": [], "response": ""}


def fetch_models(base_url: str, api_key: str) -> dict:
    """Fetch available models from a provider.
    
    Returns:
        {"success": bool, "models": list, "error": str|None}
    """
    try:
        client = openai.OpenAI(
            base_url=base_url.rstrip("/"),
            api_key=api_key,
            timeout=10.0,
        )
        resp = client.models.list()
        models = sorted([m.id for m in resp.data])
        return {"success": True, "models": models, "error": None}
    except Exception as e:
        return {"success": False, "models": [], "error": f"{type(e).__name__}: {e}"}


def switch_provider(base_url: str, api_key: str, model: str, 
                     max_iterations: int = 10, max_tokens: int = 4096,
                     temperature: float = 0.7) -> dict:
    """Switch the active provider in agent_loop. Hot-swap, no restart.
    
    Returns:
        {"success": bool, "model": str, "error": str|None}
    """
    try:
        import agent_loop
        agent_loop.configure(base_url, api_key, model, max_iterations, max_tokens, temperature)
        return {"success": True, "model": model, "error": None}
    except Exception as e:
        return {"success": False, "model": model, "error": f"{type(e).__name__}: {e}"}
