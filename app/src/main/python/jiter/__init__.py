"""jiter shim — pure Python fallback for Android.

The real jiter is a Rust JSON parser. openai SDK only uses:
  from jiter import from_json

This shim provides the same interface using stdlib json.loads.
"""
import json


def from_json(data, **kwargs):
    """Drop-in replacement for jiter.from_json using stdlib.
    
    Args:
        data: bytes or str containing JSON
        **kwargs: ignored (cache_string, etc. are Rust-specific)
    
    Returns:
        Parsed Python object
    """
    if isinstance(data, bytes):
        data = data.decode('utf-8')
    return json.loads(data)
